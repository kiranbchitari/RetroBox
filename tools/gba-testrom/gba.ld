ENTRY(_start)
MEMORY { rom (rx) : ORIGIN = 0x08000000, LENGTH = 32M }
SECTIONS {
    .text : { *(.text.start) *(.text .text.*) *(.rodata .rodata.*) } > rom
    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) *(.data .data.*) *(.bss .bss.*) }
}
