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

    // Contención de bus
    private boolean vram_blocked;
    private boolean oam_blocked;
    public boolean get_vram_blocked() { return vram_blocked; }
    public boolean get_oam_blocked() { return oam_blocked; }

    // Cable interno oculto de la compuerta OR (Guarda el estado anterior para detectar el flanco 0 -> 1)
    private boolean stat_irq_line = false;

    // -- Delay de 1 M-Cycle (4 T-Cycles) para LCD_STAT interrupt --
    private int lcd_stat_delay = 0;

    // Almacén para los 10 sprites máximos permitidos por línea (DMG hardware limitation)
    public List<SpriteEntry> lineSprites = new ArrayList<>();

    /*
     * Retraso exacto de 1 M-Cycle (4 T-Cycles) al pedir la interrupción LCD STAT.
     * Se decrementa en cada T-Cycle y dispara únicamente al llegar a 0.
     */
    public void tick_t_cycle() {
        if (lcd_stat_delay > 0) {
            lcd_stat_delay--;
            if (lcd_stat_delay == 0) {
                Bus.intrp.request_interrupt(Interrupts.LCD_STAT);
            }
        }
    }

    /*
     * Circuito combinacional interno de STAT: 4 compuertas AND conectadas a 1 compuerta OR.
     * Solo agenda el delay de 1 M-Cycle cuando la línea pasa de 0 (false) a 1 (true).
     */
    public void update_stat_irq() {
        // Si el LCD está apagado, la línea interna cae a 0
        if ((MemoryMapped_IO.lcd.getLcdc() & 0x80) == 0) {
            stat_irq_line = false;
            return;
        }

        int stat = MemoryMapped_IO.lcd.getStat();
        int mode = stat & 0x03;

        // Las 4 compuertas AND del hardware:
        boolean andLyc   = ((stat & 0x04) != 0) && ((stat & 0x40) != 0); // (LYC == LY) AND Bit 6
        boolean andMode0 = (mode == 0)          && ((stat & 0x08) != 0); // (Mode 0)    AND Bit 3
        boolean andMode1 = (mode == 1)          && ((stat & 0x10) != 0); // (Mode 1)    AND Bit 4
        boolean andMode2 = (mode == 2)          && ((stat & 0x20) != 0); // (Mode 2)    AND Bit 5

        // La compuerta OR interna que une los 4 cables en uno solo:
        boolean currentLine = andLyc || andMode0 || andMode1 || andMode2;

        // Detector de flanco de subida (0 -> 1):
        // Si antes era 0 y ahora es 1, iniciamos el delay de 4 T-Cycles (1 M-Cycle).
        // Si ya estaba en 1 (1 OR 1), se produce el STAT Blocking y no hace nada.
        if (!stat_irq_line && currentLine) {
            lcd_stat_delay = 4;
        }

        // Actualizamos el estado del cable para el siguiente ciclo
        stat_irq_line = currentLine;
    }

    /*
     * Motor principal de la PPU. Se ejecuta en cada T-Cycle del sistema.
     * Sincroniza los estados (Modos) basándose en el reloj de línea.
     */
    public void ppu_tick() {
        tick_t_cycle(); // Procesa el conteo regresivo de 1 M-Cycle para LCD STAT

        // Verificamos si el LCD está APAGADO (Bit 7 en 0)
        if ((MemoryMapped_IO.lcd.getLcdc() & 0x80) == 0) {
            // El hardware detiene la PPU y fuerza LY a 0
            line_ticks = 0;
            MemoryMapped_IO.lcd.setLy(0);
            window_line = 0;
            fetcher.reset(); // Vaciamos el pipeline de píxeles

            // Evitar problemas de contención de bus y limpiar línea STAT
            vram_blocked = false;
            oam_blocked = false;
            stat_irq_line = false;
            lcd_stat_delay = 0;

            // Forzamos el Modo 0 directo en memoria
            int currentStat = MemoryMapped_IO.lcd.getStat();
            MemoryMapped_IO.lcd.setStat(currentStat & ~0x03);
            return; // Detenemos la PPU. La CPU ahora tiene vía libre en la VRAM.
        }

        // Si el LCD está encendido, la PPU corre normalmente
        this.line_ticks++;

        // Guardamos el modo al entrar para detectar transiciones en el mismo tick
        int mode = getMode();

        switch (mode) {
            case 2 -> ppu_mode_oam();    // OAM Search
            case 3 -> ppu_mode_xfer();   // Pixel Transfer
            case 0 -> ppu_mode_hblank(); // H-Blank
            case 1 -> ppu_mode_vblank(); // V-Blank
        }
    }

    // -- MODO 2: OAM SEARCH --
    private void ppu_mode_oam() {
        oam_blocked = true;
        vram_blocked = false;
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
        oam_blocked = true;
        vram_blocked = true;
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
        oam_blocked = false;
        vram_blocked = false;
        if (line_ticks >= 456) {
            line_ticks = 0; // Reiniciamos ticks primero
            step_ly();      // Incrementa LY y evalúa LY == LYC

            if (MemoryMapped_IO.lcd.getLy() >= 144) {
                setMode(1); // Entramos a V-Blank (evalúa la compuerta OR adentro de setMode)
                Bus.intrp.request_interrupt(Interrupts.VBLANK);
            } else {
                // Si no es V-Blank, regresamos a OAM para la siguiente línea visible
                setMode(2);
            }
        }
    }

    // -- MODO 1: V-BLANK --
    private void ppu_mode_vblank() {
        oam_blocked = false;
        vram_blocked = false;
        if (line_ticks >= 456) {
            line_ticks = 0;
            step_ly(); // Incrementa LY (y si pasa de 153 a 0, lo envuelve limpio antes de evaluar LYC)

            // Si step_ly() envolvió LY de vuelta a la línea 0, salimos de V-Blank hacia Modo 2
            if (MemoryMapped_IO.lcd.getLy() == 0) {
                setMode(2);
            }
        }
    }

    /*
     * Escanea la memoria OAM para encontrar hasta 10 sprites que intersecten con LY.
     */
    private void scan_line_sprites() {
        lineSprites.clear(); // Limpiar la estructura de datos
        // Los sprites pueden ser de 8x8 o de 8x16
        int height = (MemoryMapped_IO.lcd.getLcdc() & 0x04) != 0 ? 16 : 8;
        // Máximo 10 sprites por línea y 40 objetos (160 bytes, 4 bytes por objeto)
        for (int i = 0; i < 40 && lineSprites.size() < 10; ++i) {
            int y = oam_ram[(i << 2)] & 0xFF;
            int x = oam_ram[(i << 2) + 1] & 0xFF;
            int tile = oam_ram[(i << 2) + 2] & 0xFF;
            int flags = oam_ram[(i << 2) + 3] & 0xFF;

            if (y <= MemoryMapped_IO.lcd.getLy() + 16 && y + height > MemoryMapped_IO.lcd.getLy() + 16) {
                lineSprites.add(new SpriteEntry(y, x, tile, flags));
            }
        }
        // Prioridad: El sprite con menor coordenada X se dibuja encima, debemos ordenarlos
        lineSprites.sort(Comparator.comparingInt(s -> s.x));
    }

    /*
     * Avanza el contador de líneas, actualiza el bit 2 (LY == LYC) y evalúa la compuerta OR.
     */
    private void step_ly() {
        // Lógica de Window interna
        if (isWinVisible() && MemoryMapped_IO.lcd.getLy() >= MemoryMapped_IO.lcd.getWinY()) {
            window_line++;
        }

        int nextLy = MemoryMapped_IO.lcd.getLy() + 1;

        // Envolver de 153 -> 0 ANTES de evaluar LY == LYC para no generar un falso flanco en 154
        if (nextLy >= 154) {
            nextLy = 0;
            window_line = 0;
        }
        MemoryMapped_IO.lcd.setLy(nextLy);

        // Actualizar el Bit 2 de STAT (Coincidencia LY == LYC)
        if (MemoryMapped_IO.lcd.getLy() == MemoryMapped_IO.lcd.getLyc()) {
            MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() | 0x04);
        } else {
            MemoryMapped_IO.lcd.setStat(MemoryMapped_IO.lcd.getStat() & ~0x04);
        }

        // Pasamos la nueva señal por el circuito de compuertas AND/OR
        update_stat_irq();
    }

    // -- MÉTODOS DE UTILIDAD Y ACCESO --

    public int getMode() { return MemoryMapped_IO.lcd.getStat() & 0x03; }

    public void setMode(int m) {
        MemoryMapped_IO.lcd.regs[0x01] = (MemoryMapped_IO.lcd.getStat() & ~0x03) | (m & 0x03);

        // Sincronizar bloqueo de VRAM/OAM al instante del cambio de modo
        oam_blocked  = (m == 2 || m == 3);
        vram_blocked = (m == 3);

        // Pasamos el nuevo modo por el circuito de compuertas AND/OR
        update_stat_irq();
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