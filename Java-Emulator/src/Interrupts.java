/*
* Se encarga de disparar interrupciones a elección del programador
*/

public class Interrupts {
    // Constantes de bits para cada interrupción (Orden de prioridad)
    public static final int VBLANK = 0;   // Bit 0: Mayor prioridad
    public static final int LCD_STAT = 1; // Bit 1
    public static final int TIMER = 2;    // Bit 2
    public static final int SERIAL = 3;   // Bit 3
    public static final int JOYPAD = 4;   // Bit 4: Menor prioridad
    // Los bits 5-7 son 1 por defecto, no se utilizan

    private int ime_delay = 0; // Flag para el delay de la instrucción EI

    // Direcciones de los Vectores de Interrupción (Donde salta el PC)
    // V-Blank salta a 0x0040, LCD_STAT salta a 0x0048, Timer salta a 0x0050
    // Serial salta a 0x0058 y Joypad salta a 0x0060
    private static final int[] VECTORS = {0x0040, 0x0048, 0x0050, 0x0058, 0x0060};

    // Interrupt Flag es controlado por la máquina al momento de ejecutar las instrucciones
    // Interrupt Enabler es controlado por el programador para decidir quién puede interrumpir al procesador
    private int if_register = 0xE1; // 0xFF0F: Flags (Por defecto los bits altos son 1) y el V-Blank en 1 tras el logo de Nintendo
    private int ie_register = 0x00; // 0xFFFF: Enable

    // El "Master Switch" de la CPU (IME), protege de interrupciones dentro de interrupciones
    private boolean master_enabled = false;

    // -- MÉTODOS DE HARDWARE --
    public void request_interrupt(int bit) {
        if_register |= (1 << bit);
    }

    public int get_if_register() { return if_register | 0xE0; } // Bits 5-7 siempre retornan 1
    public void set_if_register(int val) { if_register = val | 0xE0; }

    public int get_ie_register() { return ie_register; }
    public void set_ie_register(int val) { ie_register = val; }


    // -- MÉTODOS DE CONTROL --

    public void set_master_enabled(boolean value) {
        this.master_enabled = value; // RESPETA el DI

        // Si se hace un DI, se debe cancelar cualquier delay de un EI anterior
        if (!value) {
            this.ime_delay = 0;
        }
    }

    public boolean get_master_enabled() {
        return master_enabled;
    }

    // El delay es de solo 1 instrucción.
    public void schedule_enable_ime() {
        ime_delay = 1;
    }

    public void update_ime() {
        if (ime_delay > 0) {
            ime_delay--;
            if (ime_delay == 0) {
                master_enabled = true;
            }
        }
    }

    // 0x1F permite ignorar los bits eternamente encendidos (5-7)
    public boolean has_pending_interrupts() {
        return (if_register & ie_register & 0x1F) != 0;
    }

    public int  consume_interrupt() {
        // Verificación de interrupciones
        if (!master_enabled) return -1;

        int pending = if_register & ie_register & 0x1F;
        if (pending == 0) return -1;

        for (int i = 0; i < 5; ++i) {
            // Usamos BIT para checar si la bandera está encendida
            if (Common.BIT(pending, i) != 0) {
                // Limpiamos la bandera usando BIT_SET
                if_register = Common.BIT_SET(if_register, i, false);

                // Apagamos el Master Enable
                master_enabled = false;
                // Devolvemos el vector
                return VECTORS[i];
            }
        }
        return -1;
    }
}