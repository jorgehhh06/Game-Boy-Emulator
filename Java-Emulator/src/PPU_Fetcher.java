/*
 * Pipeline de composición de píxeles previos a su renderizado en pantalla
 */

import java.util.*;

public class PPU_Fetcher {
    private PPU ppu;
    public PPU_Fetcher(PPU ppu) {
        this.ppu = ppu;
    }

    // Máquina de estados finitos (Autómata)
    // Alfabeto de estados del autómata
    public enum State { TILE, DATA0, DATA1, IDLE, PUSH }
    // Estado inicial del autómata
    public State state = State.TILE;

    private int[] bgwData = new int[3]; // Background/Window Data. Almacén de construcción de tiles
    public int fetchX, pushedX, fifoX; // Punteros de control
    // fetchX - Coordenada actual que se está trabajando en pantalla
    // pushedX - Contador de píxeles que ya fueron validados y escritos en el búfer de video
    // fifoX - Contador interno que rastrea cuántos píxeles ha intentado meter el Fetcher a la cola en la línea actual

    public int lineX;

    // -- VARIABLES PARA LA VENTANA --
    private boolean fetchingWindow = false;
    private int currentMapY = 0; // Guarda la Y calculada para que fetch_data no se confunda

    // Declaración del pipeline
    public Queue<Integer> fifo = new LinkedList<>();

    public void reset() {
        state = State.TILE;
        fetchX = 0; pushedX = 0; fifoX = 0;
        fifo.clear();
    }

    public void process() {
        // El fetcher avanza cada 2 T-Ticks (frecuencia de la PPU)
        if ((ppu.line_ticks & 0b01) == 0) { // & 0b01 es equivalente a hacer % 2
            // Función de cambio de estado
            switch (state) {
                // TILE -> DATA0 -> DATA1 -> IDLE
                // Los pasos para construir una fila de 8 píxeles (parte de una tile)
                case TILE -> fetch_tile();
                case DATA0 -> fetch_data(0);
                case DATA1 -> fetch_data(1);
                case IDLE -> state = State.PUSH; // Control de ciclos (no hace nada por 2 T-Cycles)
                case PUSH -> { if (add_to_fifo()) state = State.TILE; }
            }
        }
        // Flujo constante hacia el buffer, mientras se hace fetch y se trabajan los píxeles mientras se dibujan
        // Eso es el pipeline de video
        push_pixel();
    }


    private boolean add_to_fifo() {
        // Leemos el scroll en vivo, y si estamos en la ventana, forzamos el descarte a 0
        int scx = MemoryMapped_IO.lcd.getScrollX();
        int fine_scroll = inWindowMode ? 0 : (scx & 7);

        // Solo se pone en la pila si hay menos de 8 elementos en la pila
        if (fifo.size() >= 8) return false;

        // Masking para aislar bits individuales
        for (int i = 0; i < 8; ++i) {
            int bit = 7 - i; // Cuántas veces vamos a recorrer el byte de datos
            // Checamos bits de izquierda a derecha
            int lo = (bgwData[1] >> bit) & 1;
            int hi = ((bgwData[2] >> bit) & 1) << 1;
            // Reconstruimos el píxel completo (2 bits - 4 colores)
            int bg_color_idx = hi | lo;

            // Estándares modernos pide 0xFF al inicio de un color de 32 bits
            // Se checa el correspondiente obtenido con esos 2 bits
            int pixel = MemoryMapped_IO.lcd.bg_colors[bg_color_idx] | 0xFF000000;

            // -- PIXEL MIXER (SPRITES) --
            if ((MemoryMapped_IO.lcd.getLcdc() & 0x02) != 0) { // OBJ Enable, ¿Hay sprites visibles?

                // Calculamos en qué coordenada X de la PANTALLA caerá este píxel
                // fifoX es el total de píxeles generados. Le restamos el scroll fino (que es 0 si estamos en la ventana)
                // porque esos primeros píxeles se van a ir a la basura en push_pixel()
                // Esto permite scroll fino en el fondo y evita el "Window Sprite Wiggle"
                int current_screen_x = fifoX - fine_scroll;

                // Para cada sprite en la línea
                for (PPU.SpriteEntry sp : ppu.lineSprites) {
                    int xPos = sp.x - 8; // Posición real en pantalla según hardware
                    int offset = current_screen_x - xPos;
                    // ¿El píxel de la pantalla actual cae dentro de los 8px del sprite?
                    // Los sprites miden 8px de alto, por eso offset >= 0 && offset <= 7
                    if (offset >= 0 && offset <= 7) {
                        // Reversión de orden de lectura
                        int sp_bit = (sp.flags & 0x20) != 0 ? offset : 7 - offset; // X-Flip
                        int spriteHeight = (MemoryMapped_IO.lcd.getLcdc() & 0x04) != 0 ? 16 : 8;

                        int yPos = sp.y - 16;
                        int py = MemoryMapped_IO.lcd.getLy() - yPos; // Posición vertical

                        if ((sp.flags & 0x40) != 0) py = (spriteHeight - 1) - py; // Y-Flip

                        // -- En modo 8x16, el bit menos significativo se ignora --
                        int tileIndex = sp.tile;
                        if (spriteHeight == 16) {
                            tileIndex &= 0xFE; // Máscara de hardware obligatoria
                            // Al quitar el bit menos significativo, obligamos a que el número sea par
                            // Esto es porque cada tile es de 16 bytes, si permitimos impar se corta la tile
                        }
                        // Leer datos del tile del sprite
                        // La dirección base + el índice * 16 bytes por tile + línea en dibujo * 2 bytes por fila de píxeles
                        int tileAddr = 0x8000 + (tileIndex << 4) + (py << 1);
                        int b1 = ppu.vram_read(tileAddr); // Se lee el byte con bits menos significativos
                        int b2 = ppu.vram_read(tileAddr + 1); // Byte con bits más significativos

                        // Aislar el bit y juntarlos
                        int spColorIdx = ((b1 >> sp_bit) & 1) | (((b2 >> sp_bit) & 1) << 1);

                        // -- Arreglo de la prioridad --
                        if (spColorIdx != 0) { // Si el sprite NO es transparente

                            int[] pal = (sp.flags & 0x10) != 0 ? MemoryMapped_IO.lcd.sp2_colors : MemoryMapped_IO.lcd.sp1_colors;
                            boolean bgPriority = (sp.flags & 0x80) != 0;

                            // ¿El sprite le gana al fondo? (O si el fondo es transparente)
                            if (!bgPriority || bg_color_idx == 0) {
                                pixel = pal[spColorIdx] | 0xFF000000;
                            }
                            // Gane o pierda contra el fondo, este sprite ya le ganó
                            // en prioridad a los sprites que están debajo de él.
                            // Ocultamos el resto rompiendo el ciclo.
                            break;
                        }
                    }
                }
            }
            // Avanzar la Queue
            fifo.add(pixel);
            fifoX++;
        }
        fetchX += 8; // El fetcher acaba de procesar 8 píxeles (un tile completo)
        return true;
    }
    private void fetch_tile() {
        int lcdc = MemoryMapped_IO.lcd.getLcdc(); // LCDC es el registro de control de la PPU
        boolean windowActive = (lcdc & 0x20) != 0; // Bit 5

        // Posición x de la ventana
        int wy = MemoryMapped_IO.lcd.getWy();
        // Posición y de la pantalla
        int wx = MemoryMapped_IO.lcd.getWx() - 7; // El hardware tiene un offset de 7 pixeles en WX
        int ly = MemoryMapped_IO.lcd.getLy(); // Scanline actual

        // ¿La ventana está activa y dentro de la parte que se está dibujando en este instante?
        fetchingWindow = windowActive && ly >= wy && fetchX >= wx;

        int map_y, map_x, area; // Datos de lo que se va a dibujar

        if (inWindowMode) {
            map_y = ppu.window_line;
            map_x = fetchX; // La ventana es plana.
            area = (lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;
        } else {
            // -- FONDO (BACKGROUND) --
            // Sumas la línea actual (ly/fetchC) a la coordenada
            // donde empieza la pantalla (ScrollY/ScrollX)
            map_y = (ly + MemoryMapped_IO.lcd.getScrollY()) & 0xFF;
            map_x = (fetchX + MemoryMapped_IO.lcd.getScrollX()) & 0xFF;
            area = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800; // El fondo usa el Bit 3
        }

        // Guardamos la Y para que fetch_data sepa qué fila de la tile sacar
        currentMapY = map_y;
        // Calculamos la dirección de memoria exacta del Tile ID
        // (map_x >> 3) & 0x1F asegura que no nos salgamos de los 32 tiles de ancho
        // Dividir entre 8 convierte de píxeles a tiles
        // Multiplicar por 32 salta a la siguiente línea
        // Mapeo de un espacio vectorial R2 a R1

        // mapAddr es el índice del arreglo de datos a acceder
        // Los mapas en memoria son arreglos de índices, mapAddr es el índice a la posición del mapa
        // que contiene el índice para acceder al arreglo de datos de dibujo
        int mapAddr = area + ((map_y >> 3) << 5) + ((map_x >> 3) & 0x1F);

        // Leer datos y cambiar de estado
        bgwData[0] = ppu.vram_read(mapAddr); // Guardas el índice del array de tiles en bgwData[0]
        state = State.DATA0;
    }

    private void fetch_data(int plane) {
        // En lugar de recalcular con ScrollY, usamos la Y que determinó fetch_tile
        // tile_row es la línea específica (0-7) dentro del tile de 8x8
        // currentMapY es la coordenada vertical absoluta, el uso de & 0x07 permite obtener
        // el píxel exacto en la tile, esto es usado para scroll suave sin saltos bruscos
        int tile_row = (currentMapY & 0x07) << 1;
        int addr;

        if ((MemoryMapped_IO.lcd.getLcdc() & 0x10) != 0) {
            // Modo 0x8000 (Unsigned): Tile ID * 16 bytes que ocupa cada tile
            addr = 0x8000 + (bgwData[0] * 16) + tile_row + plane; // bgwData[0] es el índice de la tile
            // addr es la dirección de la tile
        } else {
            // Modo 0x8800 (Signed): ID 0 está en 0x9000
            byte signed_id = (byte) bgwData[0];
            addr = 0x9000 + (signed_id * 16) + tile_row + plane;
        }

        // Se guarda en bgwData[1] el byte con los bits menos significativos
        // y en bgwData[2] el byte con los bits maás significativos
        bgwData[plane + 1] = ppu.vram_read(addr);

        // Cambio a DATA1 o IDLE dependiendo del byte que se está leyendo
        state = (plane == 0) ? State.DATA1 : State.IDLE;
    }


    public boolean inWindowMode = false;
    // -- TRANSFERIR AL BUFFER DE VIDEO --
    private void push_pixel() {
        // -- DETECCIÓN DE VENTANA EN TIEMPO REAL --
        int lcdc = MemoryMapped_IO.lcd.getLcdc();
        boolean windowEnabled = (lcdc & 0x20) != 0;
        int wy = MemoryMapped_IO.lcd.getWy();
        int wx = MemoryMapped_IO.lcd.getWx() - 7;

        // Si la ventana está habilitada, chocamos con WX, la línea es correcta, y NO estamos ya en la ventana
        if (windowEnabled && MemoryMapped_IO.lcd.getLy() >= wy && pushedX >= wx && !inWindowMode) {
            inWindowMode = true;
            fifo.clear();               // Tiramos a la basura lo que quedaba del fondo
            state = State.TILE;         // Reiniciamos el Fetcher
            fetchX = 0;                 // La ventana siempre empieza a leer desde 0
            fifoX = 0;                  // Reiniciamos contador de píxeles metidos

            return; // Abortamos el push en este ciclo para darle tiempo al Fetcher de llenarse
        }

        // Siempre se mantiene al menos 8 pixeles en la cola
        if (fifo.size() >= 8) {
            // Obtener el primero de la fila y sacarlo
            int p = fifo.poll();

            // Si NO es ventana, aplicamos el descarte por scroll fino (ScrollX & 7)
            // Si ES ventana, NO descartamos nada (la ventana siempre empieza limpia)
            boolean targetReached = fetchingWindow || lineX >= (MemoryMapped_IO.lcd.getScrollX() & 7);
            if (targetReached) {
                if (pushedX < 160) {
                    int pixelIndex = pushedX + (MemoryMapped_IO.lcd.getLy() * 160);
                    ppu.video_buffer[pixelIndex] = p;
                    pushedX++;
                }
            }
            // Incrementamos lineX cada vez que sale un píxel, se dibuje o no
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