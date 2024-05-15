package net.lomibao.nes.components;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Scanner;

@Builder
@Data
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Log4j2
public class CPU6502  extends CPUBusComponent {

    private int readShortFromPCAddress(){
        int low=read(pc);
        pc++;
        int high=read(pc);
        pc++;
        return ((high<<8)|low)&0xFFFF;
    }
    private int readShortFromAddress(int address){
        address&=0xFFFF;
        return read(address)|(read(address+1)<<8);
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
        this.temp = temp & 0xFFFF ;
    }

    public int getAddressAbs() {
        return addressAbs  & 0xFFFF;
    }

    public void setAddressAbs(int addressAbs) {
        this.addressAbs = addressAbs  & 0xFFFF;
    }

    public int getAddressRel() {
        return addressRel  & 0xFFFF;
    }

    public void setAddressRel(int addressRel) {
        this.addressRel = addressRel  & 0xFFFF;
    }

    //private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(CPU6502.class);
    //registers and flags
    @Builder.Default
    private int  a      = 0x00;//accumulator (8bits)
    @Builder.Default
    private int  x      = 0x00;//index x (8bits)
    @Builder.Default
    private int  y      = 0x00;//index y;\ (8bits)
    @Builder.Default
    private int  stkp   = 0x00;//stack pointer (8bits)
    @Builder.Default
    private int pc     = 0x0000;//program counter (16bit)
    @Builder.Default
    private byte  status = 0x00;//status flags (8bits)
    /*
    7  bit  0
    ---- ----
    NV1B DIZC
    |||| ||||
    |||| |||+- Carry
    |||| ||+-- Zero
    |||| |+--- Interrupt Disable
    |||| +---- Decimal
    |||+------ (No CPU effect; see: the B flag)
    ||+------- (No CPU effect; always pushed as 1)
    |+-------- Overflow
    +--------- Negative
     */

    enum Flag{
        Carry(1<<0,"Carry Bit"),
        Zero(1<<1,"Zero"),
        InterruptDisable(1<<2,"disable interrupts"),
        Decimal(1<<3,"Decimal Mode (unused)"),
        Break(1<<4,"Break (no cpu effect"),
        U(1<<5,"unused always a 1 value, called U to align with one lone coder "),
        VOverflow(1<<6,"overflow flag (put v at beginning so it aligns with one lone coder impl"),
        Negative(1<<7,"Negative");

        public final int mask;
        String description;
        private Flag(int mask,String description){
            this.mask=mask;
            this.description=description;
        }
    }
    boolean getFlag(Flag flag){
        return (flag.mask & status)>0;
    }
    void setFlag(Flag flag,boolean value){
        if(value){
            status |=flag.mask;
        }else{
            status &= ~flag.mask;
        }
    }
    /*helper method for casting*/
//    public static short sVal(int val){
//        return (short)val;
//    }
//    public static byte bVal(int val){
//        return (byte)val;
//    }
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor

    public static class Instruction{
        private int id;
        private String hexOpcode;
        private String opcodeName;
        private String addressingModeDescription;
        private String description;
        private int byteCount;
        private int clocks;
        private String addressingMode;

        private CPU6502 cpu;
        private Method handler;
        private Method addressingHandler;


        private int runAddressMode(){
            if(addressingHandler==null){
                addressingHandler=Arrays.stream(cpu.getClass().getDeclaredMethods())
                        .filter(method -> addressingMode.equals(method.getName()))
                        .findFirst().orElse(null);
            }
            if(addressingHandler!=null){
                try {
                    return (int) addressingHandler.invoke(cpu);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }else{
                log.error(()->String.format("no addressing handler configured for %s",addressingMode));
            }
            return 0;
        }
        private void initHandlers(){
            if(handler==null){
                handler=Arrays.stream(cpu.getClass().getDeclaredMethods())
                        .filter(method -> opcodeName.equals(method.getName()))
                        .findFirst().orElse(null);
            }
            if(addressingHandler==null){
                addressingHandler=Arrays.stream(cpu.getClass().getDeclaredMethods())
                        .filter(method -> addressingMode.equals(method.getName()))
                        .findFirst().orElse(null);
            }
        }
        private int runInstruction(){
            if(handler==null){
                handler=Arrays.stream(cpu.getClass().getDeclaredMethods())
                        .filter(method -> opcodeName.equals(method.getName()))
                        .findFirst().orElse(null);
            }


            if(handler!=null){
                try {
                    return (int) handler.invoke(cpu);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }else{
                log.error(()->String.format("no instruction handler configured for %s",opcodeName));
            }
            return 0;
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
                    ", cpu=" + (cpu!=null) +
                    ", handler="+(handler!=null)  +
                    ", addHandler="+(addressingHandler!=null)  +
                    '}';
        }
    }

    //emulation support
    @Builder.Default
    private int fetched=0x00;//working input value of ALU (8bit)
    @Builder.Default
    private int temp=0x0000;//temp var
    @Builder.Default
    private int addressAbs=0x0000;//all used addresses go here
    @Builder.Default
    private int addressRel=0x0000;//the absolute address that was resolved from relative addressing
    @Builder.Default
    private int opcode=0x00;//instruction byte
    @Builder.Default
    private int cycles = 0;//count of how many cycles left the instruction has remaining
    @Builder.Default
    private long clockCount=0; //global clock count since start

    private Instruction[] instructions;
    public CPU6502() {


        instructions=new Instruction[256];
        InputStream stream=this.getClass().getResourceAsStream("/opcodes/opcodes.csv");
        Scanner scanner=new Scanner(stream);
        scanner.nextLine();
        while(scanner.hasNextLine()){
            String line=scanner.nextLine();
            log.info("{}",()->line);
            String[] s=line.split(",");
            Instruction instruction=Instruction.builder()
                    .cpu(this)
                    .id(Integer.valueOf(s[0]))
                    .hexOpcode(s[1])
                    .opcodeName("???".equals(s[2])?"XXX":s[2])
                    .addressingModeDescription(s[3])
                    .description(s[4])
                    .byteCount(Integer.valueOf(s[5]))
                    .clocks(Integer.valueOf(s[6]))
                    .addressingMode(s[7])
                    .build();
            instructions[instruction.id]=instruction;
        }
        for(Instruction x: instructions){
            x.initHandlers();
            log.trace(x);
        }
    }

    /**
     * reset interrupt - forces cpu into known state, hard wired in the CPU
     */
    public void reset(){
        //get address to set pc to
        pc= getProgramCounterAtAddress((short)0xFFFC);
        //reset registers
        a=0;
        x=0;
        y=0;
        stkp=(byte)0xFD;
        status=0x00;//clear flags
        setFlag(Flag.U,true);//u is always true
        //clear helpers
        addressRel=0x0;
        addressAbs=0x0;
        fetched=0x0;
        //reset takes some time
        cycles =8;

    }

    /*takes an address, reads in the low byte and high byte and sets the pc*/
    private int getProgramCounterAtAddress(int address) {
        addressAbs=address;
        int low=read(addressAbs);
        int high=read(addressAbs+1);
        //set program counter
        return (high<<8)|low;
    }
    private void writeShortToStack(int value){
        value&=0xFFFF;//mask to 16b
        write((0x0100+stkp),(byte)((value>>8) & 0x00FF));//stack starts at 0100
        stkp--;
        write((0x0100+stkp),(byte)(value & 0x00FF));
        stkp--;
    }
    private void writeByteToStack(byte value){
        write(0x0100+stkp,value);
        stkp--;
    }
    private int popByteOffStack(){
        stkp++;
        return (read(0x100+stkp))&0x00FF;
    }
    private int popShortOffStack(){
        int l=popByteOffStack();
        int h=popByteOffStack();
        return (h<<8)|l;
    }


    /**
     * interrupt request
     */
    public void irq(){
        if(!getFlag(Flag.InterruptDisable)){
            writeShortToStack(pc);
            setFlag(Flag.Break,false);
            setFlag(Flag.U,true);
            setFlag(Flag.InterruptDisable,true);
            write((0x0100+getStkp()),status);//write status flags
            stkp--;
            addressAbs=0xFFFE;
            pc=getProgramCounterAtAddress(addressAbs);

            cycles=7;//IRQ takes 7 cycles
        }
    }

    /**
     * non-maskable inerrupt request ( can't be disabled)
     */
    public void nmi(){
        writeShortToStack(pc);
        setFlag(Flag.Break,false);
        setFlag(Flag.U,true);
        setFlag(Flag.InterruptDisable,true);
        writeByteToStack(status);

        addressAbs=0xFFFA;
        pc=getProgramCounterAtAddress(addressAbs);

        cycles=8;
    }

    /**
     * perform one clock cycle of update
     */
    public void clock(){
        //adds 1 to clock, decrements remaining clocks for current instruction, if 0, starts executing next instruction

        //handle new instruction
        if(cycles==0){
            opcode=read(pc);
            log.trace("pc={}",()->pc);

            setFlag(Flag.U,true);//just to be sure
            pc++;

            Instruction i=instructions[opcode];

            cycles=i.getClocks();

            //process addressing
            int additionalCycle1 =i.runAddressMode();
            int additionalCycle2 =i.runInstruction();
            cycles+=(additionalCycle1 & additionalCycle2);

            setFlag(Flag.U,true);//just to be sure

            log.trace("globalClock={},PC={},opcode={},a={},x={},y={},status={},stkp={}",clockCount,pc,opcode,a,x,y,status,stkp);
        }
        clockCount++;//global clock count
        cycles--;//reduce remaining
    }

    /**
     * is current instruction complete
     * @return
     */
    boolean complete(){
        return cycles==0;
    }

    /**
     * opcode handlers
     *
     * **/

    /** add with carry in**/
    int ADC(){
        fetch();
        temp=getA()+getFetched()+(getFlag(Flag.Carry)?0x01:0x00);
        setFlag(Flag.Carry,temp>255);
        setFlag(Flag.Zero,(temp&0x00FF)==0);

        //fancy logic see ADC() in https://github.com/OneLoneCoder/olcNES/blob/master/Part%232%20-%20CPU/olc6502.cpp
        int over=(~(a ^ fetched) & (a ^ temp)) & 0x0080;
        setFlag(Flag.VOverflow,over!=0);
        setFlag(Flag.Negative,(temp&0x80)!=0);
        setA(temp&0x00FF);
        return 1;//has potential to require additional clocks
    }
    /**bitwise and**/
    int AND(){
        fetch();
        setA(a&fetched);
        setFlag(Flag.Zero,a==0x00);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 1;
    }
    /*arithmetic shift left*/
    int ASL(){
        fetch();
        temp=getFetched()<<1;
        setFlag(Flag.Carry,(temp&0xFF00)>0);
        setFlag(Flag.Zero,(temp&0x00FF)==0x0);
        setFlag(Flag.Negative,(temp&0x80)!=0);
        if(instructions[opcode].getAddressingMode()=="IMP"){
            setA(temp&0x00FF);
        }else{
            write(addressAbs,(byte)(temp&0x00FF));
        }
        return 0;
    }
    /**branch if carry clear**/
    int BCC(){
        if(!getFlag(Flag.Carry)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());

            if((getAddressAbs()&0xFF00)!=(getPc()&0xFF00)){
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }
    /**branch if carry set**/
    int BCS(){
        if(getFlag(Flag.Carry)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());

            if((getAddressAbs()&0xFF00)!=(getPc()&0xFF00)){
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }
    /**branch if equal*/
    int BEQ(){
        if(getFlag(Flag.Zero)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());
            if((addressAbs&0xFF00)!=(pc&0xFF00)){
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }
    int BIT(){
        fetch();
        temp=a&fetched;
        setFlag(Flag.Zero,(temp&0x00ff)==0x00);
        setFlag(Flag.Negative,(fetched&(1<<7))!=0x0);
        setFlag(Flag.VOverflow,(fetched&(1<<6))!=0x0);
        return 0;
    }
    /**Branch if negative*/
    int BMI(){
        if(getFlag(Flag.Negative)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());
            if((addressAbs&0xFF00)!=(pc&0xFF00)){
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }
    /**branch not equal**/
    int BNE(){
        if(!getFlag(Flag.Zero)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());
            if((addressAbs&0xFF00)!=(pc&0xFF00)){
                cycles++;
            }
            setPc(addressAbs);
        }
        return 0;
    }
    /**branch if positive**/
    int BPL(){
        if(!getFlag(Flag.Negative)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());
            if((addressAbs&0xFF00)!=(pc&0xFF00)){
                cycles++;

            }
            setPc(addressAbs);

        }
        return 0;
    }
    /**break**/
    int BRK(){
        pc++;
        setFlag(Flag.InterruptDisable,true);
        writeShortToStack(getPc());
        setFlag(Flag.Break,true);
        writeByteToStack(getStatus());
        setFlag(Flag.Break,false);
        setPc(readShortFromAddress(0xFFFE));

        return 0;
    }
    /**branch if overflow clear**/
    int BVC(){
        if(!getFlag(Flag.VOverflow)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());

            if((getAddressAbs()&0xFF00)!=(getPc()&0xFF00)){
                cycles++;
            }
            setPc(getAddressAbs());
        }

        return 0;
    }



    /** branch if overflow set*/
    int BVS(){
        if(getFlag(Flag.VOverflow)){
            cycles++;
            setAddressAbs(getPc()+getAddressRel());

            if((getAddressAbs()&0xFF00)!=(getPc()&0xFF00)){
                cycles++;
            }
            setPc(getAddressAbs());
        }
        return 0;
    }
    int CLC(){
        setFlag(Flag.Carry,false);
        return 0;
    }
    int CLD(){
        setFlag(Flag.Decimal,false);
        return 0;
    }
    int CLI(){
        setFlag(Flag.InterruptDisable,false);
        return 0;
    }
    int CLV(){
        setFlag(Flag.VOverflow,false);
        return 0;
    }
    /** compare accumlator**/
    int CMP(){
        fetch();
        temp=getA()-getFetched();
        setFlag(Flag.Carry,getA()>=getFetched());
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        return 1;//extra cycle possible?
    }
    /**compare x register*/
    int CPX(){
        fetch();
        temp=getX()-getFetched();
        setFlag(Flag.Carry,getX()>=getFetched());
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        return 0;
    }
    /** compare y register*/
    int CPY(){
        fetch();
        temp=getY()-getFetched();
        setFlag(Flag.Carry,getY()>=getFetched());
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        return 0;
    }
    /**decrement value at memory location*/
    int DEC(){
        fetch();
        temp=getFetched()-1;
        write(getAddressAbs(),(byte)(temp&0x00FF));
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        return 0;
    }
    /** decrement x register**/
    int DEX(){
        setX(getX()-1);
        setFlag(Flag.Zero,x==0);
        setFlag(Flag.Negative,(x&0x80)!=0);
        return 0;
    }
    /**dec y register**/
    int DEY(){
        setY(getY()-1);
        setFlag(Flag.Zero,y==0);
        setFlag(Flag.Negative,(y&0x80)!=0);
        return 0;
    }
    /**XOR bitwise**/
    int EOR(){
        fetch();
        a=a^fetched;
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 1;
    }
    int INC(){
        fetch();
        temp=fetched+1;
        write(getAddressAbs(),(byte)(temp&0x00FF));
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        return 0;
    }
    /**increment x**/
    int INX(){
        setX(getX()+1);
        setFlag(Flag.Zero,x==0);
        setFlag(Flag.Negative,(x&0x80)!=0);
        return 0;
    }
    /**increment y**/
    int INY(){
        setY(getY()+1);
        setFlag(Flag.Zero,y==0);
        setFlag(Flag.Negative,(y&0x80)!=0);
        return 0;
    }
    /** jump to location**/
    int JMP(){
        setPc(getAddressAbs());
        return 0;
    }
    /**jump to sub routine**/
    int JSR(){
        setPc(getPc()-1);
        writeShortToStack(getPc());
        setPc(getAddressAbs());
        return 0;
    }
    /**load the accumulator**/
    int LDA(){
        fetch();
        setA(getFetched());
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 1;
    }
    /**load x register**/
    int LDX(){
        fetch();
        setX(getFetched());
        setFlag(Flag.Zero,x==0);
        setFlag(Flag.Negative,(x&0x80)!=0);
        return 1;
    }
    /**load y register**/
    int LDY(){
        fetch();
        setY(getFetched());
        setFlag(Flag.Zero,y==0);
        setFlag(Flag.Negative,(y&0x80)!=0);
        return 1;
    }
    /**Logical shift right**/
    int LSR(){
        fetch();
        setFlag(Flag.Carry,(getFetched()&0x0001)!=0);
        temp=getFetched()>>1;
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        if("IMP".equals(instructions[opcode].getAddressingMode())){
            setA(temp&0x00FF);
        }else{
            write(getAddressAbs(),(byte)(temp&0x00FF));
        }
        return 0;

    }
    int NOP(){
        switch(opcode){
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
    /**bitwise logic or**/
    int ORA(){
        fetch();
        setA(getA()|getFetched());
        setA(getFetched());
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 1;
    }
    /** push accumulator to stack**/
    int PHA(){
        writeByteToStack((byte)getA());
        return 0;
    }
    /** push status register to stack**/
    int PHP(){
        writeByteToStack((byte)getStatus());
        setFlag(Flag.Break,false);
        setFlag(Flag.U,false);
        return 0;
    }
    /**pop accumulator off stack**/
    int PLA(){
        setA(popByteOffStack());//read from stack
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 0;
    }
    /**pop status off stack**/
    int PLP(){
        setStatus((byte)popByteOffStack());
        setFlag(Flag.U,true);
        return 0;
    }
    /** rotate left*/
    int ROL(){
        fetch();
        temp=(getFetched()<<1)|(getFlag(Flag.Carry)?0x1:0x0);
        setFlag(Flag.Carry,(temp&0xFF00)!=0);
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        if("IMP".equals(instructions[opcode].getAddressingMode())){
            setA(temp&0x00FF);
        }else{
            write(getAddressAbs(),(byte)(temp&0x00FF));
        }
        return 0;
    }
    /**rotate right**/
    int ROR(){
        fetch();
        temp=((getFlag(Flag.Carry)?0x01:0x00)<<7)|(getFetched()>>1);
        setFlag(Flag.Carry,(getFetched()&0x01)!=0);
        setFlag(Flag.Zero,(temp&0x00ff)==0x0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        if("IMP".equals(instructions[opcode].getAddressingMode())){
            setA(temp&0x00FF);
        }else{
            write(getAddressAbs(),(byte)(temp&0x00FF));
        }
        return 0;
    }
    /**return from interrupt**/
    int RTI(){
        setStatus((byte)popByteOffStack());
        status&=~Flag.Break.mask;
        status &= ~Flag.U.mask;
        setPc(popShortOffStack());
        return 0;
    }
    /**return from subroutine**/
    int RTS(){
        temp=popShortOffStack();
        temp++;
        setPc(temp);
        return 0;
    }
    /**subtraction with borrow in**/
    int SBC(){
        fetch();
        int value=getFetched()&0x00FF;
        temp=getA()+value+(getFlag(Flag.Carry)?0x01:0x00);
        setFlag(Flag.Carry,(temp&0xFF00)!=0);
        setFlag(Flag.Zero,(temp&0x00FF)==0);
        setFlag(Flag.VOverflow,((temp^a)&(temp^value)&0x0080)!=0);
        setFlag(Flag.Negative,(temp&0x0080)!=0);
        setA(temp&0x00FF);
        return 1;
    }
    /**set carry flag**/
    int SEC(){
        setFlag(Flag.Carry,true);
        return 0;
    }
    /** set decimal flag**/
    int SED(){
        setFlag(Flag.Decimal,true);
        return 0;
    }
    /**set interrupt flag/enable interrupts**/
    int SEI(){
        setFlag(Flag.InterruptDisable,true);
        return 0;
    }
    /**store accumulator at address**/
    int STA(){
        write(getAddressAbs(),(byte)getA());
        return 0;
    }
    /**store x register at address**/
    int STX(){
        write(getAddressAbs(),(byte)getX());
        return 0;
    }
    /** store y register at address**/
    int STY(){
        write(getAddressAbs(),(byte)getY());
        return 0;
    }
    /**transfer acc to x register**/
    int TAX(){
        setX(getA());
        setFlag(Flag.Zero,x==0);
        setFlag(Flag.Negative,(x&0x80)!=0);
        return 0;
    }
    /**transfer acc to  y register**/
    int TAY(){
        setY(getA());
        setFlag(Flag.Zero,y==0);
        setFlag(Flag.Negative,(y&0x80)!=0);
        return 0;
    }
    /**transfer stack pointer to x**/
    int TSX(){
        setX(getStkp());
        setFlag(Flag.Zero,x==0);
        setFlag(Flag.Negative,(x&0x80)!=0);
        return 0;
    }
    /**transfer x register to accumulator**/
    int TXA(){
        setA(getX());
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 0;
    }
    /**transfer x to stack pointer**/
    int TXS(){
        setStkp(getX());
        return 0;
    }
    /**transfer y register to acc**/
    int TYA(){
        setA(getY());
        setFlag(Flag.Zero,a==0);
        setFlag(Flag.Negative,(a&0x80)!=0);
        return 0;
    }
    public int XXX(){
        log.info("XXX called");
        return 0;
    }

    /** addressing modes
     *
     * change the number of bytes that make up a full instruction
     * the number of cycles can change based on how it is addressed (ex: page boundaries)
     * so returns the adjustment from base
     * **/
    public int IMP(){
        fetched=getA();
        return 0;
    }
    public int IMM(){
        addressAbs=getPc();
        setPc(addressAbs+1);
        return 0;
    }
    public int ZP0(){
        addressAbs=read(pc);
        setPc(pc+1);
        addressAbs&=0x00FF;
        return 0;
    }
    public int ZPX(){
        addressAbs=read(pc)+getX();
        setPc(pc+1);
        addressAbs&=0x00FF;
        return 0;
    }
    public int ZPY(){
        addressAbs=read(pc)+getY();
        setPc(pc+1);
        addressAbs&=0x00FF;
        return 0;
    }
    public int REL(){
        addressRel=read(pc);
        setPc(pc+1);
        if((addressRel&0x80)>0){//handle negative offset
            addressRel|=0xFF00;
        }
        return 0;
    }
    public int ABS(){
        addressAbs=readShortFromPCAddress();
        return 0;
    }
    public int ABX(){
        int address=readShortFromPCAddress();
        int withOffset=address+getX();
        setAddressAbs(withOffset);
        //if the page changed, an additional clock in neeeded
        if((withOffset&0xFF00)!=(address&0xFF00)){
            return 1;
        }
        return 0;
    }
    public int ABY(){
        int address=readShortFromPCAddress();
        int withOffset=address+getY();
        setAddressAbs(withOffset);
        //if the page changed, an additional clock in neeeded
        if((withOffset&0xFF00)!=(address&0xFF00)){
            return 1;
        }
        return 0;
    }
    /*indirect addressing. gets the actual address from the supplied address, hardware bug on page boundary*/
    public int IND(){
        int ptrLow=read(pc);
        pc++;
        int ptrHigh=read(pc);
        pc++;
        int ptr=(ptrHigh<<8)|ptrLow;
        if(ptrLow==0x00FF){//simulatepage boundary hardware bug
            setAddressAbs((read(ptr&0xFF00)<<8)|read(ptr));
        }else{
            setAddressAbs((read(ptr+1)<<8)|read(ptr));
        }
        return 0;


    }
    /* indirect x
    supplied 8 bit address is offset by x register and then the 16bit address is read from that
     */
    public int IZX(){
        int t = read(pc);
        pc++;
        int low=read((t+getX())&0x00FF);
        int high=read((t+getX()+1)&0x00FF);
        setAddressAbs((high<<8)|low);
        return 0;
    }
    /*offset by y. if page changes, extra clock needed*/
    public int IZY(){
        int t = read(pc);
        pc++;
        int low=read((t)&0x00FF);
        int high=read((t+1)&0x00FF);
        setAddressAbs(((high<<8)|low)+getY());
        if((addressAbs&0xFF00)!=(high<<8)){
            return 1;
        }
        return 0;
    }



    //////
    //helper function
    private int fetch(){
        if(!("IMP".equals(instructions[opcode].addressingMode))){
            fetched=read(addressAbs);
        }
        return fetched;
    }
}
