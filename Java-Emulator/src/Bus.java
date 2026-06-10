/*
* Aquí es por donde los componentes se comunican con la memoria, es la Unidad de Gestión de Memoria (MMU)
*/

// 0x0000 - 0x3FFF : ROM Bank 0
// 0x4000 - 0x7FFF : ROM Bank 1 - Switchable
// 0x8000 - 0x97FF : CHR RAM
// 0x9800 - 0x9BFF : BG Map 1
// 0x9C00 - 0x9FFF : BG Map 2
// 0xA000 - 0xBFFF : Cartridge RAM
// 0xC000 - 0xCFFF : RAM Bank 0
// 0xD000 - 0xDFFF : RAM Bank 1-7 - switchable - Color only
// 0xE000 - 0xFDFF : Reserved - Echo RAM
// 0xFE00 - 0xFE9F : Object Attribute Memory
// 0xFEA0 - 0xFEFF : Reserved - Unusable
// 0xFF00 - 0xFF7F : I/O Registers
// 0xFF80 - 0xFFFE : Zero Page

public class Bus {
    public static Cartridge currentCart;
    public static Ram currentRam;
    public static DMA dma;
    public static Interrupts intrp;
    public static MemoryMapped_IO io;
    public static Timer timer;
    public static PPU ppu = new PPU();
    public static APU apu = new APU();

    public static void ready(MemoryMapped_IO ioInstance, DMA dmaInstance, Ram ramInstance, Interrupts intrpInstance, Timer timerInstance) {
        Bus.io = ioInstance;
        Bus.dma = dmaInstance;
        Bus.currentRam = ramInstance;
        Bus.intrp = intrpInstance;
        Bus.timer = timerInstance;
    }


    public static int bus_read(int address) {
        address &= 0xFFFF;

        // 0x0000 - 0x7FFF: ROM
        if (address < 0x8000) {
            return currentCart.cart_read(address);
        }
        // 0x8000 - 0x9FFF: VRAM
        else if (address < 0xA000) {
            if (ppu.get_vram_blocked()) return 0xFF;
            return ppu.vram_read(address);
        }
        // 0xA000 - 0xBFFF: Cartridge RAM (External)
        else if (address < 0xC000) {
            return currentCart.cart_read(address);
        }
        // 0xC000 - 0xDFFF: Work RAM (WRAM)
        else if (address < 0xE000) {
            return currentRam.read_wram(address);
        }
        // 0xE000 - 0xFDFF: Echo RAM (Generalmente ignorada)
        else if (address < 0xFE00) {
            return 0;
        }
        // 0xFE00 - 0xFE9F: OAM (Sprites)
        else if (address < 0xFEA0) {
            if (dma.dma_transferring() || ppu.get_oam_blocked()) return 0xFF;
            return ppu.oam_read(address);
        }
        // 0xFEA0 - 0xFEFF: Reservado / Inutilizable
        else if (address < 0xFF00) {
            return 0;
        }
        // 0xFF00 - 0xFF7F: I/O Registers
        else if (address < 0xFF80) {
            // Caso especial para el registro IF que a veces da lata en el rango
            if (address == 0xFF0F) return Bus.intrp.get_if_register();
            return io.io_read(address);
        }
        // 0xFF80 - 0xFFFE: High RAM (HRAM)
        else if (address < 0xFFFF) {
            return currentRam.read_hram(address);
        }
        // 0xFFFF: Interrupt Enable Register (IE)
        else {
            return Bus.intrp.get_ie_register();
        }
    }

    public static void bus_write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;

        if (address < 0x8000) {
            currentCart.cart_write(address, value);
        }
        else if (address < 0xA000) {
            if (ppu.get_vram_blocked()) return;
            ppu.vram_write(address, value);
        }
        else if (address < 0xC000) {
            currentCart.cart_write(address, value);
        }
        else if (address < 0xE000) {
            currentRam.write_wram(address, value);
        }
        else if (address < 0xFE00) {
            // Echo RAM - No se escribe
        }
        else if (address < 0xFEA0) {
            if (dma.dma_transferring() || ppu.get_oam_blocked()) return;
            ppu.oam_write(address, value);
        }
        else if (address < 0xFF00) {
            // Reservado
        }
        else if (address < 0xFF80) {
            if (address == 0xFF0F) {
                Bus.intrp.set_if_register(value);
                return;
            }
            io.io_write(address, value);
        }
        else if (address < 0xFFFF) {
            currentRam.write_hram(address, value);
        }
        else {
            Bus.intrp.set_ie_register(value);
        }
    }
}