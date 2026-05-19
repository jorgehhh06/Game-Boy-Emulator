/*
* Instrucciones extendidas del Game Boy (prefijo CB), son otras 256 posibles entradas
*/
public class CB_InstructionTable {

    // Arreglo de instrucciones, 8 bits de instrucciones base
    private static Instruction[] cb_instructions = new Instruction[0x100];

    // Dirigir al índice de la tabla de referencia
    public static Instruction get_cb_instruction_by_opcode(int opcode) {
        return cb_instructions[opcode & 0xFF];
    }

    public static void init() {
        // Inicialización por defecto, hace que cada posible entrada tenga un comportamiento definido
        for (int i = 0; i < 0x100; i++) {
            cb_instructions[i] = new Instruction(Instructions_Enum.InType.IN_ERR, Instructions_Enum.AddrMode.AM_IMP);
        }

        // RT_NONE representa a (HL)
        Instructions_Enum.RegType[] rtLookup = {
                Instructions_Enum.RegType.RT_B, Instructions_Enum.RegType.RT_C,
                Instructions_Enum.RegType.RT_D, Instructions_Enum.RegType.RT_E,
                Instructions_Enum.RegType.RT_H, Instructions_Enum.RegType.RT_L,
                Instructions_Enum.RegType.RT_NONE, Instructions_Enum.RegType.RT_A
        };

        // Tipos de rotación y desplazamiento (0x00 - 0x3F)
        Instructions_Enum.InType[] shiftTypes = {
                Instructions_Enum.InType.IN_RLC, // Rotate Left Circular
                Instructions_Enum.InType.IN_RRC, // Rotate Right Circular
                Instructions_Enum.InType.IN_RL,  // Rotate Left
                Instructions_Enum.InType.IN_RR, // Rotate Right
                Instructions_Enum.InType.IN_SLA, // Shift Left Arithmetic
                Instructions_Enum.InType.IN_SRA, // Shift Right Arithmetic
                Instructions_Enum.InType.IN_SWAP, // Swap nibbles
                Instructions_Enum.InType.IN_SRL // Shift Right Logical
        };

        for (int i = 0; i < 0x100; i++) {
            // Módulo 8 permite ordenar los registros checando los 3 bits menos significativos
            Instructions_Enum.RegType reg = rtLookup[i & 0x07];
            // Ver si es memoria o registros sobre lo que se va a trabajar
            Instructions_Enum.AddrMode mode = (reg == Instructions_Enum.RegType.RT_NONE) ?
                    Instructions_Enum.AddrMode.AM_MR : Instructions_Enum.AddrMode.AM_R;
            // Cambiar RT_NONE por HL
            Instructions_Enum.RegType actualReg = (reg == Instructions_Enum.RegType.RT_NONE) ?
                    Instructions_Enum.RegType.RT_HL : reg;

            if (i >= 0x00 && i <= 0x3F) {
                // GRUPO 1: Rotaciones y Desplazamientos
                // Verificar bits 3-5, saltos de 8 en 8, 8 saltos en total que corresponde a las rotaciones
                Instructions_Enum.InType type = shiftTypes[(i >> 3) & 0x07];
                cb_instructions[i] = new Instruction(type, mode, actualReg);

            } else if (i >= 0x40 && i <= 0x7F) {
                // GRUPO 2: BIT (Probado de bits)
                // Saltos de 8 en 8 para las operaciones RES
                int bit = (i >> 3) & 0x07;
                cb_instructions[i] = new Instruction(Instructions_Enum.InType.IN_BIT, mode, actualReg, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.CondType.CT_NONE, bit);

            } else if (i >= 0x80 && i <= 0xBF) {
                // GRUPO 3: RES (Reset de bits)
                // Saltos de 8 en 8 para las operaciones RES
                int bit = (i >> 3) & 0x07;
                cb_instructions[i] = new Instruction(Instructions_Enum.InType.IN_RES, mode, actualReg, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.CondType.CT_NONE, bit);

            } else if (i >= 0xC0 && i <= 0xFF) {
                // GRUPO 4: SET (Set de bits)
                // Saltos de 8 en 8 para las operaciones SET
                int bit = (i >> 3) & 0x07;
                cb_instructions[i] = new Instruction(Instructions_Enum.InType.IN_SET, mode, actualReg, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.CondType.CT_NONE, bit);
            }
        }
    }
}