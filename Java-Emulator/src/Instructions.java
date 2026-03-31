/*
* Aquí se encuentran los enums, se usan para hacer la decodificación de instrucciones más legible.
* Permite cambiar el hexadecimal por algo más fácil de leer
*/
public class Instructions {
    public enum AddrMode {
        /* Implícito: La instrucción no requiere operandos extra (ej: NOP, SCF) */
        AM_IMP,

        /* Registro y Dato de 16 bits: Carga un valor inmediato de 16 bits en un registro (ej: LD BC, D16) */
        AM_R_D16,

        /* Registro a Registro: Copia el valor de un registro a otro (ej: LD B, C) */
        AM_R_R,

        /* Memoria (apuntada por Registro) desde Registro: Escribe un registro en una dirección de memoria (ej: LD (BC), A) */
        AM_MR_R,

        /* Registro: La instrucción opera sobre un solo registro (ej: INC B) */
        AM_R,

        /* Registro y Dato de 8 bits: Carga un valor inmediato de 8 bits en un registro (ej: LD B, D8) */
        AM_R_D8,

        /* Registro desde Memoria (apuntada por Registro): Lee de memoria a un registro (ej: LD A, (BC)) */
        AM_R_MR,

        /* Registro desde Memoria (HL) e Incremento: Lee de (HL) a un registro e incrementa HL (ej: LD A, (HL+)) */
        AM_R_HLI,

        /* Registro desde Memoria (HL) y Decremento: Lee de (HL) a un registro y decrementa HL (ej: LD A, (HL-)) */
        AM_R_HLD,

        /* Memoria (HL) desde Registro e Incremento: Escribe un registro en (HL) e incrementa HL (ej: LD (HL+), A) */
        AM_HLI_R,

        /* Memoria (HL) desde Registro y Decremento: Escribe un registro en (HL) y decrementa HL (ej: LD (HL-), A) */
        AM_HLD_R,

        /* Registro desde Memoria de Hardware (A8): Lee de $FF00 + un byte al registro A (ej: LDH A, (A8)) */
        AM_R_A8,

        /* Memoria de Hardware (A8) desde Registro: Escribe el registro A en $FF00 + un byte (ej: LDH (A8), A) */
        AM_A8_R,

        /* HL desde Stack Pointer + Relativo: Calcula una dirección relativa al SP y la guarda en HL (ej: LD HL, SP+r8) */
        AM_HL_SPR,

        /* Dato de 16 bits: La instrucción usa un valor inmediato de 16 bits (ej: JP D16) */
        AM_D16,

        /* Dato de 8 bits: La instrucción usa un valor inmediato de 8 bits (ej: JR r8) */
        AM_D8,

        /* Memoria (D16) desde Registro de 16 bits: Escribe un registro de 16 bits en una dirección fija (ej: LD (D16), SP) */
        AM_D16_R,

        /* Memoria (apuntada por Registro) y Dato de 8 bits: Escribe un valor de 8 bits en (HL) (ej: LD (HL), D8) */
        AM_MR_D8,

        /* Memoria (apuntada por Registro): Opera sobre la dirección en el registro (ej: INC (HL)) */
        AM_MR,

        /* Memoria (Dirección de 16 bits) desde Registro: Escribe el registro A en una dirección fija (ej: LD ($A16), A) */
        AM_A16_R,

        /* Registro desde Memoria (Dirección de 16 bits): Lee de una dirección fija al registro A (ej: LD A, ($A16)) */
        AM_R_A16
    }

    public enum RegType {
        /* Sin registro */
        RT_NONE,

        /* Registro A */
        RT_A,

        /* Registro F */
        RT_F,

        /* Registro B */
        RT_B,

        /* Registro C */
        RT_C,

        /* Registro D */
        RT_D,

        /* Registro E */
        RT_E,

        /* Registro H */
        RT_H,

        /* Registro L */
        RT_L,

        /* Registro AF */
        RT_AF,

        /* Registro BC */
        RT_BC,

        /* Registro DE */
        RT_DE,

        /* Registro HL */
        RT_HL,

        /* Stack Pointer */
        RT_SP,

        /* Program Counter */
        RT_PC
    }

    public enum InType {
        IN_NONE,

        /*
        * Todas las operaciones aritméticas se hacen con el registro A (acumulador)
        */

        // --- INSTRUCCIONES BASE (Opcodes 0x00 - 0xFF) ---
        IN_NOP, // No Operation: No hace nada por 4 ciclos (Oscilador principal: 4,194,304 Hz)
        IN_LD, // Load
        IN_INC, // Increment
        IN_DEC, // Decrease
        IN_RLCA, // Rotate Left Circular Accumulator: Shift de 1 bit a la izquierda en el registro A de manera circular
        IN_ADD, // Addition
        IN_RRCA, // Rotate Right Circular Accumulator: Shift de 1 bit a la derecha en el registro A de manera circular
        IN_STOP, // Stop: detiene la CPU y el oscilador principal
        IN_RLA, // Rotate Left Accumulator: Shift de 1 bit a la izquierda en el registro A
        IN_JR, // Jump relative: salto condicional o incondicional sumando al Program Counter
        IN_RRA, // Rotate Left Accumulator: Shift de 1 bit a la derecha en el registro A
        IN_DAA, // Decimal Adjust Accumulator: Ajusta el registro A para aritmética BCD tras una suma o resta.
        IN_CPL, // Complement: Aplica un operador NOT
        IN_SCF, // Set Carry Flag: Pone el valor de la bandera Carry en 1
        IN_CCF, // Complement Carry Flag: Invierte el valor de la bandera Carry
        IN_HALT, // Halt: Detiene la CPU hasta que ocurra una interrupción. TODO: Implementar el HALT Bug
        IN_ADC, // Add with Carry: Suma un valor + el estado del Carry al registro A
        IN_SUB, // Subtract
        IN_SBC, // Subtract with Carry: Resta un valor + el estado del Carry al registro A
        IN_AND, // And
        IN_XOR, // Xor
        IN_OR, // Or
        IN_CP, // Compare: Resta y actualiza flags sin guardar el resultado
        IN_POP, // Pop: Saca un valor de 16 bits de la Pila (Stack) y lo pone en un par de registros
        IN_JP, // Jump: Salto incondicional o condicional a una dirección absoluta
        IN_PUSH, // Push: Mete un valor de 16 bits de un par de registros a la Pila (Stack)
        IN_RET, // Return: Vuelve de una subrutina (saca el PC de la pila)
        IN_CALL, // Call: Llama a una subrutina (guarda el PC actual en la pila y salta)
        IN_RETI, // Return from Interrupt: Vuelve de una interrupción y reactiva las interrupciones globalmente
        IN_LDH, // Load High: Carga/guarda en el área de memoria $FF00+n (I/O de hardware)
        IN_JPHL, // Jump to HL: Salto indirecto cargando el valor de HL en el Program Counter (PC)
        IN_DI, // Disable Interrupts: Desactiva el manejo de interrupciones globalmente
        IN_EI, // Enable Interrupts: Activa el manejo de interrupciones globalmente
        IN_RST, // Restart: Salto rápido a una de las 8 direcciones fijas en la página cero (vectores RST)
        IN_ERR, // Error: Instrucción no válida o no implementada (Trap)

        /* Prefijo CB: Indica al CPU que debe leer un segundo byte
         * para acceder a las instrucciones extendidas de bits.
         * Es algo único del procesador de la Game Boy, esto no viene en el Zilog Z80 ni en el Intel 8080
         */
        IN_CB,

        // --- INSTRUCCIONES EXTENDIDAS (Prefijo 0xCB + Opcode) ---
        // Estas instrucciones se especializan en manipulación de bits y rotaciones complejas
        IN_RLC,  // Rotate Left Circular
        IN_RRC,  // Rotate Right Circular
        IN_RL,   // Rotate Left (a través del Carry)
        IN_RR,   // Rotate Right (a través del Carry)
        IN_SLA,  // Shift Left Arithmetic
        IN_SRA,  // Shift Right Arithmetic
        IN_SWAP, // Intercambia nibbles (bits 0-3 con 4-7)
        IN_SRL,  // Shift Right Logical
        IN_BIT,  // Comprobar bit específico
        IN_RES,  // Resetear (poner a 0) bit específico
        IN_SET   // Setear (poner a 1) bit específico
    }

    public enum CondType { // Usado para verificar el estado de las flags
        CT_NONE, // Sin condición
        CT_NZ, // Not Zero
        CT_Z, // Zero
        CT_NC, // Not Carry
        CT_C // Carry
    }

    /*
    * Vectores RST (Restart): 8 direcciones fijas de la página 0 (0x0000 - 0x00FF), permite realizar saltos optimizados:
    RST $00	$0000	Suele usarse para reiniciar la consola.
    RST $08	$0008	Funciones matemáticas o lectura de memoria.
    RST $10	$0010	Utilizado frecuentemente por compiladores de C.
    RST $18	$0018	Rutinas genéricas del sistema.
    RST $20	$0020	Rutinas de usuario.
    RST $28	$0028	Rutinas de usuario.
    RST $30	$0030	Rutinas de usuario.
    RST $38	$0038	Muy común para manejo de errores o debuggers.
    */

    /*
    * HALT Bug:En condiciones normales, la instrucción HALT detiene la CPU hasta que llegue una interrupción.
    * El bug ocurre específicamente cuando se cumplen estas tres condiciones:
    Las interrupciones están desactivadas (DI).
    Hay una interrupción pendiente (por ejemplo, el Timer llegó a cero justo antes).
    Ejecutas HALT.
    * El resultado del bug: La CPU no se detiene, pero se "atonta" en la siguiente instrucción.
    * El Program Counter (PC) no se incrementa tras leer el siguiente opcode.
    */
}