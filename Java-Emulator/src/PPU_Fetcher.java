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
    public int fetchX; // Donde está buscando el fetcher en el mapa de tiles de la vram
    public int fifoX; // Índice de la cola de dibujado
    public int pushedX; // Píxeles ya dibujados
    public int lineX; // Píxeles que han salido del fifo incluso si son descartados

    public boolean inWindowMode = false;
    private int currentMapY = 0;

    // Registros de hardware en latches para preservar el valor en el tiempo
    private int latched_scx = 0; // Posición horizontal de la pantalla
    private int latched_scy = 0; // Posición vertical de la pantalla
    private int latched_lcdc = 0; // Registro de control
    private int latched_wx = 0; // Posición horizontal de la ventana
    private int latched_wy = 0; // Posición vertical d ela ventana

    // EL FIFO almacena ÚNICAMENTE el índice de color (0 a 3) del fondo/ventana.
    // Esto blinda la información de los sprites frente a limpiezas como fifo.clear().
    public Queue<Integer> fifo = new LinkedList<>();

    public void reset() {
        state = State.TILE;
        fetchX = 0; pushedX = 0; fifoX = 0;
        inWindowMode = false;
        fifo.clear();
    }

    public void process() {
        // Inicio del ciclo Pixel Transfer
        // Se guardan en registros intermedios el valor de los registros para conservar su valor
        if (pushedX == 0 && fifoX == 0 && fifo.isEmpty()) {
            latched_scx = MemoryMapped_IO.lcd.getScrollX();
            latched_scy = MemoryMapped_IO.lcd.getScrollY();
            latched_lcdc = MemoryMapped_IO.lcd.getLcdc();
            latched_wx = MemoryMapped_IO.lcd.getWx();
            latched_wy = MemoryMapped_IO.lcd.getWy();
            lineX = 0;
        }

        // El píxel transfer corre a la mitad de la velocidad del reloj global
        if ((ppu.line_ticks & 0b01) == 0) {
            switch (state) {
                case TILE -> fetch_tile(); // Se consigue la información de la tile
                case DATA0 -> fetch_data(0); // Se obtiene el primer byte
                case DATA1 -> fetch_data(1); // Se obtiene el segundo byte
                case IDLE -> state = State.PUSH; // Pausa para sincronización
                case PUSH -> { if (add_to_fifo()) state = State.TILE; } // Se agrega a la cola de píxeles
            }
        }
        push_pixel(); // Se dibuja a la vez que pasan los estados, es un pipeline
    }

    // Agregar a la cola de píxeles
    private boolean add_to_fifo() {
        if (fifo.size() > 8) return false; // Se deben tener al menos 8 píxeles en el fifo en cada instante

        // El Fetcher procesa exclusivamente los datos de tiles del fondo/ventana.
        for (int i = 0; i < 8; ++i) {
            int bit = 7 - i; // Debido al endianess de la consola, la lectura debe invertirse para la lógica del emulador
            int lo = (bgwData[1] >> bit) & 1;
            int hi = ((bgwData[2] >> bit) & 1) << 1; // << 1 mueve el bit a la parte alta
            fifo.add(hi | lo); // Se introduce únicamente el ID de paleta (0 a 3)
            fifoX++;
        }
        fetchX += 8; // Se suma 8 de los 8 píxeles de cada tile dibujado
        return true;
    }

    private void fetch_tile() {
        // Ly es la scanline actual
        int ly = MemoryMapped_IO.lcd.getLy();
        int map_y, map_x, area;

        // Esto es un multiplexor, los registros map_x, map_y y área se conectan a cables diferentes según la entrada
        if (inWindowMode) {
            map_y = ppu.window_line;
            map_x = fetchX;
            // El registro LCDC controla el mapa de memoria a utilizar
            area = (latched_lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;
        } else {
            map_y = (ly + latched_scy) & 0xFF;
            map_x = (fetchX + latched_scx) & 0xFF;
            area = (latched_lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;
        }

        //
        currentMapY = map_y;
        // Area + offset, cada línea vertical representa 32 tiles horizontales en memoria
        // La división entre 8 ayuda a obtener el índice de la tile en memoria
        int mapAddr = area + ((map_y / 8) * 32) + ((map_x / 8) & 0x1F);
        // Se leen los datos de la tile y se almacenan en los registros
        bgwData[0] = ppu.vram_read(mapAddr);
        state = State.DATA0;
    }

    private void fetch_data(int plane) {
        // Se le aplica % 8 (& 7) al mapa en y para scroll fino de tiles
        // << 1 (multiplicar por 2) porque cada renglón tile ocupa 2 bytes en ram
        // Así se encuentra a cuántos bytes de distancia está del inicio de la tile
        int tile_row = (currentMapY & 0x07) << 1;
        int addr;

        // El dato en el mapa de memoria es el índice del arreglo de tiles en memoria
        // 8 renglones * 2 bytes = 16 bytes (por eso el 16 en el multiplexor)
        if ((latched_lcdc & 0x10) != 0) {
            // En este modo el mapa de memoria tiene offset de 0 a 255
            addr = 0x8000 + (bgwData[0] * 16) + tile_row + plane;
        } else {
            // En este modo con complemento a 2, el offset va de -128 a 127
            // Se hizo para ahorrar memoria
            byte signed_id = (byte) bgwData[0];
            addr = 0x9000 + (signed_id * 16) + tile_row + plane;
        }
        // Se pasa a la siguiente parte
        bgwData[plane + 1] = ppu.vram_read(addr); // Leer la otra parte de la tile
        state = (plane == 0) ? State.DATA1 : State.IDLE; // O cambio de modo
    }

    private void push_pixel() {
        // Si no nos encontramos en Píxel Transfer, no dibujamos
        if (ppu.getMode() != 3) return;

        // Cuando se apaga el fondo se muestra en pantalla el color 0 (blanco/transparente)
        boolean bgEnabled = (latched_lcdc & 0x01) != 0;
        boolean windowEnabled = (latched_lcdc & 0x20) != 0 && bgEnabled;
        int wy = latched_wy;
        int wx = latched_wx - 7; // Retraso físico de 8 píxeles al posicionar la ventana horizontalmente

        // Al entrar en Modo Ventana, limpiamos el fondo pendiente sin afectar la capa de sprites
        if (windowEnabled && MemoryMapped_IO.lcd.getLy() >= wy && pushedX >= wx && !inWindowMode) {
            inWindowMode = true;
            fifo.clear();
            state = State.TILE;
            fetchX = 0;
            fifoX = pushedX;
            return;
        }

        // Solo se dibuja si hay más de 8 píxeles en el fifo
        if (fifo.size() > 8) {
            // Obtenemos el valor del color en el fifo
            int bg_color_idx = fifo.poll();

            // Filtro de descarte para el scroll fino horizontal (SCX & 7)
            // Si lineX todavía no alcanza ese residuo de scroll, el píxel se bota a la basura
            // Descartar píxeles para scroll fino cuesta un dot
            boolean targetReached = inWindowMode || lineX >= (latched_scx & 7);

            if (targetReached) {
                if (pushedX < 160) {
                    // Si el fondo está apagado, se fuerza el blanco
                    int bg_pixel_idx = bgEnabled ? bg_color_idx : 0;
                    int finalPixel = MemoryMapped_IO.lcd.bg_colors[bg_pixel_idx] | 0xFF000000;

                    // MEZCLA TARDÍA DE SPRITES (Late Mixing):

                    if ((latched_lcdc & 0x02) != 0) { // ¿Los objetos están encendidos?
                        int numSprites = ppu.lineSprites.size(); // Se revisa el tamaño de la cola de sprites
                        for (int s = 0; s < numSprites; s++) {
                            PPU.SpriteEntry sp = ppu.lineSprites.get(s);
                            int xPos = sp.x - 8; // Offset del hardware de 8 píxeles horizontales
                            int offset = pushedX - xPos; // A qué distancia se encuentra el sprite del píxel actual

                            // Comprobamos si el haz de pantalla actual toca la anchura de este sprite
                            if (offset >= 0 && offset <= 7) {
                                int spriteHeight = (latched_lcdc & 0x04) != 0 ? 16 : 8;
                                int py = MemoryMapped_IO.lcd.getLy() - (sp.y - 16); // offset vertical

                                // Inversión vertical (Y-Flip)
                                // Se cambia la posición absoluta del sprite
                                // Su altura - 1 (por cómo funcionan los arreglos en las computadoras a base de offsets)
                                // El elemento 7 o 15 del arreglo de píxeles
                                // Al restarse el py original se obliga a recorrerse de abajo hacia arriba
                                if ((sp.flags & 0x40) != 0) py = (spriteHeight - 1) - py;

                                // Índice de la tile
                                int tileIndex = sp.tile;

                                // Si la altura es de 16, se fuerza el índice a ser par para evitar el desalineamiento
                                // altura de 2 ocupa 2 tiles
                                if (spriteHeight == 16) tileIndex &= 0xFE;

                                // Se lee desde VRAM los 4 bytes del objeto
                                // Byte 0 = Posición Y
                                // Byte 1 = Posición X
                                // Byte 2 = Índice de la tile
                                // Byte 3 = Flags
                                // Bit 4: Selección de paleta
                                // Bit 5: Inversión horizontal
                                // Bit 6: Inversión vertical
                                // Bit 7: Prioridad del fondo
                                // Cada renglón de píxeles ocupa 2 bytes
                                // py te da el renglón actual y aplicas offset de 2 bytes
                                // Estamos leyendo el dato de la tile
                                int tileAddr = 0x8000 + (tileIndex << 4) + (py << 1);
                                // Leemos byte alto y bajo
                                int b1 = ppu.vram_read(tileAddr);
                                int b2 = ppu.vram_read(tileAddr + 1);

                                // Inversión horizontal (X-Flip)
                                int sp_bit = (sp.flags & 0x20) != 0 ? offset : 7 - offset;

                                // Mezcla de bits para obtener el color
                                int spColorIdx = ((b1 >> sp_bit) & 1) | (((b2 >> sp_bit) & 1) << 1);

                                if (spColorIdx != 0) { // El color 0 es transparente para los sprites, no se dibuja si es 0
                                    // Si el bit 7 de la flag del objeto es 1, el objeto tiene prioridad sobre el fondo
                                    boolean bgPriority = (sp.flags & 0x80) != 0;

                                    // Regla de prioridad de hardware DMG:
                                    // El sprite se dibuja si no tiene prioridad de fondo,
                                    // o si el píxel del fondo es transparente (índice 0),
                                    // o si la capa de fondo está apagada por completo en LCDC.
                                    if (!bgPriority || bg_color_idx == 0 || !bgEnabled) {
                                        // Se selecciona la paleta y se obtiene el color final que será mandado al DAC
                                        int[] pal = (sp.flags & 0x10) != 0 ? MemoryMapped_IO.lcd.sp2_colors : MemoryMapped_IO.lcd.sp1_colors;
                                        finalPixel = pal[spColorIdx] | 0xFF000000;
                                    }
                                    // Al estar ordenados por prioridad OAM/eje X, el primer sprite coincidente bloquea al resto
                                    break;
                                }
                            }
                        }
                    }

                    // Envío al búfer final de salida
                    int pixelIndex = pushedX + (MemoryMapped_IO.lcd.getLy() * 160);
                    ppu.video_buffer[pixelIndex] = finalPixel;
                    pushedX++; // Aumento del número de píxeles en pantalla
                }
            }
            lineX++; // Se incrementa el número de píxeles que han salido del fifo incluso si fueron descartados
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