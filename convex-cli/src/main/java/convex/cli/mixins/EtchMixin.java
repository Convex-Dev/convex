package convex.cli.mixins;

import java.io.File;
import java.io.IOException;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.core.util.Utils;
import etch.EtchStore;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class EtchMixin extends AMixin {

	@Option(names={"-e", "--etch"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_ETCH_FILE:-~/.convex/etch.db}",
			description="Convex Etch database filename. Will default to CONVEX_ETCH_FILE or "+Constants.ETCH_FILENAME)
	String etchStoreFilename;
	
	public EtchStore getEtchStore(String fileName) {
		if (fileName == null) {
			throw new CLIError(
					"No Etch store file specified. Maybe include --etch option or set environment variable CONVEX_ETCH_FILE ?");
		}

		File etchFile = Utils.getPath(fileName);

		try {
			EtchStore store = EtchStore.create(etchFile);
			return store;
		} catch (IOException e) {
			throw new CLIError("Unable to load Etch store at: " + etchFile + " cause: " + e.getMessage());
		}
	}
	
	public EtchStore getEtchStore() {
		return getEtchStore(etchStoreFilename);
	}

}
