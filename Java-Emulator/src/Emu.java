import javax.swing.*;        // Para JFrame y JPanel
import java.awt.*;           // Para Graphics y Dimension
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;


public class Emu {
    public static class emu_context {
        public boolean paused;
        public boolean running;
        public long ticks; // Registra el número total de ciclos procesados
    }

    private static emu_context ctx = new emu_context();

    public static emu_context emu_get_context() {
        return ctx;
    }

    public int emu_run(String[] args) {

        if (args.length < 1) {
            System.out.println("Usage: emu <rom_file>");
            return -1;
        }

        // Instanciamos Cartridge y cargamos ROM
        Cartridge cart = new Cartridge();
        if (!cart.cart_load(args[0])) {
            System.out.printf("Failed to load ROM file: %s\n", args[0]);
            return -2;
        }

        System.out.println("Cart loaded..");

        // Conexiones del Bus
        Bus.currentCart = cart;
        InstructionTable.init();

        // Inicializamos los componentes de la consola
        CPU cpu = new CPU();
        Ram currentRam = new Ram();
        DMA dma = new DMA();
        Interrupts intrp = new Interrupts();
        LCD lcd = new LCD();
        Gamepad gamepad = new Gamepad();
        MemoryMapped_IO io = new MemoryMapped_IO();
        Timer timer = new Timer();

        MemoryMapped_IO.lcd = lcd;
        MemoryMapped_IO.gamepad = gamepad;

        cpu.cpu_init();

        CB_InstructionTable.init();
        Bus.ready(io, dma, currentRam, intrp, timer);

        initGraphics();
        System.out.println("Graphics INIT");

        ctx.running = true;
        ctx.paused = false;
        ctx.ticks = 0;

        // -- CONFIGURACIÓN DE TIEMPO REAL (NANO-SEGUNDOS) --
        // La Game Boy corre a 59.7275 FPS exactos.
        long target_frame_time_ns = 16742706L;
        long last_frame_time = System.nanoTime();

        // Variables para el RADAR DE FPS
        long fps_timer = System.nanoTime();
        int frames_drawn = 0;

        while (ctx.running) {
            if (ctx.paused) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {}

                // Actualizamos el reloj al salir de pausa
                last_frame_time = System.nanoTime();
                fps_timer = System.nanoTime();
                continue;
            }

            // Ejecutamos un FRAME completo
            long start_ticks = ctx.ticks;


            long frame_ticks = 70224;

            while (ctx.ticks - start_ticks < frame_ticks) {
                cpu.cpu_step();
            }

            // Aquí es donde la PPU terminó de dibujar el frame; lo mostramos en ventana
            render();

            // -- FPS --
            frames_drawn++;

            // EL FRENO
            long current_time = System.nanoTime();
            long elapsed = current_time - last_frame_time;

            // Si terminamos de dibujar el frame muy rápido, esperamos
            if (elapsed < target_frame_time_ns) {
                long time_to_wait = target_frame_time_ns - elapsed;

                // Si sobra MUCHO tiempo (> 2ms), dejamos que el hilo duerma
                if (time_to_wait > 2000000L) {
                    try {
                        Thread.sleep((time_to_wait - 1000000L) / 1000000L);
                    } catch (InterruptedException e) {}
                }

                // Busy Wait: Atrapamos los últimos microsegundos exactos
                while (System.nanoTime() - last_frame_time < target_frame_time_ns) {
                    // No hacemos nada, solo observamos el reloj
                }
            }

            // FIX: Previene la "Espiral de la Muerte".
            // Reseteamos el reloj al tiempo real actual, no al teórico.
            last_frame_time = System.nanoTime();
        }

        return 0;
    }

    // -- VISUALIZACIÓN EN PANTALLA --

    private EmuWindow window;

    // -- VISUALIZACIÓN EN PANTALLA --

    public void initGraphics() {
        window = new EmuWindow();
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Llamamos al cartucho global que está en el Bus
                if (Bus.currentCart != null && Bus.currentCart.needs_save) {
                    Bus.currentCart.save_battery();
                }
                System.exit(0);
            }
        });
    }

    public void render() {
        if (window == null) return;
        window.updateImage(Bus.ppu.video_buffer);
    }
}