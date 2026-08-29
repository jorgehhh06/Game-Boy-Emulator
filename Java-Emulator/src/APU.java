import javax.sound.sampled.*;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    // -- RELOJES --
    private int frame_sequencer_timer = 0;
    private int frame_sequencer_step = 0;

    private int sample_timer = 0;
    private static final int SAMPLE_RATE = 44100;
    private static final int TICKS_PER_SAMPLE = 4194304 / SAMPLE_RATE;

    // -- JAVA SOUND API Y MULTITHREADING --
    private SourceDataLine audioLine;
    private byte[] audio_buffer = new byte[2048];
    private int buffer_pos = 0;

    // Cola concurrente para no bloquear el CPU del emulador
    private final ConcurrentLinkedQueue<byte[]> audioQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isRunning = true;

    public APU() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 2, false, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(format, 4096);
            audioLine.start();

            Thread audioThread = new Thread(() -> {
                while (isRunning) {
                    byte[] data = audioQueue.poll();
                    if (data != null) {
                        audioLine.write(data, 0, data.length);
                    } else {
                        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                    }
                }
            });
            audioThread.setDaemon(true);
            audioThread.start();

        } catch (Exception e) {
            System.err.println("Error al inicializar el audio en Java");
            e.printStackTrace();
        }
    }

    // -- LECTURA Y ESCRITURA MMIO --
    public int apu_read(int addr) {
        if (addr >= 0xFF30 && addr <= 0xFF3F) {
            return wave_ram[addr - 0xFF30];
        }
        int offset = addr - 0xFF10;

        if (addr == 0xFF26) {
            int res = regs[offset] & 0x80;
            res |= 0x70;
            if (ch1.enabled) res |= 1;
            if (ch2.enabled) res |= 2;
            if (ch3.enabled) res |= 4;
            if (ch4.enabled) res |= 8;
            return res;
        }
        return regs[offset] | READ_MASKS[offset];
    }

    public void apu_write(int addr, int val) {
        val &= 0xFF;
        if (addr >= 0xFF30 && addr <= 0xFF3F) {
            wave_ram[addr - 0xFF30] = val;
            return;
        }

        int offset = addr - 0xFF10;

        if (addr == 0xFF26) {
            regs[offset] = val & 0x80;
            if ((val & 0x80) == 0) {
                for (int i = 0; i < 0x30; i++) regs[i] = 0;
                ch1.enabled = false; ch2.enabled = false;
                ch3.enabled = false; ch4.enabled = false;
            }
            return;
        }

        if ((regs[0x16] & 0x80) == 0) return;

        regs[offset] = val;

        if (addr == 0xFF11) ch1.length_counter = 64 - (val & 0x3F);
        if (addr == 0xFF16) ch2.length_counter = 64 - (val & 0x3F);
        if (addr == 0xFF1B) ch3.length_counter = 256 - val;
        if (addr == 0xFF20) ch4.length_counter = 64 - (val & 0x3F);

        // -- TRIGGERS --
        if (addr == 0xFF14 && (val & 0x80) != 0) ch1.trigger(1);
        if (addr == 0xFF19 && (val & 0x80) != 0) ch2.trigger(2);
        if (addr == 0xFF1E && (val & 0x80) != 0) ch3.trigger();
        if (addr == 0xFF23 && (val & 0x80) != 0) ch4.trigger();
    }

    // -- CICLO PRINCIPAL --
    public void apu_tick() {
        if ((regs[0x16] & 0x80) == 0) return;

        ch1.tick(1);
        ch2.tick(2);
        ch3.tick();
        ch4.tick();

        frame_sequencer_timer++;
        if (frame_sequencer_timer >= 8192) {
            frame_sequencer_timer -= 8192;

            if (frame_sequencer_step % 2 == 0) {
                ch1.clock_length(1); ch2.clock_length(2);
                ch3.clock_length(); ch4.clock_length();
            }
            if (frame_sequencer_step == 7) {
                ch1.clock_envelope(1); ch2.clock_envelope(2);
                ch4.clock_envelope();
            }
            if (frame_sequencer_step == 2 || frame_sequencer_step == 6) {
                ch1.clock_sweep();
            }

            frame_sequencer_step = (frame_sequencer_step + 1) % 8;
        }

        sample_timer++;
        if (sample_timer >= TICKS_PER_SAMPLE) {
            sample_timer -= TICKS_PER_SAMPLE;
            mix_audio();
        }
    }

    private void mix_audio() {
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
        int vol_left = ((nr50 >> 4) & 0x07) + 1; // +1 para evitar multiplicar por 0 en volumen mínimo real
        int vol_right = (nr50 & 0x07) + 1;

        left = left * vol_left;
        right = right * vol_right;

        // Escalar volumen máximo teórico (60 * 8 = 480) al límite del byte (255) para evitar clipping
        int scaled_left = (left * 255) / 480;
        int scaled_right = (right * 255) / 480;

        audio_buffer[buffer_pos++] = (byte) scaled_left;
        audio_buffer[buffer_pos++] = (byte) scaled_right;

        if (buffer_pos >= audio_buffer.length) {
            audioQueue.offer(audio_buffer.clone());
            buffer_pos = 0;
        }
    }

    // -- CLASES INTERNAS DE LOS CANALES --

    private class SquareChannel {
        boolean has_sweep;
        boolean enabled = false;

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

        public void trigger(int ch_num) {
            enabled = true;
            int offset = (ch_num == 1) ? 0 : 5;

            int freq = regs[offset+3] | ((regs[offset+4] & 7) << 8);
            freq_timer = (2048 - freq) * 4;

            int nrX2 = regs[offset+2];
            vol = (nrX2 >> 4) & 0x0F;
            env_timer = nrX2 & 7;
            if (env_timer == 0) env_timer = 8;

            // El length_counter se inicializa solo si está en 0
            if (length_counter == 0) {
                length_counter = 64;
            }

            if (has_sweep) {
                shadow_freq = freq;
                int nr10 = regs[0];
                sweep_timer = (nr10 >> 4) & 7;
                if (sweep_timer == 0) sweep_timer = 8;
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
            int offset = (ch_num == 1) ? 0 : 5;
            boolean length_enabled = (regs[offset+4] & 0x40) != 0;
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

            if (period != 0) {
                sweep_timer--;
                if (sweep_timer <= 0) {
                    sweep_timer = period;
                    if (shift != 0) {
                        int new_freq = shadow_freq >> shift;
                        if (decrease) new_freq = shadow_freq - new_freq;
                        else new_freq = shadow_freq + new_freq;

                        if (new_freq > 2047) enabled = false;
                        else {
                            shadow_freq = new_freq;
                            regs[3] = new_freq & 0xFF;
                            regs[4] = (regs[4] & 0xF8) | ((new_freq >> 8) & 7);
                        }
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
        int freq_timer = 0;
        int wave_pos = 0;
        int length_counter = 0;

        public void trigger() {
            enabled = true;
            int freq = regs[0x0D] | ((regs[0x0E] & 7) << 8);
            freq_timer = (2048 - freq) * 2;
            if (length_counter == 0) length_counter = 256;
            wave_pos = 0;
        }

        public void tick() {
            if (!enabled) return;
            freq_timer--;
            if (freq_timer <= 0) {
                int freq = regs[0x0D] | ((regs[0x0E] & 7) << 8);
                freq_timer += (2048 - freq) * 2;
                wave_pos = (wave_pos + 1) % 32;
            }
        }

        public void clock_length() {
            boolean length_enabled = (regs[0x0E] & 0x40) != 0;
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
        int lfsr = 0x7FFF;
        int timer = 0;
        int length_counter = 0;

        int vol = 0;
        int env_timer = 0;

        public void trigger() {
            enabled = true;
            lfsr = 0x7FFF;

            int nr42 = regs[0x11];
            vol = (nr42 >> 4) & 0x0F;
            env_timer = nr42 & 7;
            if (env_timer == 0) env_timer = 8;

            if (length_counter == 0) length_counter = 64;
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
            boolean length_enabled = (regs[0x13] & 0x40) != 0;
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