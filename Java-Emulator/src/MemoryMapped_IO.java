/*
 * Mapea las direcciones de memoria de la CPU 0xFF00 - 0xFF7F
 * a los registros físicos de los componentes del Hardware
 */

public class MemoryMapped_IO {

    // Link Cable, comunicación entre dos consolas mediante un protocolo tipo SPI
    private static int[] serial_data = new int[2];

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
        // 0xFF01 - 0xFF02: Serial Data
        else if (address == 0xFF01) {
            return serial_data[0];
        }
        else if (address == 0xFF02) {
            return serial_data[1];
        }
        // 0xFF04 - 0xFF07: Timer
        else if (address >= 0xFF04 && address <= 0xFF07) {
            return Bus.timer.timer_read(address);
        }
        // 0xFF0F: Interrupt Flag
        else if (address == 0xFF0F) {
            return Bus.intrp.get_if_register();
        }
        // 0xFF10 - 0xFF3F: Sound (Ignorado por ahora)
        else if (address >= 0xFF10 && address <= 0xFF3F) {
            return 0;
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
        // 0xFF01 - 0xFF02: Serial Data
        else if (address == 0xFF01) {
            serial_data[0] = value;
        }
        else if (address == 0xFF02) {
            serial_data[1] = value;
        }
        // 0xFF04 - 0xFF07: Timer
        else if (address >= 0xFF04 && address <= 0xFF07) {
            Bus.timer.timer_write(address, value);
        }
        // 0xFF0F: Interrupt Flag
        else if (address == 0xFF0F) {
            Bus.intrp.set_if_register(value);
        }
        // 0xFF10 - 0xFF3F: Sound (Ignorado de momento)
        else if (address >= 0xFF10 && address <= 0xFF3F) {
            // No hacemos nada
        }
        // 0xFF40 - 0xFF4B: LCD / PPU Registers
        else if (address >= 0xFF40 && address <= 0xFF4B) {
            lcd.lcd_write(address, value);
        }
    }
}