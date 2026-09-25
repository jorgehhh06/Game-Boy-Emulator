import javax.sound.sampled.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class APU {

    // -- RAM DEL AUDIO --
    private int[] regs = new int[0x30]; // 0xFF10 - 0xFF3F
    private int[] wave_ram = new int[0x10]; // 0xFF30 - 0xFF3F

    // Los bits en 1 son aquellos que el hardware siempre devuelve como 1 al leer.
    private static final int[] READ_MASKS = {
            0x80, 0x3F, 0x00, 0xFF, 0xBF, // 10-14 (Ch1)
            0xFF, 0x3F, 0x00, 0xFF, 0xBF, // 15-19 (Ch2)
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // 1A-1E (Ch3)
            0xFF, 0xFF, 0x00, 0x00, 0xBF, // 1F-23 (Ch4)
            0x00, 0x00, 0x70, 0xFF, 0xFF, // 24-28 (Master/Control)
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF // 29-2F (Unused)
    };

    // -- COMPONENTES --
    private SquareChannel ch1 = new SquareChannel(true);
    private SquareChannel ch2 = new SquareChannel(false);
    private WaveChannel ch3 = new WaveChannel();
    private NoiseChannel ch4 = new NoiseChannel();

    // -- RELOJES Y SINCRONIZACIÓN --
    private int frame_sequencer_timer = 0; // 512 Hz, basado en el timer DIV
    private int frame_sequencer_step = 0; // Estados de FSMs

    // Acumuladores de sobremuestreo (Anti-aliasing para el "ruido metálico")
    private int left_accum = 0;
    private int right_accum = 0;
    private int accum_count = 0;

    // Sincronización exacta usando aritmética entera (Evita el stuttering)
    private int sample_timer = 0;
    private static final int SAMPLE_RATE = 44100;
    private static final int CLOCK_RATE = 4194304;

    // -- JAVA SOUND API Y COLA CONCURRENTE --
    private SourceDataLine audioLine;
    private byte[] audio_buffer = new byte[512];
    private int buffer_pos = 0;
    private BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(16);

    public APU() {
        try {
            // 8 bits por muestra, dos canales, entero sin signo, little endian
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 2, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            // Reducimos el buffer de la tarjeta de sonido a 2048 para que reaccione más rápido
            audioLine.open(format, 2048);
            audioLine.start();

            // Hilo consumidor dedicado. Lee la cola y escribe en la tarjeta de sonido sin bloquear la CPU.
            Thread audioThread = new Thread(() -> {
                while (true) {
                    try {
                        byte[] chunk = audioQueue.take();
                        audioLine.write(chunk, 0, chunk.length);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            audioThread.setDaemon(true); // Permite que el programa se cierre limpiamente
            audioThread.start();

        } catch (Exception e) {
            System.err.println("Error al inicializar el audio");
            e.printStackTrace();
        }
    }

    // -- API EXTERNA PARA LA CPU --

    // IMPORTANTE: Llamar desde el bus de memoria cuando la CPU escriba en 0xFF04 (DIV)
    public void reset_div() {
        // En hardware, el Frame Sequencer es empujado por el flanco de bajada (falling edge) del bit 12 de DIV.
        if (frame_sequencer_timer >= 4096) {
            step_frame_sequencer();
        }
        frame_sequencer_timer = 0;
    }

    // -- LECTURA Y ESCRITURA MMIO --

    public int apu_read(int addr) {
        // Ram del canal 3
        if (addr >= 0xFF30 && addr <= 0xFF3F) {
            // FIX BLARGG 09: En DMG real, leer devuelve 0xFF a menos que aciertes el T-cycle exacto
            if (ch3.enabled) {
                if (ch3.just_accessed) return wave_ram[ch3.wave_pos / 2];
                else return 0xFF;
            }
            return wave_ram[addr - 0xFF30];
        }
        int offset = addr - 0xFF10;

        // NR52
        if (addr == 0xFF26) {
            int res = regs[offset] & 0x80; // Aislar el bit 7
            res |= 0x70; // Se hace pull up a los bits 4-6
            if (ch1.enabled) res |= 1; // Bit 0 = Canal 1
            if (ch2.enabled) res |= 2; // Bit 1 = Canal 2
            if (ch3.enabled) res |= 4; // Bit 2 = Canal 3
            if (ch4.enabled) res |= 8; // Bit 3 = Canal 4
            return res;
        }

        // Máscaras para replicar los pull up
        return regs[offset] | READ_MASKS[offset];
    }

    public void apu_write(int addr, int val) {
        val &= 0xFF;
        // Ram del canal 3
        if (addr >= 0xFF30 && addr <= 0xFF3F) {
            // FIX BLARGG 12: En DMG real, escrituras son bloqueadas a menos que aciertes el T-cycle exacto
            if (ch3.enabled) {
                if (ch3.just_accessed) wave_ram[ch3.wave_pos / 2] = val;
            } else {
                wave_ram[addr - 0xFF30] = val;
            }
            return;
        }

        int offset = addr - 0xFF10;

        // NR52
        if (addr == 0xFF26) {
            boolean was_on = (regs[offset] & 0x80) != 0;
            boolean is_on = (val & 0x80) != 0;
            regs[offset] = val & 0x80; // Bit de encendido

            if (was_on && !is_on) { // Si se acaba de apagar
                // Todos los registros se ponen en 0
                for (int i = 0; i < 0x30; i++) regs[i] = 0;
                ch1.enabled = false; ch1.length_enabled = false;
                ch2.enabled = false; ch2.length_enabled = false;
                ch3.enabled = false; ch3.length_enabled = false;
                ch4.enabled = false; ch4.length_enabled = false;
            } else if (!was_on && is_on) { // Si se acaba de encender
                frame_sequencer_step = (frame_sequencer_timer >= 4096) ? 1 : 0;
            }
            return;
        }

        // FIX BLARGG 11: Si el bit 7 de NR52 es 0, sí se escribe a regs[offset], pero recortando el ciclo de trabajo
        if ((regs[0x16] & 0x80) == 0) {
            if (addr == 0xFF11) { ch1.length_counter = 64 - (val & 0x3F); regs[offset] = val & 0x3F; }
            if (addr == 0xFF16) { ch2.length_counter = 64 - (val & 0x3F); regs[offset] = val & 0x3F; }
            if (addr == 0xFF1B) { ch3.length_counter = 256 - val; regs[offset] = val; }
            if (addr == 0xFF20) { ch4.length_counter = 64 - (val & 0x3F); regs[offset] = val & 0x3F; }
            return;
        }

        // FIX BLARGG 05: Chequeo para apagar el canal si se sale del modo resta en el Sweep.
        if (addr == 0xFF10) {
            boolean was_decrease = (regs[0] & 8) != 0;
            boolean is_decrease = (val & 8) != 0;
            if (ch1.negate_calc_done && was_decrease && !is_decrease) {
                ch1.enabled = false;
            }
        }

        regs[offset] = val;

        // Los dos bits de arriba controlan cuál de los 4 ciclos de trabajo utilizar
        if (addr == 0xFF11) ch1.length_counter = 64 - (val & 0x3F);
        if (addr == 0xFF16) ch2.length_counter = 64 - (val & 0x3F);
        if (addr == 0xFF1B) ch3.length_counter = 256 - val;
        if (addr == 0xFF20) ch4.length_counter = 64 - (val & 0x3F);

        // Deshabilitar el DAC apaga el canal de inmediato
        if (addr == 0xFF12 && (val & 0xF8) == 0) ch1.enabled = false;
        if (addr == 0xFF17 && (val & 0xF8) == 0) ch2.enabled = false;
        if (addr == 0xFF1A && (val & 0x80) == 0) ch3.enabled = false;
        if (addr == 0xFF21 && (val & 0xF8) == 0) ch4.enabled = false;

        // Triggers manejados internamente en las clases para conservar los Glitches exactos
        if (addr == 0xFF14) ch1.write_nrx4(1, val);
        if (addr == 0xFF19) ch2.write_nrx4(2, val);
        if (addr == 0xFF1E) ch3.write_nrx4(val);
        if (addr == 0xFF23) ch4.write_nrx4(val);
    }

    // -- CICLO PRINCIPAL --
    public void apu_tick() {
        frame_sequencer_timer++;
        if (frame_sequencer_timer >= 8192) {
            frame_sequencer_timer -= 8192;
            if ((regs[0x16] & 0x80) != 0) {
                step_frame_sequencer();
            }
        }

        // Si la APU está encendida procesamos los canales.
        if ((regs[0x16] & 0x80) != 0) {
            ch1.tick(1);
            ch2.tick(2);
            ch3.tick();
            ch4.tick();
        }

        // Sobremuestreo para el filtro Boxcar
        accumulate_audio();

        // Se debe sincronizar el reloj de la consola con el sample rate de 44.1 kHz
        sample_timer += SAMPLE_RATE;
        if (sample_timer >= CLOCK_RATE) {
            sample_timer -= CLOCK_RATE;
            mix_audio();
        }
    }

    private void step_frame_sequencer() {
        frame_sequencer_step = (frame_sequencer_step + 1) & 7;

        if ((frame_sequencer_step & 1) == 1) { // Pasos impares
            ch1.clock_length(1);
            ch2.clock_length(2);
            ch3.clock_length();
            ch4.clock_length();
        }
        if ((frame_sequencer_step & 3) == 3) {
            ch1.clock_sweep();
        }
        if ((frame_sequencer_step & 7) == 7) {
            ch1.clock_envelope(1);
            ch2.clock_envelope(2);
            ch4.clock_envelope();
        }
    }

    private void accumulate_audio() {
        int out1 = ch1.get_output();
        int out2 = ch2.get_output();
        int out3 = ch3.get_output();
        int out4 = ch4.get_output();

        int left = 0, right = 0;
        int nr51 = regs[0x15];

        if ((nr51 & 0x10) != 0) left += out1;
        if ((nr51 & 0x20) != 0) left += out2;
        if ((nr51 & 0x40) != 0) left += out3;
        if ((nr51 & 0x80) != 0) left += out4;

        if ((nr51 & 0x01) != 0) right += out1;
        if ((nr51 & 0x02) != 0) right += out2;
        if ((nr51 & 0x04) != 0) right += out3;
        if ((nr51 & 0x08) != 0) right += out4;

        int nr50 = regs[0x14];
        left *= (((nr50 >> 4) & 0x07) + 1);
        right *= ((nr50 & 0x07) + 1);

        left_accum += left;
        right_accum += right;
        accum_count++;
    }

    private void mix_audio() {
        if (accum_count == 0) return;

        // Promediar los ciclos de reloj acumulados
        int left = left_accum / accum_count;
        int right = right_accum / accum_count;

        left_accum = 0;
        right_accum = 0;
        accum_count = 0;

        // Escalar volumen máximo teórico (60 * 8 = 480) al límite del byte (255)
        int scaled_left = (left * 255) / 480;
        int scaled_right = (right * 255) / 480;

        audio_buffer[buffer_pos++] = (byte) scaled_left;
        audio_buffer[buffer_pos++] = (byte) scaled_right;

        if (buffer_pos >= audio_buffer.length) {
            // offer() es no bloqueante. Descartará silenciosamente el frame si la tarjeta
            // de sonido está llena, liberando a la CPU.
            audioQueue.offer(audio_buffer.clone());
            buffer_pos = 0;
        }
    }

    // -- CLASES INTERNAS DE LOS CANALES --

    private class SquareChannel {
        boolean has_sweep;
        boolean enabled = false;
        boolean length_enabled = false;

        boolean sweep_enabled_flag = false;
        boolean negate_calc_done = false;

        int freq_timer = 0;
        int duty_step = 0;
        int length_counter = 0;

        int vol = 0;
        int env_timer = 0;

        int sweep_timer = 0;
        int shadow_freq = 0;

        int[][] duty_table = {
                {0,0,0,0,0,0,0,1}, {1,0,0,0,0,0,0,1},
                {1,0,0,0,0,1,1,1}, {0,1,1,1,1,1,1,0}
        };

        public SquareChannel(boolean has_sweep) { this.has_sweep = has_sweep; }

        public void write_nrx4(int ch_num, int val) {
            int offset = (ch_num == 1) ? 0 : 5;
            boolean trigger = (val & 0x80) != 0;
            boolean enable_length = (val & 0x40) != 0;

            if (trigger && length_counter == 0) {
                length_counter = 64;
                length_enabled = false;
            }

            if (enable_length && !length_enabled && (frame_sequencer_step & 1) == 1 && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) {
                    if (trigger) length_counter = 63;
                    else enabled = false;
                }
            }

            length_enabled = enable_length;

            if (trigger) {
                if ((regs[offset+2] & 0xF8) != 0) enabled = true;

                int freq = regs[offset+3] | ((regs[offset+4] & 7) << 8);
                freq_timer = (2048 - freq) * 4;

                vol = (regs[offset+2] >> 4) & 0x0F;
                env_timer = regs[offset+2] & 7;
                if (env_timer == 0) env_timer = 8;

                if (has_sweep) {
                    shadow_freq = freq;
                    int nr10 = regs[0];
                    int period = (nr10 >> 4) & 7;
                    int shift = nr10 & 7;
                    boolean decrease = (nr10 & 8) != 0;

                    sweep_timer = (period == 0) ? 8 : period;
                    sweep_enabled_flag = (period > 0 || shift > 0);
                    negate_calc_done = false;

                    if (shift > 0) {
                        int calc_freq = shadow_freq >> shift;
                        calc_freq = decrease ? shadow_freq - calc_freq : shadow_freq + calc_freq;
                        if (decrease) negate_calc_done = true;
                        if (calc_freq > 2047) enabled = false;
                    }
                }
            }
        }

        public void tick(int ch_num) {
            if (!enabled) return;
            freq_timer--;
            if (freq_timer <= 0) {
                int offset = (ch_num == 1) ? 0 : 5;
                int freq = regs[offset+3] | ((regs[offset+4] & 7) << 8);
                freq_timer += (2048 - freq) * 4;
                duty_step = (duty_step + 1) % 8;
            }
        }

        public void clock_length(int ch_num) {
            if (length_enabled && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) enabled = false;
            }
        }

        public void clock_envelope(int ch_num) {
            int offset = (ch_num == 1) ? 0 : 5;
            int nrX2 = regs[offset+2];
            int env_period = nrX2 & 7;
            boolean env_up = (nrX2 & 8) != 0;

            if (env_period != 0) {
                env_timer--;
                if (env_timer <= 0) {
                    env_timer = env_period;
                    if (env_up && vol < 15) vol++;
                    else if (!env_up && vol > 0) vol--;
                }
            }
        }

        public void clock_sweep() {
            if (!has_sweep || !enabled) return;
            int nr10 = regs[0];
            int period = (nr10 >> 4) & 7;
            int shift = nr10 & 7;
            boolean decrease = (nr10 & 8) != 0;

            sweep_timer--;
            if (sweep_timer <= 0) {
                sweep_timer = (period == 0) ? 8 : period;

                if (sweep_enabled_flag && period > 0) {
                    int new_freq = shadow_freq >> shift;
                    new_freq = decrease ? shadow_freq - new_freq : shadow_freq + new_freq;

                    if (decrease) negate_calc_done = true;

                    if (new_freq > 2047) {
                        enabled = false;
                    } else if (shift > 0) {
                        shadow_freq = new_freq;
                        regs[3] = new_freq & 0xFF;
                        regs[4] = (regs[4] & 0xF8) | ((new_freq >> 8) & 7);

                        int calc2 = shadow_freq >> shift;
                        calc2 = decrease ? shadow_freq - calc2 : shadow_freq + calc2;
                        if (calc2 > 2047) enabled = false;
                    }
                }
            }
        }

        public int get_output() {
            if (!enabled || vol == 0) return 0;
            int offset = (has_sweep) ? 0 : 5;
            int duty = regs[offset+1] >> 6;
            return duty_table[duty][duty_step] * vol;
        }
    }

    private class WaveChannel {
        boolean enabled = false;
        boolean length_enabled = false;
        int freq_timer = 0;
        int wave_pos = 0;
        int length_counter = 0;

        boolean just_accessed = false; // FIX BLARGG 09/12

        public void write_nrx4(int val) {
            boolean trigger = (val & 0x80) != 0;
            boolean enable_length = (val & 0x40) != 0;

            if (trigger && length_counter == 0) {
                length_counter = 256;
                length_enabled = false;
            }

            if (enable_length && !length_enabled && (frame_sequencer_step & 1) == 1 && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) {
                    if (trigger) length_counter = 255;
                    else enabled = false;
                }
            }

            length_enabled = enable_length;

            if (trigger) {
                // FIX BLARGG 10: Glitch de corrupción de memoria al disparar la Wave RAM justo cuando iba a leer
                if (enabled && freq_timer <= 2) {
                    int offset = ((wave_pos + 1) % 32) / 2;
                    if (offset < 4) {
                        wave_ram[0] = wave_ram[offset];
                    } else {
                        int base = offset & ~3;
                        for (int i = 0; i < 4; i++) {
                            wave_ram[i] = wave_ram[base + i];
                        }
                    }
                }

                if ((regs[0x0A] & 0x80) != 0) enabled = true;
                int freq = regs[0x0D] | ((regs[0x0E] & 7) << 8);
                // Retraso extra simulando el reinicio del reloj como en bsnes
                freq_timer = (2048 - freq) * 2 + 6;
                wave_pos = 0;
            }
        }

        public void tick() {
            just_accessed = false; // Solo dura este ciclo T
            if (!enabled) return;
            freq_timer--;
            if (freq_timer <= 0) {
                int freq = regs[0x0D] | ((regs[0x0E] & 7) << 8);
                freq_timer += (2048 - freq) * 2;
                wave_pos = (wave_pos + 1) % 32;
                just_accessed = true; // Abre la ventana a la CPU por un tick exacto
            }
        }

        public void clock_length() {
            if (length_enabled && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) enabled = false;
            }
        }

        public int get_output() {
            if (!enabled || (regs[0x0A] & 0x80) == 0) return 0;

            int vol_code = (regs[0x0C] >> 5) & 3;
            if (vol_code == 0) return 0;

            int ram_byte = wave_ram[wave_pos / 2];
            int sample = (wave_pos % 2 == 0) ? (ram_byte >> 4) : (ram_byte & 0x0F);

            if (vol_code == 1) return sample;
            if (vol_code == 2) return sample >> 1;
            return sample >> 2;
        }
    }

    private class NoiseChannel {
        boolean enabled = false;
        boolean length_enabled = false;
        int lfsr = 0x7FFF;
        int timer = 0;
        int length_counter = 0;

        int vol = 0;
        int env_timer = 0;

        public void write_nrx4(int val) {
            boolean trigger = (val & 0x80) != 0;
            boolean enable_length = (val & 0x40) != 0;

            if (trigger && length_counter == 0) {
                length_counter = 64;
                length_enabled = false;
            }

            if (enable_length && !length_enabled && (frame_sequencer_step & 1) == 1 && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) {
                    if (trigger) length_counter = 63;
                    else enabled = false;
                }
            }

            length_enabled = enable_length;

            if (trigger) {
                if ((regs[0x11] & 0xF8) != 0) enabled = true;
                lfsr = 0x7FFF;
                vol = (regs[0x11] >> 4) & 0x0F;
                env_timer = regs[0x11] & 7;
                if (env_timer == 0) env_timer = 8;
            }
        }

        public void tick() {
            if (!enabled) return;
            timer--;
            if (timer <= 0) {
                int nr43 = regs[0x12];
                int shift = nr43 >> 4;
                int div = nr43 & 7;
                int divisor = (div == 0) ? 8 : (div * 16);
                timer += divisor << shift;

                int xor_bit = (lfsr & 1) ^ ((lfsr >> 1) & 1);
                lfsr = (lfsr >> 1) | (xor_bit << 14);
                if ((nr43 & 8) != 0) {
                    lfsr = (lfsr & ~(1 << 6)) | (xor_bit << 6);
                }
            }
        }

        public void clock_length() {
            if (length_enabled && length_counter > 0) {
                length_counter--;
                if (length_counter == 0) enabled = false;
            }
        }

        public void clock_envelope() {
            int nr42 = regs[0x11];
            int env_period = nr42 & 7;
            boolean env_up = (nr42 & 8) != 0;

            if (env_period != 0) {
                env_timer--;
                if (env_timer <= 0) {
                    env_timer = env_period;
                    if (env_up && vol < 15) vol++;
                    else if (!env_up && vol > 0) vol--;
                }
            }
        }

        public int get_output() {
            if (!enabled || vol == 0) return 0;
            return ((~lfsr) & 1) * vol;
        }
    }
}