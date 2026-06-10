/*
 * Pipeline de composición de píxeles previos a su renderizado en pantalla
 * Ajustado para Zero-Drift, Hardware-Accurate Sprite Mixing y optimización de VRAM.
 */

import java.util.*;

public class PPU_Fetcher {
    private PPU ppu;
    public PPU_Fetcher(PPU ppu) {
        this.ppu = ppu;
    }

    public enum State { TILE, DATA0, DATA1, IDLE, PUSH }
    public State state = State.TILE;

    private int[] bgwData = new int[3];

    /*
     * Contadores del pipeline de renderizado (Pixel FIFO).
     * El flujo de datos sigue la secuencia: fetchX -> fifoX -> pushedX.
     */

    // Puntero a la tile en la VRAM
    public int fetchX;
    // Número de píxeles procesados de la cola
    public int fifoX;
    // Puntero de escritura al buffer de video / pantalla
    public int pushedX;
    // Filtro de scroll fino
    public int lineX;

    public boolean inWindowMode = false;
    private int currentMapY = 0;

    private int latched_scx = 0;
    private int latched_scy = 0;
    private int latched_lcdc = 0;
    private int latched_wx = 0;
    private int latched_wy = 0;

    public Queue<Integer> fifo = new LinkedList<>();

    // Estado por defecto de la FSM
    public void reset() {
        state = State.TILE;
        fetchX = 0; pushedX = 0; fifoX = 0;
        inWindowMode = false;
        fifo.clear();
    }

    // Procesar un píxel
    public void process() {
        // Si está empezando el proceso, se capturan y preservan los valores de los registros
        if (pushedX == 0 && fifoX == 0 && fifo.isEmpty()) {
            latched_scx = MemoryMapped_IO.lcd.getScrollX();
            latched_scy = MemoryMapped_IO.lcd.getScrollY();
            latched_lcdc = MemoryMapped_IO.lcd.getLcdc();
            latched_wx = MemoryMapped_IO.lcd.getWx();
            latched_wy = MemoryMapped_IO.lcd.getWy();
            lineX = 0;
        }

        // El pipeline corre a la mitad de velocidad del reloj
        if ((ppu.line_ticks & 0b01) == 0) {
            switch (state) {
                case TILE -> fetch_tile();
                case DATA0 -> fetch_data(0);
                case DATA1 -> fetch_data(1);
                case IDLE -> state = State.PUSH;
                case PUSH -> { if (add_to_fifo()) state = State.TILE; }
            }
        }
        push_pixel();
    }

    private boolean add_to_fifo() {
        // Si hay más de 8 píxeles en la cola, no se pueden agregar más
        if (fifo.size() > 8) return false;
        // Si no se está dibujando la ventana aplicamos scroll fino con módulo
        int fine_scroll = inWindowMode ? 0 : (latched_scx & 7);
        // Si el fondo no está activo, se pinta de transparente
        boolean bgEnabled = (latched_lcdc & 0x01) != 0;
        // Preparación de los objetos
        int numSprites = ppu.lineSprites.size();
        int[] sprite_b1 = new int[numSprites];
        int[] sprite_b2 = new int[numSprites];
        // Si los sprites están activados como visibles
        if ((latched_lcdc & 0x02) != 0) {
            // Se obtienen los datos de cada uno
            for (int s = 0; s < numSprites; s++) {
                PPU.SpriteEntry sp = ppu.lineSprites.get(s);
                // ¿De qué tamaño son los sprites?
                int spriteHeight = (latched_lcdc & 0x04) != 0 ? 16 : 8;
                // Posición vertical
                // Línea y actual - (posición y del objeto - offset de 16 píxeles para que sprites salgan de la pantalla)
                int py = MemoryMapped_IO.lcd.getLy() - (sp.y - 16);

                // ¿Está volteado verticalmente?
                if ((sp.flags & 0x40) != 0) py = (spriteHeight - 1) - py;

                int tileIndex = sp.tile;
                // Los índices deben ser pares para los sprites grandes
                if (spriteHeight == 16) tileIndex &= 0xFE;

                // Se leen los datos de los sprites desde el arreglo de tiles en memoria
                int tileAddr = 0x8000 + (tileIndex << 4) + (py << 1);
                sprite_b1[s] = ppu.vram_read(tileAddr);
                sprite_b2[s] = ppu.vram_read(tileAddr + 1);
            }
        }

        // Creación de los píxeles
        for (int i = 0; i < 8; ++i) {
            // Se lee desde el bit menos importante hasta el más importante
            int bit = 7 - i;
            // Se extrae individualmente el dato del píxel de cada byte
            int lo = (bgwData[1] >> bit) & 1;
            int hi = ((bgwData[2] >> bit) & 1) << 1;

            // Se obtiene el píxel (2 bits para 4 colores)
            int bg_color_idx = hi | lo;
            int pixel;
            if (bgEnabled) {
                // Se obtiene el color correspondiente
                pixel = MemoryMapped_IO.lcd.bg_colors[bg_color_idx] | 0xFF000000;
            } else {
                // Si el fondo no está activado, se escoge el color por defecto
                pixel = MemoryMapped_IO.lcd.bg_colors[0] | 0xFF000000;
            }

            // Si los objetos están visibles
            if ((latched_lcdc & 0x02) != 0) {
                // Coordenada absoluta en pantalla
                // número de píxeles procesados en la línea - scroll fino
                int current_screen_x = fifoX - fine_scroll;
                // Para cada sprite
                for (int s = 0; s < numSprites; s++) {
                    PPU.SpriteEntry sp = ppu.lineSprites.get(s);
                    int xPos = sp.x - 8; // offset del hardware, permite que sprites salgan de la pantalla por x
                    // ¿Estoy dibujando un sprite?
                    int offset = current_screen_x - xPos;
                    if (offset >= 0 && offset <= 7) { // Si estoy dibujando un sprite
                        // ¿Está invertido el sprite?
                        int sp_bit = (sp.flags & 0x20) != 0 ? offset : 7 - offset;
                        // Se lee el bit bajo y alto en los bytes y se unen para formar el píxel
                        int spColorIdx = ((sprite_b1[s] >> sp_bit) & 1) | (((sprite_b2[s] >> sp_bit) & 1) << 1);
                        if (spColorIdx != 0) { // Si no es transparente
                            // Se selecciona una de las dos paletas disponibles
                            int[] pal = (sp.flags & 0x10) != 0 ? MemoryMapped_IO.lcd.sp2_colors : MemoryMapped_IO.lcd.sp1_colors;
                            // Si el bit está prendido, el fondo tiene prioridad
                            boolean bgPriority = (sp.flags & 0x80) != 0;

                            // ¿El fondo es capaz de tapar a este sprite?
                            // Si el fondo no está activado, no tiene prioridad o si el fondo es transparente
                            if (!bgEnabled || !bgPriority || bg_color_idx == 0) {
                                pixel = pal[spColorIdx] | 0xFF000000; // Se dibuja el píxel del sprite
                            }
                            break; // Se dibuja el que tenga mayor prioridad (aparece antes en la memoria OAM)
                        }
                    }
                }
            }
            fifo.add(pixel); // Se agrega a la pila
            fifoX++;
        }
        fetchX += 8; // La tile completa
        return true;
    }

    private void fetch_tile() {
        int ly = MemoryMapped_IO.lcd.getLy(); // Scanline actual
        int map_y, map_x, area;

        if (inWindowMode) { // ¿La ventana está activa?
            map_y = ppu.window_line;
            map_x = fetchX;
            area = (latched_lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;
        } else {
            map_y = (ly + latched_scy) & 0xFF;
            map_x = (fetchX + latched_scx) & 0xFF;
            area = (latched_lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;
        }

        currentMapY = map_y;
        int mapAddr = area + ((map_y / 8) * 32) + ((map_x / 8) & 0x1F);
        bgwData[0] = ppu.vram_read(mapAddr);
        state = State.DATA0;
    }

    private void fetch_data(int plane) {
        int tile_row = (currentMapY & 0x07) << 1;
        int addr;

        if ((latched_lcdc & 0x10) != 0) {
            addr = 0x8000 + (bgwData[0] * 16) + tile_row + plane;
        } else {
            byte signed_id = (byte) bgwData[0];
            addr = 0x9000 + (signed_id * 16) + tile_row + plane;
        }

        bgwData[plane + 1] = ppu.vram_read(addr);
        state = (plane == 0) ? State.DATA1 : State.IDLE;
    }

    private void push_pixel() {
        if (ppu.getMode() != 3) return;

        boolean bgEnabled = (latched_lcdc & 0x01) != 0;
        boolean windowEnabled = (latched_lcdc & 0x20) != 0 && bgEnabled;

        int wy = latched_wy;
        int wx = latched_wx - 7;

        if (windowEnabled && MemoryMapped_IO.lcd.getLy() >= wy && pushedX >= wx && !inWindowMode) {
            inWindowMode = true;
            fifo.clear();
            state = State.TILE;
            fetchX = 0;
            fifoX = pushedX;
            return;
        }

        if (fifo.size() > 8) {
            int p = fifo.poll();

            boolean targetReached = inWindowMode || lineX >= (latched_scx & 7);

            if (targetReached) {
                if (pushedX < 160) {
                    int pixelIndex = pushedX + (MemoryMapped_IO.lcd.getLy() * 160);
                    ppu.video_buffer[pixelIndex] = p;
                    pushedX++;
                }
            }
            lineX++;
        }
    }

    public void fifo_reset() {
        state = State.TILE;
        pushedX = 0;
        fetchX = 0;
        fifoX = 0;
        lineX = 0;
        inWindowMode = false;
        fifo.clear();
    }
}