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
            cb_instructions[i] = new Instruction(Instructions.InType.IN_ERR, Instructions.AddrMode.AM_IMP);
        }

    }
}