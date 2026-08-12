package convex.cli.etch;

import java.io.File;
import java.io.IOException;
import java.util.List;

import convex.cli.CLIError;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.exceptions.MissingDataException;
import convex.core.text.Text;
import convex.core.util.FileUtils;
import convex.etch.EtchStore;
import convex.etch.EtchUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to garbage-collect an Etch store. Thin wrapper over the
 * EtchStore GC lifecycle (see convex-core/docs/ETCH_GC.md).
 */
@Command(name="gc",
mixinStandardHelpOptions=true,
description="Garbage collects an Etch store, retaining only the root and all reachable data. "
		+ "In-place by default (the store file is rebuilt and replaced). With --output, collects "
		+ "into a fresh file and leaves the source untouched (note: status levels above PERSISTED, "
		+ "e.g. ANNOUNCED, are preserved in-place but not with --output).")
public class EtchGC extends AEtchCommand {

	@Option(names={"-o", "--output"},
			description="Collect into this new file instead of in-place. The source store is not modified.")
	private String outputFilename;

	@Override
	public void execute() {
		EtchStore store=store();
		try {
			if (store.getRootRef()==null) {
				informWarning("Store has no root data: the collected store will be empty.");
			}
			if (outputFilename!=null) {
				collectTo(store, FileUtils.getFile(outputFilename));
			} else {
				collectInPlace(store);
			}
		} catch (MissingDataException e) {
			throw new CLIError("Source store is missing data reachable from its root ("
					+ e.getMissingHash() + "). The store may be corrupt or truncated; "
					+ "run 'etch validate' to check. No data has been modified.", e);
		} catch (IOException e) {
			throw new CLIError("IO error during Etch GC: "+e.getMessage(), e);
		} finally {
			store.close();
			closeKeyContexts();
		}
	}

	private void collectInPlace(EtchStore store) throws IOException {
		long before=store.getEtch().getDataLength();
		File baseFile=store.getBaseFile();

		store.startGC();
		store.transferGC();
		List<Hash> missing=store.verifyGC();
		if (!missing.isEmpty()) {
			// verifyGC empty is guaranteed after a successful sweep: reaching here
			// means something is deeply wrong, and the original file is untouched
			throw new CLIError("GC verification failed: "+missing.size()
					+" value(s) missing from the collected store. The original file is untouched;"
					+ " run 'etch validate' to check it for corruption.");
		}
		EtchStore collected=store.completeGC();
		long after=collected.getEtch().getDataLength();
		store.close();     // deletes the superseded original (or defers if pinned)
		collected.close();

		// Try to install the collected file under the original name now; if
		// files are pinned by this process, the next open completes it
		File open=EtchUtils.recover(baseFile);

		println("Etch GC complete");
		println("Size before:  "+Text.toFriendlyNumber(before)+" bytes");
		println("Size after:   "+Text.toFriendlyNumber(after)+" bytes");
		println("Reclaimed:    "+Text.toFriendlyDecimal(100.0*(before-after)/Math.max(1, before))+"%");
		if (open.getCanonicalFile().equals(baseFile.getCanonicalFile())) {
			println("Store file:   "+baseFile.getCanonicalPath());
		} else {
			println("Store file:   "+open.getCanonicalPath());
			informWarning("The collected file could not yet be renamed to "+baseFile.getName()
					+" (still pinned by this process). It will be installed automatically the"
					+" next time the store is opened.");
		}
	}

	private void collectTo(EtchStore store, File outFile) throws IOException {
		if (outFile.exists() && (outFile.length()>0)) {
			throw new CLIError("Output file already exists: "+outFile
					+". Use 'etch migrate --into' to merge into an existing store.");
		}
		long before=store.getEtch().getDataLength();

		EtchStore dest=EtchStore.create(outFile,
				destinationConfig(store.getEtch().getConfig(),true));
		try {
			ACell root=store.getRootData();
			if (root!=null) {
				// Persists the root tree into dest (children resolve via the
				// source-bound refs) and sets the destination root
				dest.setRootData(root);
				List<Hash> missing=EtchUtils.verify(dest.getEtch(), dest.getRootHash());
				if (!missing.isEmpty()) {
					throw new CLIError("GC verification failed: "+missing.size()
							+" value(s) missing from the output store. The source is untouched;"
							+ " run 'etch validate' to check it for corruption.");
				}
			}
			dest.flush();
			println("Etch GC complete (source untouched)");
			println("Source size:  "+Text.toFriendlyNumber(before)+" bytes");
			println("Output size:  "+Text.toFriendlyNumber(dest.getEtch().getDataLength())+" bytes");
			println("Output file:  "+dest.getFile().getCanonicalPath());
		} finally {
			dest.close();
		}
	}
}
