/*
* La ejecución de las otras 256 instrucciones con prefijoCB, la ISA extendida
*/

public class CB_Execute {
    public static void process(CPU cpu, Instruction instr) {
        int val = cpu.getFetchData(); // El valor ya viene preparado por fetch_data()
        int res = 0;
        int bit = instr.param; // El número de bit (0-7) para BIT, RES, SET

        switch (instr.type) {
            case IN_RLC:
                res = ALU.rlc(cpu, val);
                break;
            case IN_RRC:
                res = ALU.rrc(cpu, val);
                break;
            case IN_RL:
                res = ALU.rl(cpu, val);
                break;
            case IN_RR:
                res = ALU.rr(cpu, val);
                break;
            case IN_SLA:
                res = ALU.sla(cpu, val);
                break;
            case IN_SRA:
                res = ALU.sra(cpu, val);
                break;
            case IN_SWAP:
                res = ALU.swap(cpu, val);
                break;
            case IN_SRL:
                res = ALU.srl(cpu, val);
                break;
            case IN_BIT:
                ALU.bit(cpu, bit, val);
                return; // BIT no escribe resultados, solo cambia flags
            case IN_RES:
                res = ALU.res(bit, val);
                break;
            case IN_SET:
                res = ALU.set(bit, val);
                break;
            default:
                System.err.printf("ERROR: Instrucción CB %s no implementada\n", instr.type);
                return;
        }

        // -- ESCRITURA DEL RESULTADO --
        // Si el modo es AM_MR, significa que operamos sobre (HL)
        if (instr.mode == Instructions_Enum.AddrMode.AM_MR) {
            int addr = cpu.read_reg(Instructions_Enum.RegType.RT_HL);
            Bus.bus_write(addr, res);
            cpu.cycle(4); // Ciclo extra por escribir a memoria
        } else {
            // De lo contrario, escribimos al registro destino (reg_1)
            cpu.write_reg(instr.reg_1, res);
        }
    }
}
