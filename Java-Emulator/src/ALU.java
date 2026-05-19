public class ALU {
    // -- ROTACIONES Y SHIFTS (Para el prefijo CB) --

    public static int rl(CPU cpu, int val) { // Rotate Left a través del Carry
        int carry = cpu.getFlagC() ? 1 : 0;
        int nextCarry = (val >> 7) & 1;
        int res = ((val << 1) | carry) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    // -- MANIPULACIÓN DE BITS --

    public static void bit(CPU cpu, int bit, int val) {
        cpu.setFlagZ((val & (1 << bit)) == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(true);
        // Flag C no se ve afectada
    }

    public static int res(int bit, int val) {
        return (val & ~(1 << bit)) & 0xFF; // Apaga el bit n
    }

    public static int set(int bit, int val) {
        return (val | (1 << bit)) & 0xFF; // Enciende el bit n
    }

    public static int rr(CPU cpu, int val) { // Rotate Right a través del Carry
        int carry = cpu.getFlagC() ? 0x80 : 0;
        int nextCarry = val & 1;
        int res = ((val >> 1) | carry) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    // -- ROTACIONES Y SHIFTS ADICIONALES --

    public static int rlc(CPU cpu, int val) { // Rotate Left Circular
        int nextCarry = (val >> 7) & 1;
        int res = ((val << 1) | nextCarry) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    public static int rrc(CPU cpu, int val) { // Rotate Right Circular
        int nextCarry = val & 1;
        int res = ((val >> 1) | (nextCarry << 7)) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    public static int sla(CPU cpu, int val) { // Shift Left Arithmetic
        int nextCarry = (val >> 7) & 1;
        int res = (val << 1) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    public static int sra(CPU cpu, int val) { // Shift Right Arithmetic (Preserva el bit de signo)
        int nextCarry = val & 1;
        int msb = val & 0x80;
        int res = (val >> 1) | msb;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    public static int srl(CPU cpu, int val) { // Shift Right Logical
        int nextCarry = val & 1;
        int res = (val >> 1) & 0xFF;

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(nextCarry == 1);
        return res;
    }

    public static int swap(CPU cpu, int val) { // Intercambia nibbles (0-3 con 4-7)
        int res = ((val & 0xF0) >> 4) | ((val & 0x0F) << 4);

        cpu.setFlagZ(res == 0);
        cpu.setFlagN(false);
        cpu.setFlagH(false);
        cpu.setFlagC(false);
        return res;
    }
}