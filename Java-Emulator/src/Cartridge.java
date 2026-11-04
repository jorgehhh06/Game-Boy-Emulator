import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/*
 * Extrae la información del cartucho y se encarga de gestionar los datos de
 * lectura/escritura, guardado con batería y memory banking.
 *
 * Soporta: ROM ONLY, MBC1, MBC2, MBC3 (con RTC completo para Pokémon Oro/Plata/Cristal) y MBC5.
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
    private int banking_mode = 0; // Solo usado por MBC1 (0 = ROM Mode, 1 = RAM/Advanced Mode)

    // -- Variables para el Reloj de Tiempo Real (RTC) del MBC3 --
    private boolean rtc_latch_prep = false;
    private final int[] rtc_regs = new int[5];         // 0:Seg, 1:Min, 2:Horas, 3:Días(Low), 4:Días(High)+Flags
    private final int[] latched_rtc_regs = new int[5]; // Copia congelada al hacer Latch (0x00 -> 0x01)
    private long rtc_last_time = System.currentTimeMillis() / 1000;

    // -- Static RAM (SRAM) --
    private int[][] sram_banks;
    private int current_sram_bank = 0;

    // -- Guardado con batería --
    public boolean has_battery = false;
    public boolean needs_save = false;

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
        LIC_CODE[0x13] = "Electronic Arts";
        LIC_CODE[0x18] = "Hudson Soft";
        LIC_CODE[0x19] = "b-ai";
        LIC_CODE[0x20] = "kss";
        LIC_CODE[0x22] = "pow";
        LIC_CODE[0x24] = "PCM Complete";
        LIC_CODE[0x25] = "san-x";
        LIC_CODE[0x28] = "Kemco Japan";
        LIC_CODE[0x29] = "seta";
        LIC_CODE[0x30] = "Viacom";
        LIC_CODE[0x31] = "Nintendo";
        LIC_CODE[0x32] = "Bandai";
        LIC_CODE[0x33] = "Ocean/Acclaim";
        LIC_CODE[0x34] = "Konami";
        LIC_CODE[0x35] = "Hector";
        LIC_CODE[0x37] = "Taito";
        LIC_CODE[0x38] = "Hudson";
        LIC_CODE[0x39] = "Banpresto";
        LIC_CODE[0x41] = "Ubi Soft";
        LIC_CODE[0x42] = "Atlus";
        LIC_CODE[0x44] = "Malibu";
        LIC_CODE[0x46] = "angel";
        LIC_CODE[0x47] = "Bullet-Proof";
        LIC_CODE[0x49] = "irem";
        LIC_CODE[0x50] = "Absolute";
        LIC_CODE[0x51] = "Acclaim";
        LIC_CODE[0x52] = "Activision";
        LIC_CODE[0x53] = "American sammy";
        LIC_CODE[0x54] = "Konami";
        LIC_CODE[0x55] = "Hi tech entertainment";
        LIC_CODE[0x56] = "LJN";
        LIC_CODE[0x57] = "Matchbox";
        LIC_CODE[0x58] = "Mattel";
        LIC_CODE[0x59] = "Milton Bradley";
        LIC_CODE[0x60] = "Titus";
        LIC_CODE[0x61] = "Virgin";
        LIC_CODE[0x64] = "LucasArts";
        LIC_CODE[0x67] = "Ocean";
        LIC_CODE[0x69] = "Electronic Arts";
        LIC_CODE[0x70] = "Infogrames";
        LIC_CODE[0x71] = "Interplay";
        LIC_CODE[0x72] = "Broderbund";
        LIC_CODE[0x73] = "sculptured";
        LIC_CODE[0x75] = "sci";
        LIC_CODE[0x78] = "THQ";
        LIC_CODE[0x79] = "Accolade";
        LIC_CODE[0x80] = "misawa";
        LIC_CODE[0x83] = "lozc";
        LIC_CODE[0x86] = "Tokuma Shoten Intermedia";
        LIC_CODE[0x87] = "Tsukuda Original";
        LIC_CODE[0x91] = "Chunsoft";
        LIC_CODE[0x92] = "Video system";
        LIC_CODE[0x93] = "Ocean/Acclaim";
        LIC_CODE[0x95] = "Varie";
        LIC_CODE[0x96] = "Yonezawa/s’pal";
        LIC_CODE[0x97] = "Kaneko";
        LIC_CODE[0x99] = "Pack in soft";
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
        address &= 0xFFFF;

        // ROM Banco 0 (0x0000 - 0x3FFF)
        if (address < 0x4000) {
            // En MBC1 modo 1 (Avanzado), el banco 0 puede desplazarse a 0x20, 0x40 o 0x60 en ROMs > 512KB
            if (isMBC1 && banking_mode == 1) {
                int total_rom_banks = rom_data.length / 0x4000;
                int mask = (total_rom_banks > 0) ? (total_rom_banks - 1) : 0;
                int bank0 = (rom_bank_value & 0x60) & mask;
                int target = (bank0 * 0x4000) + address;
                return (target < rom_data.length) ? (rom_data[target] & 0xFF) : 0xFF;
            }
            return rom_data[address] & 0xFF;
        }
        // ROM Banco Variable (0x4000 - 0x7FFF)
        else if (address < 0x8000) {
            int target = rom_bank_offset + (address - 0x4000);
            return (target < rom_data.length) ? (rom_data[target] & 0xFF) : 0xFF;
        }
        // SRAM / RTC (0xA000 - 0xBFFF)
        else if (address >= 0xA000 && address < 0xC000) {
            if (!sram_enabled) return 0xFF;

            // CASO MBC2: 512 nibbles con mirroring en 0xA000-0xBFFF y bits 4-7 en 1
            if (isMBC2) {
                return (sram_banks[0][address & 0x1FF] & 0x0F) | 0xF0;
            }

            // CASO MBC3 RTC: Bancos 0x08 a 0x0C devuelven los registros LATCHED del reloj
            if (isMBC3 && current_sram_bank >= 0x08 && current_sram_bank <= 0x0C) {
                return latched_rtc_regs[current_sram_bank - 0x08] & 0xFF;
            }

            // CASO MBC1, MBC3, MBC5 (Memoria SRAM estándar)
            if (sram_banks != null && sram_banks.length > 0 && current_sram_bank < 0x08) {
                int target_bank = current_sram_bank % sram_banks.length;
                if (isMBC1 && banking_mode == 0) {
                    target_bank = 0;
                }
                return sram_banks[target_bank][address - 0xA000] & 0xFF;
            }
        }
        return 0xFF;
    }

    public void cart_write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;

        // -- CONTROL DE BANKING Y HABILITACIÓN DE RAM (0x0000 - 0x3FFF) --
        if (address < 0x4000) {
            if (isMBC2) {
                // En MBC2 el bit 8 de la dirección decide entre RAM Enable (0) y ROM Bank (1)
                if ((address & 0x0100) == 0) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                } else {
                    rom_bank_value = value & 0x0F;
                    if (rom_bank_value == 0) rom_bank_value = 1;
                    update_rom_bank();
                }
            }
            else if (isMBC1) {
                if (address < 0x2000) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                } else {
                    int bank = value & 0x1F;
                    if (bank == 0) bank = 1;
                    rom_bank_value = (rom_bank_value & 0x60) | bank;
                    update_rom_bank();
                }
            }
            else if (isMBC3) {
                if (address < 0x2000) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                } else {
                    rom_bank_value = value & 0x7F;
                    if (rom_bank_value == 0) rom_bank_value = 1;
                    update_rom_bank();
                }
            }
            else if (isMBC5) {
                if (address < 0x2000) {
                    sram_enabled = ((value & 0x0F) == 0x0A);
                } else if (address < 0x3000) {
                    rom_bank_value = (rom_bank_value & 0x100) | value;
                    update_rom_bank();
                } else {
                    rom_bank_value = (rom_bank_value & 0xFF) | ((value & 0x01) << 8);
                    update_rom_bank();
                }
            }
        }

        // -- SELECTOR DE BANCO RAM / RTC (0x4000 - 0x5FFF) --
        else if (address < 0x6000) {
            if (isMBC1) {
                int val = value & 0x03;
                current_sram_bank = val;
                rom_bank_value = (rom_bank_value & 0x1F) | (val << 5);
                update_rom_bank();
            }
            else if (isMBC3) {
                current_sram_bank = value;
            }
            else if (isMBC5) {
                current_sram_bank = value & 0x0F;
            }
        }

        // -- CAMBIO DE MODO (MBC1) / LATCH DEL RTC (MBC3) (0x6000 - 0x7FFF) --
        else if (address < 0x8000) {
            if (isMBC1) {
                banking_mode = value & 0x01;
            }
            else if (isMBC3) {
                if (value == 0x00) {
                    rtc_latch_prep = true;
                } else if (value == 0x01) {
                    if (rtc_latch_prep) {
                        update_rtc();
                        System.arraycopy(rtc_regs, 0, latched_rtc_regs, 0, 5);
                    }
                    rtc_latch_prep = false;
                } else {
                    rtc_latch_prep = false;
                }
            }
        }

        // -- ESCRITURA EN SRAM / RTC (0xA000 - 0xBFFF) --
        else if (address >= 0xA000 && address < 0xC000) {
            if (!sram_enabled) return;

            // Escritura en los registros del RTC del MBC3 (0x08 - 0x0C)
            if (isMBC3 && current_sram_bank >= 0x08 && current_sram_bank <= 0x0C) {
                update_rtc();
                int reg = current_sram_bank - 0x08;
                switch (reg) {
                    case 0:
                        rtc_regs[0] = value & 0x3F; // Segundos (0-59)
                        rtc_last_time = System.currentTimeMillis() / 1000;
                        break;
                    case 1:
                        rtc_regs[1] = value & 0x3F; // Minutos (0-59)
                        break;
                    case 2:
                        rtc_regs[2] = value & 0x1F; // Horas (0-23)
                        break;
                    case 3:
                        rtc_regs[3] = value & 0xFF; // Días (bits 0-7)
                        break;
                    case 4:
                        rtc_regs[4] = value & 0xC1; // Bit 0: Día bit 8 | Bit 6: Halt | Bit 7: Day Carry
                        break;
                }
                latched_rtc_regs[reg] = rtc_regs[reg];
                needs_save = true;
                return;
            }

            // Escritura en SRAM normal o interna de MBC2
            if (sram_banks != null) {
                if (isMBC2) {
                    sram_banks[0][address & 0x1FF] = (value & 0x0F) | 0xF0;
                    needs_save = true;
                } else if (sram_banks.length > 0 && current_sram_bank < 0x08) {
                    int target_bank = current_sram_bank % sram_banks.length;
                    if (isMBC1 && banking_mode == 0) {
                        target_bank = 0;
                    }
                    sram_banks[target_bank][address - 0xA000] = value;
                    needs_save = true;
                }
            }
        }
    }

    // Avanza el reloj interno del MBC3 según el tiempo real transcurrido
    private void update_rtc() {
        long now = System.currentTimeMillis() / 1000;
        long elapsed = now - rtc_last_time;
        rtc_last_time = now;

        // Si el bit 6 (0x40) del registro 0x0C está activo, el reloj está en HALT (pausado)
        if ((rtc_regs[4] & 0x40) != 0 || elapsed <= 0) {
            return;
        }

        long totalSeconds = elapsed + rtc_regs[0];
        rtc_regs[0] = (int) (totalSeconds % 60);

        long totalMinutes = (totalSeconds / 60) + rtc_regs[1];
        rtc_regs[1] = (int) (totalMinutes % 60);

        long totalHours = (totalMinutes / 60) + rtc_regs[2];
        rtc_regs[2] = (int) (totalHours % 24);

        long elapsedDays = totalHours / 24;
        if (elapsedDays > 0) {
            int currentDays = (rtc_regs[3] & 0xFF) | ((rtc_regs[4] & 0x01) << 8);
            long totalDays = currentDays + elapsedDays;

            if (totalDays > 511) {
                rtc_regs[4] |= 0x80; // Activar Bit 7 (Day Counter Carry / Overflow)
                totalDays %= 512;
            }

            rtc_regs[3] = (int) (totalDays & 0xFF);
            rtc_regs[4] = (rtc_regs[4] & 0xFE) | (int) ((totalDays >> 8) & 0x01);
        }
    }

    // Actualiza el offset del banco ROM actual aplicando la máscara del tamaño físico del cartucho
    private void update_rom_bank() {
        int total_rom_banks = rom_data.length / 0x4000;
        if (total_rom_banks > 0) {
            int bank = rom_bank_value;
            // EXCLUSIVO de MBC1: si los 5 bits bajos son 0 (0x00, 0x20, 0x40, 0x60), suma 1.
            // En MBC3 los bancos 0x20, 0x40 y 0x60 son completamente válidos.
            if (isMBC1 && (bank & 0x1F) == 0) {
                bank |= 1;
            }

            int mask = total_rom_banks - 1;
            int effective_bank = bank & mask;
            rom_bank_offset = effective_bank * 0x4000;
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
            // El MBC2 tiene 512 nibbles de RAM integrada en el propio chip
            sram_banks = new int[1][512];
            for (int i = 0; i < 512; i++) {
                sram_banks[0][i] = 0xFF;
            }
            System.out.println("Hardware: MBC2 Internal RAM (512 nibbles)");
        } else {
            int num_banks = 0;
            switch (header.ram_size) {
                case 0: num_banks = 0; break;
                case 1: num_banks = 1; break; // 2KB (algunos homebrew/prototipos)
                case 2: num_banks = 1; break; // 8KB
                case 3: num_banks = 4; break; // 32KB (4 bancos de 8KB)
                case 4: num_banks = 16; break; // 128KB (16 bancos de 8KB)
                case 5: num_banks = 8; break; // 64KB (8 bancos de 8KB)
            }

            if (num_banks > 0) {
                sram_banks = new int[num_banks][0x2000];
                for (int i = 0; i < num_banks; i++) {
                    for (int j = 0; j < 0x2000; j++) {
                        sram_banks[i][j] = 0xFF;
                    }
                }
                System.out.println("SRAM: " + num_banks + " bancos de 8KB");
            }
        }
        System.out.printf("Hardware detectado -> MBC1: %b | MBC2: %b | MBC3: %b | MBC5: %b\n",
                isMBC1, isMBC2, isMBC3, isMBC5);
    }

    private void check_battery() {
        int t = header.type;
        this.has_battery = (t == 0x03 || t == 0x06 || t == 0x09 || t == 0x0D ||
                t == 0x0F || t == 0x10 || t == 0x13 || t == 0x1B || t == 0x1E);
    }

    // Genera la ruta .sav de forma segura sin riesgo de sobrescribir la ROM original
    private String getSavePath() {
        if (filename.matches("(?i).*\\.gbc?$")) {
            return filename.replaceAll("(?i)\\.gbc?$", ".sav");
        }
        return filename + ".sav";
    }

    public void load_battery() {
        if (!has_battery) return;
        String savePath = getSavePath();

        try (DataInputStream dis = new DataInputStream(new FileInputStream(savePath))) {
            if (sram_banks != null) {
                for (int i = 0; i < sram_banks.length; i++) {
                    byte[] temp = new byte[sram_banks[i].length];
                    int bytesRead = dis.read(temp);
                    if (bytesRead > 0) {
                        for (int j = 0; j < bytesRead; j++) {
                            sram_banks[i][j] = temp[j] & 0xFF;
                        }
                    }
                }
            }

            // Si es MBC3 y el .sav incluye los 48 bytes del estado del RTC al final
            if (isMBC3 && dis.available() >= 48) {
                for (int i = 0; i < 5; i++) rtc_regs[i] = dis.readInt() & 0xFF;
                for (int i = 0; i < 5; i++) latched_rtc_regs[i] = dis.readInt() & 0xFF;
                rtc_last_time = dis.readLong();
                update_rtc(); // Avanza el reloj por todo el tiempo que el emulador estuvo cerrado
            }
            System.out.println("Partida cargada desde: " + savePath);
        } catch (IOException e) {
            System.out.println("No se encontró archivo de guardado previo.");
        }
    }

    public void save_battery() {
        if (!has_battery) return;
        String savePath = getSavePath();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(savePath))) {
            if (sram_banks != null) {
                for (int[] bank : sram_banks) {
                    byte[] temp = new byte[bank.length];
                    for (int i = 0; i < bank.length; i++) {
                        temp[i] = (byte) bank[i];
                    }
                    dos.write(temp);
                }
            }

            // Persistir el estado del RTC al final del archivo .sav (48 bytes)
            if (isMBC3) {
                update_rtc();
                for (int i = 0; i < 5; i++) dos.writeInt(rtc_regs[i]);
                for (int i = 0; i < 5; i++) dos.writeInt(latched_rtc_regs[i]);
                dos.writeLong(rtc_last_time);
            }
            System.out.println("Partida guardada en: " + savePath);
        } catch (IOException e) {
            System.err.println("Error al guardar partida: " + e.getMessage());
        }
    }
}