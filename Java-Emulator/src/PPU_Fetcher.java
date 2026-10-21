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

    // bgwData[0] guarda el índice de la tile
    // bgwData[1] guarda el byte bajo de la tile
    // bgwData[2] guarda el byte alto de la tile
    private int[] bgwData = new int[3];

    /*
     * Contadores del pipeline de renderizado (Pixel FIFO).
     * El flujo de datos sigue la secuencia: fetchX -> fifoX -> pushedX.
     */
    public int fetchX;  // Donde está buscando el fetcher en el mapa de tiles de la VRAM
    public int fifoX;   // Índice de la cola de dibujado
    public int pushedX; // Píxeles ya dibujados en pantalla (0 a 159)
    public int lineX;   // Píxeles extraídos del FIFO para descartar el scroll fino

    public boolean inWindowMode = false;
    private int currentMapY = 0;

    // Latch únicamente para el descarte fino de píxeles al inicio de la línea o ventana
    private int fine_scroll_discard = 0;
    private boolean line_initialized = false;

    // EL FIFO almacena ÚNICAMENTE el índice de color (0 a 3) del fondo/ventana.
    public Queue<Integer> fifo = new LinkedList<>();

    public void reset() {
        state = State.TILE;
        fetchX = 0;
        pushedX = 0;
        fifoX = 0;
        lineX = 0;
        inWindowMode = false;
        line_initialized = false;
        fifo.clear();
    }

    public void process() {
        // Al iniciar el Pixel Transfer de la línea, capturamos el descarte fino de SCX (SCX & 7)
        if (!line_initialized) {
            fine_scroll_discard = MemoryMapped_IO.lcd.getScrollX() & 0x07;
            lineX = 0;
            line_initialized = true;
        }

        // El Fetcher avanza de estado cada 2 T-Cycles (4 pasos x 2 T-Cycles = 8 T-Cycles por tile)
        if ((ppu.line_ticks & 0b01) == 0) {
            switch (state) {
                case TILE  -> fetch_tile();    // Ticks 1-2: Índice del tile
                case DATA0 -> fetch_data(0);   // Ticks 3-4: Byte bajo del tile
                case DATA1 -> fetch_data(1);   // Ticks 5-6: Byte alto del tile
                case IDLE, PUSH -> {           // Ticks 7-8: Push al FIFO
                    if (add_to_fifo()) {
                        state = State.TILE;
                    }
                }
            }
        }
        push_pixel(); // Se dibuja a 1 píxel por T-Cycle en paralelo con el Fetcher
    }

    // Agregar 8 píxeles a la cola FIFO
    private boolean add_to_fifo() {
        if (fifo.size() > 8) return false;

        for (int i = 0; i < 8; ++i) {
            int bit = 7 - i;
            int lo = (bgwData[1] >> bit) & 1;
            int hi = ((bgwData[2] >> bit) & 1) << 1;
            fifo.add(hi | lo); // ID de color crudo (0 a 3)
            fifoX++;
        }
        fetchX += 8;
        return true;
    }

    private void fetch_tile() {
        int ly = MemoryMapped_IO.lcd.getLy();
        int lcdc = MemoryMapped_IO.lcd.getLcdc();
        int scx = MemoryMapped_IO.lcd.getScrollX();
        int scy = MemoryMapped_IO.lcd.getScrollY();
        int map_y, map_x, area;

        if (inWindowMode) {
            map_y = ppu.window_line;
            map_x = fetchX;
            area = (lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;
        } else {
            map_y = (ly + scy) & 0xFF;
            map_x = (fetchX + scx) & 0xFF;
            area = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;
        }

        currentMapY = map_y;
        int mapAddr = area + ((map_y / 8) * 32) + ((map_x / 8) & 0x1F);
        bgwData[0] = ppu.vram_read(mapAddr);
        state = State.DATA0;
    }

    private void fetch_data(int plane) {
        int lcdc = MemoryMapped_IO.lcd.getLcdc();
        int tile_row = (currentMapY & 0x07) << 1;
        int addr;

        if ((lcdc & 0x10) != 0) {
            addr = 0x8000 + (bgwData[0] * 16) + tile_row + plane;
        } else {
            byte signed_id = (byte) bgwData[0];
            addr = 0x9000 + (signed_id * 16) + tile_row + plane;
        }

        bgwData[plane + 1] = ppu.vram_read(addr);
        // Al terminar DATA1 pasamos directo a PUSH para completar el tile en 8 T-Cycles
        state = (plane == 0) ? State.DATA1 : State.PUSH;
    }

    private void push_pixel() {
        if (ppu.getMode() != 3) return;

        // Leemos LCDC, WX y WY en vivo para respetar cambios a mitad de línea / interrupciones STAT
        int lcdc = MemoryMapped_IO.lcd.getLcdc();
        int ly = MemoryMapped_IO.lcd.getLy();
        boolean bgEnabled = (lcdc & 0x01) != 0;
        boolean windowEnabled = (lcdc & 0x20) != 0 && bgEnabled;
        int wy = MemoryMapped_IO.lcd.getWy();
        int rawWx = MemoryMapped_IO.lcd.getWx();
        int wx = rawWx - 7;

        // Activación del Modo Ventana cuando el haz alcanza WX - 7
        if (!inWindowMode && windowEnabled && ly >= wy && rawWx <= 166 && pushedX >= wx) {
            inWindowMode = true;
            fifo.clear();
            state = State.TILE;
            fetchX = 0;
            fifoX = pushedX;
            lineX = 0;
            // Si WX < 7, descartamos los primeros (7 - WX) píxeles de la ventana que caen fuera de pantalla
            fine_scroll_discard = (rawWx < 7) ? (7 - rawWx) : 0;
            return;
        }

        // Extraemos píxeles cuando el FIFO tiene datos listos
        if (fifo.size() > 8) {
            int bg_color_idx = fifo.poll();

            // Descarte fino horizontal (SCX & 7 para el fondo, o 7 - WX si la ventana empezó en WX < 7)
            if (lineX < fine_scroll_discard) {
                lineX++;
                return;
            }

            if (pushedX < 160) {
                // Si LCDC Bit 0 está apagado en DMG, el fondo/ventana fuerza el color 0
                int bg_pixel_idx = bgEnabled ? bg_color_idx : 0;
                int finalPixel = MemoryMapped_IO.lcd.bg_colors[bg_pixel_idx] | 0xFF000000;

                // MEZCLA DE SPRITES (Objetos):
                if ((lcdc & 0x02) != 0) {
                    int spriteHeight = (lcdc & 0x04) != 0 ? 16 : 8;
                    int numSprites = ppu.lineSprites.size();

                    for (int s = 0; s < numSprites; s++) {
                        PPU.SpriteEntry sp = ppu.lineSprites.get(s);
                        int xPos = sp.x - 8;
                        int offset = pushedX - xPos;

                        if (offset >= 0 && offset <= 7) {
                            int py = ly - (sp.y - 16);
                            if (py < 0 || py >= spriteHeight) continue;

                            // Inversión vertical (Y-Flip, Bit 6)
                            if ((sp.flags & 0x40) != 0) {
                                py = (spriteHeight - 1) - py;
                            }

                            int tileIndex = sp.tile;
                            if (spriteHeight == 16) {
                                tileIndex &= 0xFE;
                            }

                            int tileAddr = 0x8000 + (tileIndex << 4) + (py << 1);
                            int b1 = ppu.vram_read(tileAddr);
                            int b2 = ppu.vram_read(tileAddr + 1);

                            // Inversión horizontal (X-Flip, Bit 5)
                            int sp_bit = (sp.flags & 0x20) != 0 ? offset : 7 - offset;
                            int spColorIdx = ((b1 >> sp_bit) & 1) | (((b2 >> sp_bit) & 1) << 1);

                            // El color 0 del sprite es transparente; si no es 0, este sprite gana la capa de objetos
                            if (spColorIdx != 0) {
                                // Bit 7 = 1 significa que el Fondo/Ventana (colores 1-3) tiene prioridad sobre el Objeto
                                boolean bgHasPriority = (sp.flags & 0x80) != 0;

                                if (!bgHasPriority || bg_pixel_idx == 0) {
                                    int[] pal = (sp.flags & 0x10) != 0
                                            ? MemoryMapped_IO.lcd.sp2_colors
                                            : MemoryMapped_IO.lcd.sp1_colors;
                                    finalPixel = pal[spColorIdx] | 0xFF000000;
                                }
                                // El primer sprite con píxel opaco (menor X / menor índice OAM) bloquea a los demás sprites detrás
                                break;
                            }
                        }
                    }
                }

                int pixelIndex = pushedX + (ly * 160);
                ppu.video_buffer[pixelIndex] = finalPixel;
                pushedX++;
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
        line_initialized = false;
        fifo.clear();
    }
}