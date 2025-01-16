package convex.cli.mixins;

import java.io.File;
import java.io.IOException;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.core.data.AccountKey;
import convex.core.util.FileUtils;
import convex.etch.EtchStore;
import convex.peer.API;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class EtchMixin extends AMixin {

	@Option(names={"-e", "--etch"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_ETCH_FILE:-~/.convex/etch.db}",
			description="Etch database. Defaults to CONVEX_ETCH_FILE or "+Constants.ETCH_FILENAME)
	String etchStoreFilename;
	
	EtchStore etch=null;
	
	/**
	 * Gets the etch store for a given file name. Throws an error if not found
	 * @param fileName Filename to load as Etch store
	 * @return EtchStore instance
	 */
	public synchronized EtchStore getEtchStore(String fileName) {
		if (etch!=null) return etch;
		
		if (fileName == null) {
			throw new CLIError(
					"No Etch store file specified. Maybe include --etch option or set environment variable CONVEX_ETCH_FILE ?");
		}
		
		File etchFile=null;
		try {
			if ("temp".equals(fileName)) {
				etch = EtchStore.createTemp("tempCLIStore");
				return etch;
			}
	
			etchFile = FileUtils.getFile(fileName);

			etch = EtchStore.create(etchFile);
			return etch;
		} catch (IOException e) {
			throw new CLIError("Unable to load Etch store at: " + fileName+ " due to "+e,e);
		}
	}
	
	public EtchStore getEtchStore() {
		return getEtchStore(etchStoreFilename);
	}

	public List<AccountKey> getPeerList() {
		EtchStore etchStore=getEtchStore();
		
		try {
			List<AccountKey> keys=API.listPeers(getEtchStore());
			return keys;
		} catch (IOException e) {
			throw new CLIError("Unable to list peers in store: "+etchStore);
		}
	}
}
