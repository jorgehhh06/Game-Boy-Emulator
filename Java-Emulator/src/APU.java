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

    // Acumuladores de sobremuestreo (Anti-aliasing)
    private int left_accum = 0; // Suma todos los valores de sonido generados para el altavoz izquierdo
    private int right_accum = 0; // Suma todos los valores de sonido generados para el altavoz derecho
    // Lleva la cuenta de cuántos ticks pasaron durante el lapso que la tarjeta de sonido tardó en pedir
    // su siguiente muestra
    private int accum_count = 0;

    // Sincronización exacta usando aritmética entera (Evita el stuttering)
    // 4,194,304 / 44,100 = 95.108938 ticks / segundo
    // Si se redondea a 95 ticks, el desfase se acumulará
    private int sample_timer = 0; // El sobrante se almacena aquí
    private static final int SAMPLE_RATE = 44100;
    private static final int CLOCK_RATE = 4194304;

    // -- JAVA SOUND API Y COLA CONCURRENTE --
    private SourceDataLine audioLine; // clase de Java Sound API
    // Se almacenan muestras y se mandan al llenarse las 512
    private byte[] audio_buffer = new byte[512];
    private int buffer_pos = 0; // Índice para escribir en audio_buffer
    // Cola de Java diseñada para que dos hilos (emulador y tarjeta de sonido) trabajen en armonía
    // Es una cola con un límite estricto de 16 bloques, 16 x 512 = 8,192 bytes de audio en espera
    // .offer() es no bloqueante y permite la ejecución del emulador de manera fluída
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

    // Llamar desde el bus de memoria cuando la CPU escriba en 0xFF04 (DIV)
    public void reset_div() {
        // 4,194,304 / 512 = 8,192 ciclos
        // En hardware, el Frame Sequencer es empujado por el flanco de bajada (falling edge) del bit 12 de DIV.
        // Si el temporizador ya iba por la mitad o más (>= 4096 de los 8192 ciclos totales)
        // Si el número es la mitad del ciclo (4096) el bit estaba en 1
        // Más allá de eso es un 0 y por ende un flanco de bajada
        if (frame_sequencer_timer >= 4096) {
            step_frame_sequencer(); // Fuerza un paso del frame sequencer
        }
        frame_sequencer_timer = 0; // Se reinicia el reloj del secuenciador junto a DIV
    }

    // -- LECTURA Y ESCRITURA MMIO --

    public int apu_read(int addr) {
        // Ram del canal 3 (Wave Ram)
        if (addr >= 0xFF30 && addr <= 0xFF3F) {
            // En DMG real, leer devuelve 0xFF a menos que aciertes el T-cycle exacto
            if (ch3.enabled) {
                if (ch3.just_accessed) return wave_ram[ch3.wave_pos >> 1];
                else
                    return 0xFF;
            }
            return wave_ram[addr - 0xFF30];
        }
        int offset = addr - 0xFF10;

        // NR52 - Controla todos los canales
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
            // En DMG real, escrituras son bloqueadas a menos que aciertes el T-cycle exacto
            // Si el canal está activo
            if (ch3.enabled) {
                if (ch3.just_accessed) wave_ram[ch3.wave_pos >> 1] = val;
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
                // Se apagan todos los canales
                ch1.enabled = false; ch1.length_enabled = false;
                ch2.enabled = false; ch2.length_enabled = false;
                ch3.enabled = false; ch3.length_enabled = false;
                ch4.enabled = false; ch4.length_enabled = false;
            } else if (!was_on && is_on) { // Si se acaba de encender (flanco de subida)
                // frame_sequencer_timer cuenta los T-Cycles y hasta que llega a 8,192 lanza una señal
                // 4,194,304 / 8,192 = 512, la frecuencia del APU
                // frame_sequencer_step es una FSM de 8 estados que le dice al Game Boy qué hacer
                // Si el step iba más cerca de terminar al momento de prender el sistema de sonido
                // Se avanza 1 paso en la FSM
                frame_sequencer_step = (frame_sequencer_timer >= 4096) ? 1 : 0;
            }
            return;
        }

        // Si el bit 7 de NR52 es 0, sí se escribe a regs[offset], pero recortando el ciclo de trabajo
        if ((regs[0x16] & 0x80) == 0) {
            // Con 6 bits (0x3F) solo se pueden representar 64 valores
            // La resta transforma un valor en una cuenta regresiva para apagar la nota
            if (addr == 0xFF11) {
                ch1.length_counter = 64 - (val & 0x3F);
                regs[offset] = val & 0x3F;
            }
            if (addr == 0xFF16) {
                ch2.length_counter = 64 - (val & 0x3F);
                regs[offset] = val & 0x3F;
            }
            if (addr == 0xFF1B) {
                ch3.length_counter = 256 - val;
                regs[offset] = val;
            }
            if (addr == 0xFF20) {
                ch4.length_counter = 64 - (val & 0x3F);
                regs[offset] = val & 0x3F;
            }

            return;
        }

        // Chequeo para apagar el canal si se sale del modo resta en el Sweep.
        if (addr == 0xFF10) {
            // NR10
            boolean was_decrease = (regs[0] & 8) != 0; // Valor viejo del bit 3
            boolean is_decrease = (val & 8) != 0; // Valor nuevo del bit 3 tras ser cambiado
            // Si el sweep ya realizó al menos una operación matemática de resta desde que disparó
            // la nota actual y hay flanco de bajada en el bit 3 de NR10, se apaga el canal 1
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
    public void apu_tick() { // always @(posedge clk) begin
        frame_sequencer_timer++; // 1 T-Cycle
        if (frame_sequencer_timer >= 8192) { // Un pulso de 512 Hz
            frame_sequencer_timer -= 8192; // La resta en lugar de 0 permite preservar los ciclos sobrantes
            if ((regs[0x16] & 0x80) != 0) { // NR52, si el audio está prendido
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

    // -- Función de cambio de estado de la FSM --
    // Cambia a 512 Hz
    private void step_frame_sequencer() {
        frame_sequencer_step = (frame_sequencer_step + 1) & 7; // Cambio de estado

        // 256 Hz
        if ((frame_sequencer_step & 1) == 1) { // Pasos impares
            // Temporizadores de duración de las notas de cada canal
            ch1.clock_length();
            ch2.clock_length();
            ch3.clock_length();
            ch4.clock_length();
        }
        // 128 Hz
        if ((frame_sequencer_step & 3) == 3) {
            // Sweep del canal 1
            ch1.clock_sweep();
        }
        // 64 Hz
        if ((frame_sequencer_step & 7) == 7) {
            // Envolvente de canales 1, 2 y 4
            ch1.clock_envelope(1);
            ch2.clock_envelope(2);
            ch4.clock_envelope();
        }
    }

    private void accumulate_audio() {
        // Salida de los 4 canales
        int out1 = ch1.get_output();
        int out2 = ch2.get_output();
        int out3 = ch3.get_output();
        int out4 = ch4.get_output();

        // Canales derecho e izquierdo
        int left = 0, right = 0;
        int nr51 = regs[0x15];

        // Encendido del canal izquierdo
        if ((nr51 & 0x10) != 0) left += out1;
        if ((nr51 & 0x20) != 0) left += out2;
        if ((nr51 & 0x40) != 0) left += out3;
        if ((nr51 & 0x80) != 0) left += out4;

        // Encendido del canal derecho
        if ((nr51 & 0x01) != 0) right += out1;
        if ((nr51 & 0x02) != 0) right += out2;
        if ((nr51 & 0x04) != 0) right += out3;
        if ((nr51 & 0x08) != 0) right += out4;

        int nr50 = regs[0x14];
        // Los 3 bits superiores controlan en volumen del canal izquierdo
        left *= (((nr50 >> 4) & 0x07) + 1);
        // Los 3 bits inferiores controlan el volumen del canal derecho
        right *= ((nr50 & 0x07) + 1);

        // Se suma cuánto se acumula y las veces que lo hace para promediarlo después
        left_accum += left;
        right_accum += right;
        accum_count++;
    }

    private void mix_audio() {
        if (accum_count == 0) return; // Evitar división entre 0

        // Promediar los ciclos de reloj acumulados
        int left = left_accum / accum_count;
        int right = right_accum / accum_count;

        // Se reinicia el proceso de acumular
        left_accum = 0;
        right_accum = 0;
        accum_count = 0;

        // Escalar volumen máximo teórico (60 * 8 = 480) al límite del byte (255)
        // 480 es el techo máximo teórico que puede alcanzar la suma de los canales
        // antes de pasar al mezclador general
        // 255 es el valor máximo en un byte sin signo
        // la división entre 480 normaliza el vector, 255 lo escala al límite permitido
        int scaled_left = (left * 255) / 480;
        int scaled_right = (right * 255) / 480;

        // Se llena el buffer
        audio_buffer[buffer_pos++] = (byte) scaled_left;
        audio_buffer[buffer_pos++] = (byte) scaled_right;

        // Se revisa si ya se acumularon las muestras necesarias
        if (buffer_pos >= audio_buffer.length) {
            // offer() es no bloqueante. Descartará silenciosamente el frame si la tarjeta
            // de sonido está llena, liberando a la CPU.
            audioQueue.offer(audio_buffer.clone()); // Se le entrega el buffer a la tarjeta de sonido
            buffer_pos = 0;
        }
    }

    // -- CLASES INTERNAS DE LOS CANALES --

    private class SquareChannel {
        boolean has_sweep; // Solo el canal 1 tiene sweep
        boolean enabled = false;
        boolean length_enabled = false;

        boolean sweep_enabled_flag = false;
        boolean negate_calc_done = false; // Si el sweep ha hecho un paso

        // Cuenta los T-Cycles del sistema, al llegar a 0 vuelve a cargar su valor según el registro
        // de frecuencia y avanza el duty_step
        int freq_timer = 0;
        int duty_step = 0; // Puntero dentro de la forma de onda
        int length_counter = 0; // Contador de duración de la nota

        int vol = 0; // Volumen actual del canal
        int env_timer = 0; // Temporizador de la envolvente del volumen

        // -- Exclusivo del canal 1 --
        // Cuenta los pulsos de 128 Hz del frame sequencer
        int sweep_timer = 0; // Temporizador de barrido de frecuencia (Sweep Timer)
        int shadow_freq = 0; // Copia de 0xFF13/0xFF14 para modificar sin alterar el registro original

        // Ciclo de trabajo o ancho de pulso (PWM)
        int[][] duty_table = {
                // Ciclo de trabajo del 12.5%
                {0,0,0,0,0,0,0,1},
                // Ciclo de trabajo del 25%
                {1,0,0,0,0,0,0,1},
                // Ciclo de trabajo del 50%
                {1,0,0,0,0,1,1,1},
                // Ciclo de trabajo del 75%
                {0,1,1,1,1,1,1,0}
        };

        // Canal 1 vs Canal 2
        public SquareChannel(boolean has_sweep) { this.has_sweep = has_sweep; }

        public void write_nrx4(int ch_num, int val) {
            // Distancia entre NR14 y NR24
            int offset = (ch_num == 1) ? 0 : 5;
            boolean trigger = (val & 0x80) != 0; // Se dispara la nota
            boolean enable_length = (val & 0x40) != 0; // Activar contador de duración

            // El juego acaba de mandar una orden para disparar una nota y ocurre porque
            // la nota anterior se quedó sin tiempo
            if (trigger && length_counter == 0) {
                length_counter = 64; // Se reinicia el contador
                length_enabled = false; // Resetea la bandera de habilitación de longitud
            }

            // Se activa por primera vez el contador de duración, está en el paso impar del frame sequencer
            // y el canal todavía no se acaba
            if (enable_length && !length_enabled && (frame_sequencer_step & 1) == 1 && length_counter > 0) {
                length_counter--; // Resta un punto de duración
                if (length_counter == 0) { // Si el contador llega a 0
                    // Se estaba esperando una nota de trigger, length_counter se guarda con 63 en vez de 64
                    if (trigger) length_counter = 63;
                    // Si no se estaba disparando una nota, el canal se apaga de golpe
                    else enabled = false;
                }
            }

            length_enabled = enable_length; // Se actualiza el valor

            // El juego quiere que el canal empiece a sonar ya mismo
            if (trigger) {
                // Registro de volumen envolvente, si todos estos bits están en 0, el DAC está apagado
                if ((regs[offset + 2] & 0xF8) != 0) enabled = true;

                // Se forma un número de 11 bits (0 a 2047), se unen los 8 bits
                // de la frecuencia más baja (NRx3) y los 3 bits más bajos
                // del registro alto (NRx4)
                int freq = regs[offset + 3] | ((regs[offset + 4] & 7) << 8);
                // 4 * (2048 - freq), determina los T-Cycles a esperar antes de dar
                // el siguiente paso de la onda
                freq_timer = (2048 - freq) << 2;

                // En NRx2 los 4 bits más altos definen el volumen inicial
                // El bit 3 define si sube o baja
                // Los 3 bits más bajos definen el periodo
                vol = (regs[offset + 2] >> 4) & 0x0F;
                env_timer = regs[offset + 2] & 7; // Timer del envolvente (periodo)
                if (env_timer == 0) env_timer = 8; // El 0 es interpretado como un 8

                // Solo para el canal 1
                if (has_sweep) {
                    shadow_freq = freq;
                    int nr10 = regs[0];
                    int period = (nr10 >> 4) & 7; // Bits 4 a 6 controlan la velocidad
                    int shift = nr10 & 7; // Bits 0 a 2 son para intensidad
                    boolean decrease = (nr10 & 8) != 0; // Bit 3 es la dirección

                    sweep_timer = (period == 0) ? 8 : period; // Temporizador del barrido
                    // Si hay un periodo o desplazamiento mayor a 0 se activa la sweep flag
                    sweep_enabled_flag = (period > 0 || shift > 0);
                    negate_calc_done = false; // Hace reset a la memoria de cálculos de la resta

                    // Pre-cálculo del desbordamiento
                    if (shift > 0) {
                        int calc_freq = shadow_freq >> shift; // Número a sumar o restar
                        // Nueva frecuencia
                        calc_freq = decrease ? shadow_freq - calc_freq : shadow_freq + calc_freq;
                        if (decrease) negate_calc_done = true; // Prohíbe sumar a mitad del camino
                        if (calc_freq > 2047) enabled = false; // Previene desbordamientos
                    }
                }
            }
        }

        // Lógica secuencial
        // always @(posedge clk) begin
        public void tick(int ch_num) {
            if (!enabled) return;
            freq_timer--;
            // Si se llega a 0 se debe avanzar el ciclo de trabajo
            if (freq_timer <= 0) {
                int offset = (ch_num == 1) ? 0 : 5;
                // Extrae la frecuencia de ambos bytes
                int freq = regs[offset + 3] | ((regs[offset + 4] & 7) << 8);
                freq_timer += (2048 - freq) << 2; // Se reinicia freq_timer
                // FSM del ciclo de trabajo
                duty_step = (duty_step + 1) & 7;
            }
        } // end

        public void clock_length() {
            // Si la nota tiene límite de duración y sigue habiendo tiempo por sonar
            if (length_enabled && length_counter > 0) {
                length_counter--; // Se decrementa el contador
                if (length_counter == 0) enabled = false; // Cuando llega a 0 se apaga la nota
            }
        }

        public void clock_envelope(int ch_num) {
            int offset = (ch_num == 1) ? 0 : 5;
            int nrX2 = regs[offset + 2];
            int env_period = nrX2 & 7; // Bits 0 a 2 son la velocidad de cambio
            boolean env_up = (nrX2 & 8) != 0; // Bit 3 es la dirección

            if (env_period != 0) { // Si el envolvente está activo
                env_timer--; // El temporizador decrece
                if (env_timer <= 0) { // Si llega a 0
                    env_timer = env_period; // Se recarga
                    // Si es una suma y aún no se llega al valor máximo, el volumen aumenta
                    if (env_up && vol < 15) vol++;
                    // Si es una resta y aún no se llega al valor mínimo, el volumen disminuye
                    else if (!env_up && vol > 0) vol--;
                }
            }
        }

        public void clock_sweep() {
            if (!has_sweep || !enabled) return;
            int nr10 = regs[0];
            int period = (nr10 >> 4) & 7; // Los bits 4 a 6 dicta qué tan rápido debe latir el sweep
            int shift = nr10 & 7; // Bits del 0 al 2 dicen qué tan grande será cada paso
            boolean decrease = (nr10 & 8) != 0; // Bit 3 si es suma o resta

            sweep_timer--; // Disminuye el timer
            if (sweep_timer <= 0) { // Si llega a 0
                sweep_timer = (period == 0) ? 8 : period; // Se recarga, el 0 es interpretado como un 8
                // Si el sweep está activado y el periodo es mayor a 0
                if (sweep_enabled_flag && period > 0) {
                    int new_freq = shadow_freq >> shift; // Se obtiene la nueva frecuencia
                    // Se suma o se resta según el bit 3
                    new_freq = decrease ? shadow_freq - new_freq : shadow_freq + new_freq;

                    if (decrease) negate_calc_done = true; // Flag de que se ha decrementado al menos una vez

                    // Valor máximo del registro
                    if (new_freq > 2047) {
                        enabled = false; // Se apaga el canal
                    } else if (shift > 0) { // Si todavía no se apaga, y la frecuencia es distinta
                        shadow_freq = new_freq; // Se actualiza la frecuencia
                        // Se actualizan los registros para que el canal empiece
                        // a sonar inmediatamente con la nueva frecuencia
                        regs[3] = new_freq & 0xFF;
                        regs[4] = (regs[4] & 0xF8) | ((new_freq >> 8) & 7);

                        // Se hace un nuevo cálculo con la nueva frecuenta para ver si se va a desbordar
                        // y en caso de ser así se apaga preventivamente
                        int calc2 = shadow_freq >> shift;
                        calc2 = decrease ? shadow_freq - calc2 : shadow_freq + calc2;
                        if (calc2 > 2047) enabled = false;
                    }
                }
            }
        }

        public int get_output() {
            if (!enabled || vol == 0) return 0;
            int offset = (has_sweep) ? 0 : 5; // Canal 1 vs Canal 2
            int duty = regs[offset + 1] >> 6; // Los dos bits superiores indican la forma del duty cycle
            return duty_table[duty][duty_step] * vol; // Se amplifica según el volumen
        }
    }

    // El funcionamiento es similar al square channel, solo que hay RAM dedicada para formar la onda
    // Esos valores se meten al buffer y se amplifican según el volumen
    private class WaveChannel {
        boolean enabled = false;
        boolean length_enabled = false;
        int freq_timer = 0;
        int wave_pos = 0;
        int length_counter = 0;

        boolean just_accessed = false;

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
                if (enabled && freq_timer <= 2) {
                    int offset = ((wave_pos + 1) & 31) >> 1;
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
                freq_timer = ((2048 - freq) << 1) + 6;
                wave_pos = 0;
            }
        }

        public void tick() {
            just_accessed = false; // Solo dura este ciclo T
            if (!enabled) return;
            freq_timer--;
            if (freq_timer <= 0) {
                int freq = regs[0x0D] | ((regs[0x0E] & 7) << 8);
                freq_timer += (2048 - freq) << 1; // Se recarga el timer
                wave_pos = (wave_pos + 1) & 31; // Avanza el sample a usar
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
            // Si el canal no está activo o el DAC está apagado
            if (!enabled || (regs[0x0A] & 0x80) == 0) return 0;
            // Esos 2 bits superiores son el volumen
            int vol_code = (regs[0x0C] >> 5) & 3;
            if (vol_code == 0) return 0;
            // Cada nibble es una muestra
            // Al dividir entre 2 se obtiene el byte exacto de la muestra
            int ram_byte = wave_ram[wave_pos >> 1];
            // Depende de si es paso par o impar, usamos una de las dos muestras del byte
            int sample = ((wave_pos & 1) == 0) ? (ram_byte >> 4) : (ram_byte & 0x0F);
            // Se ajusta el volumen
            if (vol_code == 1) return sample;
            if (vol_code == 2) return sample >> 1;
            return sample >> 2;
        }
    }

    // Usa un algoritmo LFSR con desplazamiento de bits y XOR para la generación de
    // números pseudoaleatorios
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

        // always @(posedge clk) begin
        public void tick() {
            if (!enabled) return;
            timer--;
            if (timer <= 0) { // Cuando se acaba el tiempo
                int nr43 = regs[0x12];
                int shift = nr43 >> 4;
                int div = nr43 & 7;
                int divisor = (div == 0) ? 8 : (div << 4);
                timer += divisor << shift; // Se recarga el timer

                // Linear Feedback Shift Register
                // Se toman los dos bits menos significativos y se les aplica xor
                // El resultado del xor marcará el número que se ingresará como MSB
                // cuando el registro sea desplazado a la derecha
                int xor_bit = (lfsr & 1) ^ ((lfsr >> 1) & 1);
                lfsr = (lfsr >> 1) | (xor_bit << 14);

                // Si el bit 3 de NR43 está activo, el registro se recorta
                // a 7 bits, en lugar de los 15
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
            // DAC de 1 solo bit
            // Ese único bit escala con el volumen
            return ((~lfsr) & 1) * vol;
        }
    }
}