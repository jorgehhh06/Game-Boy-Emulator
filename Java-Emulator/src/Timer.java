public class Timer {
    private int internalDivider = 0xAC00;
    private int timerCounter = 0;
    private int timerModulo = 0;
    private int timerControl = 0;

    // -- Contador de T-Cycles para sincronizar con M-Cycles --
    private int internal_cnt = 0;

    public void timer_tick() {
        // El Divider interno (DIV) aumenta cada 1 M-Cycle (4 T-Cycles)
        // Como CPU llama a cycle() por cada T-Cycle, filtramos aquí
        internal_cnt++;
        if (internal_cnt < 4) {
            return;
        }
        internal_cnt = 0;

        // Guarda el valor del contador viejo y nuevo para poder compararlos
        int previousDivider = internalDivider;
        internalDivider = (internalDivider + 1) & 0xFFFF;

        boolean shouldIncrementTimer = false;

        // Selección de bit de frecuencia (Bits 0-1 de timerControl)
        switch (timerControl & 0b11) {
            // Detección de flanco de bajada (de 1 a 0) en el bit correspondiente
            case 0b00 -> shouldIncrementTimer = (previousDivider & (1 << 9)) != 0
                    && (internalDivider & (1 << 9)) == 0;
            case 0b01 -> shouldIncrementTimer = (previousDivider & (1 << 3)) != 0
                    && (internalDivider & (1 << 3)) == 0;
            case 0b10 -> shouldIncrementTimer = (previousDivider & (1 << 5)) != 0
                    && (internalDivider & (1 << 5)) == 0;
            case 0b11 -> shouldIncrementTimer = (previousDivider & (1 << 7)) != 0
                    && (internalDivider & (1 << 7)) == 0;
        }

        // Si hay flanco de bajada y el bit 2 de TAC (0xFF07) es 1 (Timer Enabled)
        if (shouldIncrementTimer && (timerControl & 0b100) != 0) {
            timerCounter++;

            // Si el contador (TIMA) se desborda (pasa de 255 a 256)
            if (timerCounter > 0xFF) {
                // Se recarga con el valor del Modulo (TMA)
                timerCounter = timerModulo;

                // Solicitamos interrupción de Timer (Bit 2 del IF)
                Bus.intrp.request_interrupt(Interrupts.TIMER);
            }
        }
    }

    public int timer_read(int address) {
        return switch (address) {
            case 0xFF04 -> (internalDivider >> 8) & 0xFF; // Los 8 bits altos del contador interno son el registro DIV
            case 0xFF05 -> timerCounter & 0xFF;
            case 0xFF06 -> timerModulo & 0xFF;
            case 0xFF07 -> timerControl & 0xFF;
            default -> 0;
        };
    }

    public void timer_write(int address, int value) {
        switch (address) {
            case 0xFF04 -> internalDivider = 0; // Escribir cualquier cosa a DIV lo resetea a 0
            case 0xFF05 -> timerCounter = value & 0xFF;
            case 0xFF06 -> timerModulo = value & 0xFF;
            case 0xFF07 -> timerControl = value & 0xFF;
        }
    }
}