# Sources and licensing

This extension is licensed under the Apache License, Version 2.0 (see
`LICENSE`), the licence of Ghidra itself.  It was written from public
documentation; no code from other disassemblers is included.

## Documentation

* *SHARC Processor Programming Reference* (ADSP-2136x/2137x/214xx, rev 2.2),
  Analog Devices: the instruction encodings and opcode tables transcribed into
  `tools/sharc_isa.py` (Table 10-2, chapters 10 and 12), instruction
  semantics, VISA and hardware-loop rules, the interrupt vector table.
* *VisualDSP++ 5.0 Loader and Utilities Manual* (rev 2.5), Analog Devices: the
  boot-stream block format read by the LDR loader.
* ADSP-214xx data sheets: the internal memory map.

No manual, or text extracted from one, is redistributed here.

## Cross-checks

Decodes were compared with an independently written Python decoder over real
SHARC firmware; neither that decoder nor any firmware is included.
