/*
* Es la encargada de mover los datos de los sprites (objetos) a la Object Attribute Memory (OAM)
*/
public class DMA {
    private boolean active;
    private int current_byte;
    private int value; // Valor escrito en 0xFF46
    private int start_delay;
    private int tick_count;

    // Bus write llama a io_write() e io_write() llama a dma_init()
    public void dma_init(int start){
        active = true;
        current_byte = 0;
        start_delay = 8; // El DMA tarda 8 T-Cycles (2 M-Cycles) antes de mover los datos
        value = start;
    }

    public void dma_tick(){
        // Verificar condiciones antes de hacer la transferencia
        if(!active) return;

        if(start_delay > 0) {
            start_delay--;
            return;
        }

        // Simulación de 1 M-Cycle, se hace 1 vez cada 4 T-Cycles
        if (tick_count == 0) {
            // << 8 es equivalente a multiplicar por 256
            // El programador escribe un byte (C0 por ejemplo)
            // y se convierte a 16 bits mediante el desplazamiento
            // C0 << 8 = C000
            int address = (value << 8) + current_byte;
            // Se mueve 1 byte por M-Cycle
            Bus.ppu.oam_write(0xFE00 + current_byte, Bus.bus_read(address));
            current_byte++;
            active = current_byte < 0xA0;
        }

        tick_count = (tick_count + 1) & 3; // Mod 4
    }

    public boolean dma_transferring(){
        return active;
    }

}