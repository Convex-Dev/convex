package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.core.text.Text;
import convex.etch.EtchStore;
import picocli.CommandLine.Command;

/**
 * CLI command to run Etch GC recovery for a store file and report the
 * resulting state. Recovery itself happens automatically whenever a store is
 * opened (see convex-core/docs/ETCH_GC.md, "File lifecycle"); this command
 * exists to run it deliberately and see the outcome.
 */
@Command(name="recover",
mixinStandardHelpOptions=true,
description="Runs GC recovery for an Etch store file: adopts a completed GC cutover and rolls "
		+ "back any abandoned GC cycle, then reports the store state. (Recovery also runs "
		+ "automatically whenever a store is opened; this command just makes it explicit.)")
public class EtchRecover extends AEtchCommand {

	@Override
	public void execute() {
		// Opening the store runs recovery (EtchStore.create -> EtchUtils.recover);
		// detailed actions are reported via the convex.etch.recovery logger
		EtchStore store=store();
		try {
			println("Etch recovery complete");
			println("Store file:  "+store.getFile().getCanonicalPath());
			if (!store.getFile().getCanonicalFile().equals(store.getBaseFile().getCanonicalFile())) {
				informWarning("Adoption deferred: the current data is in "+store.getFile().getName()
						+" and will be installed as "+store.getBaseFile().getName()
						+" the next time the store is opened (the file is still pinned by this process).");
			}
			println("Root hash:   "+store.getRootHash());
			println("Data length: "+Text.toFriendlyNumber(store.getEtch().getDataLength())+" bytes");
		} catch (IOException e) {
			throw new CLIError("IO error reading recovered Etch store: "+e.getMessage(), e);
		} finally {
			store.close();
		}
	}
}
