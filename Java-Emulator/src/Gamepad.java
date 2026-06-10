public class Gamepad {

    public enum Button {
        RIGHT, LEFT, UP, DOWN,
        A, B, SELECT, START
    }

    private boolean buttonSelected = false;
    private boolean dpadSelected = false;

    private int dpadState = 0x0F;
    private int buttonState = 0x0F;

    // Latch para detectar el "Flanco de bajada" (Falling Edge)
    private int previousState = 0x0F;

    public void write(int value) {
        // En el hardware, 0 significa "Seleccionado"
        buttonSelected = (value & 0x20) == 0;
        dpadSelected = (value & 0x10) == 0;

        // La CPU acaba de cambiar el multiplexor.
        // Si hay un botón presionado, esto podría detonar una interrupción.
        checkInterrupt();
    }

    public int read() {
        // En el GB real, los bits 6 y 7 siempre devuelven 1
        int state = 0xCF;

        // Reflejamos qué fila está seleccionada en los bits 4 y 5
        if (!buttonSelected) state |= 0x20;
        if (!dpadSelected)   state |= 0x10;

        // Mezclamos el estado solo en los 4 bits más bajos
        if (buttonSelected) state &= (buttonState | 0xF0);
        if (dpadSelected)   state &= (dpadState | 0xF0);

        return state;
    }

    public void press(Button btn) {
        switch (btn) {
            case RIGHT: dpadState &= ~1; break;
            case LEFT:  dpadState &= ~2; break;
            case UP:    dpadState &= ~4; break;
            case DOWN:  dpadState &= ~8; break;

            case A:     buttonState &= ~1; break;
            case B:     buttonState &= ~2; break;
            case SELECT:buttonState &= ~4; break;
            case START: buttonState &= ~8; break;
        }
        // El jugador apretó un botón, revisamos si genera interrupción
        checkInterrupt();
    }

    public void release(Button btn) {
        switch (btn) {
            case RIGHT: dpadState |= 1; break;
            case LEFT:  dpadState |= 2; break;
            case UP:    dpadState |= 4; break;
            case DOWN:  dpadState |= 8; break;

            case A:     buttonState |= 1; break;
            case B:     buttonState |= 2; break;
            case SELECT:buttonState |= 4; break;
            case START: buttonState |= 8; break;
        }
        // Al soltar también validamos el estado (aunque no genera interrupción directamente,
        // actualiza el previousState para la próxima vez).
        checkInterrupt();
    }

    private void checkInterrupt() {
        // Evaluamos solo los 4 bits bajos de salida de la matriz
        int currentState = read() & 0x0F;

        // LÓGICA DE SILICIO (Falling Edge Detection):
        // Si previousState tenía un 1, y currentState tiene un 0 en la misma posición,
        // la operación (previousState & ~currentState) dará un resultado diferente de 0.
        if ((previousState & ~currentState) != 0) {
            Bus.intrp.request_interrupt(Interrupts.JOYPAD);
        }

        // Guardamos la "foto" de este instante para la próxima evaluación
        previousState = currentState;
    }
}