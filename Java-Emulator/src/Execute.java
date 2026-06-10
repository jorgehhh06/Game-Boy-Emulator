
/*
 * Aquí es donde la máquina cambiará de estado de acuerdo a la instrucción decodificada.
 * Recomiendo mucho Game Boy: Complete Technical Reference: https://gekkio.fi/files/gb-docs/gbctr.pdf
 */
public class Execute {

    // Usamos -1 para "no modificar"
    private static void setFlags(CPU cpu, int z, int n, int h, int c) {
        if (z != -1) cpu.setFlagZ(z == 1);
        if (n != -1) cpu.setFlagN(n == 1);
        if (h != -1) cpu.setFlagH(h == 1);
        if (c != -1) cpu.setFlagC(c == 1);
    }

    public static void process(CPU cpu, Instruction instr, boolean isDestMem) {
        int a, val, res;

        switch (instr.type) {
            case IN_NONE:
                System.err.println("INVALID INSTRUCTION!");
                System.exit(-7);
                break;
            case IN_NOP:
                break;
            case IN_HALT:
                // Lógica para manejar el HALT Bug
                boolean ime = Bus.intrp.get_master_enabled();
                if (ime) {
                    // Caso normal: El CPU se duerme hasta una interrupción
                    cpu.setHalted(true);
                } else {
                    // Si el IME está apagado pero hay algo pendiente en IF habilitado en IE
                    if (Bus.intrp.has_pending_interrupts()) {
                        // HALT BUG: El CPU no se duerme, pero el próximo fetch no incrementará PC
                        cpu.setHaltBug(true);
                    } else {
                        // Caso normal con IME off: Se duerme hasta que llegue una señal
                        cpu.setHalted(true);
                    }
                }
                break;
            case IN_LD:
                execute_ld(cpu, instr, isDestMem);
                break;
            case IN_LDH:
                // Guarda valores en el acumulador, cargar hacia A
                if (instr.reg_1 == Instructions_Enum.RegType.RT_A) {
                    cpu.write_reg(instr.reg_1, cpu.getFetchData());
                } else {
                // LDH se usa para escribir IO del Hardware por medio del bus
                    Bus.bus_write(cpu.getMemDest(), cpu.getFetchData());
                    cpu.cycle(4);
                }
                break;

            case IN_ADD:
                // Siempre participa un registro en la sima
                val = cpu.read_reg(instr.reg_1) + cpu.getFetchData();
                boolean is16Bit = is16Bit(instr.reg_1);

                if (is16Bit) { // La ALU de 8 bits suma en 2 pasos
                    cpu.cycle(4);
                }

                if (instr.reg_1 == Instructions_Enum.RegType.RT_SP) {
                    cpu.cycle(4);
                    // Si se trabaja con el SP, convertimos el número de 8 bits sin signo (u8)
                    // en un número con complemento a dos (byte - i8)
                    val = cpu.read_reg(instr.reg_1) + (byte)(cpu.getFetchData() & 0xFF);
                }

                int z = (val & 0xFF) == 0 ? 1 : 0; // Si el resultado fue 0 se activa la Zero Flag
                // Si la suma de los dos nibbles bajos del sistema pone un 1 en el bit 4 del byte, se activa la Half-Carry Flag
                int h = ((cpu.read_reg(instr.reg_1) & 0xF) + (cpu.getFetchData() & 0xF)) >= 0x10 ? 1 : 0;
                // Si la suma de los dos bytes activa un 8vo byte, se activa la Carry Flag
                int c = ((cpu.read_reg(instr.reg_1) & 0xFF) + (cpu.getFetchData() & 0xFF)) >= 0x100 ? 1 : 0;

                if (is16Bit) {
                    z = -1; // La Zero Flag no se modifica
                    // Half-Carry aquí verifica acarreo del bit 11 al 12
                    h = ((cpu.read_reg(instr.reg_1) & 0xFFF) + (cpu.getFetchData() & 0xFFF)) >= 0x1000 ? 1 : 0;
                    // Carry Flag funciona del bit 15 al 16
                    c = (cpu.read_reg(instr.reg_1) + cpu.getFetchData()) >= 0x10000 ? 1 : 0;
                }

                if (instr.reg_1 == Instructions_Enum.RegType.RT_SP) {
                    z = 0; // Se desactiva esta flag
                    // Half-Carry y Carry se basan en los 8 bits inferiores
                    h = ((cpu.read_reg(instr.reg_1) & 0xF) + (cpu.getFetchData() & 0xF)) >= 0x10 ? 1 : 0;
                    c = ((cpu.read_reg(instr.reg_1) & 0xFF) + (cpu.getFetchData() & 0xFF)) >= 0x100 ? 1 : 0;
                }

                // Se almacena el valor en el registro y se actualizan las flags
                cpu.write_reg(instr.reg_1, val & 0xFFFF);
                setFlags(cpu, z, 0, h, c); // La n flag dice si la operación fue una resta
                break;

            case IN_ADC: // Suma normal pero se le suma el Carry, solo se realiza con el acumulador de 8 bits
                // Obtención de datos y suma
                int u = cpu.getFetchData();
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                int cFlag = cpu.getFlagC() ? 1 : 0;
                res = (a + u + cFlag) & 0xFF;

                // Escritura del resultado
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);

                // Flag Z se activa si el resultado fue 0, N desactivada porque la operación no fue una resta
                z = res == 0 ? 1 : 0;
                // H se activa si la suma de los nibbles bajos y el carry es mayor a un número de 4 bits
                h = (a & 0xF) + (u & 0xF) + cFlag > 0xF ? 1 : 0;
                // C se activa si la suma del conjunto es mayor a un número de 8 bits
                c = a + u + cFlag > 0xFF ? 1 : 0;
                setFlags(cpu, z, 0, h, c);
                break;

            case IN_SUB: // Resta para registros de 8 bits
                val = cpu.read_reg(instr.reg_1) - cpu.getFetchData();
                // Z flag si el resultado fue 0
                int zero = (val & 0xFF) == 0 ? 1 : 0;
                // La H flag y C flag indican si hubo un préstamo durante la resta
                // Si la resta de los nibbles bajos es igual a 0 significa que hubo un préstamo de Half-Carry
                int half = ((cpu.read_reg(instr.reg_1) & 0xF) - (cpu.getFetchData() & 0xF)) < 0 ? 1 : 0;
                // Si la resta da menor que 0, se requirió un préstamo del bit 8
                int cflag = (cpu.read_reg(instr.reg_1) - cpu.getFetchData()) < 0 ? 1 : 0;

                // Actualización del estado
                cpu.write_reg(instr.reg_1, val & 0xFFFF);
                setFlags(cpu, zero, 1, half, cflag);
                break;

            case IN_SBC: // Resta con carry
                int carry = cpu.getFlagC() ? 1 : 0;
                val = cpu.getFetchData() + carry; // Valor total a restar

                int sbc_res = cpu.read_reg(instr.reg_1) - val;
                // Cálculo de flags
                z = (sbc_res & 0xFF) == 0 ? 1 : 0;
                h = ((cpu.read_reg(instr.reg_1) & 0xF) - (cpu.getFetchData() & 0xF) - carry) < 0 ? 1 : 0;
                c = (cpu.read_reg(instr.reg_1) - cpu.getFetchData() - carry) < 0 ? 1 : 0;

                cpu.write_reg(instr.reg_1, sbc_res & 0xFFFF);
                setFlags(cpu, z, 1, h, c);
                break;

            case IN_RET: // Return
                if (instr.cond != Instructions_Enum.CondType.CT_NONE) { // Condición del Return
                    cpu.cycle(4); // Delay si es condicional
                }
                // Ejecución
                if (check_condition(cpu, instr.cond)) {
                    int lo = cpu.stack_pop(); // stack_pop ya cobra 4 ticks
                    int hi = cpu.stack_pop(); // stack_pop ya cobra 4 ticks
                    cpu.setPC(((hi << 8) | lo) & 0xFFFF);
                    cpu.cycle(4); // Se debió usar el bus para poner el PC
                }
                break;

            case IN_RETI: // RET + EI
                cpu.setIntMasterEnabled(true); // EI

                int lo_reti = cpu.stack_pop(); // 4 ticks
                int hi_reti = cpu.stack_pop(); // 4 ticks

                cpu.setPC((hi_reti << 8) | lo_reti);
                cpu.cycle(4); // Hardware delay, bus de direcciones
                break;

            case IN_POP: // Sacar algo de la pila
                int lo_pop = cpu.stack_pop(); // 4 ticks
                int hi_pop = cpu.stack_pop(); // 4 ticks
                int pop_val = (hi_pop << 8) | lo_pop;

                if (instr.reg_1 == Instructions_Enum.RegType.RT_AF) {
                    pop_val &= 0xFFF0; // AF siempre tiene los 4 bits bajos en 0
                }
                // Almacena en valor sacado del SP en el registro
                cpu.write_reg(instr.reg_1, pop_val);
                break;

            case IN_PUSH: // Poner algo en la pila
                // Nibble alto primero
                int hi_push = (cpu.read_reg(instr.reg_1) >> 8) & 0xFF;
                cpu.cycle(4); // Preparación del dato
                cpu.stack_push(hi_push); // 4 ticks

                // Nibble bajo después
                int lo_push = cpu.read_reg(instr.reg_1) & 0xFF;
                cpu.stack_push(lo_push); // 4 ticks
                break;

            case IN_AND: // Operador a nivel de bits &
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                res = a & cpu.getFetchData();
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, res == 0 ? 1 : 0, 0, 1, 0);
                break;

            case IN_XOR: // Operador a nivel de bits ^
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                res = a ^ (cpu.getFetchData() & 0xFF);
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, res == 0 ? 1 : 0, 0, 0, 0);
                break;

            case IN_OR: // Operador a nivel de bits |
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                res = a | (cpu.getFetchData() & 0xFF);
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, res == 0 ? 1 : 0, 0, 0, 0);
                break;

            case IN_CP: // Comparación (resta) de un valor contra el registro A
                int n = (cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF) - (cpu.getFetchData() & 0xFF);
                setFlags(cpu, n == 0 ? 1 : 0, 1,
                        ((cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0x0F) - (cpu.getFetchData() & 0x0F)) < 0 ? 1 : 0,
                        n < 0 ? 1 : 0);
                break;

            case IN_INC:
                execute_inc(cpu, instr);
                break;
            case IN_DEC:
                execute_dec(cpu, instr);
                break;

            case IN_DAA: // Decimal Adjust Accumulator - Binary Coded Decimal
                int u_daa = 0; // Acumulador de corrección
                int fc = 0; // Final carry
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;

                // Si la suma anterior tuvo acarreo del bit 3 al 4 (se pasó de 15)
                // o si no estamos restando (Flag N desactivada) y el primer dífito es mayor a 9
                // Debemos hacer un ajuste y sumar 6 para desbordar el hexadecimal a decimal
                if (cpu.getFlagH() || (!cpu.getFlagN() && (a & 0xF) > 9)) {
                    u_daa = 6;
                }
                // Si la suma anterior se pasó de 255
                // o si no estamos restando y el acumulador es mayor a 0x99
                // El número es demasiado grande para dos dígitos decimales
                if (cpu.getFlagC() || (!cpu.getFlagN() && a > 0x99)) {
                    u_daa |= 0x60; // Añadimos 6 decenas
                    fc = 1; // Avisamos de desbordamiento inicial
                }

                // Si la última operación fue una resta, el acumulador de corrección se vuelve negativo
                a += cpu.getFlagN() ? -u_daa : u_daa;
                cpu.write_reg(Instructions_Enum.RegType.RT_A, a & 0xFF);
                setFlags(cpu, (a & 0xFF) == 0 ? 1 : 0, -1, 0, fc);
                break;

            case IN_CPL: // Complemento a 1, invierte los bits con un NOT
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A);
                cpu.write_reg(Instructions_Enum.RegType.RT_A, (~a) & 0xFF);
                setFlags(cpu, -1, 1, 1, -1);
                break;

            case IN_SCF: // Set Carry Flag
                setFlags(cpu, -1, 0, 0, 1);
                break;
            case IN_CCF: // Complement Carry Flag
                setFlags(cpu, -1, 0, 0, cpu.getFlagC() ? 0 : 1);
                break;

            case IN_JP: // Jump
                if (instr.mode == Instructions_Enum.AddrMode.AM_R) { // JP (HL)
                    cpu.setPC(cpu.getFetchData()); // 4 ciclos totales, sin delay extra
                } else {
                    goto_addr(cpu, cpu.getFetchData(), false, instr);
                }
                break;

            case IN_JR: // Jump Relative
                byte relative_addr = (byte)(cpu.getFetchData() & 0xFF);
                if (check_condition(cpu, instr.cond)) {
                    cpu.setPC(cpu.getPC() + relative_addr);
                    cpu.cycle(4); // Ciclo extra por el salto
                }
                break;

            case IN_CALL: // Call
                goto_addr(cpu, cpu.getFetchData(), true, instr);
                break;

            case IN_RST: // Reset (va a un vector)
                goto_addr(cpu, instr.param, true, instr);
                break;

            case IN_DI: // Disable Interrupts
                cpu.setIntMasterEnabled(false);
                break;
            case IN_EI:
                cpu.setEnablingIme(true);
                break;

            case IN_RLCA: // Rotate Left Circular Accumulator
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                int c_rlca = (a >> 7) & 1; // EL bit 7 reemplaza al bit 0
                res = ((a << 1) | c_rlca) & 0xFF;
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, 0, 0, 0, c_rlca);
                break;

            case IN_RRCA: // Rotate Right Circular Accumulator
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                int c_rrca = a & 1; // El bit 0 reemplaza al bit 7
                res = ((a >> 1) | (c_rrca << 7)) & 0xFF;
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, 0, 0, 0, c_rrca);
                break;

            case IN_RLA: // Rotate Left Accumulator
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                int cf = cpu.getFlagC() ? 1 : 0; // El carry rellena el hueco

                int c_rla = (a >> 7) & 1; // El carry es el bit que se quita
                res = ((a << 1) | cf) & 0xFF;
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, 0, 0, 0, c_rla);
                break;

            case IN_RRA: // Rotate Right Accumulator
                a = cpu.read_reg(Instructions_Enum.RegType.RT_A) & 0xFF;
                carry = cpu.getFlagC() ? 1 : 0; // Carry reemplaza el bit que se salió

                int new_c = a & 1; // El Carry es el bit que se quitó
                res = ((a >> 1) | (carry << 7)) & 0xFF;
                cpu.write_reg(Instructions_Enum.RegType.RT_A, res);
                setFlags(cpu, 0, 0, 0, new_c);
                break;

            case IN_STOP:
                cpu.setPC((cpu.getPC() + 1) & 0xFFFF);
                cpu.setStopped(true);
                Bus.timer.setDIV(0);
                break;

            default:
                System.err.printf("Opcode no implementado: %s\n", instr.type);
                break;
        }
    }

    // -- FUNCIONES AUXILIARES --

    private static void goto_addr(CPU cpu, int addr, boolean pushpc, Instruction instr) {
        if (check_condition(cpu, instr.cond)) {
            if (pushpc) {
                int pc_to_push = cpu.getPC();
                cpu.stack_push((pc_to_push >> 8) & 0xFF);
                cpu.stack_push(pc_to_push & 0xFF);
            }
            cpu.setPC(addr);
            cpu.cycle(4); // Delay final del salto
        }
    }

    private static void execute_ld(CPU cpu, Instruction instr, boolean isDestMem) {
        // Cargar a memoria
        if (isDestMem) {
            // Solo la instrucción 0x08 escribe 16 bits (el Stack Pointer) a memoria
            if (instr.type == Instructions_Enum.InType.IN_LD && instr.reg_2 == Instructions_Enum.RegType.RT_SP) {
                int spVal = cpu.read_reg(Instructions_Enum.RegType.RT_SP); // Valor en el SP
                // Escritura en dos partes
                Bus.bus_write(cpu.getMemDest(), spVal & 0xFF);
                cpu.cycle(4);
                Bus.bus_write((cpu.getMemDest() + 1) & 0xFFFF, (spVal >> 8) & 0xFF);
                cpu.cycle(4);
            } else {
                // El resto es de 8 bits
                Bus.bus_write(cpu.getMemDest(), cpu.getFetchData() & 0xFF); // Se escribe el valor en el bus
                cpu.cycle(4);
            }
            return;
        }

        // Carga de HL + r8 (Signed)
        if (instr.mode == Instructions_Enum.AddrMode.AM_HL_SPR) {
            int r1 = cpu.read_reg(instr.reg_2);
            int r2 = cpu.getFetchData(); // El valor d8 ya viene procesado

            // Flags específicos para esta operación de 16 bits
            // Desbordamiento entre el bit 3 y 4
            int h = ((r1 & 0xF) + (r2 & 0xF) >= 0x10) ? 1 : 0;
            // Desbordamiento del bit 7 al 8
            int c = ((r1 & 0xFF) + (r2 & 0xFF) >= 0x100) ? 1 : 0;
            setFlags(cpu, 0, 0, h, c);

            // (byte) hace que sea interpretado como complemento a 2
            cpu.write_reg(instr.reg_1, (r1 + (byte)r2) & 0xFFFF);
            cpu.cycle(4);
            return;
        }

        // Carga normal entre registros
        cpu.write_reg(instr.reg_1, cpu.getFetchData());
        // FIX: LD SP, HL (0xF9) toma 8 ciclos en total (4 decode + 4 internos).
        if (instr.reg_1 == Instructions_Enum.RegType.RT_SP && instr.reg_2 == Instructions_Enum.RegType.RT_HL) {
            cpu.cycle(4);
        }
    }

    private static void execute_inc(CPU cpu, Instruction instr) {
        // FIX: INC (HL) opera en 8 bits, no debe sufrir el delay de is16Bit()
        if (instr.reg_1 == Instructions_Enum.RegType.RT_HL && instr.mode == Instructions_Enum.AddrMode.AM_MR) {
            int val = (cpu.getFetchData() + 1) & 0xFF;
            Bus.bus_write(cpu.read_reg(Instructions_Enum.RegType.RT_HL), val);
            cpu.cycle(4);
            setFlags(cpu, val == 0 ? 1 : 0, 0, (val & 0x0F) == 0 ? 1 : 0, -1);
            return; // Salimos inmediatamente
        }

        int val = (cpu.read_reg(instr.reg_1) + 1) & 0xFFFF;

        if (is16Bit(instr.reg_1)) {
            cpu.cycle(4); // Trabajo extra por operaciones de 16 bits puros
        }

        cpu.write_reg(instr.reg_1, val);
        val = cpu.read_reg(instr.reg_1);

        if ((cpu.getCurOpcode() & 0x03) == 0x03) {
            return;
        }
        setFlags(cpu, val == 0 ? 1 : 0, 0, (val & 0x0F) == 0 ? 1 : 0, -1);
    }

    private static void execute_dec(CPU cpu, Instruction instr) {
        // FIX: DEC (HL) opera en 8 bits, no debe sufrir el delay de is16Bit()
        if (instr.reg_1 == Instructions_Enum.RegType.RT_HL && instr.mode == Instructions_Enum.AddrMode.AM_MR) {
            int val = (cpu.getFetchData() - 1) & 0xFF;
            Bus.bus_write(cpu.read_reg(Instructions_Enum.RegType.RT_HL), val);
            cpu.cycle(4);
            setFlags(cpu, val == 0 ? 1 : 0, 1, (val & 0x0F) == 0x0F ? 1 : 0, -1);
            return; // Salimos inmediatamente
        }

        int val = (cpu.read_reg(instr.reg_1) - 1) & 0xFFFF;

        if (is16Bit(instr.reg_1)) {
            cpu.cycle(4); // Delay por trabajo de 16 bits puros
        }

        cpu.write_reg(instr.reg_1, val);
        val = cpu.read_reg(instr.reg_1);

        if ((cpu.getCurOpcode() & 0x0B) == 0x0B) {
            return;
        }
        setFlags(cpu, val == 0 ? 1 : 0, 1, (val & 0x0F) == 0x0F ? 1 : 0, -1);
    }

    private static boolean is16Bit(Instructions_Enum.RegType rt) {
        // Como organicé el enum, todos los registros a partir de AF son de 16 bits
        return rt.ordinal() >= Instructions_Enum.RegType.RT_AF.ordinal();
    }

    private static boolean check_condition(CPU cpu, Instructions_Enum.CondType cond) {
        boolean z = cpu.getFlagZ();
        boolean c = cpu.getFlagC();
        switch (cond) {
            case CT_NONE: return true;
            case CT_Z:    return z;
            case CT_NZ:   return !z;
            case CT_C:    return c;
            case CT_NC:   return !c;
        }
        return false;
    }
}