/*
*  Esta clase funciona como Capa de Abstracción de Hardware que apunta a proporcionar a las demás clases las
*  herramientas matemáticas de las que carece Java mientras se aplica la modularización al código
*/

public class Common {

    // Usamos int/long para representar u8, u16, u32, u64
    // Para u8 y u16 usamos int (32 bits) porque Java no tiene unsigned.
    // Para u64 usamos long (64 bits).

    /* Verifica si un bit está encendido (ejemplo, flags del registro F) */
    public static int BIT(long a, int n) {
        return ((a & (1L << n)) != 0) ? 1 : 0;
    }

    /* Modifica un solo bit sin alterar el resto */
    public static int BIT_SET(int a, int n, boolean on) {
        if (on) {
            return (a | (1 << n));
        } else {
            return (a & ~(1 << n));
        }
    }

    /* Comprueba si un número se ubica entre dos valores, será de utilidad para el Bus */
    public static boolean BETWEEN(int a, int b, int c) {
        return (a >= b) && (a <= c);
    }

    /*
     * Encontré que esta es la mejor manera de controlar el tiempo de ejecución del programa
     */

    public static void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) { // Medida de seguridad
            Thread.currentThread().interrupt();
        }
    }
}