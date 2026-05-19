public class Ram {
    private int[] wram = new int[0x2000]; // 8KB (C000-DFFF)
    private int[] hram = new int[0x80]; // 128 bytes (FF80-FFFF) - Aunque FF es IE

    // Escribir en WRAM
    public void write_wram(int address, int value) {
        address -= 0xC000;
        if (address >= 0 && address < 0x2000) {
            wram[address] = value & 0xFF;
        }
    }

    // Leer de WRAM
    public int read_wram(int address) {
        address -= 0xC000;
        if (address >= 0 && address < 0x2000) {
            return wram[address];
        }
        return 0xFF;
    }

    // Escribir en HRAM
    public void write_hram(int address, int value) {
        address -= 0xFF80; // ¡USA LA RESTA!
        if (address >= 0 && address < 0x80) {
            hram[address] = value & 0xFF;
        }
    }

    // Leer de HRAM
    public int read_hram(int address) {
        address -= 0xFF80; // ¡USA LA RESTA!
        if (address >= 0 && address < 0x80) {
            return hram[address];
        }
        return 0xFF;
    }
}