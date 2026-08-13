package convex.cli.etch;

import java.io.File;
import java.io.IOException;

import convex.cli.CLIError;
import convex.core.data.ACell;
import convex.core.text.Text;
import convex.core.util.FileUtils;
import convex.etch.EtchStore;
import convex.etch.EtchUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to migrate everything from one Etch store into another. Thin
 * wrapper over EtchUtils.migrate (see convex-core/docs/ETCH_GC.md).
 */
@Command(name="migrate",
mixinStandardHelpOptions=true,
description="Migrates everything from a source Etch store into a destination store. The "
		+ "destination may be non-empty: it gains every source entry at its recorded status, and "
		+ "its own root is unchanged unless --set-root is given. The source is not modified.")
public class EtchMigrate extends AEtchCommand {

	@Option(names={"--into"},
			required=true,
			description="Destination Etch store file. Created if it does not exist.")
	private String destFilename;

	@Option(names={"--set-root"},
			description="Also set the destination root to the source root (default: destination root unchanged).")
	private boolean setRoot;

	@Override
	public void execute() {
		EtchStore source=store();
		try {
			File destFile=FileUtils.getFile(destFilename);
			if (destFile.getCanonicalFile().equals(source.getFile().getCanonicalFile())) {
				throw new CLIError("Source and destination are the same file: "+destFile);
			}

			EtchStore dest=EtchStore.create(destFile,
					destinationConfig(source.getEtch().getConfig(),false));
			try {
				long count=EtchUtils.migrate(source, dest);
				if (setRoot) {
					ACell root=source.getRootData();
					if (root==null) {
						informWarning("Source has no root data: destination root left unchanged.");
					} else {
						// Root tree is already migrated; this just persists (a
						// no-op) and sets the destination root hash
						dest.setRootData(root);
					}
				}
				dest.flush();
				println("Etch migration complete");
				println("Entries processed: "+Text.toFriendlyNumber(count));
				println("Destination:       "+dest.getFile().getCanonicalPath());
				println("Destination size:  "+Text.toFriendlyNumber(dest.getEtch().getDataLength())+" bytes");
			} finally {
				dest.close();
			}
		} catch (IOException e) {
			throw new CLIError("IO error during Etch migration: "+e.getMessage(), e);
		} finally {
			source.close();
			closeKeyContexts();
		}
	}
}
