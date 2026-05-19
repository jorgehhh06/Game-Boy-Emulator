/*
* Aquí se presenta la anatomía de una instrucción
*/
public class Instruction {
    public Instructions_Enum.InType type; // Tipo
    public Instructions_Enum.AddrMode mode; // Modo de direccionamiento
    public Instructions_Enum.RegType reg_1; // Registro 1
    public Instructions_Enum.RegType reg_2; // Registro 2
    public Instructions_Enum.CondType cond; // Condición
    public int param; // Datos inmediatos adicionales

    /*
     * Constructor maestro para inicialización completa
     */
    public Instruction(Instructions_Enum.InType type, Instructions_Enum.AddrMode mode,
                       Instructions_Enum.RegType reg_1, Instructions_Enum.RegType reg_2,
                       Instructions_Enum.CondType cond, int param) {
        this.type = type;
        this.mode = mode;
        this.reg_1 = reg_1;
        this.reg_2 = reg_2;
        this.cond = cond;
        this.param = param;
    }

    /* Constructor para instrucciones simples sin operandos ni condiciones */
    public Instruction(Instructions_Enum.InType type, Instructions_Enum.AddrMode mode) {
        this(type, mode, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.CondType.CT_NONE, 0);
    }

    /* Constructor para instrucciones con un solo registro */
    public Instruction(Instructions_Enum.InType type, Instructions_Enum.AddrMode mode, Instructions_Enum.RegType reg_1) {
        this(type, mode, reg_1, Instructions_Enum.RegType.RT_NONE, Instructions_Enum.CondType.CT_NONE, 0);
    }

    /* Constructor para instrucciones de transferencia entre registros */
    public Instruction(Instructions_Enum.InType type, Instructions_Enum.AddrMode mode, Instructions_Enum.RegType reg_1, Instructions_Enum.RegType reg_2) {
        this(type, mode, reg_1, reg_2, Instructions_Enum.CondType.CT_NONE, 0);
    }
}