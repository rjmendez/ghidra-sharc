/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Disassembles, slot by slot, the 48-bit ranges the loader marked {@code visa = 0}.
 * <p>
 * Flow-following disassembly only reaches code that is called or jumped to directly; code
 * reached through function-pointer tables stays undisassembled.  This sweep disassembles
 * every 3-short-word slot that is not already code or defined data, skips slots that do not
 * decode, and then creates functions at the call targets it found.  Program-memory data in
 * such a range can decode as instructions, so the analyzer can be switched off per image.
 */
public class SharcCodeSweepAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "SHARC Code Sweep";
	private static final String DESCRIPTION =
		"Linearly disassembles the 48-bit ranges a boot stream loaded (visa = 0) and creates " +
			"functions at call targets.";

	public SharcCodeSweepAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setPriority(AnalysisPriority.DISASSEMBLY.after());
		setDefaultEnablement(true);
		setSupportsOneTimeAnalysis();
	}

	@Override
	public boolean canAnalyze(Program program) {
		return "SHARC".equals(program.getLanguage().getProcessor().toString());
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor,
			MessageLog log) throws CancelledException {
		Register visa = program.getRegister("visa");
		if (visa == null) {
			return false;
		}
		Listing listing = program.getListing();
		Memory mem = program.getMemory();
		List<AddressRange> ranges = new ArrayList<>();
		AddressRangeIterator it = program.getProgramContext().getRegisterValueAddressRanges(visa);
		while (it.hasNext()) {
			AddressRange r = it.next();
			RegisterValue rv = program.getProgramContext().getRegisterValue(visa, r.getMinAddress());
			if (rv != null && rv.hasValue() && rv.getUnsignedValue().intValue() == 0) {
				ranges.add(r);
			}
		}
		long found = 0, skipped = 0;
		for (AddressRange r : ranges) {
			Address a = r.getMinAddress();
			while (a != null && a.compareTo(r.getMaxAddress()) <= 0) {
				monitor.checkCancelled();
				MemoryBlock b = mem.getBlock(a);
				if (b == null || !b.isInitialized() || !set.contains(a)) {
					a = a.next();
					continue;
				}
				Instruction ins = listing.getInstructionAt(a);
				if (ins == null) {
					Data d = listing.getDefinedDataContaining(a);
					if (d != null) {
						a = d.getMaxAddress().next();
						continue;
					}
					new DisassembleCommand(a, null, false).applyTo(program, monitor);
					ins = listing.getInstructionAt(a);
					if (ins != null) {
						found++;
					}
				}
				if (ins != null) {
					a = ins.getMaxAddress().next();
				}
				else {
					skipped++;
					a = a.add(3);          // one 48-bit slot is three short words
				}
			}
		}
		// functions at call targets
		ReferenceManager refs = program.getReferenceManager();
		Set<Address> targets = new HashSet<>();
		for (AddressRange r : ranges) {
			for (Address from : refs.getReferenceSourceIterator(new AddressSet(r), true)) {
				monitor.checkCancelled();
				for (Reference ref : refs.getReferencesFrom(from)) {
					if (ref.getReferenceType().isCall() && ref.getToAddress().isMemoryAddress() &&
						listing.getInstructionAt(ref.getToAddress()) != null &&
						program.getFunctionManager().getFunctionAt(ref.getToAddress()) == null) {
						targets.add(ref.getToAddress());
					}
				}
			}
		}
		for (Address t : targets) {
			monitor.checkCancelled();
			new CreateFunctionCmd(t).applyTo(program, monitor);
		}
		log.appendMsg(NAME, "swept " + ranges.size() + " range(s): " + found +
			" instructions added, " + skipped + " slots did not decode, " + targets.size() +
			" functions created at call targets");
		return true;
	}
}
