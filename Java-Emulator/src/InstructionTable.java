/*
* Aquí se decidirá qué función ejecutar en base en el opcode, hay 512 posibles entradas
* El funcionamiento de cada instrucción se sacó de la tabla de opcodes del Game Boy y de una referencia técnica
*
* Enlaces a la documentación:
* gbops, an accurate opcode table for the Game Boy: https://izik1.github.io/gbops/
* Game Boy: Complete Technical Reference: https://gekkio.fi/files/gb-docs/gbctr.pdf
 */

public class InstructionTable {

    // Arreglo de instrucciones, 8 bits de instrucciones base
    private static Instruction[] instructions = new Instruction[0x100];

    // Dirigir al índice de la tabla de referencia
    public static Instruction get_instruction_by_opcode(int opcode) {
        return instructions[opcode & 0xFF];
    }

    public static void init() {
        // Inicialización por defecto, hace que cada posible entrada tenga un comportamiento definido
        for (int i = 0; i < 0x100; i++) {
            instructions[i] = new Instruction(Instructions.InType.IN_ERR, Instructions.AddrMode.AM_IMP);
        }
        // ==========================================
        // BLOQUE 0x00 - 0x3F (Cargas de 16 bits y Aritmética simple)
        // ==========================================
        instructions[0x00] = new Instruction(Instructions.InType.IN_NOP, Instructions.AddrMode.AM_IMP);
        instructions[0x01] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_R_D16, Instructions.RegType.RT_BC);
        instructions[0x02] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_MR_R,  Instructions.RegType.RT_BC, Instructions.RegType.RT_A);
        instructions[0x03] = new Instruction(Instructions.InType.IN_INC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_BC);
        instructions[0x04] = new Instruction(Instructions.InType.IN_INC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_B);
        instructions[0x05] = new Instruction(Instructions.InType.IN_DEC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_B);
        instructions[0x06] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_B);
        instructions[0x07] = new Instruction(Instructions.InType.IN_RLCA, Instructions.AddrMode.AM_IMP);
        instructions[0x08] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_A16_R, Instructions.RegType.RT_NONE, Instructions.RegType.RT_SP);
        instructions[0x09] = new Instruction(Instructions.InType.IN_ADD, Instructions.AddrMode.AM_R_R,   Instructions.RegType.RT_HL, Instructions.RegType.RT_BC);
        instructions[0x0A] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_R_MR,  Instructions.RegType.RT_A,  Instructions.RegType.RT_BC);
        instructions[0x0B] = new Instruction(Instructions.InType.IN_DEC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_BC);
        instructions[0x0C] = new Instruction(Instructions.InType.IN_INC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_C);
        instructions[0x0D] = new Instruction(Instructions.InType.IN_DEC, Instructions.AddrMode.AM_R,     Instructions.RegType.RT_C);
        instructions[0x0E] = new Instruction(Instructions.InType.IN_LD,  Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_C);
        instructions[0x0F] = new Instruction(Instructions.InType.IN_RRCA, Instructions.AddrMode.AM_IMP);

        instructions[0x10] = new Instruction(Instructions.InType.IN_STOP, Instructions.AddrMode.AM_IMP);
        instructions[0x11] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D16, Instructions.RegType.RT_DE);
        instructions[0x12] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_MR_R,  Instructions.RegType.RT_DE, Instructions.RegType.RT_A);
        instructions[0x13] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_DE);
        instructions[0x14] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_D);
        instructions[0x15] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_D);
        instructions[0x16] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_D);
        instructions[0x17] = new Instruction(Instructions.InType.IN_RLA,  Instructions.AddrMode.AM_IMP);
        instructions[0x18] = new Instruction(Instructions.InType.IN_JR,   Instructions.AddrMode.AM_D8);
        instructions[0x19] = new Instruction(Instructions.InType.IN_ADD,  Instructions.AddrMode.AM_R_R,   Instructions.RegType.RT_HL, Instructions.RegType.RT_DE);
        instructions[0x1A] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_MR,  Instructions.RegType.RT_A,  Instructions.RegType.RT_DE);
        instructions[0x1B] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_DE);
        instructions[0x1C] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_E);
        instructions[0x1D] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_E);
        instructions[0x1E] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_E);
        instructions[0x1F] = new Instruction(Instructions.InType.IN_RRA,  Instructions.AddrMode.AM_IMP);

        instructions[0x20] = new Instruction(Instructions.InType.IN_JR,   Instructions.AddrMode.AM_D8,    Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NZ, 0);
        instructions[0x21] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D16, Instructions.RegType.RT_HL);
        instructions[0x22] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_HLI_R, Instructions.RegType.RT_HL, Instructions.RegType.RT_A);
        instructions[0x23] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_HL);
        instructions[0x24] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_H);
        instructions[0x25] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_H);
        instructions[0x26] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_H);
        instructions[0x27] = new Instruction(Instructions.InType.IN_DAA,  Instructions.AddrMode.AM_IMP);
        instructions[0x28] = new Instruction(Instructions.InType.IN_JR,   Instructions.AddrMode.AM_D8,    Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_Z, 0);
        instructions[0x29] = new Instruction(Instructions.InType.IN_ADD,  Instructions.AddrMode.AM_R_R,   Instructions.RegType.RT_HL, Instructions.RegType.RT_HL);
        instructions[0x2A] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_HLI, Instructions.RegType.RT_A,  Instructions.RegType.RT_HL);
        instructions[0x2B] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_HL);
        instructions[0x2C] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_L);
        instructions[0x2D] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_L);
        instructions[0x2E] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_L);
        instructions[0x2F] = new Instruction(Instructions.InType.IN_CPL,  Instructions.AddrMode.AM_IMP);

        instructions[0x30] = new Instruction(Instructions.InType.IN_JR,   Instructions.AddrMode.AM_D8,    Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NC, 0);
        instructions[0x31] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D16, Instructions.RegType.RT_SP);
        instructions[0x32] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_HLD_R, Instructions.RegType.RT_HL, Instructions.RegType.RT_A);
        instructions[0x33] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_SP);
        instructions[0x34] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_MR,    Instructions.RegType.RT_HL);
        instructions[0x35] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_MR,    Instructions.RegType.RT_HL);
        instructions[0x36] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_MR_D8, Instructions.RegType.RT_HL);
        instructions[0x37] = new Instruction(Instructions.InType.IN_SCF,  Instructions.AddrMode.AM_IMP);
        instructions[0x38] = new Instruction(Instructions.InType.IN_JR,   Instructions.AddrMode.AM_D8,    Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_C, 0);
        instructions[0x39] = new Instruction(Instructions.InType.IN_ADD,  Instructions.AddrMode.AM_R_R,   Instructions.RegType.RT_HL, Instructions.RegType.RT_SP);
        instructions[0x3A] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_HLD, Instructions.RegType.RT_A,  Instructions.RegType.RT_HL);
        instructions[0x3B] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_SP);
        instructions[0x3C] = new Instruction(Instructions.InType.IN_INC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_A);
        instructions[0x3D] = new Instruction(Instructions.InType.IN_DEC,  Instructions.AddrMode.AM_R,     Instructions.RegType.RT_A);
        instructions[0x3E] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_D8,  Instructions.RegType.RT_A);
        instructions[0x3F] = new Instruction(Instructions.InType.IN_CCF,  Instructions.AddrMode.AM_IMP);

        // --- BLOQUE DINÁMICO 0x40 - 0x7F ---
        // Son todas las instrucciones posibles para mover un dato a otro (IN_LD), la única excepción es 0x76, esa es un HALT
        // Este array sigue la progresión de registros de la tabla de opcodes, son 8 filas por registro principal
        // RT_NONE hace referencia a (HL), que es una dirección de memoria
        Instructions.RegType[] rtLookup = {Instructions.RegType.RT_B, Instructions.RegType.RT_C, Instructions.RegType.RT_D, Instructions.RegType.RT_E, Instructions.RegType.RT_H, Instructions.RegType.RT_L, Instructions.RegType.RT_NONE, Instructions.RegType.RT_A};

        for (int i = 0x40; i <= 0x7F; i++) {
            if (i == 0x76) { // Esta instrucción es un HALT, las demás son LD (aunque en realidad debería ser LD [HL], [HL], pero eso no tiene sentido
                instructions[i] = new Instruction(Instructions.InType.IN_HALT, Instructions.AddrMode.AM_IMP);
                continue;
            }
            // Al restar 0x40, el rango pasa de 64-127 a 0-63, >> 0x03 es una forma de dividir entre 8 más rápida
            // Si el resultado de la división es 0, la instrucción tendrá como objetivo el tipo de registro B en el array
            Instructions.RegType r1 = rtLookup[(i - 0x40) >> 0x03];

            // & 0x07 reemplaza el módulo 8, es más rápido hacer operaciones a nivel de bits
            // El residuo te dice cuál es el segundo registro, este bloque sigue patrones que permiten implementarlo así
            // El opcode table de esta sección está construído como una matriz, mapeamos un espacio lineal a un espacio bidimensional
            // fila = i//N, columna = i (mod N)
            Instructions.RegType r2 = rtLookup[(i - 0x40) & 0x07];

            // En base al tipo de ambos registros se determina el tipo de direccionamiento (registros a memoria, memoria a registros o registros a registros)
            Instructions.AddrMode mode = (r2 == Instructions.RegType.RT_NONE) ? Instructions.AddrMode.AM_R_MR : (r1 == Instructions.RegType.RT_NONE ? Instructions.AddrMode.AM_MR_R : Instructions.AddrMode.AM_R_R);

            // El registro [HL] (110 en binario) es usado como registro nulo, ya que es usado como puntero para acceder a dirección de memoria
            instructions[i] = new Instruction(Instructions.InType.IN_LD, mode, (r1 == Instructions.RegType.RT_NONE ? Instructions.RegType.RT_HL : r1), (r2 == Instructions.RegType.RT_NONE ? Instructions.RegType.RT_HL : r2));
        }

        // --- BLOQUE DINÁMICO 0x80 - 0xBF ---
        // Igual que antes, este array sigue la progresión de tipo de operación según la tabla de opcodes, le toca 8 instrucciones a cada tipo
        Instructions.InType[] arithmeticTypes = {Instructions.InType.IN_ADD, Instructions.InType.IN_ADC, Instructions.InType.IN_SUB, Instructions.InType.IN_SBC, Instructions.InType.IN_AND, Instructions.InType.IN_XOR, Instructions.InType.IN_OR, Instructions.InType.IN_CP};
        for (int i = 0x80; i <= 0xBF; i++) {
            Instructions.InType type = arithmeticTypes[(i - 0x80) >> 0x03];
            Instructions.RegType r2 = rtLookup[(i - 0x80) & 0x07];
            Instructions.AddrMode mode = (r2 == Instructions.RegType.RT_NONE) ? Instructions.AddrMode.AM_R_MR : Instructions.AddrMode.AM_R_R;
            instructions[i] = new Instruction(type, mode, Instructions.RegType.RT_A, (r2 == Instructions.RegType.RT_NONE ? Instructions.RegType.RT_HL : r2));
        }

        // ==========================================
        // BLOQUE 0xC0 - 0xFF (Control, Stack, I/O)
        // ==========================================
        instructions[0xC0] = new Instruction(Instructions.InType.IN_RET,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NZ, 0);
        instructions[0xC1] = new Instruction(Instructions.InType.IN_POP,  Instructions.AddrMode.AM_R,   Instructions.RegType.RT_BC);
        instructions[0xC2] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NZ, 0);
        instructions[0xC3] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_D16);
        instructions[0xC4] = new Instruction(Instructions.InType.IN_CALL, Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NZ, 0);
        instructions[0xC5] = new Instruction(Instructions.InType.IN_PUSH, Instructions.AddrMode.AM_R,   Instructions.RegType.RT_BC);
        instructions[0xC6] = new Instruction(Instructions.InType.IN_ADD,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xC7] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x00);
        instructions[0xC8] = new Instruction(Instructions.InType.IN_RET,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_Z, 0);
        instructions[0xC9] = new Instruction(Instructions.InType.IN_RET,  Instructions.AddrMode.AM_IMP);
        instructions[0xCA] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_Z, 0);

        // ==========================================
        // INSTRUCCIONES EXTENDIDAS (PREFIJO CB)
        // ==========================================
        instructions[0xCB] = new Instruction(Instructions.InType.IN_CB,   Instructions.AddrMode.AM_D8);

        // ==========================================
        // RESTO DE INSTRUCCIONES DE CONTROL
        // ==========================================
        instructions[0xCC] = new Instruction(Instructions.InType.IN_CALL, Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_Z, 0);
        instructions[0xCD] = new Instruction(Instructions.InType.IN_CALL, Instructions.AddrMode.AM_D16);
        instructions[0xCE] = new Instruction(Instructions.InType.IN_ADC,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xCF] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x08);

        instructions[0xD0] = new Instruction(Instructions.InType.IN_RET,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NC, 0);
        instructions[0xD1] = new Instruction(Instructions.InType.IN_POP,  Instructions.AddrMode.AM_R,   Instructions.RegType.RT_DE);
        instructions[0xD2] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NC, 0);
        instructions[0xD4] = new Instruction(Instructions.InType.IN_CALL, Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NC, 0);
        instructions[0xD5] = new Instruction(Instructions.InType.IN_PUSH, Instructions.AddrMode.AM_R,   Instructions.RegType.RT_DE);
        instructions[0xD6] = new Instruction(Instructions.InType.IN_SUB,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xD7] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x10);
        instructions[0xD8] = new Instruction(Instructions.InType.IN_RET,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_C, 0);
        instructions[0xD9] = new Instruction(Instructions.InType.IN_RETI, Instructions.AddrMode.AM_IMP);
        instructions[0xDA] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_C, 0);
        instructions[0xDC] = new Instruction(Instructions.InType.IN_CALL, Instructions.AddrMode.AM_D16, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_C, 0);
        instructions[0xDE] = new Instruction(Instructions.InType.IN_SBC,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xDF] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x18);

        instructions[0xE0] = new Instruction(Instructions.InType.IN_LDH,  Instructions.AddrMode.AM_A8_R, Instructions.RegType.RT_NONE, Instructions.RegType.RT_A);
        instructions[0xE1] = new Instruction(Instructions.InType.IN_POP,  Instructions.AddrMode.AM_R,    Instructions.RegType.RT_HL);
        instructions[0xE2] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_MR_R, Instructions.RegType.RT_C,    Instructions.RegType.RT_A);
        instructions[0xE5] = new Instruction(Instructions.InType.IN_PUSH, Instructions.AddrMode.AM_R,    Instructions.RegType.RT_HL);
        instructions[0xE6] = new Instruction(Instructions.InType.IN_AND,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xE7] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP,  Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x20);
        instructions[0xE8] = new Instruction(Instructions.InType.IN_ADD,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_SP);
        instructions[0xE9] = new Instruction(Instructions.InType.IN_JP,   Instructions.AddrMode.AM_R,    Instructions.RegType.RT_HL);
        instructions[0xEA] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_A16_R, Instructions.RegType.RT_NONE, Instructions.RegType.RT_A);
        instructions[0xEE] = new Instruction(Instructions.InType.IN_XOR,  Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xEF] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP,  Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x28);

        instructions[0xF0] = new Instruction(Instructions.InType.IN_LDH,  Instructions.AddrMode.AM_R_A8, Instructions.RegType.RT_A);
        instructions[0xF1] = new Instruction(Instructions.InType.IN_POP,  Instructions.AddrMode.AM_R,    Instructions.RegType.RT_AF);
        instructions[0xF2] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_MR, Instructions.RegType.RT_A,    Instructions.RegType.RT_C);
        instructions[0xF3] = new Instruction(Instructions.InType.IN_DI,   Instructions.AddrMode.AM_IMP);
        instructions[0xF5] = new Instruction(Instructions.InType.IN_PUSH, Instructions.AddrMode.AM_R,    Instructions.RegType.RT_AF);
        instructions[0xF6] = new Instruction(Instructions.InType.IN_OR,   Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xF7] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP,  Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x30);
        instructions[0xF8] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_HL_SPR, Instructions.RegType.RT_HL, Instructions.RegType.RT_SP);
        instructions[0xF9] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_R,  Instructions.RegType.RT_SP,   Instructions.RegType.RT_HL);
        instructions[0xFA] = new Instruction(Instructions.InType.IN_LD,   Instructions.AddrMode.AM_R_A16, Instructions.RegType.RT_A);
        instructions[0xFB] = new Instruction(Instructions.InType.IN_EI,   Instructions.AddrMode.AM_IMP);
        instructions[0xFE] = new Instruction(Instructions.InType.IN_CP,   Instructions.AddrMode.AM_R_D8, Instructions.RegType.RT_A);
        instructions[0xFF] = new Instruction(Instructions.InType.IN_RST,  Instructions.AddrMode.AM_IMP,  Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0x38);
    }
}