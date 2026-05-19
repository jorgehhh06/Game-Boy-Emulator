public class Gamepad {

    public enum Button {
        RIGHT, LEFT, UP, DOWN,
        A, B, SELECT, START
    }

    private boolean buttonSelected = false;
    private boolean dpadSelected = false;

    private int dpadState = 0x0F;
    private int buttonState = 0x0F;

    public void write(int value) {
        buttonSelected = (value & 0x20) == 0;
        dpadSelected = (value & 0x10) == 0;
    }

    public int read() {
        // En el GB real, los bits 6 y 7 casi siempre devuelven 1
        int state = 0xCF;

        // Reflejamos qué fila está seleccionada en los bits 4 y 5 (1 = inactivo, 0 = activo)
        if (!buttonSelected) state |= 0x20;
        if (!dpadSelected)   state |= 0x10;

        // Mezclamos el estado solo en los 4 bits más bajos (usamos | 0xF0 para proteger los bits altos)
        if (buttonSelected) state &= (buttonState | 0xF0);
        if (dpadSelected)   state &= (dpadState | 0xF0);

        return state;
    }

    public void press(Button btn) {
        boolean requestInterrupt = false;

        switch (btn) {
            case RIGHT:
                if (dpadSelected && (dpadState & 1) != 0) requestInterrupt = true;
                dpadState &= ~(1);
                break;
            case LEFT:
                if (dpadSelected && (dpadState & 2) != 0) requestInterrupt = true;
                dpadState &= ~(2);
                break;
            case UP:
                if (dpadSelected && (dpadState & 4) != 0) requestInterrupt = true;
                dpadState &= ~(4);
                break;
            case DOWN:
                if (dpadSelected && (dpadState & 8) != 0) requestInterrupt = true;
                dpadState &= ~(8);
                break;

            case A:
                if (buttonSelected && (buttonState & 1) != 0) requestInterrupt = true;
                buttonState &= ~(1);
                break;
            case B:
                if (buttonSelected && (buttonState & 2) != 0) requestInterrupt = true;
                buttonState &= ~(2);
                break;
            case SELECT:
                if (buttonSelected && (buttonState & 4) != 0) requestInterrupt = true;
                buttonState &= ~(4);
                break;
            case START:
                if (buttonSelected && (buttonState & 8) != 0) requestInterrupt = true;
                buttonState &= ~(8);
                break;
        }

        if (requestInterrupt) {
            Bus.intrp.request_interrupt(Interrupts.JOYPAD);
        }
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
    }
}