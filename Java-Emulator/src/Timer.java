public class Timer {
    // Actúa como el System Counter de 16 bits, incrementando cada T-Cycle
    private int DIV = 0xAC00;

    private int TIMA = 0;
    private int TMA = 0;
    private int TAC = 0;

    // Estado para el retraso del hardware
    private boolean timaOverflowing = false;
    private int overflowDelay = 0;

    public void setDIV(int DIV) {
        this.DIV = DIV;
    }

    private int getTimerMultiplexerSignal(int divider, int control) {
        if ((control & 0b100) == 0) return 0;

        int bitPos = switch (control & 0b11) {
            case 0b00 -> 9;  // 4096 Hz
            case 0b01 -> 3;  // 262144 Hz
            case 0b10 -> 5;  // 65536 Hz
            case 0b11 -> 7;  // 16384 Hz
            default -> 9;
        };

        return (divider >> bitPos) & 1;
    }

    public void timer_tick() {
        // Manejo del retraso de 4 T-Cycles (1 M-Cycle) al desbordar TIMA
        if (timaOverflowing) {
            overflowDelay++;
            if (overflowDelay >= 4) {
                TIMA = TMA;
                Bus.intrp.request_interrupt(Interrupts.TIMER);
                timaOverflowing = false;
                overflowDelay = 0;
            }
        }

        int prevSignal = getTimerMultiplexerSignal(DIV, TAC);

        //System Counter libre a ritmo de T-Cycle
        DIV = (DIV + 1) & 0xFFFF;

        int currentSignal = getTimerMultiplexerSignal(DIV, TAC);
        if (prevSignal == 1 && currentSignal == 0) {
            increment_tima();
        }
    }

    private void increment_tima() {
        TIMA++;
        if (TIMA > 0xFF) {
            TIMA = 0x00;
            timaOverflowing = true;
            overflowDelay = 0;
        }
    }

    public int timer_read(int address) {
        return switch (address) {
            case 0xFF04 -> (DIV >> 8) & 0xFF; // >> 8 hace que div corra a la velocidad correcta de 16,384Hz
            case 0xFF05 -> TIMA & 0xFF;
            case 0xFF06 -> TMA & 0xFF;
            case 0xFF07 -> TAC & 0xFF;
            default -> 0;
        };
    }

    public void timer_write(int address, int value) {
        int prevSignal = getTimerMultiplexerSignal(DIV, TAC);

        switch (address) {
            case 0xFF04 -> DIV = 0;
            case 0xFF05 -> {
                // Cancelar desbordamiento si la CPU escribe justo a tiempo (Obscure Behavior)
                if (timaOverflowing) {
                    timaOverflowing = false;
                    overflowDelay = 0;
                }
                TIMA = value & 0xFF;
            }
            case 0xFF06 -> TMA = value & 0xFF;
            case 0xFF07 -> TAC = value & 0xFF;
        }

        int currentSignal = getTimerMultiplexerSignal(DIV, TAC);
        if (prevSignal == 1 && currentSignal == 0) {
            increment_tima();
        }
    }
}