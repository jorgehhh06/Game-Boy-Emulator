/*
* Este archivo se encarga de la estructura del CPU
*/
public class CPU {

    // Posiciones de las flags en el registro F (ZNHC 0000)
    private static final int BIT_Z = 7;
    private static final int BIT_N = 6;
    private static final int BIT_H = 5;
    private static final int BIT_C = 4;

    private static class CpuRegisters {
        int a, b, c, d, e, f, h, l; // Registros de la CPU
        int pc = 0x100; // Punto de entrada después del Boot ROM (logo de nintendo)
        int sp = 0xFFFE; // El Stack suele empezar al final de la HRAM

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

        public int getAF() { return ((this.a & 0xFF) << 8) | (this.f & 0xFF); } // Se usa para preservar el estado del sistema
        public void setAF(int val) {
            a = (val >> 8) & 0xFF;
            f = val & 0xF0;
        }

        public int getBC() { return ((this.b & 0xFF) << 8) | (this.c & 0xFF); }
        public void setBC(int val) {
            b = (val >> 8) & 0xFF;
            c = val & 0xFF;
        }

        public int getDE() { return ((this.d & 0xFF)<< 8) | (this.e & 0xFF); }
        public void setDE(int val) {
            d = (val >> 8) & 0xFF;
            e = val & 0xFF;
        }

        public int getHL() { return ((this.h & 0xFF) << 8) | (this.l & 0xFF); } // Es usado como puntero maestro, High & Low
        public void setHL(int val) {
            h = (val >> 8) & 0xFF;
            l = val & 0xFF;
        }

    }

    /*
    * CpuContext me permite depurar el emulador, es el objeto de la CPU
    */

    private class CpuContext {
        CpuRegisters regs = new CpuRegisters();

        Instruction curInst; // Instrucción actual

        int fetchData; // Información recogida
        int memDest; // Destinación de la memoria
        int curOpcode; // Opcode actual

        boolean destIsMem; // Se escribirá en memoria o en registro

        boolean halted = false;
        boolean halt_bug = false;
        boolean stopped = false;
    }
    public void setStopped(boolean stopped) { CPUctx.stopped = stopped; }

    private CpuContext CPUctx = new CpuContext(); // Objeto de la CPU

    public int getFetchData() { return CPUctx.fetchData; }
    public int getMemDest() { return CPUctx.memDest; }
    public int getCurOpcode() { return CPUctx.curOpcode; }
    public void setPC(int pc) { CPUctx.regs.pc = pc & 0xFFFF; }
    public int getPC() { return CPUctx.regs.pc; }
    public int getSP() { return CPUctx.regs.sp; } // Se usó para depurar
    public void setHalted(boolean halted) { CPUctx.halted = halted; }
    public void setHaltBug(boolean bug) { CPUctx.halt_bug = bug; }
    public boolean isHalted() { return CPUctx.halted; } // Se usó para depurar

    // -- INICIALIZACIÓN TRAS EL LOGO DE NINTENDO --
    public void cpu_init() {
        CPUctx.regs.pc = 0x100;
        CPUctx.regs.a = 0x01; // 0x01 fuerza el modo Clásico. 0x11 activaría la GBC y crashearía.
        CPUctx.regs.f = 0xB0;
        CPUctx.regs.b = 0x00;
        CPUctx.regs.c = 0x13;
        CPUctx.regs.d = 0x00;
        CPUctx.regs.e = 0xD8;
        CPUctx.regs.h = 0x01;
        CPUctx.regs.l = 0x4D;
        CPUctx.regs.sp = 0xFFFE;

        // -- VALORES POST-BIOS CRÍTICOS --
        MemoryMapped_IO.lcd.regs[0x00] = 0x91; // LCDC: Encendido, BG activo, etc.
        MemoryMapped_IO.lcd.regs[0x07] = 0xE4; // BGP: Paleta estándar
        MemoryMapped_IO.lcd.lcd_write(0xFF40, 0x91); // Esto activa LCDC
        MemoryMapped_IO.lcd.lcd_write(0xFF47, 0xE4); // Esto activa la paleta de fondo
    }

    // -- CICLO (FUNCIÓN DE TRANSICIÓN DEL AUTÓMATA FINITO) --

    public void cpu_step() {

        if (CPUctx.stopped) {
            // El hardware real despierta por la interrupción (Flanco)
            // O si simplemente la línea ya está presionada (Nivel bajo)
            boolean joypadInterrupt = (Bus.intrp.get_if_register() & (1 << Interrupts.JOYPAD)) != 0;
            boolean buttonHeld = (MemoryMapped_IO.gamepad.read() & 0x0F) != 0x0F;

            if (joypadInterrupt || buttonHeld) {
                CPUctx.stopped = false;
            } else {
                return; // Si sigue dormida, el tiempo no avanza
            }
        }

        if (CPUctx.halted) {
            // HALT despierta si hay CUALQUIER interrupción pendiente (IE & IF != 0)
            if (Bus.intrp.has_pending_interrupts()) {
                CPUctx.halted = false;
            } else {
                cycle(4);
                return; // Si sigue dormida, el oscilador sigue corriendo
            }
        }

        // Manejo de Interrupciones (Antes del Fetch)
        // Solo saltamos al vector si el Master Enable está activo
        if (Bus.intrp.get_master_enabled() && Bus.intrp.has_pending_interrupts()) {
            handle_interrupts();
            return;
        }

        // Actualizar el IME (EI tiene un retraso de 1 instrucción)
        Bus.intrp.update_ime();


        // FETCH NORMAL (O con HALT BUG)
        int pc_actual = CPUctx.regs.pc & 0xFFFF;
        int opcode;

        if (CPUctx.halt_bug) {
            // HALT BUG: Leemos el byte pero NO incrementamos el PC.
            // Esto causa que la siguiente instrucción repita el opcode
            // o lea un operando como opcode.
            opcode = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
            CPUctx.halt_bug = false;
        } else {
            opcode = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;
        }
        cycle(4);

        // DECODE Y EXECUTE
        if (opcode == 0xCB) {
            int cbOpcode = Bus.bus_read(CPUctx.regs.pc++) & 0xFF;
            cycle(4);
            CPUctx.curOpcode = cbOpcode;
            CPUctx.curInst = CB_InstructionTable.get_cb_instruction_by_opcode(cbOpcode);
            fetch_data();
            CB_Execute.process(this, CPUctx.curInst);
        } else {
            CPUctx.curOpcode = opcode;
            CPUctx.curInst = InstructionTable.get_instruction_by_opcode(opcode);
            fetch_data();
            Execute.process(this, CPUctx.curInst, CPUctx.destIsMem);
        }

        // Validación y Log
        if (CPUctx.curInst == null || CPUctx.curInst.type == Instructions_Enum.InType.IN_ERR) {
            System.err.printf("ERROR: Opcode desconocido 0x%02X en PC: 0x%04X\n", CPUctx.curOpcode, pc_actual);
        }
    }

    private void fetch_data() {
        // Reset por seguridad
        CPUctx.memDest = 0;
        CPUctx.destIsMem = false;
        CPUctx.fetchData = 0;

        if (CPUctx.curInst == null) {
            return;
        }

        switch (CPUctx.curInst.mode) {
            case AM_IMP: // Implícito
                return;

            case AM_R: // Operación sobre un registro
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_1);
                return;

            case AM_R_R: // Registro a registro
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                return;

            case AM_R_D8: // Dato de 8 bits a registro
            case AM_D8: // Dato de 8 bits
                CPUctx.fetchData = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                CPUctx.regs.pc = (CPUctx.regs.pc + 1) & 0xFFFF;
                return;

            case AM_R_D16: // Dato de 16 bits a registro
            case AM_D16: { // Dato de 16 bits
                int lo = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                int hi = Bus.bus_read((CPUctx.regs.pc + 1) & 0xFFFF) & 0xFF;
                cycle(4);

                CPUctx.fetchData = lo | (hi << 8);
                CPUctx.regs.pc = (CPUctx.regs.pc + 2) & 0xFFFF;
                return;
            }

            case AM_MR_R: // Registro a memoria
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                CPUctx.memDest = read_reg(CPUctx.curInst.reg_1);
                CPUctx.destIsMem = true;

                if (CPUctx.curInst.reg_1 == Instructions_Enum.RegType.RT_C) {
                    CPUctx.memDest |= 0xFF00;
                }
                return;

            case AM_R_MR: { // Memoria a registro
                int addr = read_reg(CPUctx.curInst.reg_2);

                if (CPUctx.curInst.reg_2 == Instructions_Enum.RegType.RT_C) {
                    addr |= 0xFF00;
                }

                CPUctx.fetchData = Bus.bus_read(addr) & 0xFF;
                cycle(4);
                return;
            }

            case AM_R_HLI: // Registro HL a registro
                CPUctx.fetchData = Bus.bus_read(read_reg(Instructions_Enum.RegType.RT_HL)) & 0xFF;
                cycle(4);
                write_reg(Instructions_Enum.RegType.RT_HL, (read_reg(Instructions_Enum.RegType.RT_HL) + 1) & 0xFFFF);
                return;

            case AM_R_HLD: // Registro HL decrement a registro
                CPUctx.fetchData = Bus.bus_read(read_reg(Instructions_Enum.RegType.RT_HL)) & 0xFF;
                cycle(4);
                write_reg(Instructions_Enum.RegType.RT_HL, (read_reg(Instructions_Enum.RegType.RT_HL) - 1) & 0xFFFF);
                return;

            case AM_HLI_R: // Registro a registro HL increment
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                CPUctx.memDest = read_reg(Instructions_Enum.RegType.RT_HL);
                CPUctx.destIsMem = true;
                write_reg(Instructions_Enum.RegType.RT_HL, (read_reg(Instructions_Enum.RegType.RT_HL) + 1) & 0xFFFF);
                return;

            case AM_HLD_R: // Registro a registro HL decrement
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                CPUctx.memDest = read_reg(Instructions_Enum.RegType.RT_HL);
                CPUctx.destIsMem = true;

                write_reg(Instructions_Enum.RegType.RT_HL, (read_reg(Instructions_Enum.RegType.RT_HL) - 1) & 0xFFFF);
                return;

            case AM_R_A8: // Dirección de memoria de 8 bits a registro (optimizado para high page)
                int offsetA8 = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                CPUctx.regs.pc = (CPUctx.regs.pc + 1) & 0xFFFF;
                CPUctx.fetchData = Bus.bus_read(0xFF00 | offsetA8) & 0xFF;
                cycle(4);
                return;

            case AM_A8_R: // Registro a dirección de memoria de 8 bits (optimizado para high page)
                CPUctx.memDest = (Bus.bus_read(CPUctx.regs.pc) & 0xFF) | 0xFF00;
                CPUctx.destIsMem = true;
                cycle(4);
                CPUctx.regs.pc = (CPUctx.regs.pc + 1) & 0xFFFF;
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                return;

            case AM_HL_SPR: // Stack pointer relative a registro HL
                // Este modo de direccionamiento solo se usa en LD HL,SP+i8 - 0xF8
                // i8 es un número inmediato de 8 bits
                // Al usar registros de la CPU solo es necesario obtener el offset i8
                CPUctx.fetchData = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                CPUctx.regs.pc = (CPUctx.regs.pc + 1) & 0xFFFF;
                return;

            case AM_A16_R: { // Registro a dirección de memoria de 16 bits
                // La dirección de memoria objetivo está en los siguientes 2 bytes
                int lo = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                int hi = Bus.bus_read((CPUctx.regs.pc + 1) & 0xFFFF) & 0xFF;
                cycle(4);

                CPUctx.memDest = lo | (hi << 8);
                CPUctx.destIsMem = true;
                CPUctx.regs.pc = (CPUctx.regs.pc + 2) & 0xFFFF;
                CPUctx.fetchData = read_reg(CPUctx.curInst.reg_2);
                return;
            }

            case AM_MR_D8: // Dato de 8 bits a memoria
                CPUctx.fetchData = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                CPUctx.regs.pc = (CPUctx.regs.pc + 1) & 0xFFFF;
                CPUctx.memDest = read_reg(CPUctx.curInst.reg_1);
                CPUctx.destIsMem = true;
                return;

            case AM_MR: // Memoria
                // PD: Todas las instrucciones que usan este modo de direccionamiento usan un puntero
                // Eso explica la siguiente línea
                CPUctx.memDest = read_reg(CPUctx.curInst.reg_1);
                CPUctx.destIsMem = true;
                CPUctx.fetchData = Bus.bus_read(CPUctx.memDest) & 0xFF;
                cycle(4);
                return;

            case AM_R_A16: { // Dirección de memoria de 16 bits a registro
                int lo = Bus.bus_read(CPUctx.regs.pc) & 0xFF;
                cycle(4);
                int hi = Bus.bus_read((CPUctx.regs.pc + 1) & 0xFFFF) & 0xFF;
                cycle(4);
                int addr = lo | (hi << 8);
                CPUctx.regs.pc = (CPUctx.regs.pc + 2) & 0xFFFF;
                CPUctx.fetchData = Bus.bus_read(addr) & 0xFF;
                cycle(4);
                return;
            }

            default:
                System.err.printf("Unknown Addressing Mode! %s (%02X)\n", CPUctx.curInst.mode, CPUctx.curOpcode);
                System.exit(-7);
                return;
        }
    }

    // -- PUENTES PARA EL MANEJO DE INTERRUPCIONES (DI / EI) --
    public void setIntMasterEnabled(boolean enabled) {
        Bus.intrp.set_master_enabled(enabled);
    }

    public void setEnablingIme(boolean enabling) {
        if (enabling) {
            Bus.intrp.schedule_enable_ime();
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
    public boolean getFlagN() { return Common.BIT(CPUctx.regs.f, BIT_N) != 0; }
    public boolean getFlagH() { return Common.BIT(CPUctx.regs.f, BIT_H) != 0; }
    public boolean getFlagC() { return Common.BIT(CPUctx.regs.f, BIT_C) != 0; }

    // -- REGISTROS --
    public int read_reg(Instructions_Enum.RegType rt) { // Leer registro
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
    public void write_reg(Instructions_Enum.RegType rt, int val) { // Escribir registro
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

    // -- OPERACIONES DE LA PILA --
    public void stack_push(int data){
        Bus.bus_write(--CPUctx.regs.sp, data & 0xFF);
        cycle(4);
    }

    public int stack_pop(){
        int data = Bus.bus_read(CPUctx.regs.sp++) & 0xFF;
        cycle(4);
        return data & 0xFF;
    }

    public void stack_push16(int data){
        stack_push( (data>>8) & 0xFF); // Little Endian
        stack_push(data & 0xFF);
    }

    // -- MANEJO DE INTERRUPCIONES --

    private void handle_interrupts() {
        // Intentamos consumir una interrupción (esto checa prioridades y limpia el flag IF)
        int vector = Bus.intrp.consume_interrupt();
        if (vector != -1) {
            // Si entramos aquí, es que hay una interrupción que DEBE atenderse

            // El proceso de "Aceptar" una interrupción toma tiempo (5 M-Cycles / 20 T-Cycles)
            cycle(8); // Delay inicial de hardware

            // Guardamos el PC actual en el Stack para poder volver (como un CALL)
            stack_push16(CPUctx.regs.pc);

            // Saltamos a la dirección del vector (0x40, 0x48, etc.)
            CPUctx.regs.pc = vector;
            cycle(4); // Tiempo que tarda el salto

            // Nota: consume_interrupt() ya puso el master_enabled (IME) en false
        }
    }

    // =======================
    // ACTUALIZACIÓN DE CICLOS
    // =======================

    public void cycle(int ticks) {
        for (int i = 0; i < ticks; ++i) {
            // Prioridad de propagación de la señal del reloj
            Emu.emu_get_context().ticks++; // Contador global
            Bus.ppu.ppu_tick(); // La PPU es muy sensible al timing
            Bus.timer.timer_tick(); // Disparar interrupciones es importante
            Bus.dma.dma_tick(); // Primero se actualiza la PPU y luego el DMA puede actuar
        }
    }
}