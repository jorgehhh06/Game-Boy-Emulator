import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/*
* Extrae la información del cartucho
*/

public class Cartridge {

    // Estructura del Header
    public static class RomHeader {
        public int[] entry = new int[4];
        public int[] logo = new int[0x30];
        public String title;
        public int new_lic_code;
        public int sgb_flag;
        public int type;
        public int rom_size;
        public int ram_size;
        public int dest_code;
        public int lic_code;
        public int version;
        public int checksum;
        public int global_checksum;
    }

    // Contexto del cartucho
    private String filename;
    private int rom_size;
    private byte[] rom_data;
    private RomHeader header = new RomHeader();

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

    // Tipo de licencia
    public String cart_lic_name() {
        if (header.lic_code <= 0xA4 && LIC_CODE[header.lic_code] != null) {
            return LIC_CODE[header.lic_code];
        }
        return "UNKNOWN";
    }

    // Tipo de cartucho
    public String cart_type_name() {
        if (header.type <= 0x22) {
            return ROM_TYPES[header.type];
        }
        return "UNKNOWN";
    }


    // Carga el cartucho a memoria (todavía no del sistema)
    public boolean cart_load(String cartPath) {
        this.filename = cartPath;

        try {
            this.rom_data = Files.readAllBytes(Paths.get(cartPath));
            this.rom_size = rom_data.length;

            System.out.println("Opened: " + this.filename);

            // Mapeo manual del header (rom_data + 0x100)
            // Title: 0x0134 a 0x0143
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

            // Cálculo del Checksum (u16 x en C)
            int x = 0;
            for (int i = 0x0134; i <= 0x014C; i++) {
                x = x - (rom_data[i] & 0xFF) - 1;
            }

            System.out.println(String.format("\t Checksum : %02X (%s)", header.checksum,
                    ((x & 0xFF) == header.checksum) ? "PASSED" : "FAILED"));

            return true;
        } catch (IOException e) {
            System.out.println("Failed to open: " + cartPath);
            return false;
        }
    }

    public int cart_read(int address){
        return rom_data[address] & 0xFF;
    }

    public void cart_write(int address, int value){

    }
}