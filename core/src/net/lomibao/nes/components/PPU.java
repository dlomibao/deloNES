package net.lomibao.nes.components;

import lombok.extern.log4j.Log4j2;
import net.lomibao.nes.components.ppu.NameTableMemory;

//Picture Processing Unit
@Log4j2
public class PPU  extends CPUBusComponent implements PPUBusComponent{
    public int CPUBUS_START_ADDRESS =0x2000;
    public int REGISTER_SIZE=8;
    public int CPUBUS_END_ADDRESS =0x4000;//exclusive

    /**
     * The 8-entry register file ($2000-$2007). Kept PRIVATE intentionally:
     * direct access bypasses the register side-effects that the {@code $200x}
     * mapping enforces (e.g. reading {@code $2002} clears VBlank + address
     * latch; reading {@code $2007} is buffered for non-palette addresses;
     * writing {@code $2000} can retrigger NMI mid-VBlank). All CPU-driven
     * access MUST go through {@link #cpuBusRead}/{@link #cpuBusWrite}.
     *
     * <p>External non-CPU reads (debug renderers, diagnostics) use the
     * non-destructive {@code peekCtrl()/peekMask()/peekStatus()} helpers.
     * Tests poke individual registers via the package-private
     * {@code setRegisterForTest(int, byte)} seam.
     */
    private byte[] registers;
    public PPUBus ppuBus;
    private Cartridge cartridge;  // Reference for CHR ROM access
    
    // PPU memory components
    private NameTableMemory nameTableMemory;

    // Internal PPU memory
    private byte[] paletteRAM = new byte[32];  // Palette RAM (internal to PPU chip)
    private byte[] oam = new byte[256];        // Object Attribute Memory (sprites)
    
    // PPUADDR/PPUDATA protocol state
    private int ppuAddress = 0;           // Current PPU address (14-bit)
    /**
     * Shared write-toggle latch for PPUSCROLL ($2005) and PPUADDR ($2006).
     * On real hardware this is the "loopy w" register: false = next write
     * is the FIRST half (scrollX or addr-high), true = next write is the
     * SECOND half (scrollY or addr-low). Reading PPUSTATUS resets it.
     */
    private boolean addressLatch = false;
    private byte ppuDataBuffer = 0;       // Read buffer for non-palette reads

    // PPUSCROLL state — Step 7. Snapshotted from PPUSCROLL writes; the
    // background fetcher reads them every cycle. Games typically write
    // PPUSCROLL once per frame in the NMI handler, so mid-frame reads
    // are stable.
    /** Horizontal scroll in pixels (0..255). */
    private int scrollX = 0;
    /** Vertical scroll in pixels (0..255). */
    private int scrollY = 0;

    // Screen buffer and rendering state
    // Full NES resolution including overscan/blanking for debugging
    // 341 cycles per scanline (visible 0-255), 262 scanlines (visible 0-239)
    public static final int SCREEN_WIDTH = 341;   // Including horizontal blanking
    public static final int SCREEN_HEIGHT = 262;  // Including vertical blanking
    public static final int VISIBLE_WIDTH = 256;  // Visible pixels
    public static final int VISIBLE_HEIGHT = 240; // Visible scanlines
    
    private int[][] screen = new int[SCREEN_HEIGHT][SCREEN_WIDTH];  // screen[y][x] = RGBA as int
    /**
     * Shadow of the per-pixel background pattern value (0..3). Recorded
     * during background rendering so the sprite renderer can apply the
     * priority bit (sprite-behind-background pixel only loses to bg
     * pixels with non-zero pattern value). Bounds match the visible area
     * only ({@link #VISIBLE_HEIGHT} x {@link #VISIBLE_WIDTH}).
     */
    private int[][] bgPatternPixel = new int[VISIBLE_HEIGHT][VISIBLE_WIDTH];
    private int scanline = 0;   // Current scanline (0-261, where 261 is pre-render)
    private int cycle = 0;      // Current cycle within scanline (0-340)
    private boolean frameComplete = false;  // Set when frame finishes, cleared when checked
    private boolean oddFrame = false;       // Tracks odd/even frames
    // Once the sprite-0 hit fires for a frame it can't fire again until the
    // pre-render scanline clears PPUSTATUS bit 6. Tracking this lets us skip
    // the entire checkSpriteZeroHit() call (rather than just early-returning
    // inside it) for the ~89k/frame ticks after the hit has happened. The
    // function-call overhead alone showed up at ~3.7% of total CPU on the web
    // build profile.
    private boolean spriteZeroHitChecked = false;
    
    // NMI latch — set on VBlank entry when PPUCTRL bit 7 is 1, cleared by
    // {@link #consumeNmi()}. Hosts (e.g. NesSystem) poll this after each tick
    // and forward to the CPU on the false→true transition. The PPU itself
    // never calls cpu.nmi() directly — that coupling lives in the host.
    private boolean nmiPending = false;
    
    // Background rendering shift registers (16 bits each)
    private int bgShiftPatternLow = 0;   // Low bit plane pattern shifter
    private int bgShiftPatternHigh = 0;  // High bit plane pattern shifter
    private int bgShiftAttrLow = 0;      // Low bit attribute shifter
    private int bgShiftAttrHigh = 0;     // High bit attribute shifter
    
    // Tile fetch latches (loaded during fetch, then shifted into registers)
    private int bgNextTileId = 0;          // Next tile ID from nametable
    private int bgNextTileAttr = 0;        // Next tile attribute from attribute table
    private int bgNextTilePatternLow = 0;  // Next tile pattern low byte
    private int bgNextTilePatternHigh = 0; // Next tile pattern high byte
    
    public PPU(){
        registers=new byte[REGISTER_SIZE];
        clearScreen();
        initializeMemoryComponents();
    }
    
    /**
     * Initializes PPU memory components and connects them to the PPU bus
     */
    private void initializeMemoryComponents() {
        nameTableMemory = new NameTableMemory();
        
        // Connect components to PPU bus if bus is available
        if (ppuBus != null) {
            ppuBus.connect(nameTableMemory);
        }
    }

    /**
     * Retained for API compatibility — the PPU does not call the CPU directly.
     * NMI forwarding is done by the host (e.g. {@code NesSystem}) by polling
     * {@link #consumeNmi()} after each clock tick. This method is intentionally
     * a no-op; the {@code cpu} reference is not stored.
     *
     * @param cpu ignored
     */
    public void setCPU(CPU6502 cpu) {
        // NMI coupling lives in the host, not in the PPU. See consumeNmi().
    }

    /**
     * Sets the cartridge reference for CHR ROM access
     * Also updates nametable memory with cartridge reference for mirroring
     * @param cartridge the cartridge with CHR ROM data
     */
    public void setCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        if (nameTableMemory != null) {
            nameTableMemory.setCartridge(cartridge);
        }
    }

    @Override
    public int getCPUBusStartAddress() {
        return CPUBUS_START_ADDRESS;
    }
    @Override
    public int getCPUBusEndAddress(){
        return CPUBUS_END_ADDRESS;
    }

    @Override
    public void cpuBusWrite(int address, byte value){
        int index= getCPUBusIndex(address);
        if (index == -1) {
            return;
        }
        
        // Handle specific register writes
        switch (index) {
            case 0: { // PPUCTRL (0x2000)
                // Rising-edge NMI: if software sets bit 7 of PPUCTRL while
                // the PPUSTATUS VBlank flag (bit 7) is still set AND bit 7
                // of PPUCTRL was previously clear, a second NMI is asserted
                // mid-VBlank. Battletoads (among others) depends on this
                // behaviour to fire a second NMI per frame.
                // See https://www.nesdev.org/wiki/PPU_registers#PPUCTRL
                byte oldCtrl = registers[index];
                registers[index] = value;
                boolean oldNmiEnable = (oldCtrl & 0x80) != 0;
                boolean newNmiEnable = (value & 0x80) != 0;
                boolean inVBlank = (registers[2] & 0x80) != 0;
                if (newNmiEnable && !oldNmiEnable && inVBlank) {
                    nmiPending = true;
                }
                break;
            }
            case 1: // PPUMASK (0x2001)
                registers[index] = value;
                break;
            case 2: // PPUSTATUS (0x2002) - read-only
                // Ignore writes to status register
                break;
            case 3: // OAMADDR (0x2003)
                registers[index] = value;
                break;
            case 4: // OAMDATA (0x2004): write to OAM at OAMADDR, auto-increment OAMADDR.
                int oamAddrW = Byte.toUnsignedInt(registers[3]);
                oam[oamAddrW & 0xFF] = value;
                registers[3] = (byte) ((oamAddrW + 1) & 0xFF);
                break;
            case 5: // PPUSCROLL (0x2005)
                registers[index] = value;
                // Step 7: shared-latch protocol. First write = scrollX,
                // second write = scrollY. Latch is shared with PPUADDR
                // ($2006) — see {@link #addressLatch}.
                if (!addressLatch) {
                    scrollX = Byte.toUnsignedInt(value);
                    addressLatch = true;
                } else {
                    scrollY = Byte.toUnsignedInt(value);
                    addressLatch = false;
                }
                break;
            case 6: // PPUADDR (0x2006)
                if (!addressLatch) {
                    // First write: high byte
                    ppuAddress = (value & 0x3F) << 8; // Only 6 bits used for high byte
                    addressLatch = true;
                } else {
                    // Second write: low byte
                    ppuAddress = (ppuAddress & 0x3F00) | (value & 0xFF);
                    ppuAddress &= 0x3FFF; // Mask to 14 bits
                    addressLatch = false;
                }
                break;
            case 7: // PPUDATA (0x2007)
                writePPUData(value);
                ppuAddress += getAddressIncrement();
                ppuAddress &= 0x3FFF; // Wrap at 14 bits
                break;
        }
    }

    /**
     * if read only is true, only reads current state. reads on 6502 can under normal operation have sideeffects
     * @param address
     * @param readOnly
     * @return
     */
    @Override
    public int cpuBusRead(int address, boolean readOnly){
        int index= getCPUBusIndex(address);
        if(index==-1){
            return 0;
        }
        
        // Handle specific register reads
        switch (index) {
            case 0: // PPUCTRL (0x2000) - write-only
                return 0;
            case 1: // PPUMASK (0x2001) - write-only
                return 0;
            case 2: // PPUSTATUS (0x2002)
                int status = Byte.toUnsignedInt(registers[index]);
                if (!readOnly) {
                    // Clear vblank flag (bit 7) after read
                    registers[index] &= 0x7F;
                    // Reset address latch
                    addressLatch = false;
                }
                return status;
            case 3: // OAMADDR (0x2003) - write-only
                return 0;
            case 4: // OAMDATA (0x2004): read OAM at OAMADDR (no auto-increment on read).
                return Byte.toUnsignedInt(oam[Byte.toUnsignedInt(registers[3]) & 0xFF]);
            case 5: // PPUSCROLL (0x2005) - write-only
                return 0;
            case 6: // PPUADDR (0x2006) - write-only
                return 0;
            case 7: // PPUDATA (0x2007)
                int data = readPPUData();
                if (!readOnly) {
                    ppuAddress += getAddressIncrement();
                    ppuAddress &= 0x3FFF; // Wrap at 14 bits
                }
                return data;
            default:
                return Byte.toUnsignedInt(registers[index]);
        }
    }

    private int getCPUBusIndex(int address){
        if(address< CPUBUS_START_ADDRESS || address>= CPUBUS_END_ADDRESS){
            log.error("attempting to read memory out of range {}. valid range [{},{}]",address, CPUBUS_START_ADDRESS, CPUBUS_END_ADDRESS);
            return -1;
        }
        return address%REGISTER_SIZE;
    }

    public void clock() {
        // Increment cycle
        cycle++;
        
        // Wrap cycle at 341 (0-340)
        if (cycle >= 341) {
            cycle = 0;
            scanline++;

            // Wrap scanline at 262 (0-261)
            if (scanline >= 262) {
                scanline = 0;
                frameComplete = true;
                oddFrame = !oddFrame;
            } else if (scanline == 240) {
                // Just entered post-render scanline — composite sprites
                // onto the now-complete background frame. Step 5.
                SpriteRenderer.render(this);
            }
        }
        
        // Set VBlank flag at scanline 241, cycle 1
        if (scanline == 241 && cycle == 1) {
            registers[2] |= 0x80;  // Set bit 7 of PPUSTATUS (VBlank flag)

            // Latch NMI for the host to consume. Production code (NesSystem)
            // polls {@link #consumeNmi()} once per tick and forwards to CPU.
            if (isNMIEnabled()) {
                nmiPending = true;
            }
        }
        
        // Clear VBlank flag and sprite 0 hit at pre-render scanline
        if (scanline == 261 && cycle == 1) {
            registers[2] &= ~0x80;  // Clear bit 7 of PPUSTATUS (VBlank flag)
            registers[2] &= ~0x40;  // Clear bit 6 of PPUSTATUS (sprite 0 hit)
            frameComplete = false;
            spriteZeroHitChecked = false;
            // Reset bg-pattern shadow for the next frame so stale values
            // don't bleed into the next frame's sprite-priority decisions.
            clearBgPatternShadow();
        }

        // Sprite-0 hit (per-pixel opacity): once it's fired we don't re-check
        // until pre-render scanline clears the flag. The helper still does
        // its own per-tick visible-cycle gating; this is the outer skip that
        // avoids paying the function-call cost for the ~89k/frame ticks after
        // the hit has happened.
        if (!spriteZeroHitChecked) {
            checkSpriteZeroHit();
        }

        // Perform background tile fetching on visible and pre-render scanlines.
        // Inlined the isVisible/isPreRender/isRenderingEnabled checks — the
        // method calls were ~3.7% of total CPU on the web profile (TeaVM
        // doesn't inline these one-liners). Order matters: scanline check
        // first so the byte-array read short-circuits during VBlank.
        if ((scanline <= 239 || scanline == 261) && (registers[1] & 0x18) != 0) {
            // Render pixel BEFORE shifting. At cycle 1 the shifter's HIGH byte
            // holds col 0 (from the previous scanline's prefetch); reading
            // bit 15 gives col 0's leftmost pixel. Shifting first would
            // discard that pixel.
            if (isVisibleScanline() && cycle >= 1 && cycle <= 256) {
                renderBackgroundPixel();
            }

            // In the VISIBLE region (cycles 1-256), LOAD must run BEFORE
            // the per-cycle shift on case 0 (cycles 9, 17, 25, ...). The
            // case-0 LOAD copies the just-fetched tile bytes into the
            // LOW byte of the shifter; bit 7 of LOW then needs exactly 8
            // shifts to propagate up to HIGH bit 15, which is what the
            // renderer reads at the next case-0 render (cycle c+8).
            // Putting LOAD AFTER the shift (the old ordering) left only
            // 7 shifts before that render, displacing every tile col 2+
            // by one screen pixel (B17). Col 0/col 1 weren't affected
            // because they come from the prior scanline's prefetch
            // (cycles 329/337 LOADs).
            //
            // At cycle 1 the LOAD is a no-op: cycle 337 of the prior
            // scanline already loaded col 1's bytes into LOW and no
            // shift has run since, and bgNextTile* still holds col 1's
            // values, so re-loading writes the same bits back. No guard
            // needed.
            //
            // The PREFETCH region (cycles 321-337) is the inverse: there
            // is no render between cycle 329's LOAD and the next render
            // (cycle 1 of the next scanline), so the LOAD-to-render gap
            // is 9 shift cycles wide (329..337 inclusive). LOAD-after-
            // shift gives the right 8-shift count there; LOAD-before-
            // shift would overshoot by one. We therefore handle the
            // prefetch case-0 LOAD AFTER shift, inside the existing
            // fetch switch below.
            if (cycle >= 1 && cycle <= 256) {
                int cycleMod = (cycle - 1) % 8;
                if (cycleMod == 0) {
                    loadBackgroundShifters();
                }
            }

            // Shift registers during visible cycles (1-256) AND during the
            // pre-fetch window (322-337). The pre-fetch shifts move the first
            // tile (col 0 of the upcoming scanline) from the LOW byte of the
            // shifter into the HIGH byte by end of cycle 337.
            if ((cycle >= 1 && cycle <= 256) || (cycle >= 322 && cycle <= 337)) {
                shiftBackgroundRegisters();
            }

            // Fetch tiles in 8-cycle pattern.
            //   Cycles 1-256: fetch tiles for the SAME scanline that's rendering.
            //     Case-0 LOAD here was hoisted above to run before the
            //     per-cycle shift (see B17 comment).
            //   Cycles 321-337: pre-fetch the first 2 tiles of the NEXT scanline.
            //     - Cycle 329 (mod 0) loads col 0 into LOW byte AFTER this
            //       cycle's shift, then cycles 330-337 shift it up into HIGH.
            //     - Cycle 337 (mod 0) loads col 1 into LOW byte. By cycle 1 of
            //       the next scanline, HIGH byte = col 0 (ready to render).
            if ((cycle >= 1 && cycle <= 256) || (cycle >= 321 && cycle <= 337)) {
                int cycleMod = (cycle - 1) % 8;
                switch (cycleMod) {
                    case 0:
                        // Prefetch-only LOAD. The visible-region case-0
                        // LOAD ran above (before shift). Cycle 321 is
                        // outside the shift range (322-337) so cycle 321
                        // LOADs garbage that gets clobbered by cycle 329
                        // before anything renders — harmless.
                        if (cycle >= 321) {
                            loadBackgroundShifters();
                        }
                        break;
                    case 1: fetchNametableByte();       break;
                    case 3: fetchAttributeByte();       break;
                    case 5: fetchPatternLowByte();      break;
                    case 7: fetchPatternHighByte();     break;
                }
            }

            // Load shift registers at cycle 257 (after visible area)
            if (cycle == 257) {
                loadBackgroundShifters();
            }
        }
    }

    /**
     * Writes data to PPU memory via PPUDATA register
     */
    private void writePPUData(byte value) {
        int addr = ppuAddress & 0x3FFF;
        
        if (addr >= 0x3F00 && addr < 0x4000) {
            // Palette RAM range (0x3F00-0x3FFF, mirrored every 32 bytes)
            int paletteAddr = mirrorPaletteAddress(addr);
            paletteRAM[paletteAddr] = value;
        } else {
            // Other addresses go to PPU bus (nametables, pattern tables)
            if (ppuBus != null) {
                ppuBus.write(addr, value);
            }
        }
    }

    /**
     * Reads data from PPU memory via PPUDATA register
     * Palette reads are immediate, other reads are buffered
     */
    private int readPPUData() {
        int addr = ppuAddress & 0x3FFF;
        
        if (addr >= 0x3F00 && addr < 0x4000) {
            // Palette RAM reads are immediate (no buffering)
            int paletteAddr = mirrorPaletteAddress(addr);
            // Update buffer with nametable data "underneath" the palette
            if (ppuBus != null) {
                ppuDataBuffer = (byte) ppuBus.read(addr & 0x2FFF);
            }
            return Byte.toUnsignedInt(paletteRAM[paletteAddr]);
        } else {
            // Non-palette reads are buffered (read returns previous buffer)
            int result = Byte.toUnsignedInt(ppuDataBuffer);
            if (ppuBus != null) {
                ppuDataBuffer = (byte) ppuBus.read(addr);
            }
            return result;
        }
    }

    /**
     * Mirrors palette addresses to the actual 32-byte palette RAM
     * Special mirroring: 0x3F10, 0x3F14, 0x3F18, 0x3F1C mirror to 0x3F00, 0x3F04, 0x3F08, 0x3F0C
     */
    private int mirrorPaletteAddress(int address) {
        int addr = address & 0x1F; // Mirror every 32 bytes (0x3F00-0x3F1F)
        
        // Special case: background color mirrors
        // 0x3F10, 0x3F14, 0x3F18, 0x3F1C -> 0x3F00, 0x3F04, 0x3F08, 0x3F0C
        if (addr >= 0x10 && (addr & 0x03) == 0) {
            addr &= 0x0F;
        }
        
        return addr;
    }

    /**
     * Gets the address increment amount based on PPUCTRL bit 2
     * @return 1 for horizontal increment, 32 for vertical increment
     */
    private int getAddressIncrement() {
        // PPUCTRL bit 2: 0 = add 1, 1 = add 32
        return ((registers[0] & 0x04) != 0) ? 32 : 1;
    }

    /**
     * Gets a palette color index for debugging
     * @param paletteIndex 0-31
     * @return color index (0-63)
     */
    public int getPaletteColor(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= 32) {
            return 0;
        }
        return Byte.toUnsignedInt(paletteRAM[paletteIndex]);
    }

    /**
     * Resets PPU state
     */
    public void reset() {
        for (int i = 0; i < registers.length; i++) {
            registers[i] = 0;
        }
        ppuAddress = 0;
        addressLatch = false;
        ppuDataBuffer = 0;
        scrollX = 0;
        scrollY = 0;
        scanline = 0;
        cycle = 0;
        frameComplete = false;
        oddFrame = false;
        spriteZeroHitChecked = false;
        nmiPending = false;
        clearBgPatternShadow();
        clearScreen();
    }

    /**
     * Per-pixel sprite-0 hit detection. Sets PPUSTATUS bit 6 the first cycle
     * an opaque sprite-0 pixel coincides with an opaque background pixel,
     * provided both rendering layers are enabled and the leftmost-8 / x=255
     * gating rules are honored.
     *
     * <p>Safe to call every PPU tick unconditionally — fully self-gated.
     * Bit 6 is cleared at the pre-render scanline (261, cycle 1) by the
     * existing reset path.
     */
    private void checkSpriteZeroHit() {
        if (!isVisibleScanline() || cycle < 1 || cycle > 256
                || !isShowBackground() || !isShowSprites()) {
            return;
        }
        if ((registers[2] & 0x40) != 0) return; // already set this frame

        int x = cycle - 1;
        if (x == 255) return; // NESdev: bit 6 never sets at x=255

        int oamY = Byte.toUnsignedInt(oam[0]);
        int oamX = Byte.toUnsignedInt(oam[3]);
        int spriteTop = oamY + 1;
        int spriteHeight = getSpriteSize();
        int spriteBottom = spriteTop + spriteHeight;
        if (scanline < spriteTop || scanline >= spriteBottom) return;
        if (x < oamX || x >= oamX + 8) return;

        // Leftmost-8 mask: needs BOTH bg-show-left-8 (bit 1) AND sprite-show-left-8 (bit 2).
        if (x < 8) {
            int mask = Byte.toUnsignedInt(registers[1]);
            if ((mask & 0x02) == 0 || (mask & 0x04) == 0) return;
        }

        if (bgPatternPixel[scanline][x] == 0) return; // background transparent here

        int tileId = Byte.toUnsignedInt(oam[1]);
        int attr   = Byte.toUnsignedInt(oam[2]);
        boolean hflip = (attr & 0x40) != 0;
        boolean vflip = (attr & 0x80) != 0;

        int rowInSprite = scanline - spriteTop;
        if (vflip) rowInSprite = spriteHeight - 1 - rowInSprite;
        int colInSprite = x - oamX;
        int colInTile   = hflip ? colInSprite : (7 - colInSprite);

        int patternTableBase;
        int tileForRow;
        int rowInTile;
        if (spriteHeight == 16) {
            patternTableBase = (tileId & 0x01) != 0 ? 0x1000 : 0x0000;
            int topTile = tileId & 0xFE;
            if (rowInSprite >= 8) { tileForRow = topTile + 1; rowInTile = rowInSprite - 8; }
            else                  { tileForRow = topTile;     rowInTile = rowInSprite;     }
        } else {
            patternTableBase = getSpritePatternTableAddress();
            tileForRow = tileId;
            rowInTile = rowInSprite;
        }

        if (ppuBus == null) return;
        int patternAddr = patternTableBase + tileForRow * 16 + rowInTile;
        int patternLow  = ppuBus.read(patternAddr);
        int patternHigh = ppuBus.read(patternAddr + 8);
        int p0 = (patternLow  >> colInTile) & 0x01;
        int p1 = (patternHigh >> colInTile) & 0x01;
        int spritePixel = (p1 << 1) | p0;
        if (spritePixel == 0) return; // sprite transparent here

        registers[2] |= 0x40; // sprite-0 hit
        spriteZeroHitChecked = true; // skip the call for the rest of this frame
    }

    /**
     * Zero the bg-pattern shadow that the sprite renderer reads for
     * priority decisions. Called from {@link #reset()} and from the
     * pre-render scanline at the end of every frame.
     */
    private void clearBgPatternShadow() {
        for (int y = 0; y < VISIBLE_HEIGHT; y++) {
            java.util.Arrays.fill(bgPatternPixel[y], 0);
        }
    }

    /**
     * Clears the screen buffer to black
     */
    public void clearScreen() {
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                screen[y][x] = 0xFF000000; // Black with full alpha
            }
        }
    }

    /**
     * Gets the current screen buffer
     * @return screen[y][x] containing RGBA values as integers
     */
    public int[][] getScreen() {
        return screen;
    }

    /**
     * Gets only the visible portion of the screen (256x240)
     * @return screen[y][x] containing RGBA values for visible area
     */
    public int[][] getVisibleScreen() {
        int[][] visible = new int[VISIBLE_HEIGHT][VISIBLE_WIDTH];
        for (int y = 0; y < VISIBLE_HEIGHT; y++) {
            System.arraycopy(screen[y], 0, visible[y], 0, VISIBLE_WIDTH);
        }
        return visible;
    }

    /**
     * Gets the full screen buffer as a 2D array compatible with PixelRenderer
     * Format: pixels[y][x] = RGBA
     * @return 2D array of size [SCREEN_HEIGHT][SCREEN_WIDTH] (262 x 341)
     */
    public int[][] getScreenPixels2D() {
        int[][] pixels = new int[SCREEN_HEIGHT][SCREEN_WIDTH];
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            System.arraycopy(screen[y], 0, pixels[y], 0, SCREEN_WIDTH);
        }
        return pixels;
    }

    /**
     * Gets only the visible screen as a 2D array compatible with PixelRenderer
     * Format: pixels[y][x] = RGBA
     * @return 2D array of size [VISIBLE_HEIGHT][VISIBLE_WIDTH] (240 x 256)
     */
    public int[][] getVisibleScreenPixels2D() {
        int[][] pixels = new int[VISIBLE_HEIGHT][VISIBLE_WIDTH];
        for (int y = 0; y < VISIBLE_HEIGHT; y++) {
            System.arraycopy(screen[y], 0, pixels[y], 0, VISIBLE_WIDTH);
        }
        return pixels;
    }

    /**
     * Gets the full screen buffer as a 1D array compatible with PixelRenderer
     * Format: pixels[y * SCREEN_WIDTH + x] = RGBA
     * @return 1D array of size SCREEN_WIDTH * SCREEN_HEIGHT (341 * 262)
     */
    public int[] getScreenPixels1D() {
        int[] pixels = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                pixels[y * SCREEN_WIDTH + x] = screen[y][x];
            }
        }
        return pixels;
    }

    /**
     * Gets only the visible screen as a 1D array compatible with PixelRenderer
     * Format: pixels[y * VISIBLE_WIDTH + x] = RGBA
     * @return 1D array of size VISIBLE_WIDTH * VISIBLE_HEIGHT (256 * 240)
     */
    public int[] getVisibleScreenPixels1D() {
        int[] pixels = new int[VISIBLE_WIDTH * VISIBLE_HEIGHT];
        for (int y = 0; y < VISIBLE_HEIGHT; y++) {
            for (int x = 0; x < VISIBLE_WIDTH; x++) {
                pixels[y * VISIBLE_WIDTH + x] = screen[y][x];
            }
        }
        return pixels;
    }

    /**
     * Sets a pixel in the screen buffer
     * @param x horizontal position (0-340)
     * @param y vertical position (0-261)
     * @param rgba color value as RGBA8888 integer
     */
    public void setPixel(int x, int y, int rgba) {
        if (x >= 0 && x < SCREEN_WIDTH && y >= 0 && y < SCREEN_HEIGHT) {
            screen[y][x] = rgba;
        }
    }

    /**
     * Gets current scanline number
     * @return current scanline (0-261)
     */
    public int getScanline() {
        return scanline;
    }

    /**
     * Gets current cycle within scanline
     * @return current cycle (0-340)
     */
    public int getCycle() {
        return cycle;
    }
    
    /**
     * Checks if a frame is complete.
     * @return true if frame just completed, false otherwise
     */
    public boolean isFrameComplete() {
        return frameComplete;
    }
    
    /**
     * Clears the frame complete flag (should be called after checking)
     */
    public void clearFrameComplete() {
        frameComplete = false;
    }
    
    /**
     * Checks if current frame is odd
     * @return true for odd frames, false for even frames
     */
    public boolean isOddFrame() {
        return oddFrame;
    }
    
    /**
     * Current horizontal scroll value, set by writes to {@code $2005}.
     * Step 7 — used by the per-cycle nametable/attribute fetcher to
     * shift tile lookups by {@code scrollX} pixels.
     */
    public int getScrollX() {
        return scrollX;
    }

    /**
     * Current vertical scroll value, set by writes to {@code $2005}.
     * Step 7 — used by the per-cycle nametable/attribute fetcher to
     * shift tile lookups by {@code scrollY} pixels.
     */
    public int getScrollY() {
        return scrollY;
    }

    /**
     * Non-destructive read of the NMI latch.
     * @return true if the PPU latched an NMI that hasn't been consumed yet
     */
    public boolean peekNmi() {
        return nmiPending;
    }

    /**
     * Non-destructive read of PPUCTRL ({@code $2000}). Unlike the CPU bus
     * path, this does NOT have side effects — intended for debug renderers,
     * diagnostics, and the host's introspection. Always returns the raw
     * 8-bit value (PPUCTRL is write-only on the CPU bus, so this is the
     * only way to read what was last written).
     *
     * @return PPUCTRL bits 0..7 as an unsigned int (0..255)
     */
    public int peekCtrl() {
        return Byte.toUnsignedInt(registers[0]);
    }

    /**
     * Non-destructive read of PPUMASK ({@code $2001}). PPUMASK is
     * write-only on the CPU bus; this is the introspection path.
     *
     * @return PPUMASK bits 0..7 as an unsigned int (0..255)
     */
    public int peekMask() {
        return Byte.toUnsignedInt(registers[1]);
    }

    /**
     * Non-destructive read of PPUSTATUS ({@code $2002}). Unlike a CPU read,
     * this does NOT clear bit 7 (VBlank) or reset the address latch.
     * Intended for diagnostics and tests; production CPU code MUST go
     * through {@link #cpuBusRead(int, boolean)}.
     *
     * @return PPUSTATUS bits 0..7 as an unsigned int (0..255)
     */
    public int peekStatus() {
        return Byte.toUnsignedInt(registers[2]);
    }

    /**
     * Test-only seam: write a raw value into the PPU register file by index
     * without going through the CPU bus side-effect machinery. Lets tests
     * preload PPUSTATUS (e.g. forcing a VBlank flag without ticking the
     * clock to scanline 241) or PPUCTRL (e.g. seed an NMI-enable bit
     * cleanly) before exercising a code path.
     *
     * <p>Package-private intentionally — this is NOT production API.
     * Production code paths MUST use {@link #cpuBusRead(int, boolean)} /
     * {@link #cpuBusWrite(int, byte)} so the register side effects
     * (latch reset, VBlank clear, buffered reads, NMI re-trigger) run.
     *
     * @param index 0..7 (PPU has 8 memory-mapped registers)
     * @param value the raw byte to store
     */
    void setRegisterForTest(int index, byte value) {
        registers[index & 0x07] = value;
    }

    /**
     * Test-only seam: read the raw value from the PPU register file by index
     * without triggering CPU bus side effects (no PPUSTATUS VBlank clear,
     * no PPUDATA buffering, no address-increment). Same package-private
     * contract as {@link #setRegisterForTest(int, byte)}: this is NOT a
     * production API.
     *
     * @param index 0..7
     * @return the raw register byte as a Java byte
     */
    byte getRegisterForTest(int index) {
        return registers[index & 0x07];
    }

    /**
     * Current OAMADDR value ({@code $2003}). Used by {@link DmaController}
     * at the start of an OAM DMA burst — per NESdev the DMA copies into OAM
     * starting at this address (with wrap-around at byte 256).
     */
    public int getOamAddr() {
        return Byte.toUnsignedInt(registers[3]);
    }

    /**
     * Write a byte directly into OAM at the given index. Used by
     * {@link DmaController} to land bytes during an OAM DMA burst —
     * production code should NOT poke OAM through this API outside of DMA;
     * use {@code $2003}/{@code $2004} (OAMADDR/OAMDATA) for CPU-driven OAM
     * updates.
     *
     * @param index 0..255 (OAM is 256 bytes); high bits are masked off
     * @param value the byte to store
     */
    public void writeOam(int index, byte value) {
        oam[index & 0xFF] = value;
    }

    /**
     * Read a byte from OAM at the given index. Primarily for tests and
     * debug tooling; CPU code accesses OAM via {@code $2004} (OAMDATA).
     *
     * @param index 0..255 (OAM is 256 bytes); high bits are masked off
     * @return the byte at that OAM index
     */
    public byte readOam(int index) {
        return oam[index & 0xFF];
    }

    /** Package-public access to raw OAM array for the sprite renderer. */
    byte[] oam() {
        return oam;
    }

    /** Package-public access to the PPU bus for CHR reads from the sprite renderer. */
    PPUBus ppuBus() {
        return ppuBus;
    }

    /**
     * Look up the RGB color (0xAARRGGBB) for a palette-RAM index. Public
     * because tests (and host renderers) need to compare against expected
     * colours without re-implementing the palette pipeline.
     *
     * @param paletteRamIndex 0..31; sprite palettes are 0x10..0x1F
     * @return the ARGB color the PPU would write to the framebuffer
     */
    public int getPaletteColorRgb(int paletteRamIndex) {
        return getColorFromPalette(paletteRamIndex);
    }

    /**
     * Read the recorded background pattern value (0..3) at a visible
     * coordinate. Used by the sprite renderer for the priority-bit check.
     *
     * @param y 0..{@link #VISIBLE_HEIGHT}-1
     * @param x 0..{@link #VISIBLE_WIDTH}-1
     * @return 0 if bg pixel is transparent here, else 1..3
     */
    int getBgPatternPixel(int y, int x) {
        return bgPatternPixel[y][x];
    }

    /**
     * Test-only: directly set the bg-pattern shadow value. Lets the sprite
     * priority tests fake an opaque/transparent background pixel without
     * driving the full background pipeline. Package-private intentionally —
     * this is not a production API; the bg pipeline is the only legitimate
     * writer outside of {@link #reset()}.
     *
     * @param y row 0..{@link #VISIBLE_HEIGHT}-1
     * @param x column 0..{@link #VISIBLE_WIDTH}-1
     * @param value 0 (transparent) .. 3
     */
    void setBgPatternPixelForTest(int y, int x, int value) {
        bgPatternPixel[y][x] = value & 0x03;
    }

    /** Test-only / sprite-renderer: write directly to the frame buffer at (x, y). */
    void putScreenPixel(int x, int y, int rgba) {
        if (y >= 0 && y < SCREEN_HEIGHT && x >= 0 && x < SCREEN_WIDTH) {
            screen[y][x] = rgba;
        }
    }

    /**
     * Read AND clear the NMI latch. Returns true exactly once per VBlank
     * entry (when PPUCTRL bit 7 is set); subsequent calls return false until
     * the next rising edge. The host (typically {@code NesSystem}) calls
     * this once per master tick and, on a true return, forwards an NMI to
     * the CPU.
     *
     * @return true if an NMI was pending (and clears the latch); false otherwise
     */
    public boolean consumeNmi() {
        boolean fire = nmiPending;
        nmiPending = false;
        return fire;
    }
    
    // Scanline type helper methods
    
    /**
     * Checks if current scanline is visible (0-239)
     * @return true if scanline is visible
     */
    private boolean isVisibleScanline() {
        return scanline >= 0 && scanline <= 239;
    }
    
    /**
     * Checks if current scanline is post-render (240)
     * @return true if scanline 240
     */
    private boolean isPostRenderScanline() {
        return scanline == 240;
    }
    
    /**
     * Checks if current scanline is in VBlank period (241-260)
     * @return true if in VBlank
     */
    private boolean isVBlankScanline() {
        return scanline >= 241 && scanline <= 260;
    }
    
    /**
     * Checks if current scanline is pre-render (261)
     * @return true if scanline 261
     */
    private boolean isPreRenderScanline() {
        return scanline == 261;
    }
    
    /**
     * Checks if NMI is enabled in PPUCTRL
     * @return true if PPUCTRL bit 7 is set
     */
    private boolean isNMIEnabled() {
        return (registers[0] & 0x80) != 0;
    }

    @Override
    public void connectPPUBus(PPUBus ppuBus) {
        this.ppuBus=ppuBus;
        // Connect memory components now that bus is available
        if (nameTableMemory != null) {
            ppuBus.connect(nameTableMemory);
        }
    }

    @Override
    public PPUBus getPPUBus() {
        return ppuBus;
    }

    @Override
    public int getPPUBusStartAddress() {
        return 0;
    }

    @Override
    public int getPPUBusEndAddress() {
        return 0;
    }

    /**
     * Gets the full CHR layout decoded for debugging purposes
     * @return byte[256][128] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getCHRLayout() {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode CHR layout");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.decodeCHRLayout(chrData);
    }

    /**
     * Gets a specific pattern table decoded for debugging
     * @param table 0 or 1
     * @return byte[128][128] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getPatternTable(int table) {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode pattern table");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.decodePatternTable(chrData, table);
    }

    /**
     * Gets a specific tile decoded
     * @param tileIndex 0-511
     * @return byte[8][8] grid where [y][x] contains 2-bit color (0-3)
     */
    public byte[][] getTile(int tileIndex) {
        if (cartridge == null) {
            log.warn("Cartridge not set, cannot decode tile");
            return null;
        }
        byte[] chrData = cartridge.getCHRROM();
        return TileDecoder.getTile(chrData, tileIndex);
    }

    /**
     * Gets debug string representation of CHR layout
     * @return ASCII visualization of CHR data
     */
    public String getCHRLayoutDebugString() {
        byte[][] layout = getCHRLayout();
        if (layout == null) {
            return "CHR layout unavailable";
        }
        return TileDecoder.pixelsToDebugString(layout);
    }
    
    // ==================== PPUCTRL Helper Methods ====================
    
    /**
     * Gets the base nametable address from PPUCTRL bits 0-1
     * 0 = $2000, 1 = $2400, 2 = $2800, 3 = $2C00
     */
    private int getBaseNametableAddress() {
        int nametableSelect = registers[0] & 0x03;
        return 0x2000 + (nametableSelect * 0x0400);
    }
    
    /**
     * Gets the VRAM address increment from PPUCTRL bit 2
     * 0 = increment by 1 (horizontal), 1 = increment by 32 (vertical)
     */
    private int getVRAMIncrement() {
        return ((registers[0] & 0x04) != 0) ? 32 : 1;
    }
    
    /**
     * Gets the sprite pattern table address from PPUCTRL bit 3
     * 0 = $0000, 1 = $1000
     */
    int getSpritePatternTableAddress() {
        return ((registers[0] & 0x08) != 0) ? 0x1000 : 0x0000;
    }
    
    /**
     * Gets the background pattern table address from PPUCTRL bit 4
     * 0 = $0000, 1 = $1000
     */
    private int getBackgroundPatternTableAddress() {
        return ((registers[0] & 0x10) != 0) ? 0x1000 : 0x0000;
    }
    
    /**
     * Gets the sprite size from PPUCTRL bit 5
     * 0 = 8x8, 1 = 8x16
     */
    int getSpriteSize() {
        return ((registers[0] & 0x20) != 0) ? 16 : 8;
    }
    
    // ==================== PPUMASK Helper Methods ====================
    
    /**
     * Checks if grayscale mode is enabled (PPUMASK bit 0)
     */
    private boolean isGrayscaleEnabled() {
        return (registers[1] & 0x01) != 0;
    }
    
    /**
     * Checks if background rendering in leftmost 8 pixels is enabled (PPUMASK bit 1)
     */
    private boolean isShowBackgroundLeft() {
        return (registers[1] & 0x02) != 0;
    }
    
    /**
     * Checks if sprite rendering in leftmost 8 pixels is enabled (PPUMASK bit 2)
     */
    boolean isShowSpritesLeft() {
        return (registers[1] & 0x04) != 0;
    }
    
    /**
     * Checks if background rendering is enabled (PPUMASK bit 3)
     */
    private boolean isShowBackground() {
        return (registers[1] & 0x08) != 0;
    }
    
    /**
     * Checks if sprite rendering is enabled (PPUMASK bit 4)
     */
    boolean isShowSprites() {
        return (registers[1] & 0x10) != 0;
    }
    
    /**
     * Checks if rendering is enabled (either background or sprites)
     */
    private boolean isRenderingEnabled() {
        return isShowBackground() || isShowSprites();
    }
    
    // ==================== Background Tile Fetching ====================
    
    /**
     * Fetches the nametable byte for the current tile, honouring
     * scrollX/scrollY and the PPUCTRL base-nametable bits, with
     * cross-NT wrap (Step 7).
     *
     * <p>The viewport tile (screenTileX, screenTileY) is derived from
     * the current cycle and scanline. Adding the scroll offset and
     * PPUCTRL's base NT gives a "virtual" tile coordinate; we wrap
     * horizontally at 32 (NT width) by toggling NT bit 0, and vertically
     * at 30 (NT visible height) by toggling NT bit 1. The tile is then
     * fetched from the resolved NT.
     */
    private void fetchNametableByte() {
        ScrolledFetch f = scrolledFetchAddress();
        if (ppuBus != null) {
            bgNextTileId = ppuBus.read(f.ntAddr & 0x3FFF);
        }
    }

    /**
     * Fetches the attribute byte for the current tile. Crucially, the
     * attribute table is read from THE SAME NT as the tile (the
     * cross-NT bugzmanov ch. 7 gotcha) — when scroll wraps a tile into
     * the other NT, its palette comes from that NT's attribute table.
     */
    private void fetchAttributeByte() {
        ScrolledFetch f = scrolledFetchAddress();
        // Attribute table is at NT base + 0x3C0; each attr byte covers a
        // 4x4 tile area (32x32 pixels).
        int attrX = f.tileX / 4;
        int attrY = f.tileY / 4;
        int attrAddr = f.ntBase + 0x3C0 + (attrY * 8) + attrX;
        if (ppuBus != null) {
            int attrByte = ppuBus.read(attrAddr & 0x3FFF);
            // Extract the 2-bit palette for this tile within the 4x4 group.
            int quadX = (f.tileX % 4) / 2;
            int quadY = (f.tileY % 4) / 2;
            int shift = (quadY * 4) + (quadX * 2);
            bgNextTileAttr = (attrByte >> shift) & 0x03;
        }
    }

    /**
     * Mutable scratch object for scrolled-fetch results. Reused across
     * every fetch call to avoid the ~30K allocations/frame that an
     * immutable value object would produce. Single-threaded by
     * construction (PPU.clock is the only caller).
     */
    private static final class ScrolledFetch {
        int ntBase;   // base address of the resolved NT ($2000/$2400/$2800/$2C00)
        int tileX;    // 0..31 within that NT
        int tileY;    // 0..29 within that NT
        int ntAddr;   // computed nametable cell address

        void set(int ntBase, int tileX, int tileY) {
            this.ntBase = ntBase;
            this.tileX = tileX;
            this.tileY = tileY;
            this.ntAddr = ntBase + (tileY * 32) + tileX;
        }
    }

    /** Reusable scratch — see {@link ScrolledFetch} class doc. */
    private final ScrolledFetch fetchScratch = new ScrolledFetch();

    /**
     * Resolve the current (cycle, scanline) + scrollX/scrollY +
     * PPUCTRL base NT into the right NT, tile-X (0..31) and tile-Y
     * (0..29) within that NT, with cross-NT wrap. The returned
     * coordinate is what the background fetcher reads from this cycle.
     *
     * <p>Returns the shared {@link #fetchScratch} object — caller MUST
     * read fields immediately and not retain a reference.
     */
    private ScrolledFetch scrolledFetchAddress() {
        // Tile being fetched depends on the cycle phase:
        //   - Visible fetch (cycles 1..256): we're rendering tile col K right now,
        //     and the fetcher is preparing tile col K+2 (pipeline lookahead = 2 tiles).
        //     At cycle 1 the renderer is reading col 0 from the shifter (loaded by
        //     the previous scanline's prefetch), so the fetcher reads col 2.
        //   - Prefetch (cycles 321..337): we're done rendering the current scanline
        //     and prefetching cols 0 and 1 of the NEXT scanline into the shifter.
        int screenTileX;
        if (cycle >= 321 && cycle <= 337) {
            screenTileX = (cycle - 321) / 8;             // 0 or 1
        } else {
            screenTileX = (cycle - 1) / 8 + 2;           // +2 tile lookahead
        }

        // Add scroll. Horizontal: tile-granular is exact — screenTileX*8 is
        // 8-aligned, so (screenTileX*8 + scrollX) >> 3 == screenTileX +
        // (scrollX >> 3); fine X is applied at the shifter mux.
        // Vertical: the sum MUST happen in pixel space before dividing —
        // when (scanline%8 + scrollY%8) >= 8 the carry advances the tile
        // row. Splitting the division (scanline/8 + scrollY/8) drops that
        // carry, re-fetching the previous tile row with a wrapped fine-Y
        // for the bottom rows of every 8-pixel band: each tile row renders
        // once correctly and then again garbled one band below whenever
        // scrollY % 8 != 0 (the Micro Mages title-text doubling).
        int virtTileX = screenTileX + (scrollX >> 3);
        int virtTileY = (fetchScanline() + scrollY) >> 3;

        // PPUCTRL bits 0+1 select the base NT (0..3). We use this as the
        // starting NT, then wrap horizontally (32 cols) and vertically
        // (30 visible rows) toggling NT bits accordingly. Use modulo +
        // parity so any outlier (scroll values combined with large
        // screen-tile positions) wraps correctly, not just the [32, 63]
        // / [30, 59] ranges that arise during normal play.
        int baseNtSelect = registers[0] & 0x03;
        int ntH = baseNtSelect & 1;
        int ntV = (baseNtSelect >> 1) & 1;

        int hWraps = virtTileX / 32;
        virtTileX = virtTileX - (hWraps * 32);
        ntH ^= (hWraps & 1);

        // Vertical NT is 30 rows visible (rows 0..29); rows 30..31 are
        // attribute-table memory and should never be addressed as tiles.
        int vWraps = virtTileY / 30;
        virtTileY = virtTileY - (vWraps * 30);
        ntV ^= (vWraps & 1);

        int ntBase = 0x2000 + (ntV << 11) + (ntH << 10);
        fetchScratch.set(ntBase, virtTileX, virtTileY);
        return fetchScratch;
    }
    
    /**
     * Fetches the low byte of the pattern table for the current tile.
     * Step 7: fineY combines scanline + scrollY so the fetched row is
     * shifted by sub-tile vertical scroll.
     */
    private void fetchPatternLowByte() {
        int patternTableBase = getBackgroundPatternTableAddress();
        int fineY = (fetchScanline() + scrollY) & 0x07;
        // Pattern table address = base + (tile_id * 16) + fine_y
        int addr = patternTableBase + (bgNextTileId * 16) + fineY;
        if (ppuBus != null) {
            bgNextTilePatternLow = ppuBus.read(addr & 0x3FFF);
        }
    }

    /**
     * Fetches the high byte of the pattern table for the current tile.
     * Step 7: fineY combines scanline + scrollY so the fetched row is
     * shifted by sub-tile vertical scroll.
     */
    private void fetchPatternHighByte() {
        int patternTableBase = getBackgroundPatternTableAddress();
        int fineY = (fetchScanline() + scrollY) & 0x07;
        // Pattern table high byte is 8 bytes after low byte
        int addr = patternTableBase + (bgNextTileId * 16) + fineY + 8;
        if (ppuBus != null) {
            bgNextTilePatternHigh = ppuBus.read(addr & 0x3FFF);
        }
    }

    /**
     * The scanline we're currently fetching FOR — same as {@link #scanline}
     * during visible cycles (1..256), or {@code scanline + 1} during the
     * prefetch window (cycles 321..337), since prefetch loads tiles for the
     * NEXT scanline. Wraps 261 → 0 on the frame boundary.
     */
    private int fetchScanline() {
        if (cycle >= 321 && cycle <= 337) {
            return (scanline + 1) % 262;
        }
        return scanline;
    }
    
    /**
     * Loads the fetched tile data into the background shift registers
     * Called every 8 cycles to reload the shifters with the next tile
     */
    private void loadBackgroundShifters() {
        // Load lower 8 bits of shift registers with next tile data
        bgShiftPatternLow = (bgShiftPatternLow & 0xFF00) | bgNextTilePatternLow;
        bgShiftPatternHigh = (bgShiftPatternHigh & 0xFF00) | bgNextTilePatternHigh;
        
        // Expand attribute bits to all 8 pixels of the tile
        int attrLowBit = (bgNextTileAttr & 0x01) != 0 ? 0xFF : 0x00;
        int attrHighBit = (bgNextTileAttr & 0x02) != 0 ? 0xFF : 0x00;
        
        bgShiftAttrLow = (bgShiftAttrLow & 0xFF00) | attrLowBit;
        bgShiftAttrHigh = (bgShiftAttrHigh & 0xFF00) | attrHighBit;
    }
    
    /**
     * Shifts all background registers by one pixel
     * Called every cycle during visible rendering
     */
    private void shiftBackgroundRegisters() {
        // Inlined isShowBackground() — PPUMASK bit 3 — hot path on the web build.
        if ((registers[1] & 0x08) != 0) {
            bgShiftPatternLow <<= 1;
            bgShiftPatternHigh <<= 1;
            bgShiftAttrLow <<= 1;
            bgShiftAttrHigh <<= 1;
        }
    }
    
    /**
     * Renders a single background pixel to the screen buffer
     * Called during visible scanlines at cycles 1-256
     */
    private void renderBackgroundPixel() {
        int xPos = cycle - 1;
        // Hide BG when:
        //   - PPUMASK bit 3 (show background) is 0, OR
        //   - we're in the leftmost 8 pixels AND PPUMASK bit 1 (BG-show-left-8) is 0.
        // Real hardware fills these cells with the backdrop color; failing to honor
        // bit 1 leaves stale shift-register garbage visible in column 0..7 — the
        // "leftmost-column artifacts" that most games (DK included) hide.
        // Inlined isShowBackground (bit 3) + isShowBackgroundLeft (bit 1) —
        // called per visible pixel (61k/frame) on the web build hot path.
        final int mask = registers[1] & 0xff;
        if ((mask & 0x08) == 0 || (xPos < 8 && (mask & 0x02) == 0)) {
            int backdropColor = getColorFromPalette(0);
            screen[scanline][xPos] = backdropColor;
            // BG is transparent here for sprite-priority purposes.
            if (scanline < VISIBLE_HEIGHT && xPos < VISIBLE_WIDTH) {
                bgPatternPixel[scanline][xPos] = 0;
            }
            return;
        }
        
        // Extract 2-bit pattern pixel from shift registers using fine X.
        // Step 7: fine X is the low 3 bits of scrollX (sub-tile shift).
        int muxBit = 15 - (scrollX & 0x07);
        int patternBit0 = (bgShiftPatternLow >> muxBit) & 0x01;
        int patternBit1 = (bgShiftPatternHigh >> muxBit) & 0x01;
        int patternPixel = (patternBit1 << 1) | patternBit0;
        
        // Extract 2-bit palette from attribute shift registers
        int paletteBit0 = (bgShiftAttrLow >> muxBit) & 0x01;
        int paletteBit1 = (bgShiftAttrHigh >> muxBit) & 0x01;
        int paletteIndex = (paletteBit1 << 1) | paletteBit0;
        
        // Calculate final palette RAM index (background palettes are 0x00-0x0F)
        // Formula: (palette << 2) | pixel
        int paletteRamIndex;
        if (patternPixel == 0) {
            // Transparent pixel - use backdrop color (palette index 0)
            paletteRamIndex = 0;
        } else {
            paletteRamIndex = (paletteIndex << 2) | patternPixel;
        }
        
        // Look up RGB color from palette RAM and color palette
        int color = getColorFromPalette(paletteRamIndex);

        // Write to screen buffer (cycle - 1 because cycle is 1-256 but screen is 0-255)
        int x = cycle - 1;
        screen[scanline][x] = color;
        // Record the pattern value for sprite-priority compositing (Step 5).
        if (scanline < VISIBLE_HEIGHT && x < VISIBLE_WIDTH) {
            bgPatternPixel[scanline][x] = patternPixel;
        }
    }
    
    /**
     * Gets an RGB color from the palette RAM using the color palette
     * @param paletteIndex Index into palette RAM (0-31)
     * @return ARGB color as 32-bit integer (0xAARRGGBB)
     */
    private int getColorFromPalette(int paletteIndex) {
        // Read color index from palette RAM
        int colorIndex = Byte.toUnsignedInt(paletteRAM[paletteIndex & 0x1F]);
        colorIndex &= 0x3F;  // Mask to 0-63 range
        
        // Convert to RGB using color palette
        // NES color palette indices map to specific RGB values
        return nesColorToRGB(colorIndex);
    }
    
    /**
     * Converts NES color index (0-63) to RGB color
     * @param nesColor NES color palette index (0-63)
     * @return ARGB color as 32-bit integer (0xAARRGGBB)
     */
    /**
     * NES NTSC master palette used by this PPU's pixel output. Held as a
     * {@code static final} so {@link #nesColorToRGB} is allocation-free —
     * before this hoist the array was re-allocated on every per-pixel call,
     * which dominated the web-build profile (~58% of CPU time at 256x240).
     *
     * <p>Distinct from {@code NesMasterPalette.ARGB} (used by debug
     * renderers) — the values differ slightly; do not unify without
     * re-baselining every PPU rendering test.
     */
    private static final int[] NES_COLOR_PALETTE = {
        0xFF545454, 0xFF001E74, 0xFF081090, 0xFF300088, 0xFF440064, 0xFF5C0030, 0xFF540400, 0xFF3C1800,
        0xFF202A00, 0xFF083A00, 0xFF004000, 0xFF003C00, 0xFF00323C, 0xFF000000, 0xFF000000, 0xFF000000,
        0xFF989698, 0xFF084CC4, 0xFF3032EC, 0xFF5C1EE4, 0xFF8814B0, 0xFFA01464, 0xFF982220, 0xFF783C00,
        0xFF545A00, 0xFF287200, 0xFF087C00, 0xFF007628, 0xFF006678, 0xFF000000, 0xFF000000, 0xFF000000,
        0xFFECEEEC, 0xFF4C9AEC, 0xFF787CEC, 0xFFB062EC, 0xFFE454EC, 0xFFEC58B4, 0xFFEC6A64, 0xFFD48820,
        0xFFA0AA00, 0xFF74C400, 0xFF4CD020, 0xFF38CC6C, 0xFF38B4CC, 0xFF3C3C3C, 0xFF000000, 0xFF000000,
        0xFFECEEEC, 0xFFA8CCEC, 0xFFBCBCEC, 0xFFD4B2EC, 0xFFECAEEC, 0xFFECAED4, 0xFFECB4B0, 0xFFE4C490,
        0xFFCCD278, 0xFFB4DE78, 0xFFA8E290, 0xFF98E2B4, 0xFFA0D6E4, 0xFFA0A2A0, 0xFF000000, 0xFF000000
    };

    private int nesColorToRGB(int nesColor) {
        return NES_COLOR_PALETTE[nesColor & 0x3F];
    }
    
    // ==================== Getters for Testing ====================
    
    /**
     * Gets the background pattern low shift register value (for testing)
     */
    public int getBgShiftPatternLow() {
        return bgShiftPatternLow & 0xFFFF;
    }
    
    /**
     * Gets the background pattern high shift register value (for testing)
     */
    public int getBgShiftPatternHigh() {
        return bgShiftPatternHigh & 0xFFFF;
    }
    
    /**
     * Gets the next tile ID latch value (for testing)
     */
    public int getBgNextTileId() {
        return bgNextTileId & 0xFF;
    }
    
    /**
     * Gets the next tile attribute latch value (for testing)
     */
    public int getBgNextTileAttr() {
        return bgNextTileAttr & 0x03;
    }

}
