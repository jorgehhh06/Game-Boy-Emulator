/*
* Aquí es por donde los componentes se comunican con la memoria, es la Unidad de Gestión de Memoria (MMU)
*/
public class Bus {
    // Necesitamos una referencia al cartucho cargado
    public static Cartridge currentCart;

    public static int bus_read(int address) {
        // 0x0000 - 0x7FFF: ROM del Cartucho
        // 0x8000 - 0x9FFF: Video RAM (VRAM)
        // 0xFF00 - 0xFFFF: Registros de Hardware e Interrupciones
        if (address < 0x8000){
            return currentCart.cart_read(address);
        }
        // Aquí irían la RAM, Video RAM, etc.
        return 0;
    }

    public static void bus_write(int address, int value) {
        // Los cartuchos a veces usan las escrituras para cambiar de banco de memoria (MBC)
        if (address < 0x8000){
            currentCart.cart_write(address, value);
        }
    }
}