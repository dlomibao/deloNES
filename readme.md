# deloNES
a Java and LibGDX NES Emulator POC


## Devlog
### 5-09-2024
- setup base project
- got libgdx running
- got text to display on screen
- setup basic cpuBus class
- setup basic cpu class
- converted instruction timings and data into csv
- setup registers
### 5-10-2024
- loaded instructions into cpu from csv
### 5-12-2024
- setup Ram and basic cpuBus read/write interaction
### 5-13-2024
- finished code for cpu addressing modes
### 5-14-2024
- added code for around half the instructions
### 5-15-2024
- finally added to github
- completed instructions (untested)
- created basic way to load test programs
- First successful program output!
- takes 10 multiplies by 3 and puts 30 in memory
``` 
16:13:12.104 [main] INFO  net.lomibao.nes.NesEmulator - memory 0x0000
16:13:12.104 [main] INFO  net.lomibao.nes.NesEmulator - 0A 03 1E 00 00 00 00 00 00 00 00 00 00 00 00 00 // (10) (3) (30) 

16:13:12.105 [main] INFO  net.lomibao.nes.NesEmulator - memory 0x8000
16:13:12.105 [main] INFO  net.lomibao.nes.NesEmulator - A2 0A 8E 00 00 A2 03 8E 01 00 AC 00 00 A9 00 18 //the program code 
6D 01 00 88 D0 FA 8D 02 00 EA EA EA 00 00 00 00 
```
### 5-16-2024
- added basic mapper interface, started work n loading the cartridge

### 5-29-2024
- cartridge setup work
### 6-2-2024
- ppu work
- load color palettes