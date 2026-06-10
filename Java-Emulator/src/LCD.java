/*
* Controlador de pantalla
*/
import java.util.Arrays;

public class LCD {
    // Registros 0xFF40 a 0xFF4B, 12 bytes críticos
    // LCDC (0xFF40): El interruptor principal (¿está prendida la pantalla?, ¿mostramos sprites?, ¿dónde están los tiles?)
    // STAT (0xFF41): El estado del hardware (¿estamos en V-Blank?, ¿en H-Blank?)
    // ScrollY (0xFF42): Posición vertical de la cámara dentro del mapa de fondo (0-255)
    // ScrollX (0xFF43): Posición horizontal de la cámara dentro del mapa de fondo (0-255)
    // LY (0xFF44): La línea (scanline) que se está dibujando actualmente
    // LYC (0xFF45): LY Compare: El juego pone un valor aquí; si LY == LYC, se levanta una bandera en STAT
    // DMA (0xFF46): Escribir aquí inicia la transferencia rápida de datos de la RAM a la OAM (Sprites)
    // BGP (0xFF47): BG Palette Data: Define los 4 colores de gris para el fondo y la ventana
    // OBP0 (0xFF48): Object Palette 0 - Define los colores para los sprites que usen la paleta 0
    // OBP1 (0xFF49): Object Palette 1: - Define los colores para los sprites que usen la paleta 1
    // WY (0xFF4A): Window Y - Posición vertical donde comienza la "Ventana"
    // WX (0xFF4B): Window X - Posición horizontal de la Ventana (menos 7 por cuestiones de timing interno)

    //-- ANATOMÍA DE LCDC --

    // Bit 0: BG/Win Priority - 1: Fondo y Ventana visibles - 0:Solo se ven los Sprites.
    // Bit 1: OBJ Enable - 1: Sprites visibles - 0: Sprites invisibles
    // Bit 2: OBJ Size - 1: Sprites de 8x16 píxeles - 0: Sprites de 8x8 píxeles
    // Bit 3: BG Map - 1: Mapa de fondo en 0x9C00 - 0: Mapa de fondo en 0x9800
    // Bit 4: Data Select - 1: Tiles en 0x8000 (Unsigned) - 0: Tiles en 0x8800 (Signed)
    // Bit 5: Windows Enable - 1: Ventana visible - 0: Ventana oculta
    // Bit 6: Window Map - 1: Mapa de ventana en 0x9C00 - 0: Mapa de ventana en 0x9800
    // Bit 7: LCD Enable - 1: Pantalla encendida - 0: Pantalla apagada (VRAM accesible)

    //-- ANATOMÍA DE STAT --

    // Bit 0-1: Modo de la PPU, 00 H-Blank, 01 V-Blank, 10 OAM Search, 11 Pixel Transfer
    // Bit 2: LYC=LY Flag - Se pone en 1 si la línea actual (LY) es igual a la meta (LYC)
    // Bit 3: Mode 0 STAT - Si es 1, pide interrupción al entrar en H-Blank
    // Bit 4: Mode 1 STAT - Si es 1, pide interrupción al entrar en V-Blank
    // Bit 5: Mode 2 STAT - Si es 1, pide interrupción al entrar en OAM Search
    // Bit 6: LYC=LY STAT - Si es 1, pide interrupción cuando LY == LYC
    // Bit 7: Sin uso

    public LCD() {
        // 0x91 es el valor inicial de LCDC (Pantalla encendida)
        regs[0x00] = 0x91;

        // IMPORTANTE: El STAT (regs[0x01]) debe empezar en Modo 2 (OAM Search)
        // El valor 0x02 activa el Modo 2 y pone los bits de coincidencia en 0
        regs[0x01] = 0x02;

        regs[0x07] = 0xFC; // Paleta de fondo inicial
        update_palette(0xFC, 0);
    }

    public int[] regs = new int[0x0C];

    // Paletas procesadas (ARGB)
    public int[] bg_colors = new int[4]; // Fondo y ventana
    public int[] sp1_colors = new int[4]; // Sprites 1
    public int[] sp2_colors = new int[4]; // Sprites 2

    // 4 colores posibles del Game Boy, blanco puro (0xFFFFFFFF), gris claro (0xFFAAAAAA)
    // gris oscuro (0xFF555555) y negro puro (0xFF000000)
    // 00 = blanco, 01 = gris claro, 10 = gris xoscuro, 11 = negro, 2 bits para 4 colores
    private static final int[] COLORS_DEFAULT = {0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F};
    //private static final int[] COLORS_DEFAULT = {0xFFFFFFFF, 0xFFAAAAAA, 0xFF555555, 0xFF000000};

    public int getLcdc() { return regs[0x00]; }
    public int getStat() { return regs[0x01]; }
    public int getScrollY() { return regs[0x02]; }
    public int getScrollX() { return regs[0x03]; }
    public int getLy() { return regs[0x04] & 0xFF; }
    public void setLy(int v) { regs[0x04] = v & 0xFF; }
    public int getLyc() { return regs[0x05]; }
    public int getWinY() { return regs[0x0A]; }
    public int getWinX() { return regs[0x0B]; }

    public void setStat(int value) {
        regs[0x01] = value & 0xFF;
    }



    // 0xFF4A - Window Y Position (WY)
    public int getWy() {
        // Si usas un arreglo 'regs' donde el índice 0 es 0xFF40:
        return regs[0x0A] & 0xFF;

        // (Si no usas un arreglo 'regs', sino que lees del Bus, sería:
        // return Bus.bus_read(0xFF4A) & 0xFF;)
    }

    // 0xFF4B - Window X Position + 7 (WX)
    public int getWx() {
        return regs[0x0B] & 0xFF;
    }



    public int lcd_read(int addr) {
        int offset = addr - 0xFF40;
        return regs[offset] & 0xFF;
    }

    public void lcd_write(int addr, int val) {
        int offset = addr - 0xFF40;
        if (offset == 0x04) return; // LY es Read-Only

        if (offset == 0x01) { // Registro STAT
            // Protegemos bits 0-2 (Modo y LYC) para que la CPU no los borre
            int mask = 0b01111000;
            int currentStat = regs[0x01];
            regs[0x01] = (val & mask) | (currentStat & 0x07);
            return;
        }

        if (offset >= 0 && offset < regs.length) {
            regs[offset] = val & 0xFF;
        }

        if (addr == 0xFF46) Bus.dma.dma_init(val);
        if (addr == 0xFF47) update_palette(val, 0);
        if (addr == 0xFF48) update_palette(val & 0xFC, 1);
        if (addr == 0xFF49) update_palette(val & 0xFC, 2);
    }


    // Definición de la paleta
    private void update_palette(int data, int pal) {
        // Selección de la paleta (puntero de referencia)
        int[] p = (pal == 1) ? sp1_colors : (pal == 2) ? sp2_colors : bg_colors;

        // A cada elemento de la paleta le asigna 1 de los 4 colores posibles
        // En data se encuentran los datos de cada, cada color necesita 2 bits para ser referenciado
        for (int i = 0; i < 4; i++) {
            // Alineamos la cantidad de bits a desplazar (cada color tiene una referencia única de 2 bits)
            int bit = i << 1; // Multiplicación por 2
            p[i] = COLORS_DEFAULT[(data >> bit) & 0b11]; // selección del color en la paleta
        }
    }
}