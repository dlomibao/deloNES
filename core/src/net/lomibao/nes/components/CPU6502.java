package net.lomibao.nes.components;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

@Builder
@Data
@AllArgsConstructor
@Log4j2
public class CPU6502 {
    @ToString.Exclude
    private CPUBus cpuBus;

    public void connectCpuBus(CPUBus cpuBus) {
        this.cpuBus = cpuBus;
    }

    public CPUBus getBus() {
        return cpuBus;
    }

    public int cpuBusRead(int address, boolean readOnly) {
        return cpuBus.read(address, readOnly);
    }

    public int cpuBusRead(int address) {
        return cpuBusRead(address, false);
    }

    public void cpuBusWrite(int address, byte value) {
        cpuBus.write(address, value);
    }

    private int readShortFromPCAddress() {

        int low = cpuBusRead(pc);
        pc++;
        int high = cpuBusRead(pc);
        pc++;
        return ((high << 8) | low) & 0xFFFF;
    }

    private int readShortFromAddress(int address) {
        address &= 0xFFFF;
        return cpuBusRead(address) | (cpuBusRead(address + 1) << 8);
    }

    public int getA() {
        return a & 0xFF;
    }

    public void setA(int a) {
        this.a = a & 0xFF;
    }

    public int getX() {
        return x & 0xFF;
    }

    public void setX(int x) {
        this.x = x & 0xFF;
    }

    public int getY() {
        return y & 0xFF;
    }

    public void setY(int y) {
        this.y = y & 0xFF;
    }

    public int getStkp() {
        return stkp & 0xFF;
    }

    public void setStkp(int stkp) {
        this.stkp = stkp & 0xFF;
    }

    public int getPc() {
        return pc & 0xFFFF;
    }

    public void setPc(int pc) {
        this.pc = pc & 0xFFFF;
    }

    public byte getStatus() {
        return status;
    }

    public void setStatus(byte status) {
        this.status = status;
    }

    public int getTemp() {
        return temp & 0xFFFF;
    }

    public void setTemp(int temp) {
        this.temp = temp & 0xFFFF;
    }

    public int getAddressAbs() {
        return addressAbs & 0xFFFF;
    }

    public void setAddressAbs(int addressAbs) {
        this.addressAbs = addressAbs & 0xFFFF;
    }

    public int getAddressRel() {
        return addressRel & 0xFFFF;
    }

    public void setAddressRel(int addressRel) {
        this.addressRel = addressRel & 0xFFFF;
    }

    // private static final org.apache.logging.log4j.Logger log =
    // org.apache.logging.log4j.LogManager.getLogger(CPU6502.class);
    // registers and flags
    @Builder.Default
    private int a = 0x00;// accumulator (8bits)
    @Builder.Default
    private int x = 0x00;// index x (8bits)
    @Builder.Default
    private int y = 0x00;// index y;\ (8bits)
    @Builder.Default
    private int stkp = 0x00;// stack pointer (8bits)
    @Builder.Default
    private int pc = 0x0000;// program counter (16bit)
    @Builder.Default
    private byte status = 0x00;// status flags (8bits)

    /*
     * 7 bit 0
     * ---- ----
     * NV1B DIZC
     * |||| ||||
     * |||| |||+- Carry
     * |||| ||+-- Zero
     * |||| |+--- Interrupt Disable
     * |||| +---- Decimal
     * |||+------ (No CPU effect; see: the B flag)
     * ||+------- (No CPU effect; always pushed as 1)
     * |+-------- Overflow
     * +--------- Negative
     */

    enum Flag {
        Carry(1 << 0, "Carry Bit"),
        Zero(1 << 1, "Zero"),
        InterruptDisable(1 << 2, "disable interrupts"),
        Decimal(1 << 3, "Decimal Mode (unused)"),
        Break(1 << 4, "Break (no cpu effect"),
        U(1 << 5, "unused always a 1 value, called U to align with one lone coder "),
        VOverflow(1 << 6, "overflow flag (put v at beginning so it aligns with one lone coder impl"),
        Negative(1 << 7, "Negative");

        public final int mask;
        String description;

        private Flag(int mask, String description) {
            this.mask = mask;
            this.description = description;
        }
    }

    boolean getFlag(Flag flag) {
        return (flag.mask & status) > 0;
    }

    void setFlag(Flag flag, boolean value) {
        if (value) {
            status |= flag.mask;
        } else {
            status &= ~flag.mask;
        }
    }

    /* helper method for casting */
    // public static short sVal(int val){
    // return (short)val;
    // }
    // public static byte bVal(int val){
    // return (byte)val;
    // }
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor

    public static class Instruction {
        private int id;
        private String hexOpcode;
        private String opcodeName;
        private String addressingModeDescription;
        private String description;
        private int byteCount;
        private int clocks;
        private String addressingMode;

        private CPU6502 cpu;

        // Dispatch is a hand-rolled string switch (was Method.invoke before
        // 2026-05-12). Reflection in the hot path is dead-on-arrival for the
        // web/TeaVM target, and switch-on-string lowers to a hash + branch
        // table on the JVM/TeaVM so it's a perf win on desktop too.
        // The string keys come straight from opcodes.csv columns.
        private int runAddressMode() {
            switch (addressingMode) {
                case "IMP": return cpu.IMP();
                case "IMM": return cpu.IMM();
                case "ZP0": return cpu.ZP0();
                case "ZPX": return cpu.ZPX();
                case "ZPY": return cpu.ZPY();
                case "REL": return cpu.REL();
                case "ABS": return cpu.ABS();
                case "ABX": return cpu.ABX();
                case "ABY": return cpu.ABY();
                case "IND": return cpu.IND();
                case "IZX": return cpu.IZX();
                case "IZY": return cpu.IZY();
                default:
                    log.error("no addressing handler configured for {}", addressingMode);
                    return 0;
            }
        }

        private void initHandlers() {
            // No-op: dispatch is now a direct string switch in
            // runInstruction()/runAddressMode(). Retained as a hook for any
            // future startup-time validation (e.g. asserting every csv name
            // resolves to a real handler).
        }

        private int runInstruction() {
            switch (opcodeName) {
                case "ADC": return cpu.ADC();
                case "AND": return cpu.AND();
                case "ASL": return cpu.ASL();
                case "BCC": return cpu.BCC();
                case "BCS": return cpu.BCS();
                case "BEQ": return cpu.BEQ();
                case "BIT": return cpu.BIT();
                case "BMI": return cpu.BMI();
                case "BNE": return cpu.BNE();
                case "BPL": return cpu.BPL();
                case "BRK": return cpu.BRK();
                case "BVC": return cpu.BVC();
                case "BVS": return cpu.BVS();
                case "CLC": return cpu.CLC();
                case "CLD": return cpu.CLD();
                case "CLI": return cpu.CLI();
                case "CLV": return cpu.CLV();
                case "CMP": return cpu.CMP();
                case "CPX": return cpu.CPX();
                case "CPY": return cpu.CPY();
                case "DCP": return cpu.DCP();
                case "DEC": return cpu.DEC();
                case "DEX": return cpu.DEX();
                case "DEY": return cpu.DEY();
                case "EOR": return cpu.EOR();
                case "INC": return cpu.INC();
                case "INX": return cpu.INX();
                case "INY": return cpu.INY();
                case "ISB": return cpu.ISB();
                case "JMP": return cpu.JMP();
                case "JSR": return cpu.JSR();
                case "LAX": return cpu.LAX();
                case "LDA": return cpu.LDA();
                case "LDX": return cpu.LDX();
                case "LDY": return cpu.LDY();
                case "LSR": return cpu.LSR();
                case "NOP": return cpu.NOP();
                case "ORA": return cpu.ORA();
                case "PHA": return cpu.PHA();
                case "PHP": return cpu.PHP();
                case "PLA": return cpu.PLA();
                case "PLP": return cpu.PLP();
                case "RLA": return cpu.RLA();
                case "ROL": return cpu.ROL();
                case "ROR": return cpu.ROR();
                case "RRA": return cpu.RRA();
                case "RTI": return cpu.RTI();
                case "RTS": return cpu.RTS();
                case "SAX": return cpu.SAX();
                case "SBC": return cpu.SBC();
                case "SEC": return cpu.SEC();
                case "SED": return cpu.SED();
                case "SEI": return cpu.SEI();
                case "SLO": return cpu.SLO();
                case "SRE": return cpu.SRE();
                case "STA": return cpu.STA();
                case "STX": return cpu.STX();
                case "STY": return cpu.STY();
                case "TAX": return cpu.TAX();
                case "TAY": return cpu.TAY();
                case "TSX": return cpu.TSX();
                case "TXA": return cpu.TXA();
                case "TXS": return cpu.TXS();
                case "TYA": return cpu.TYA();
                case "XXX": return cpu.XXX();
                default:
                    log.error("no instruction handler configured for {}", opcodeName);
                    return 0;
            }
        }

        @Override
        public String toString() {
            return "Instruction{" +
                    "id=" + id +
                    ", hexOpcode='" + hexOpcode + '\'' +
                    ", opcodeName='" + opcodeName + '\'' +
                    ", addressingModeDescription='" + addressingModeDescription + '\'' +
                    ", description='" + description + '\'' +
                    ", byteCount=" + byteCount +
                    ", clocks=" + clocks +
                    ", addressingMode='" + addressingMode + '\'' +
                    ", cpu=" + (cpu != null) +
                    '}';
        }
    }

    // emulation support
    @Builder.Default
    private int fetched = 0x00;// working input value of ALU (8bit)
    @Builder.Default
    private int temp = 0x0000;// temp var
    @Builder.Default
    private int addressAbs = 0x0000;// all used addresses go here
    @Builder.Default
    private int addressRel = 0x0000;// the absolute address that was resolved from relative addressing
    @Builder.Default
    private int opcode = 0x00;// instruction byte
    @Builder.Default
    private int cycles = 0;// count of how many cycles left the instruction has remaining
    @Builder.Default
    private long clockCount = 0; // global clock count since start

    private Instruction[] instructions;

    public CPU6502() {
        this(CPU6502.class.getResourceAsStream("/opcodes/opcodes.csv"));
    }

    /**
     * Build a CPU with the opcode table loaded from the given stream. The
     * no-arg constructor uses {@code /opcodes/opcodes.csv} from the
     * classpath; web/TeaVM hosts that can't reach raw classpath resources
     * (preload.txt cache) should use this constructor with bytes fetched
     * via {@code Gdx.files.internal(...)} (or any other backend-specific
     * loader).
     */
    public CPU6502(InputStream opcodeCsv) {
        instructions = new Instruction[256];
        if (opcodeCsv == null) {
            throw new RuntimeException(
                    "Opcode CSV stream is null. The no-arg constructor reads "
                    + "/opcodes/opcodes.csv from the classpath — register the "
                    + "resource for embedding, or call CPU6502(InputStream) "
                    + "with a stream sourced another way.");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(opcodeCsv))) {
            reader.readLine(); // Skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String currentLine = line;
                log.trace("{}", () -> currentLine);
                String[] s = line.split(",");
                Instruction instruction = Instruction.builder()
                        .cpu(this)
                        .id(Integer.valueOf(s[0]))
                        .hexOpcode(s[1])
                        .opcodeName("???".equals(s[2]) ? "XXX" : s[2])
                        .addressingModeDescription(s[3])
                        .description(s[4])
                        .byteCount(Integer.valueOf(s[5]))
                        .clocks(Integer.valueOf(s[6]))
                        .addressingMode(s[7])
                        .build();
                instructions[instruction.id] = instruction;
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading opcodes.csv", e);
        }
        for (Instruction x : instructions) {
            x.initHandlers();
            log.trace(x);
        }
    }

    public Instruction getInstruction(int opcode) {
        return instructions[opcode];
    }

    /**
     * reset interrupt - forces cpu into known state, hard wired in the CPU
     */
    public void reset() {
        // get address to set pc to
        pc = getProgramCounterAtAddress((short) 0xFFFC);
        // reset registers
        a = 0;
        x = 0;
        y = 0;
        stkp = (byte) 0xFD;
        status = 0x00;// clear flags
        setFlag(Flag.U, true);// u is always true
        // Real 6502 sets the InterruptDisable flag on reset (nestest expects P=0x24)
        setFlag(Flag.InterruptDisable, true);
        // clear helpers
        addressRel = 0x0;
        addressAbs = 0x0;
        fetched = 0x0;
        // reset cycle counter — first instruction begins at CYC:7 in nestest.log
        clockCount = 0;
        // reset takes 7 cycles (per nestest convention)
        cycles = 7;

    }

    /* takes an address, reads in the low byte and high byte and sets the pc */
    private int getProgramCounterAtAddress(int address) {
        addressAbs = address;
        int low = cpuBusRead(addressAbs);
        int high = cpuBusRead(addressAbs + 1);
        // set program counter
        return (high << 8) | low;
    }

    private void writeShortToStack(int value) {
        value &= 0xFFFF;// mask to 16b
        cpuBusWrite((0x0100 + (stkp & 0xFF)), (byte) ((value >> 8) & 0x00FF));// stack starts at 0100
        stkp--;
        cpuBusWrite((0x0100 + (stkp & 0xFF)), (byte) (value & 0x00FF));
        stkp--;
    }

    private void writeByteToStack(byte value) {
        cpuBusWrite(0x0100 + (stkp & 0xFF), value);
        stkp--;
    }

    private int popByteOffStack() {
        stkp++;
        return (cpuBusRead(0x100 + (stkp & 0xFF))) & 0x00FF;
    }

    private int popShortOffStack() {
        int l = popByteOffStack();
        int h = popByteOffStack();
        return (h << 8) | l;
    }

    /**
     * interrupt request
     *
     * @return true if the IRQ was taken; false when masked by the I flag.
     *         Callers modelling a level-triggered IRQ line (e.g. the MMC3
     *         scanline counter) must keep the line asserted and retry on a
     *         false return — hardware holds the line low until serviced.
     */
    public boolean irq() {
        if (!getFlag(Flag.InterruptDisable)) {
            writeShortToStack(pc);

            // IRQ pushes status with bit 4 clear and bit 5 set
            byte statusToPush = (byte) ((status & ~Flag.Break.mask) | Flag.U.mask);
            writeByteToStack(statusToPush);

            setFlag(Flag.InterruptDisable, true);
            addressAbs = 0xFFFE;
            pc = getProgramCounterAtAddress(addressAbs);

            cycles = 7;// IRQ takes 7 cycles
            return true;
        }
        return false;
    }

    /**
     * non-maskable inerrupt request ( can't be disabled)
     */
    public void nmi() {
        writeShortToStack(pc);

        // NMI pushes status with bit 4 clear and bit 5 set
        byte statusToPush = (byte) ((status & ~Flag.Break.mask) | Flag.U.mask);
        writeByteToStack(statusToPush);

        setFlag(Flag.InterruptDisable, true);
        addressAbs = 0xFFFA;
        pc = getProgramCounterAtAddress(addressAbs);

        cycles = 8;
    }

    /**
     * perform one clock cycle of update
     */
    public void clock() {
        // adds 1 to clock, decrements remaining clocks for current instruction, if 0,
        // starts executing next instruction

        // handle new instruction
        if (cycles == 0) {
            opcode = cpuBusRead(pc);
            log.trace("pc={}", () -> pc);

            setFlag(Flag.U, true);// just to be sure
            pc++;

            Instruction i = instructions[opcode];

            cycles = i.getClocks();

            // process addressing
            int additionalCycle1 = i.runAddressMode();
            int additionalCycle2 = i.runInstruction();
            cycles += (additionalCycle1 & additionalCycle2);

            setFlag(Flag.U, true);// just to be sure

            log.trace("globalClock={},PC={},opcode={},a={},x={},y={},status={},stkp={}", clockCount, pc, opcode, a, x,
                    y, status, stkp);
        }
        clockCount++;// global clock count
        cycles--;// reduce remaining
    }

    /**
     * is current instruction complete
     *
     * @return
     */
    boolean complete() {
        return cycles == 0;
    }

    /**
     * Run instructions in a tight loop, invoking {@code shouldContinue} once
     * before each instruction fetch. This is the bugzmanov ch. 3.4 pattern:
     * a host (debugger, tracer, the Snake assembly demo) injects
     * per-instruction work without subclassing.
     *
     * <p>The predicate is called with this CPU as argument and returns
     * {@code true} to execute the next instruction or {@code false} to
     * cleanly exit the loop. The CPU is always at an instruction boundary
     * when the predicate runs (PC points at the next opcode, no cycles
     * remain on the previous instruction). On the very first call after
     * {@link #reset()}, the implementation drains the 8-cycle reset
     * sequence first, so the predicate's initial view is the CPU at the
     * reset vector — not mid-reset.
     *
     * <p><strong>Termination is the caller's responsibility.</strong> This
     * method has no built-in time, instruction, or opcode limit — it loops
     * until the predicate returns false. In particular:
     * <ul>
     *   <li><strong>Do not</strong> assume {@code BRK} ($00) is a halt. On
     *       the real 6502, BRK is a software interrupt: it pushes status +
     *       PC, jumps through {@code [$FFFE/$FFFF]}, and execution continues
     *       after the IRQ handler returns via RTI. Many real games use BRK
     *       as a dispatch primitive for their sound engine or screen-update
     *       routine.</li>
     *   <li>Real "halt" patterns on the NES are {@code JMP *} or
     *       {@code BNE *-2} style infinite loops — programs never finish in
     *       the imperative sense, they wait for the next NMI/IRQ.</li>
     *   <li>The bugzmanov Snake demo terminates with BRK only because his
     *       test fixture has no IRQ vector wired up; that is a test-harness
     *       convention, not a 6502 convention.</li>
     * </ul>
     *
     * <p>Common safe predicates:
     * <ul>
     *   <li>Bounded run: {@code int[] n = {0}; cpu.runWithCallback(c -> n[0]++ < 1_000_000)}</li>
     *   <li>Stop at PC: {@code cpu.runWithCallback(c -> c.getPc() != haltPc)}</li>
     *   <li>Wedge detection (PC unchanged for N consecutive calls): catches
     *       any tight-loop halt including {@code JMP *}.</li>
     * </ul>
     *
     * <p>Note: this drives the CPU one instruction at a time. The PPU /
     * APU / DMA arbitration that {@link net.lomibao.nes.NesSystem#runFrame()}
     * orchestrates is NOT exercised here — this is the pure-CPU path,
     * useful for nestest-style headless validation and CPU-only demos.
     *
     * @param shouldContinue invoked once before each instruction; return
     *        {@code false} to break out of the loop
     */
    public void runWithCallback(java.util.function.Predicate<CPU6502> shouldContinue) {
        while (true) {
            // Drain any in-flight instruction's remaining cycles before the
            // predicate runs, so the predicate always sees the CPU at an
            // instruction boundary.
            while (cycles != 0) {
                clock();
            }
            if (!shouldContinue.test(this)) {
                return;
            }
            // Execute exactly one full instruction.
            clock();
            while (cycles != 0) {
                clock();
            }
        }
    }

    /**
     * opcode handlers
     *
     **/

    /** add with carry in **/
    int ADC() {
        fetch();
        temp = getA() + getFetched() + (getFlag(Flag.Carry) ? 0x01 : 0x00);
        setFlag(Flag.Carry, temp > 255);
        setFlag(Flag.Zero, (temp & 0x00FF) == 0);

        // fancy logic see ADC() in
        // https://github.com/OneLoneCoder/olcNES/blob/master/Part%232%20-%20CPU/olc6502.cpp
        int over = (~(a ^ fetched) & (a ^ temp)) & 0x0080;
        setFlag(Flag.VOverflow, over != 0);
        setFlag(Flag.Negative, (temp & 0x80) != 0);
        setA(temp & 0x00FF);
        return 1;// has potential to require additional clocks
    }

    /** bitwise and **/
    int AND() {
        fetch();
        setA(a & fetched);
        setFlag(Flag.Zero, a == 0x00);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 1;
    }

    /* arithmetic shift left */
    int ASL() {
        fetch();
        temp = getFetched() << 1;
        setFlag(Flag.Carry, (temp & 0xFF00) > 0);
        setFlag(Flag.Zero, (temp & 0x00FF) == 0x0);
        setFlag(Flag.Negative, (temp & 0x80) != 0);
        if ("IMP".equals(instructions[opcode].getAddressingMode())) {
            setA(temp & 0x00FF);
        } else {
            cpuBusWrite(addressAbs, (byte) (temp & 0x00FF));
        }
        return 0;
    }

    /** branch if carry clear **/
    int BCC() {
        if (!getFlag(Flag.Carry)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());

            if ((getAddressAbs() & 0xFF00) != (getPc() & 0xFF00)) {
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }

    /** branch if carry set **/
    int BCS() {
        if (getFlag(Flag.Carry)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());

            if ((getAddressAbs() & 0xFF00) != (getPc() & 0xFF00)) {
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }

    /** branch if equal */
    int BEQ() {
        if (getFlag(Flag.Zero)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());
            if ((addressAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }

    int BIT() {
        fetch();
        temp = a & fetched;
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x00);
        setFlag(Flag.Negative, (fetched & (1 << 7)) != 0x0);
        setFlag(Flag.VOverflow, (fetched & (1 << 6)) != 0x0);
        return 0;
    }

    /** Branch if negative */
    int BMI() {
        if (getFlag(Flag.Negative)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());
            if ((addressAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }

    /** branch not equal **/
    int BNE() {
        if (!getFlag(Flag.Zero)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());
            if ((addressAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }

    /** branch if positive **/
    int BPL() {
        if (!getFlag(Flag.Negative)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());
            if ((addressAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;

            }
            setPc(addressAbs);

        }
        return 0;
    }

    /** break **/
    int BRK() {
        pc++;

        writeShortToStack(pc);

        // BRK pushes status with bits 4 and 5 set
        byte statusToPush = (byte) (status | Flag.Break.mask | Flag.U.mask);
        writeByteToStack(statusToPush);

        setFlag(Flag.InterruptDisable, true);
        setPc(readShortFromAddress(0xFFFE));

        return 0;
    }

    /** branch if overflow clear **/
    int BVC() {
        if (!getFlag(Flag.VOverflow)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());

            if ((getAddressAbs() & 0xFF00) != (getPc() & 0xFF00)) {
                cycles++;
            }
            setPc(getAddressAbs());
        }

        return 0;
    }

    /** branch if overflow set */
    int BVS() {
        if (getFlag(Flag.VOverflow)) {
            cycles++;
            setAddressAbs(getPc() + getAddressRel());

            if ((getAddressAbs() & 0xFF00) != (getPc() & 0xFF00)) {
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }

    int CLC() {
        setFlag(Flag.Carry, false);
        return 0;
    }

    int CLD() {
        setFlag(Flag.Decimal, false);
        return 0;
    }

    int CLI() {
        setFlag(Flag.InterruptDisable, false);
        return 0;
    }

    int CLV() {
        setFlag(Flag.VOverflow, false);
        return 0;
    }

    /** compare accumlator **/
    int CMP() {
        fetch();
        temp = getA() - getFetched();
        setFlag(Flag.Carry, getA() >= getFetched());
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        return 1;// extra cycle possible?
    }

    /** compare x register */
    int CPX() {
        fetch();
        temp = getX() - getFetched();
        setFlag(Flag.Carry, getX() >= getFetched());
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        return 0;
    }

    /** compare y register */
    int CPY() {
        fetch();
        temp = getY() - getFetched();
        setFlag(Flag.Carry, getY() >= getFetched());
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        return 0;
    }

    /** decrement value at memory location */
    int DEC() {
        fetch();
        temp = getFetched() - 1;
        cpuBusWrite(getAddressAbs(), (byte) (temp & 0x00FF));
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        return 0;
    }

    /** decrement x register **/
    int DEX() {
        setX(getX() - 1);
        setFlag(Flag.Zero, x == 0);
        setFlag(Flag.Negative, (x & 0x80) != 0);
        return 0;
    }

    /** dec y register **/
    int DEY() {
        setY(getY() - 1);
        setFlag(Flag.Zero, y == 0);
        setFlag(Flag.Negative, (y & 0x80) != 0);
        return 0;
    }

    /** XOR bitwise **/
    int EOR() {
        fetch();
        a = a ^ fetched;
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 1;
    }

    int INC() {
        fetch();
        temp = fetched + 1;
        cpuBusWrite(getAddressAbs(), (byte) (temp & 0x00FF));
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        return 0;
    }

    /** increment x **/
    int INX() {
        setX(getX() + 1);
        setFlag(Flag.Zero, x == 0);
        setFlag(Flag.Negative, (x & 0x80) != 0);
        return 0;
    }

    /** increment y **/
    int INY() {
        setY(getY() + 1);
        setFlag(Flag.Zero, y == 0);
        setFlag(Flag.Negative, (y & 0x80) != 0);
        return 0;
    }

    /** jump to location **/
    int JMP() {
        setPc(getAddressAbs());
        return 0;
    }

    /** jump to sub routine **/
    int JSR() {
        // In 6502, JSR pushes the address of the last byte of the JSR instruction (opcode + 2)
        // which is the same as PC - 1 where PC is at the start of the next instruction
        // At this point, PC has been incremented past the opcode by clock()
        // and ABS() has consumed the 2 address bytes, so PC = next_instruction_address
        // Therefore, we need to push PC + 2 - 3 = PC - 1
        // But actually, let's think: if opcode was at C600, PC is now at C603
        // We want to push C602 (the high byte of the operand)
        // So we push PC - 1
        setPc(getPc() - 1);
        writeShortToStack(getPc());
        setPc(getAddressAbs());
        return 0;
    }

    /** load the accumulator **/
    int LDA() {
        fetch();
        setA(getFetched());
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 1;
    }

    /** load x register **/
    int LDX() {
        fetch();
        setX(getFetched());
        setFlag(Flag.Zero, x == 0);
        setFlag(Flag.Negative, (x & 0x80) != 0);
        return 1;
    }

    /** load y register **/
    int LDY() {
        fetch();
        setY(getFetched());
        setFlag(Flag.Zero, y == 0);
        setFlag(Flag.Negative, (y & 0x80) != 0);
        return 1;
    }

    /** load a and x register (illegal opcode) **/
    int LAX() {
        fetch();
        setA(getFetched());
        setX(getFetched());
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 1;
    }

    /** Logical shift right **/
    int LSR() {
        fetch();
        setFlag(Flag.Carry, (getFetched() & 0x0001) != 0);
        temp = getFetched() >> 1;
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        if ("IMP".equals(instructions[opcode].getAddressingMode())) {
            setA(temp & 0x00FF);
        } else {
            cpuBusWrite(getAddressAbs(), (byte) (temp & 0x00FF));
        }
        return 0;

    }

    int NOP() {
        switch (opcode) {
            case 0x1C:
            case 0x3C:
            case 0x5C:
            case 0x7C:
            case 0xDC:
            case 0xFC:
                return 1;
        }
        return 0;
    }

    /** bitwise logic or **/
    int ORA() {
        fetch();
        setA(getA() | getFetched());
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 1;
    }

    /** push accumulator to stack **/
    int PHA() {
        writeByteToStack((byte) getA());
        return 0;
    }

    /** push status register to stack **/
    int PHP() {
        // PHP pushes status with bits 4 and 5 set
        byte statusToPush = (byte) (status | Flag.Break.mask | Flag.U.mask);
        writeByteToStack(statusToPush);
        return 0;
    }

    /** pop accumulator off stack **/
    int PLA() {
        setA(popByteOffStack());// read from stack
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    /** pop status off stack **/
    int PLP() {
        status = (byte) popByteOffStack();
        // Bit 4 and 5 are ignored when pulling from stack
        status &= ~Flag.Break.mask;
        status |= Flag.U.mask;
        return 0;
    }

    /** rotate left */
    int ROL() {
        fetch();
        temp = (getFetched() << 1) | (getFlag(Flag.Carry) ? 0x1 : 0x0);
        setFlag(Flag.Carry, (temp & 0xFF00) != 0);
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        if ("IMP".equals(instructions[opcode].getAddressingMode())) {
            setA(temp & 0x00FF);
        } else {
            cpuBusWrite(getAddressAbs(), (byte) (temp & 0x00FF));
        }
        return 0;
    }

    /** rotate right **/
    int ROR() {
        fetch();
        temp = ((getFlag(Flag.Carry) ? 0x01 : 0x00) << 7) | (getFetched() >> 1);
        setFlag(Flag.Carry, (getFetched() & 0x01) != 0);
        setFlag(Flag.Zero, (temp & 0x00ff) == 0x0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        if ("IMP".equals(instructions[opcode].getAddressingMode())) {
            setA(temp & 0x00FF);
        } else {
            cpuBusWrite(getAddressAbs(), (byte) (temp & 0x00FF));
        }
        return 0;
    }

    /** return from interrupt **/
    int RTI() {
        status = (byte) popByteOffStack();
        // Bit 4 and 5 are ignored when pulling from stack
        status &= ~Flag.Break.mask;
        status |= Flag.U.mask;
        setPc(popShortOffStack());
        return 0;
    }

    /** return from subroutine **/
    int RTS() {
        temp = popShortOffStack();
        temp++;
        setPc(temp);
        return 0;
    }

    /** store A AND X (illegal opcode) **/
    int SAX() {
        byte result = (byte)(getA() & getX());
        cpuBusWrite(getAddressAbs(), result);
        // SAX does not set any flags
        return 0;
    }

    /** decrement then compare (illegal opcode) **/
    int DCP() {
        fetch();
        // Perform DEC on memory
        temp = getFetched() - 1;
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform CMP with accumulator
        int cmpTemp = getA() - (temp & 0x00FF);
        setFlag(Flag.Carry, getA() >= (temp & 0x00FF));
        setFlag(Flag.Zero, (cmpTemp & 0x00FF) == 0);
        setFlag(Flag.Negative, (cmpTemp & 0x80) != 0);
        return 0;
    }

    /** increment then subtract (illegal opcode) **/
    int ISB() {
        fetch();
        // Perform INC on memory
        temp = getFetched() + 1;
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform SBC with accumulator (SBC = A - M - (1 - Carry))
        int value = (temp & 0x00FF) ^ 0x00FF;
        int sbcTemp = getA() + value + (getFlag(Flag.Carry) ? 1 : 0);
        setFlag(Flag.Carry, (sbcTemp & 0xFF00) != 0);
        setFlag(Flag.Zero, (sbcTemp & 0x00FF) == 0);
        setFlag(Flag.Negative, (sbcTemp & 0x80) != 0);
        setFlag(Flag.VOverflow, ((getA() ^ sbcTemp) & (value ^ sbcTemp) & 0x80) != 0);
        setA(sbcTemp & 0x00FF);
        return 0;
    }

    /** shift left then OR (illegal opcode) **/
    int SLO() {
        fetch();
        // Perform ASL on memory
        temp = getFetched() << 1;
        setFlag(Flag.Carry, (temp & 0xFF00) > 0);
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform ORA with accumulator
        setA(getA() | (temp & 0x00FF));
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    /** shift right then EOR (illegal opcode) **/
    int SRE() {
        fetch();
        // Perform LSR on memory
        setFlag(Flag.Carry, (getFetched() & 0x0001) != 0);
        temp = getFetched() >> 1;
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform EOR with accumulator
        setA(getA() ^ (temp & 0x00FF));
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    /** rotate left then AND (illegal opcode) **/
    int RLA() {
        fetch();
        // Perform ROL on memory
        temp = (getFetched() << 1) | (getFlag(Flag.Carry) ? 0x1 : 0x0);
        setFlag(Flag.Carry, (temp & 0xFF00) != 0);
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform AND with accumulator
        setA(getA() & (temp & 0x00FF));
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    /** rotate right then add (illegal opcode) **/
    int RRA() {
        fetch();
        // Perform ROR on memory - save old carry, set new carry from bit 0
        boolean oldCarry = getFlag(Flag.Carry);
        setFlag(Flag.Carry, (getFetched() & 0x01) != 0);
        temp = (getFetched() >> 1) | (oldCarry ? 0x80 : 0x00);
        cpuBusWrite(getAddressAbs(), (byte)(temp & 0x00FF));
        
        // Perform ADC with accumulator
        int adcTemp = getA() + (temp & 0x00FF) + (getFlag(Flag.Carry) ? 1 : 0);
        setFlag(Flag.Carry, adcTemp > 255);
        setFlag(Flag.Zero, (adcTemp & 0x00FF) == 0);
        setFlag(Flag.Negative, (adcTemp & 0x80) != 0);
        setFlag(Flag.VOverflow, ((getA() ^ adcTemp) & ((temp & 0x00FF) ^ adcTemp) & 0x80) != 0);
        setA(adcTemp & 0x00FF);
        return 0;
    }

    /** subtraction with borrow in **/
    int SBC() {
        fetch();
        // Invert the bits of the fetched value for subtraction
        int value = getFetched() ^ 0x00FF;

        // Add the inverted value, identical to ADC logic
        temp = getA() + value + (getFlag(Flag.Carry) ? 0x01 : 0x00);
        setFlag(Flag.Carry, (temp & 0xFF00) != 0);
        setFlag(Flag.Zero, (temp & 0x00FF) == 0);
        setFlag(Flag.VOverflow, ((temp ^ a) & (temp ^ value) & 0x0080) != 0);
        setFlag(Flag.Negative, (temp & 0x0080) != 0);
        setA(temp & 0x00FF);
        return 1;
    }

    /** set carry flag **/
    int SEC() {
        setFlag(Flag.Carry, true);
        return 0;
    }

    /** set decimal flag **/
    int SED() {
        setFlag(Flag.Decimal, true);
        return 0;
    }

    /** set interrupt flag/enable interrupts **/
    int SEI() {
        setFlag(Flag.InterruptDisable, true);
        return 0;
    }

    /** store accumulator at address **/
    int STA() {
        cpuBusWrite(getAddressAbs(), (byte) getA());
        return 0;
    }

    /** store x register at address **/
    int STX() {
        cpuBusWrite(getAddressAbs(), (byte) getX());
        return 0;
    }

    /** store y register at address **/
    int STY() {
        cpuBusWrite(getAddressAbs(), (byte) getY());
        return 0;
    }

    /** transfer acc to x register **/
    int TAX() {
        setX(getA());
        setFlag(Flag.Zero, x == 0);
        setFlag(Flag.Negative, (x & 0x80) != 0);
        return 0;
    }

    /** transfer acc to y register **/
    int TAY() {
        setY(getA());
        setFlag(Flag.Zero, y == 0);
        setFlag(Flag.Negative, (y & 0x80) != 0);
        return 0;
    }

    /** transfer stack pointer to x **/
    int TSX() {
        setX(getStkp());
        setFlag(Flag.Zero, x == 0);
        setFlag(Flag.Negative, (x & 0x80) != 0);
        return 0;
    }

    /** transfer x register to accumulator **/
    int TXA() {
        setA(getX());
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    /** transfer x to stack pointer **/
    int TXS() {
        setStkp(getX());
        return 0;
    }

    /** transfer y register to acc **/
    int TYA() {
        setA(getY());
        setFlag(Flag.Zero, a == 0);
        setFlag(Flag.Negative, (a & 0x80) != 0);
        return 0;
    }

    public int XXX() {
        log.info("XXX called");
        return 0;
    }

    /**
     * addressing modes
     *
     * change the number of bytes that make up a full instruction
     * the number of cycles can change based on how it is addressed (ex: page
     * boundaries)
     * so returns the adjustment from base
     **/
    public int IMP() {
        fetched = getA();
        return 0;
    }

    public int IMM() {
        addressAbs = getPc();
        setPc(addressAbs + 1);
        return 0;
    }

    public int ZP0() {
        addressAbs = cpuBusRead(pc);
        setPc(pc + 1);
        addressAbs &= 0x00FF;
        return 0;
    }

    public int ZPX() {
        addressAbs = cpuBusRead(pc) + getX();
        setPc(pc + 1);
        addressAbs &= 0x00FF;
        return 0;
    }

    public int ZPY() {
        addressAbs = cpuBusRead(pc) + getY();
        setPc(pc + 1);
        addressAbs &= 0x00FF;
        return 0;
    }

    public int REL() {
        addressRel = cpuBusRead(pc);
        setPc(pc + 1);
        if ((addressRel & 0x80) > 0) {// handle negative offset
            addressRel |= 0xFF00;
        }
        return 0;
    }

    public int ABS() {
        addressAbs = readShortFromPCAddress();
        return 0;
    }

    public int ABX() {
        int address = readShortFromPCAddress();
        int withOffset = address + getX();
        setAddressAbs(withOffset);
        // if the page changed, an additional clock in neeeded
        if ((withOffset & 0xFF00) != (address & 0xFF00)) {
            return 1;
        }
        return 0;
    }

    public int ABY() {
        int address = readShortFromPCAddress();
        int withOffset = address + getY();
        setAddressAbs(withOffset);
        // if the page changed, an additional clock in neeeded
        if ((withOffset & 0xFF00) != (address & 0xFF00)) {
            return 1;
        }
        return 0;
    }

    /*
     * indirect addressing. gets the actual address from the supplied address,
     * hardware bug on page boundary
     */
    public int IND() {
        int ptrLow = cpuBusRead(pc);
        pc++;
        int ptrHigh = cpuBusRead(pc);
        pc++;
        int ptr = (ptrHigh << 8) | ptrLow;
        if (ptrLow == 0x00FF) {// simulatepage boundary hardware bug
            setAddressAbs((cpuBusRead(ptr & 0xFF00) << 8) | cpuBusRead(ptr));
        } else {
            setAddressAbs((cpuBusRead(ptr + 1) << 8) | cpuBusRead(ptr));
        }
        return 0;

    }

    /*
     * indirect x
     * supplied 8 bit address is offset by x register and then the 16bit address is
     * read from that
     */
    public int IZX() {
        int t = cpuBusRead(pc);
        pc++;
        int low = cpuBusRead((t + getX()) & 0x00FF);
        int high = cpuBusRead((t + getX() + 1) & 0x00FF);
        setAddressAbs((high << 8) | low);
        return 0;
    }

    /* offset by y. if page changes, extra clock needed */
    public int IZY() {
        int t = cpuBusRead(pc);
        pc++;
        int low = cpuBusRead((t) & 0x00FF);
        int high = cpuBusRead((t + 1) & 0x00FF);
        setAddressAbs(((high << 8) | low) + getY());
        if ((addressAbs & 0xFF00) != (high << 8)) {
            return 1;
        }
        return 0;
    }

    //////
    // helper function
    private int fetch() {
        if (!("IMP".equals(instructions[opcode].addressingMode))) {
            fetched = cpuBusRead(addressAbs);
        }
        return fetched;
    }
}
