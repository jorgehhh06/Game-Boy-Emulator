public class ALU {
    public static int add8(CPU cpu, int val1, int val2) {
        int result = (val1 + val2) & 0xFF;
        // -- CONFIGURACIÓN DE FLAGS --

        // Zero Flag
        cpu.setFlagZ(result == 0);

        // Negative Flag
        cpu.setFlagN(false);

        // Half Carry (H): carry del bit 3 al 4
        cpu.setFlagH(((val1 & 0xF) + (val2 & 0xF)) > 0xF);

        // Carry (C): carry del bit 7 al 8
        cpu.setFlagC((val1 + val2) > 0xFF);

        return result;
    }
}