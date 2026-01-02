package net.lomibao.nes.components;

/**
 * Basic NES Controller stub
 * Handles controller port reads/writes at addresses 0x4016-0x4017
 * 
 * This is a minimal implementation that returns 0 for all button states,
 * preventing "no device found" errors on the CPU bus.
 */
public class Controller extends CPUBusComponent {
    private static final int CONTROLLER_1_ADDRESS = 0x4016;
    private static final int CONTROLLER_2_ADDRESS = 0x4017;
    
    private byte strobe = 0;
    
    @Override
    public void cpuBusWrite(int address, byte value) {
        if (address == CONTROLLER_1_ADDRESS) {
            // Writing to 0x4016 sets the strobe bit
            // When strobe is 1, controller is continuously reloading button states
            // When strobe goes 0->1->0, it latches the current button states
            strobe = (byte) (value & 0x01);
        }
        // Controller 2 (0x4017) is typically read-only for controller data
        // (it's also used for APU frame counter, but that's outside scope of this stub)
    }
    
    @Override
    public int cpuBusRead(int address, boolean readOnly) {
        if (address == CONTROLLER_1_ADDRESS) {
            // Return 0 for all buttons (not pressed)
            // Bit 0 = A, Bit 1 = B, Bit 2 = Select, Bit 3 = Start
            // Bit 4 = Up, Bit 5 = Down, Bit 6 = Left, Bit 7 = Right
            // We return 0x40 to indicate open bus (no controller connected)
            return 0x40;
        } else if (address == CONTROLLER_2_ADDRESS) {
            // Return 0 for controller 2 as well
            return 0x40;
        }
        return 0;
    }
    
    @Override
    public int getCPUBusStartAddress() {
        return CONTROLLER_1_ADDRESS;
    }
    
    @Override
    public int getCPUBusEndAddress() {
        return CONTROLLER_2_ADDRESS + 1; // Exclusive end
    }
}
