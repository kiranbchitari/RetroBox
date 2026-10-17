@ Original (CC0) startup code for the RetroBox GBA test ROM. The 192-byte header is filled in by build.sh.
    .section .text.start, "ax"
    .arm
    .global _start
_start:
    b       start_main          @ 0x00: entry point (the 0xEA opcode byte at offset 3 is part of the header format)
    .space  188                 @ 0x04..0xBF: header, patched after linking
start_main:
    mov     r0, #0x1F           @ system mode, IRQ/FIQ masked off
    msr     cpsr_c, r0
    ldr     sp, =0x03007F00
    bl      main
1:  b       1b
    .ltorg
