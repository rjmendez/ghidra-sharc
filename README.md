# SHARC

SLEIGH specification, analyzers and boot-stream loader for the Analog Devices
ADSP-214xx SHARC (ADSP-21467/21469, 21477-21479, 21483-21489): the 48-bit
SHARC instruction set (ISA) and the Variable Instruction Set Architecture
(VISA) of 16-, 32- and 48-bit instructions.  Language `SHARC:BE:32:214xx`.

References: *SHARC Processor Programming Reference* (ADSP-2136x/2137x/214xx,
rev 2.2) for the instruction set, *VisualDSP++ 5.0 Loader and Utilities
Manual* (rev 2.5) for the boot stream, and the ADSP-214xx datasheets for the
memory map.

## Model

* **Code space.** One code space, `sw`, whose addressable unit is a 16-bit
  short word.  VISA code sits at its short-word (SW) address.  48-bit ISA code
  is placed at the SW *alias* of its 48-bit address -- the three short words
  holding it in the same SRAM column, `SW = 3*A - (A & 0xe0000)` for internal
  blocks 0-3 (block 0: `0x8c000 -> 0x124000`) -- most significant parcel
  first, so a program holds both kinds of code exactly as they overlap in
  memory.  The context bit `visa` selects the decoder; the interrupt vector
  table (`0x8c000-0x8c0ff`, `sw:124000-sw:1242ff`) is 48-bit code and its
  vectors are entry points (`sharc214xx.pspec`).  Other 48-bit ranges are
  marked (`visa` = 0) by the boot-stream loader below, or by whoever loads the
  image.
* **Data space.** `dm` has 32-bit units (normal-word addresses): internal
  memory, external memory and the IOP registers (volatile).  PM data accesses
  use the same space.
* **Branch targets.** Absolute and PC-relative targets are mapped the same
  way: an address in `0x80000-0xfffff` is a 48-bit address and goes to its SW
  alias.  VISA code reaches 48-bit stubs with PC-relative jumps whose offset
  crosses the address spaces; these land on the stub.  Computed targets in ISA
  code (where code pointers are 48-bit addresses) are mapped in p-code.
* **Parallel instructions.** A compute and its data moves read their sources
  before anything is written (`R8 = R8 + R1, DM(I0,M1) = R8` stores the old
  R8).  Conditional instructions exist once per condition and once for TRUE,
  so unconditional code carries no dead branches.
* **Flags.** The ALU sets AZ/AN, the multiplier MN (condition MS), the shifter
  SZ; BIT TST/XOR set BTF.  `NOT LCE` is `CURLCNTR != 1`.
* **B registers.** Loading `Bn` also loads `In` with the same value
  (Programming Reference, "Circular Buffering Mode"), for every ureg load:
  register moves, memory loads and immediates.  A companion operand over the
  ureg field names `In` for the B codes and the scratch register `BIX`, which
  nothing reads, for all others.
* **Arithmetic.** Fixed and floating ALU, integer multiply exact, fractional
  multiply as 1.31, shifts and immediate bit-field extract/deposit exact.
  CLIP, SCALB, MANT, LOGB, RECIPS, RSQRTS, COPYSIGN, register-count FEXT/FDEP,
  MR accumulator operations and saturation are user operations.

## Hardware loops

`DO <end> UNTIL <term>` marks its loop's last instruction with context
(`lend`, `lterm`, `ltop`); the root table adds the loop-back there, so the
decompiler sees an ordinary loop:

* `UNTIL LCE`: after the last instruction `CURLCNTR` counts down and the loop
  repeats while it is not zero (a count of 0 runs 2^32 times, as on the part);
* `UNTIL FOREVER`: always repeats;
* `UNTIL <condition>`: repeats while the condition is false, tested after the
  last instruction (the hardware tests it in the pipeline; the result is the
  same for loops that obey the manual's end-of-loop restrictions).

The loop-counter stack is six registers `LCSTK0-5`; DO and `PUSH LOOP` push
`CURLCNTR`, the end of the loop, `POP LOOP` and `JUMP (LA)` pop it, so nested
loops restore their counts and the bookkeeping folds away.

In VISA code the last four instructions of a loop are always 48-bit
instructions (Programming Reference, "VISA-Related Restrictions on Hardware
Loops"), so the end address is always an instruction start.  The **SHARC Loop
End** analyzer re-marks a loop end that was disassembled before its DO (the
`globalset` in the DO only affects later disassembly).

## Delayed branches

A `(DB)` branch executes the next two instructions.  Ghidra collects
delay-slot instructions until their lengths reach a byte count, and VISA
instructions are 2, 4 or 6 bytes long, so no single count gives two
instructions: it must be one more than the length of the first slot.  The
`DS` subtable takes that length from the context field `dslot` of the branch
(0 = 2 bytes, the default and the common case; 1 = 4; 2 = 6).  The **SHARC
Delay Slot** analyzer sets `dslot` where the first slot is longer and
re-disassembles the branch.  ISA delay slots are always 12 bytes.  The branch
condition is evaluated before the slots, and the not-taken path continues
after them.

## C run-time conventions (VisualDSP++ / CrossCore)

* `CJUMP` / `RFRAME` frame handling; `JUMP (M14, I12) (DB)` is the return.
* The compiler also calls through ordinary delayed jumps, pushing the return
  address in a delay slot (`DM(I7,M7) = PC`, or in VISA code the constant
  return address - 1).  The **SHARC Call Idiom** analyzer marks these jumps
  (direct and `JUMP (M13, Ix) (DB)`) as calls.
* The compiler spec gives the argument registers (R4, R8, R12), the result
  (R0), the callee-preserved set, and tracks the constant modifier registers
  (M5 = M13 = 0, M6 = M14 = 1, M7 = M15 = -1) at every function entry.
* The **SHARC Function Pointer Arguments** analyzer creates functions at code
  addresses that constant propagation finds passed as call arguments, e.g. an
  interrupt handler given to the run time's `interrupt()`.

## Boot streams

The **ADI SHARC Boot Stream (LDR)** loader imports a loader file of
tag/count/address blocks, with or without the 256-instruction boot kernel in
front: FINAL_INIT, ZERO_LDATA, ZERO_L48, INIT_L16, INIT_L32, INIT_L48,
INIT_L64, ZERO/INIT_EXT8/16 and compressed (tag `0x2000`, zlib) blocks, which
are inflated and replayed in place.  The stream is replayed into a model of
the internal SRAM blocks and external memory, so later blocks overwrite
earlier ones as on the part, and the memory at hand-over is presented as each
block's short-word view in `sw` (48-bit code at its alias, with `visa`
cleared) and normal-word view in `dm`.  The IVT's vectors become entry
points.  MULTI_PROC streams are not supported.

## Known limitations

* SIMD (PEy), circular buffering (`L`/`B` registers), bit-reversed
  addressing and long-word (`LW`) moves are not modelled: data address
  generators update linearly and moves are 32-bit.
* Not modelled against the Programming Reference, found by executing single
  instructions in the p-code emulator against an independent interpreter: AC,
  AV, MV and SV are not set (conditions on them read whatever the register
  holds); the multiplier half of a multifunction instruction does not set MS;
  denormal float results are kept rather than flushed to zero; FIX/TRUNC of a
  value outside the int32 range gives the p-code `trunc` result, not all 1s or
  (ALUSAT) the saturated value.  None of these changes an instruction's
  register data flow.
* The status and PC stacks (`PUSH/POP STS`, `PCSTK`), cache control, IDLE and
  RTI's status restore are user operations.
* Delay slots are correct only after the SHARC Delay Slot analyzer has run;
  disassembly alone gives one slot when the first is 32 or 48 bits long.
* A loop whose end lies before its DO (the ADI boot kernel's FINAL_INIT
  sequence) is not modelled: the DO's mark conflicts with the existing
  decoding of the reset vector, and Ghidra reports inconsistent context there.
* Code pointers held as 48-bit addresses in data or registers (ISA programs)
  are not mapped to the code space by Ghidra's pointer and reference
  analysis; functions reached only through them need a manual entry point.
  Where such a pointer is not constant the decompiler shows the mapping
  expression of the indirect call.
* The IVT entry points and the memory map are those of the 5-Mbit
  ADSP-2146x/2147x/2148x.

## Regenerating

`sharc214xx.sinc` is generated from the opcode tables in `tools/sharc_isa.py`:

    python3 tools/gen_sinc.py

`sharc214xx.slaspec` is written by hand: spaces, registers, context, macros,
the `DS`, `LoopTop` and `ISA` subtables and the root table.
