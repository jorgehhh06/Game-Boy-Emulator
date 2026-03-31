/*
* Esta clase gestiona el estado global de la emulación.
*/
public class Emu {

    public static class emu_context {
        public boolean paused;
        public boolean running;
        public long ticks; // u64 en C es long en Java, registra el número total de ciclos procesados
    }

    private static emu_context ctx = new emu_context();


    public static emu_context emu_get_context() { // Permite ver a las demás clases el contexto de la ejecución
        return ctx;
    }

    // int emu_run(int argc, char **argv);
    // En Java, args ya es el array de strings, no necesitas argc
    public int emu_run(String[] args) {

        if (args.length < 1) {
            System.out.println("Usage: emu <rom_file>");
            return -1;
        }

        // Instanciamos Cartridge para usar cart_load
        Cartridge cart = new Cartridge();
        if (!cart.cart_load(args[0])) {
            System.out.printf("Failed to load ROM file: %s\n", args[0]);
            return -2;
        }

        System.out.println("Cart loaded..");

        // Aquí iría la inicialización de gráficos (JavaFX o similar)
        System.out.println("Graphics INIT");


        Bus.currentCart = cart;           // Conecta el Bus al juego
        InstructionTable.init();


        // Instanciamos y cargamos el CPU
        CPU cpu = new CPU();
        cpu.cpu_init();

        ctx.running = true;
        ctx.paused = false;
        ctx.ticks = 0;

        while (ctx.running) {
            Common common = new Common();
            if (ctx.paused) {
                common.delay(10); // Temporal, lo quitaré más adelante
                continue;
            }

            if (!cpu.cpu_step()) {
                System.out.println("CPU Stopped");
                return -3;
            }

            ctx.ticks++;
        }

        return 0;
    }

}