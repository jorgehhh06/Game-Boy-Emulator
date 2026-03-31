/*
* Aquí se presenta la anatomía de una instrucción
*/
public class Instruction {
    public Instructions.InType type; // Tipo
    public Instructions.AddrMode mode; // Modo de direccionamiento
    public Instructions.RegType reg_1; // Registro 1
    public Instructions.RegType reg_2; // Registro 2
    public Instructions.CondType cond; // Condición
    public int param; // Datos inmediatos adicionales

    /*
     * Constructor maestro para inicialización completa
     */
    public Instruction(Instructions.InType type, Instructions.AddrMode mode,
                       Instructions.RegType reg_1, Instructions.RegType reg_2,
                       Instructions.CondType cond, int param) {
        this.type = type;
        this.mode = mode;
        this.reg_1 = reg_1;
        this.reg_2 = reg_2;
        this.cond = cond;
        this.param = param;
    }

    /* Constructor para instrucciones simples sin operandos ni condiciones */
    public Instruction(Instructions.InType type, Instructions.AddrMode mode) {
        this(type, mode, Instructions.RegType.RT_NONE, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0);
    }

    /* Constructor para instrucciones con un solo registro */
    public Instruction(Instructions.InType type, Instructions.AddrMode mode, Instructions.RegType reg_1) {
        this(type, mode, reg_1, Instructions.RegType.RT_NONE, Instructions.CondType.CT_NONE, 0);
    }

    /* Constructor para instrucciones de transferencia entre registros */
    public Instruction(Instructions.InType type, Instructions.AddrMode mode, Instructions.RegType reg_1, Instructions.RegType reg_2) {
        this(type, mode, reg_1, reg_2, Instructions.CondType.CT_NONE, 0);
    }
}