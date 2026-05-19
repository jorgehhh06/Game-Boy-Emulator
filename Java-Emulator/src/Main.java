/*
* Para ejecutar una ROM se debe introducir en args el nombre de una ROM como 'zelda.gb', 'mario.gb' o 'pokemon.gb'
*/
public class Main {
    public static void main(String[] args) {
        // Verificamos una ROM
        if (args.length < 1) {
            System.out.println("Error: Debes pasar la ruta.");
            return;
        }

        // Instanciamos el motor del emulador
        Emu emulador = new Emu();

        // Arrancamos
        // Esto llamará a cart_load, imprimirá los datos y empezará el loop
        int resultado = emulador.emu_run(args);

        if (resultado != 0) {
            System.out.println("El emulador cerró con código: " + resultado);
        }
    }
}