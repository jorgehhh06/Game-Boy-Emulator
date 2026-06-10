/*
 * Mapea las direcciones de memoria de la CPU 0xFF00 - 0xFF7F
 * a los registros físicos de los componentes del Hardware
 */

public class MemoryMapped_IO {
    // Referencias a los componentes (ajusta los nombres según tus clases)

    //public static Interrupts intrp;
    public static Gamepad gamepad;
    public static LCD lcd;

    public static int io_read(int address) {
        address &= 0xFFFF;

        // 0xFF00: Gamepad
        if (address == 0xFF00) {
            return MemoryMapped_IO.gamepad.read();
        }
        // 0xFF04 - 0xFF07: Timer
        else if (address >= 0xFF04 && address <= 0xFF07) {
            return Bus.timer.timer_read(address);
        }
        // 0xFF0F: Interrupt Flag
        else if (address == 0xFF0F) {
            return Bus.intrp.get_if_register();
        }
        // 0xFF10 - 0xFF3F: Sound
        else if (address >= 0xFF10 && address <= 0xFF3F) {
            return Bus.apu.apu_read(address);
        }
        // 0xFF40 - 0xFF4B: LCD / PPU Registers
        else if (address >= 0xFF40 && address <= 0xFF4B) {
            return lcd.lcd_read(address);
        }

        // Si no es ninguna de las anteriores, devuelve 0xFF
        return 0xFF;
    }

    public static void io_write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;

        // 0xFF00: Gamepad selection
        if (address == 0xFF00) {
            MemoryMapped_IO.gamepad.write(value);
        }
        // 0xFF04 - 0xFF07: Timer
        else if (address >= 0xFF04 && address <= 0xFF07) {
            Bus.timer.timer_write(address, value);
        }
        // 0xFF0F: Interrupt Flag
        else if (address == 0xFF0F) {
            Bus.intrp.set_if_register(value);
        }
        // 0xFF10 - 0xFF3F: Sound
        else if (address >= 0xFF10 && address <= 0xFF3F) {
            Bus.apu.apu_write(address, value);
        }
        // 0xFF40 - 0xFF4B: LCD / PPU Registers
        else if (address >= 0xFF40 && address <= 0xFF4B) {
            lcd.lcd_write(address, value);
        }
    }
}