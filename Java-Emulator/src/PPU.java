/*
 * Pixel Processing Unit (PPU), es la encargada de mostrar video a la pantalla
 */
import java.util.*;

public class PPU {
    public PPU_Fetcher fetcher = new PPU_Fetcher(this);

    // Memoria dedicada de video (8KB) y memoria de atributos de objetos (sprites)
    public int[] vram = new int[0x2000];
    public int[] oam_ram = new int[0xA0];
    // Buffer de salida para el renderizado (160x144 píxeles)
    public int[] video_buffer = new int[160 * 144];

    // Reloj interno de la línea actual (0 a 455 ticks)
    public int line_ticks = 0; // Ticks por línea
    // Contador interno para el dibujo de la Window
    public int window_line = 0;

    // El Fetcher es el encargado de alimentar el pipeline de píxeles
    //public PPU_Fetcher fetcher = new PPU_Fetcher();

    // Almacén para los 10 sprites máximos permitidos por línea (DMG hardware limitation)
    public List<SpriteEntry> lineSprites = new ArrayList<>();

    /*
     * Motor principal de la PPU. Se ejecuta en cada T-Cycle del sistema.
     * Sincroniza los estados (Modos) basándose en el reloj de línea.
     */
    /*
     * Motor principal de la PPU. Se ejecuta en cada T-Cycle del sistema.
     * Sincroniza los estados (Modos) basándose en el reloj de línea.
     */
    public void ppu_tick() {
        // Verificamos si el LCD está APAGADO (Bit 7 en 0)
        if ((MemoryMapped_IO.lcd.getLcdc() & 0x80) == 0) {
            // El hardware detiene la PPU y fuerza LY a 0
            line_ticks = 0;
            MemoryMapped_IO.lcd.setLy(0);
            window_line = 0;
            fetcher.reset(); // Vaciamos el pipeline de píxeles

            // Forzamos el Modo 0 (H-Blank) directo en memoria.
            // Es CRÍTICO hacer esto modificando el registro directamente y NO
            // llamando a setMode(0), para evitar un bucle de interrupciones STAT.
            int currentStat = MemoryMapped_IO.lcd.getStat();
            MemoryMapped_IO.lcd.setStat(currentStat & ~0x03);

            return; // Detenemos la PPU. La CPU ahora tiene vía libre en la VRAM.
        }

        // Si el LCD está encendido, la PPU corre normalmente
        this.line_ticks++;

        // Guardamos el modo al entrar para detectar transiciones en el mismo tick
        int mode = getMode();

        switch (mode) {
            case 2 -> ppu_mode_oam(); // OAM Search
            case 3 -> ppu_mode_xfer(); // Pixel Transfer
            case 0 -> ppu_mode_hblank(); // H-Blank
            case 1 -> ppu_mode_vblank(); // V-Blank
        }

        // Si el modo cambió de 2 a 3 durante este tick,
        // ejecutamos el primer proceso del fetcher de inmediato para no perder sincronía.
        if (mode == 2 && getMode() == 3) {
            ppu_mode_xfer();
        }
    }

    // -- MODO 2: OAM SEARCH --
    private void ppu_mode_oam() {
        // En el primer tick de la línea, buscamos qué sprites deben dibujarse
        if (line_ticks == 1) scan_line_sprites();

        // Al terminar los 80 ticks, pasamos al dibujado
        if (line_ticks >= 80) {
            setMode(3);
            // Reinicio total de punteros del pipeline
            fetcher.state = PPU_Fetcher.State.TILE;
            fetcher.lineX = 0;   // Contador para el scroll fino
            fetcher.fetchX = 0;
            fetcher.pushedX = 0;
            fetcher.fifo.clear();
        }
    }

    // -- MODO 3: PIXEL TRANSFER --
    private void ppu_mode_xfer() {
        // El Fetcher procesa la lógica de VRAM y llena la FIFO
        fetcher.process();

        // Si el Fetcher ya empujó 160 píxeles al buffer, la línea terminó
        if (fetcher.pushedX >= 160) {
            fetcher.fifo_reset();
            setMode(0); // Entrar en H-Blank
        }
    }

    // -- MODO 0: H-BLANK --
    private void ppu_mode_hblank() {
        if (line_ticks >= 456) {
            line_ticks = 0; // Reiniciamos ticks primero
            step_ly();      // Esto incrementa LY

            // una vez cada 456 ticks (cuando cambia la línea)
            if (MemoryMapped_IO.lcd.getLy() >= 144) {
                setMode(1); // Entramos a V-Blank

                // Como esto solo se ejecuta una vez por línea (al tick 456),
                // la interrupción solo se pide UNA VEZ.
                Bus.intrp.request_interrupt(Interrupts.VBLANK);
                if ((MemoryMapped_IO.lcd.getStat() & 0x10) != 0) {
                    Bus.intrp.request_interrupt(Interrupts.LCD_STAT);
                }
            } else {
                // Si no es V-Blank, regresamos a OAM para la siguiente línea visible
                setMode(2);
            }
        }
    }

    // -- MODO 1: V-BLANK --
    private void ppu_mode_vblank() {
        if (line_ticks >= 456) {
            step_ly(); // Continuar contando líneas hasta 153

            if (MemoryMapped_IO.lcd.getLy() >= 154) {
                setMode(2);
                MemoryMapped_IO.lcd.setLy(0); // Reinicio manual a 0
                window_line = 0;

                // -- Verificar coincidencia para la línea 0 --
                if (MemoryMapped_IO.lcd.getLy() == MemoryMapped_IO.lcd.getLyc()) {
                    MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() | 0x04);
                    if ((MemoryMapped_IO.lcd.getStat() & 0x40) != 0) {
                        Bus.intrp.request_interrupt(Interrupts.LCD_STAT);
                    }
                } else {
                    MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() & ~0x04);
                }
            }
            line_ticks = 0;
        }
    }

    /*
     * Escanea la memoria OAM para encontrar hasta 10 sprites que intersecten con LY.
     */
    private void scan_line_sprites() {
        lineSprites.clear(); // Limpiar la estructura de datos
        // Los sprites pueden ser de 8x8 o de 8x16
        int height = (MemoryMapped_IO.lcd.getLcdc() & 0x04) != 0 ? 16 : 8;
        // Máximo 10 sprites por línea 7 y 40 objetos (160 bytes, 4 bytes por objeto)
        for (int i = 0; i < 40 && lineSprites.size() < 10; ++i) {
            // Como cada objeto ocupa 4 bytes, el índice se debe multiplicar por 4 para el offset
            // Es un array de estructuras, en este caso la estructura es el objeto
            int y = oam_ram[(i << 2)] & 0xFF;
            int x = oam_ram[(i << 2) + 1] & 0xFF;
            int tile = oam_ram[(i << 2) + 2] & 0xFF;
            int flags = oam_ram[(i << 2) + 3] & 0xFF;
            // Para permitir entrada y salida fluida de la pantalla, las coordenadas 0,0 no corresponden al 0,0 de pantalla
            // La coordenada Y determina si el sprite será dibujado, la coordenada x determina dónde
            // Por eso se agrega el sprite a memoria solo si aplicando la compensación para y entra
            // De x nos encargamos cuando sea momento de dibujar
            if (y <= MemoryMapped_IO.lcd.getLy() + 16 && y + height > MemoryMapped_IO.lcd.getLy() + 16) {
                lineSprites.add(new SpriteEntry(y, x, tile, flags));
            }
        }
        // Prioridad: El sprite con menor coordenada X se dibuja encima, debemos ordenarlos
        lineSprites.sort(Comparator.comparingInt(s -> s.x));
    }

    /*
     * Avanza el contador de líneas y gestiona la lógica de coincidencia LYC.
     */
    private void step_ly() {
        // Lógica de Window interna
        if (isWinVisible() && MemoryMapped_IO.lcd.getLy() >= MemoryMapped_IO.lcd.getWinY()) {
            window_line++;
        }
        MemoryMapped_IO.lcd.setLy(MemoryMapped_IO.lcd.getLy() + 1);


        // Lógica de coincidencia LY == LYC (Bit 2 del STAT)
        if (MemoryMapped_IO.lcd.getLy() == MemoryMapped_IO.lcd.getLyc()) {
            MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() | 0x04);
            if ((MemoryMapped_IO.lcd.getStat() & 0x40) != 0) {
                Bus.intrp.request_interrupt(Interrupts.LCD_STAT); // STAT INT por LYC
            }
        } else {
            MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() & ~0x04);
        }
    }

    // -- MÉTODOS DE UTILIDAD Y ACCESO --

    public int getMode() { return MemoryMapped_IO.lcd.getStat() & 0x03; }

    public void setMode(int m) {
        MemoryMapped_IO.lcd.regs[0x01] = (MemoryMapped_IO.lcd.getStat() & ~0x03) | (m & 0x03);

        // -- INTERRUPCIÓN POR CAMBIO DE MODO --
        int currentStat = MemoryMapped_IO.lcd.getStat();
        boolean interruptRequested = false;

        if (m == 0 && (currentStat & 0x08) != 0) interruptRequested = true; // Modo 0 (H-Blank)
        if (m == 1 && (currentStat & 0x10) != 0) interruptRequested = true; // Modo 1 (V-Blank, extra STAT int)
        if (m == 2 && (currentStat & 0x20) != 0) interruptRequested = true; // Modo 2 (OAM)

        if (interruptRequested) {
            Bus.intrp.request_interrupt(Interrupts.LCD_STAT);
        }
    }

    public boolean isWinVisible() {
        return (MemoryMapped_IO.lcd.getLcdc() & 0x20) != 0 && MemoryMapped_IO.lcd.getWinX() <= 166;
    }

    public static class SpriteEntry {
        int y, x, tile, flags;
        SpriteEntry(int y, int x, int t, int f) { this.y = y; this.x = x; this.tile = t; this.flags = f; }
    }

    // -- ACCESO A MEMORIA (BUS) --

    public int vram_read(int address) {
        int finalAddr = address - 0x8000;
        if (finalAddr < 0 || finalAddr >= 0x2000) {
            return 0x00;
        }
        return vram[finalAddr] & 0xFF;
    }
    public void vram_write(int address, int value) {
        int finalAddr = address - 0x8000;
        if (finalAddr >= 0 && finalAddr < 0x2000) {
            vram[finalAddr] = value & 0xFF;
        }
    }

    public int oam_read(int address) {
        int finalAddr = address - 0xFE00;
        if (finalAddr < 0 || finalAddr >= 0xA0) return 0xFF;
        return oam_ram[finalAddr] & 0xFF;

    }
    public void oam_write(int address, int value) {
        int finalAddr = address - 0xFE00;
        if (finalAddr >= 0 && finalAddr < 0xA0) {
            oam_ram[finalAddr] = value & 0xFF;
        }
    }
}