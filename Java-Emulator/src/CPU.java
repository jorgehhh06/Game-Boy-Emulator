/*
* Este archivo se encarga de la estructura del CPU
*/
public class CPU {
    // Posiciones de las flags en el registro F (ZSHC 0000)
    private static final int BIT_Z = 7;
    private static final int BIT_N = 6;
    private static final int BIT_H = 5;
    private static final int BIT_C = 4;

    private class CpuRegisters {
        int a, b, c, d, e, f, h, l; // Registros de la CPU
        int pc = 0x100; // Punto de entrada después del Boot ROM (logo de nintendo)
        int sp = 0xFFFE; // El Stack suele empezar al final de la RAM

        /*
        * El registro A es el acumulador, por ahí pasan todas las operaciones matemáticas,
        * el registro F contiene las 4 flags del sistema en sus 4 bits más significativos:
        * Registro F: ZSHC 0000. Z: Zero Flag, S: Subtraction Flag, H: Half-carry flag, C: Carry flag
        * Los registros se pueden combinar en dos para por ejemplo, ser usados como punteros.
        * a + f = af, b + c = bc, d + e = de, h + l = hl
        */

        // -- REGISTROS COMBINADOS --

        /*
        * Los setters escriben sobre los registros, los getters leen lo que hay en los registros.
        * Esto es cómo la máquina junta dos registros aplicando máscaras binarias y recorrido de bits
        */

        public int getAF() { return (a << 8) | (f & 0xFF); } // Se usa para preservar el estado del sistema
        public void setAF(int val) {
            a = (val >> 8) & 0xFF;
            f = val & 0xF0;
        }

        public int getBC() { return (b << 8) | (c & 0xFF); }
        public void setBC(int val) {
            b = (val >> 8) & 0xFF;
            c = val & 0xFF;
        }

        public int getDE() { return (d << 8) | (e & 0xFF); }
        public void setDE(int val) {
            d = (val >> 8) & 0xFF;
            e = val & 0xFF;
        }

        public int getHL() { return (h << 8) | (l & 0xFF); } // Es usado como puntero maestro, High & Low
        public void setHL(int val) {
            h = (val >> 8) & 0xFF;
            l = val & 0xFF;
        }

    }

    /*
    * CpuContext me permite debuggear el emulador, es el objeto de la CPU
    */

    private class CpuContext {
        CpuRegisters regs = new CpuRegisters();

        Instruction curInst; // Instrucción actual

        int fetchData; // Información recogida
        int memDest; // Destinación de la memoria
        int curOpcode; // Opcode actual

        // -- HERRRAMIENTAS DE DEBUGGING --

        boolean halted = false;
        boolean stepping = false;
    }

    private CpuContext CPUctx = new CpuContext(); // Objeto de la CPU

    // -- INICIALIZACIÓN TRAS EL LOGO DE NINTENDO --
    public void cpu_init() {
        // Inicialización básica
        CPUctx.regs.pc = 0x100;
        CPUctx.regs.a = 0x01; // Valor inicial típico tras el logo de Nintendo
        CPUctx.regs.f = 0xB0; // Z, H, C activados inicialmente
    }

    // -- CICLO (FUNCIÓN DE TRANSICIÓN DEL AUTÓMATA FINITO) --
    public boolean cpu_step() {
        if (!CPUctx.halted) {
            // 1. FETCH del Opcode inicial
            int pc_actual = CPUctx.regs.pc & 0xFFFF;
            int opcode = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;

            // 2. DECODE con lógica de Prefijo
            if (opcode == 0xCB) {
                // El 0xCB nos dice: "Lee el siguiente byte y búscalo en la tabla CB"
                int cbOpcode = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;
                CPUctx.curOpcode = cbOpcode;
                // Buscamos en la tabla de instrucciones extendidas
                CPUctx.curInst = CB_InstructionTable.get_cb_instruction_by_opcode(cbOpcode);
            } else {
                // Instrucción normal
                CPUctx.curOpcode = opcode;
                CPUctx.curInst = InstructionTable.get_instruction_by_opcode(opcode);
            }

            // 3. VALIDACIÓN (Verificar si la instrucción está definida)
            if (CPUctx.curInst == null || CPUctx.curInst.type == Instructions.InType.IN_ERR) {
                System.err.printf("ERROR: Opcode desconocido 0x%02X en PC: 0x%04X\n", CPUctx.curOpcode, pc_actual);
                return false;
            }

            // 4. FETCH DATA
            fetch_data();

            // 5. EXECUTE (El autómata cambia de estado)
            Execute.process(this, CPUctx.curInst);

            // 6. LOG (Vital para debug)
            System.out.printf("PC: %04X | Opcode: %02X | Inst: %s %s\n",
                    pc_actual, CPUctx.curOpcode, CPUctx.curInst.type, CPUctx.curInst.mode);
        }
        return true;
    }

    //
    private void fetch_data(){
        // Reset por seguridad
        CPUctx.memDest =  0;
        CPUctx.fetchData = 0;

        if (CPUctx.curInst == null) return;

        // Obtención de los datos acorde al modo de direccionamiento
        switch (CPUctx.curInst.mode){
            case AM_IMP: // Implícito, no hay nada que buscar
                return;

            // -- 16 bits en memoria --
            case AM_R_D16:
            case AM_D16:
                int lo = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;
                int hi = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;
                CPUctx.fetchData = (hi << 8) | lo;
                Emu.emu_get_context().ticks += 8;
                return;

            case AM_R_R:
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2) & 0xFF;
                return;

            case AM_MR_R:

            case AM_R:
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_1);
                return;
            case AM_R_D8:
            case AM_R_MR:
            case AM_R_HLI:
            case AM_R_HLD:
            case AM_HLI_R:
            case AM_HLD_R:
            case AM_R_A8:
            case AM_A8_R:
            case AM_HL_SPR:
            case AM_D8:
            case AM_D16_R:
            case AM_MR_D8:
            case AM_MR:
            case AM_A16_R:
            case AM_R_A16:
        }
    }


    /*
    * Los siguientes métodos son utilizados al momento de que alguna instrucción pida
    * leer o escribir sobre los registros
    */

    // --- MÉTODOS DE FLAGS ---
    public void setFlagZ(boolean on) { CPUctx.regs.f = Common.BIT_SET(CPUctx.regs.f, BIT_Z, on); }
    public void setFlagN(boolean on) { CPUctx.regs.f = Common.BIT_SET(CPUctx.regs.f, BIT_N, on); }
    public void setFlagH(boolean on) { CPUctx.regs.f = Common.BIT_SET(CPUctx.regs.f, BIT_H, on); }
    public void setFlagC(boolean on) { CPUctx.regs.f = Common.BIT_SET(CPUctx.regs.f, BIT_C, on); }

    public boolean getFlagZ() { return Common.BIT(CPUctx.regs.f, BIT_Z) != 0; }
    public boolean getFlagC() { return Common.BIT(CPUctx.regs.f, BIT_C) != 0; }

    // -- REGISTROS --
    public int read_reg(Instructions.RegType rt) { // Leer registro
        switch (rt) {
            case RT_A: return CPUctx.regs.a;
            case RT_B: return CPUctx.regs.b;
            case RT_C: return CPUctx.regs.c;
            case RT_D: return CPUctx.regs.d;
            case RT_E: return CPUctx.regs.e;
            case RT_H: return CPUctx.regs.h;
            case RT_L: return CPUctx.regs.l;
            case RT_AF: return CPUctx.regs.getAF();
            case RT_BC: return CPUctx.regs.getBC();
            case RT_DE: return CPUctx.regs.getDE();
            case RT_HL: return CPUctx.regs.getHL();
            case RT_SP: return CPUctx.regs.sp;
            case RT_PC: return CPUctx.regs.pc;
            default: return 0;
        }
    }

    /*
    * El masking es usado para simular un entero de 8 o 16 bits sin signo
    */
    public void write_reg(Instructions.RegType rt, int val) { // Escribir registro
        switch (rt) {
            case RT_A: CPUctx.regs.a = val & 0xFF; break;
            case RT_B: CPUctx.regs.b = val & 0xFF; break;
            case RT_C: CPUctx.regs.c = val & 0xFF; break;
            case RT_D: CPUctx.regs.d = val & 0xFF; break;
            case RT_E: CPUctx.regs.e = val & 0xFF; break;
            case RT_H: CPUctx.regs.h = val & 0xFF; break;
            case RT_L: CPUctx.regs.l = val & 0xFF; break;
            case RT_AF: CPUctx.regs.setAF(val & 0xFFFF); break;
            case RT_BC: CPUctx.regs.setBC(val & 0xFFFF); break;
            case RT_DE: CPUctx.regs.setDE(val & 0xFFFF); break;
            case RT_HL: CPUctx.regs.setHL(val & 0xFFFF); break;
            case RT_SP: CPUctx.regs.sp = val & 0xFFFF; break;
            case RT_PC: CPUctx.regs.pc = val & 0xFFFF; break;
        }
    }
}