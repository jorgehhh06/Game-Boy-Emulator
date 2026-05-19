import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/*
 * Extrae la información del cartucho y se encarga de gestionar los datos de lectura/escritura, guardado y memory banking.
 * Ahora soporta MBC1, MBC3 y MBC5.
 */

public class Cartridge {
    // Estructura del Header
    public static class RomHeader {
        public String title;
        public int type;
        public int rom_size;
        public int ram_size;
        public int lic_code;
        public int version;
        public int checksum;
    }

    // -- Información básica del cartucho --
    private String filename;
    private int rom_size;
    private byte[] rom_data;
    private RomHeader header = new RomHeader();

    // -- Detección de Hardware --
    private boolean isMBC1 = false;
    private boolean isMBC2 = false;
    private boolean isMBC3 = false;
    private boolean isMBC5 = false;

    // -- Estado del MBC --
    private boolean sram_enabled = false;
    private int rom_bank_value = 1;
    private int rom_bank_offset = 0x4000;
    private int banking_mode = 0; // Solo usado por MBC1

    // -- Static RAM (SRAM) --
    private int [][] sram_banks;
    private int current_sram_bank = 0;

    // -- Guardado con batería --
    boolean has_battery = false;
    boolean needs_save = false;

    // Tipos de cartucho
    private static final String[] ROM_TYPES = {
            "ROM ONLY", "MBC1", "MBC1+RAM", "MBC1+RAM+BATTERY", "0x04 ???",
            "MBC2", "MBC2+BATTERY", "0x07 ???", "ROM+RAM 1", "ROM+RAM+BATTERY 1",
            "0x0A ???", "MMM01", "MMM01+RAM", "MMM01+RAM+BATTERY", "0x0E ???",
            "MBC3+TIMER+BATTERY", "MBC3+TIMER+RAM+BATTERY 2", "MBC3", "MBC3+RAM 2",
            "MBC3+RAM+BATTERY 2", "0x14 ???", "0x15 ???", "0x16 ???", "0x17 ???",
            "0x18 ???", "MBC5", "MBC5+RAM", "MBC5+RAM+BATTERY", "MBC5+RUMBLE",
            "MBC5+RUMBLE+RAM", "MBC5+RUMBLE+RAM+BATTERY", "0x1F ???", "MBC6",
            "0x21 ???", "MBC7+SENSOR+RUMBLE+RAM+BATTERY"
    };

    // Publisher
    private static final String[] LIC_CODE = new String[0xA5];
    static {
        LIC_CODE[0x00] = "None";
        LIC_CODE[0x01] = "Nintendo R&D1";
        LIC_CODE[0x08] = "Capcom";
        // ... (resto de tus LIC_CODE, los resumo aquí por brevedad, deja los tuyos)
        LIC_CODE[0x31] = "Nintendo";
        LIC_CODE[0xA4] = "Konami (Yu-Gi-Oh!)";
    }

    public String cart_lic_name() {
        if (header.lic_code <= 0xA4 && LIC_CODE[header.lic_code] != null) {
            return LIC_CODE[header.lic_code];
        }
        return "UNKNOWN";
    }

    public String cart_type_name() {
        if (header.type <= 0x22) {
            return ROM_TYPES[header.type];
        }
        return "UNKNOWN";
    }

    public boolean cart_load(String cartPath) {
        this.filename = cartPath;

        try {
            this.rom_data = Files.readAllBytes(Paths.get(cartPath));
            this.rom_size = rom_data.length;

            System.out.println("Opened: " + this.filename);

            StringBuilder sb = new StringBuilder();
            for (int i = 0x0134; i <= 0x0143; i++) {
                if (rom_data[i] == 0) break;
                sb.append((char) rom_data[i]);
            }
            header.title = sb.toString();

            header.type = rom_data[0x0147] & 0xFF;
            header.rom_size = rom_data[0x0148] & 0xFF;
            header.ram_size = rom_data[0x0149] & 0xFF;
            header.lic_code = rom_data[0x014B] & 0xFF;
            header.version = rom_data[0x014C] & 0xFF;
            header.checksum = rom_data[0x014D] & 0xFF;

            System.out.println("Cartridge Loaded:");
            System.out.println("\t Title    : " + header.title);
            System.out.println("\t Type     : " + String.format("%02X", header.type) + " (" + cart_type_name() + ")");
            System.out.println("\t ROM Size : " + (32 << header.rom_size) + " KB");
            System.out.println("\t RAM Size : " + String.format("%02X", header.ram_size));
            System.out.println("\t LIC Code : " + String.format("%02X", header.lic_code) + " (" + cart_lic_name() + ")");
            System.out.println("\t ROM Vers : " + String.format("%02X", header.version));

            int x = 0;
            for (int i = 0x0134; i <= 0x014C; i++) {
                x = x - (rom_data[i] & 0xFF) - 1;
            }

            System.out.println(String.format("\t Checksum : %02X (%s)", header.checksum,
                    ((x & 0xFF) == header.checksum) ? "PASSED" : "FAILED"));

            setup_banking();
            check_battery();
            load_battery();
            return true;
        } catch (IOException e) {
            System.out.println("Failed to open: " + cartPath);
            return false;
        }
    }

    public int cart_read(int address) {
        // ROM Banco 0
        if (address < 0x4000) {
            return rom_data[address] & 0xFF;
        }
        // ROM Banco Variable
        else if (address < 0x8000) {
            int target = rom_bank_offset + (address - 0x4000);
            return (target < rom_data.length) ? rom_data[target] & 0xFF : 0xFF;
        }
        // SRAM / RTC
        else if (address < 0xC000) {
            if (!sram_enabled) return 0xFF;

            // CASO MBC2
            if (isMBC2) {
                // Solo 512 bytes disponibles, se repite (mirroring) en el rango 0xA000-0xBFFF
                // Devuelve nibble bajo. Los bits 4-7 siempre devuelven 1 en hardware real.
                return (sram_banks[0][address & 0x1FF] & 0x0F) | 0xF0;
            }

            // CASO MBC1, 3, 5
            if (sram_banks != null && sram_banks.length > 0) {
                int target_bank = current_sram_bank % sram_banks.length;
                if (isMBC1 && banking_mode == 0) target_bank = 0;
                return sram_banks[target_bank][address - 0xA000] & 0xFF;
            }

            // Simulación RTC para MBC3
            if (isMBC3 && current_sram_bank >= 0x08 && current_sram_bank <= 0x0C) {
                return (int)(System.currentTimeMillis() / 1000) & 0xFF;
            }
        }
        return 0xFF;
    }

     public void cart_write(int address, int value) {
        // -- CONTROL DE BANKING (0x0000 - 0x3FFF) --
        if (address < 0x4000) {
            if (isMBC2) {
                // Si el bit 8 de la dirección es 0: Control de RAM
                if ((address & 0x0100) == 0) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                }
                // Si el bit 8 de la dirección es 1: Cambio de Banco ROM
                else {
                    rom_bank_value = value & 0x0F;
                    if (rom_bank_value == 0) rom_bank_value = 1;
                }
            }
            else if (isMBC1) {
                if (address < 0x2000) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                } else {
                    int bank = value & 0x1F;
                    if (bank == 0) bank = 1;
                    rom_bank_value = (rom_bank_value & 0xE0) | bank;
                }
            }
            else if (isMBC3) {
                if (address < 0x2000) sram_enabled = ((value & 0x0F) == 0x0A);
                else {
                    int bank = value & 0x7F;
                    if (bank == 0) bank = 1;
                    rom_bank_value = bank;
                }
            }
            else if (isMBC5) {
                if (address < 0x2000) sram_enabled = ((value & 0x0F) == 0x0A);
                else if (address < 0x3000) rom_bank_value = (rom_bank_value & 0x100) | (value & 0xFF);
                else rom_bank_value = (rom_bank_value & 0xFF) | ((value & 1) << 8);
            }

            // Aplicar Mirroring de ROM general
            int total_rom_banks = rom_data.length / 0x4000;
            if (total_rom_banks > 0) {
                rom_bank_value %= total_rom_banks;
                if (rom_bank_value == 0) rom_bank_value = 1;
                rom_bank_offset = rom_bank_value * 0x4000;
            }
        }

        // -- SELECTOR DE BANCO RAM / RTC (0x4000 - 0x5FFF) --
        else if (address < 0x6000) {
            if (isMBC1) {
                int val = value & 0x03;
                if (banking_mode == 1) current_sram_bank = val;
                else {
                    rom_bank_value = (rom_bank_value & 0x1F) | (val << 5);
                    int total_rom_banks = rom_data.length / 0x4000;
                    rom_bank_value %= total_rom_banks;
                    if (rom_bank_value == 0) rom_bank_value = 1;
                    rom_bank_offset = rom_bank_value * 0x4000;
                }
            }
            else if (isMBC3) current_sram_bank = value;
            else if (isMBC5) current_sram_bank = value & 0x0F;
        }

        // -- CAMBIO DE MODO / LATCH RTC (0x6000 - 0x7FFF) --
        else if (address < 0x8000) {
            if (isMBC1) banking_mode = value & 0x01;
        }

        // -- ESCRITURA EN SRAM (0xA000 - 0xBFFF) --
        else if (address < 0xC000) {
            if (sram_enabled && sram_banks != null) {
                if (isMBC2) {
                    sram_banks[0][address & 0x1FF] = value & 0x0F;
                } else if (sram_banks.length > 0) {
                    int target_bank = current_sram_bank % sram_banks.length;
                    if (isMBC1 && banking_mode == 0) target_bank = 0;
                    sram_banks[target_bank][address - 0xA000] = value & 0xFF;
                }
                needs_save = true;
            }
        }
    }

    public void setup_banking() {
        int type = header.type;

        // Detección de hardware
        isMBC1 = (type >= 0x01 && type <= 0x03);
        isMBC2 = (type == 0x05 || type == 0x06);
        isMBC3 = (type >= 0x0F && type <= 0x13);
        isMBC5 = (type >= 0x19 && type <= 0x1E);

        if (isMBC2) {
            // El MBC2 tiene 512 bytes de RAM interna (en realidad 512 nibbles)
            sram_banks = new int[1][512];
            for(int i = 0; i < 512; i++) sram_banks[0][i] = 0x0F; // Inicializar a 1s
            System.out.println("Hardware: MBC2 Internal RAM (512 nibbles)");
        } else {
            // Lógica estándar para otros MBCs
            int num_banks = 0;
            switch (header.ram_size) {
                case 0: num_banks = 0; break;
                case 2: num_banks = 1; break;
                case 3: num_banks = 4; break;
                case 4: num_banks = 16; break;
                case 5: num_banks = 8; break;
            }

            if (num_banks > 0) {
                sram_banks = new int[num_banks][0x2000];
                for (int i = 0; i < num_banks; i++) {
                    for (int j = 0; j < 0x2000; j++) sram_banks[i][j] = 0xFF;
                }
                System.out.println("SRAM: " + num_banks + " bancos de 8KB");
            }
        }
        System.out.printf("Hardware detectado -> MBC1: %b | MBC2: %b | MBC3: %b | MBC5: %b\n", isMBC1, isMBC2, isMBC3, isMBC5);
    }

    private void check_battery() {
        int t = header.type;
        // Tipos con batería en MBC1, MBC2, MBC3, MBC5 y ROM+RAM
        this.has_battery = (t == 3 || t == 6 || t == 9 || t == 0x0F || t == 0x10 || t == 0x13 || t == 0x1B || t == 0x1E);
    }

    public void load_battery() {
        if (!has_battery) return;
        String savePath = filename.replace(".gb", ".sav");
        try (java.io.InputStream is = new java.io.FileInputStream(savePath)) {
            for (int i = 0; i < sram_banks.length; i++) {
                byte[] temp = new byte[0x2000];
                int bytesRead = is.read(temp);
                if (bytesRead > 0) {
                    for (int j = 0; j < bytesRead; j++) {
                        sram_banks[i][j] = temp[j] & 0xFF;
                    }
                }
            }
            System.out.println("Partida cargada desde: " + savePath);
        } catch (IOException e) {
            System.out.println("No se encontró archivo de guardado previo.");
        }
    }

    public void save_battery() {
        if (!has_battery || sram_banks == null) return;
        String savePath = filename.replace(".gb", ".sav");
        try (java.io.OutputStream os = new java.io.FileOutputStream(savePath)) {
            for (int[] bank : sram_banks) {
                byte[] temp = new byte[bank.length];
                for (int i = 0; i < bank.length; i++) {
                    temp[i] = (byte) bank[i];
                }
                os.write(temp);
            }
            System.out.println("Partida guardada en: " + savePath);
        } catch (IOException e) {
            System.err.println("Error al guardar partida: " + e.getMessage());
        }
    }
}