package convex.cli.mixins;

import java.io.File;
import java.io.IOException;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.core.data.AccountKey;
import convex.core.util.FileUtils;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import convex.peer.API;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class EtchMixin extends AMixin {

	@Option(names={"-e", "--etch"},
			scope = ScopeType.INHERIT,
			defaultValue="${env:CONVEX_ETCH_FILE:-~/.convex/etch.db}",
			description="Etch database. Defaults to CONVEX_ETCH_FILE or "+Constants.ETCH_FILENAME)
	String etchStoreFilename;
	
	EtchStore etch=null;
	private EtchConfig etchConfig=null;
	
	/**
	 * Gets the etch store for a given file name. Throws an error if not found
	 * @param fileName Filename to load as Etch store
	 * @return EtchStore instance
	 */
	public synchronized EtchStore getEtchStore(String fileName) {
		return getEtchStore(fileName,null);
	}

	/**
	 * Gets an Etch store using an already compiled configuration. Key lookup and
	 * conversion deliberately remain the caller's responsibility.
	 *
	 * @param fileName filename to open, or {@code temp}
	 * @param config compiled Etch configuration
	 * @return opened Etch store
	 */
	public synchronized EtchStore getEtchStore(String fileName, EtchConfig config) {
		if ((etch!=null)&&(config!=null)&&!config.equals(etchConfig)) {
			throw new CLIError("Etch store is already open with a different configuration");
		}
		if (etch!=null) return etch;
		
		if (fileName == null) {
			throw new CLIError(
					"No Etch store file specified. Maybe include --etch option or set environment variable CONVEX_ETCH_FILE ?");
		}
		
		File etchFile=null;
		try {
			if ("temp".equals(fileName)) {
				etch = (config==null)?EtchStore.createTemp("tempCLIStore")
						:EtchStore.createTemp("tempCLIStore",config);
				etchConfig=config;
				return etch;
			}
	
			etchFile = FileUtils.getFile(fileName);

			etch = (config==null)?EtchStore.create(etchFile):EtchStore.create(etchFile,config);
			etchConfig=config;
			return etch;
		} catch (IOException e) {
			throw new CLIError("Unable to load Etch store at: " + fileName+ " due to "+e,e);
		}
	}
	
	public EtchStore getEtchStore() {
		return getEtchStore(etchStoreFilename);
	}

	/** Opens the configured Etch path with an already compiled configuration. */
	public EtchStore getEtchStore(EtchConfig config) {
		return getEtchStore(etchStoreFilename,config);
	}

	/** Returns true only when the Etch path was supplied explicitly on argv. */
	public boolean isEtchFileSpecified() {
		ParseResult result=cli().commandLine().getParseResult();
		while (result!=null) {
			if (result.hasMatchedOption("--etch")) return true;
			result=result.subcommand();
		}
		return false;
	}

	/** Returns the configured Etch path without attempting a normal store open. */
	public File getEtchFile() {
		if (etchStoreFilename==null) {
			throw new CLIError("No Etch store file specified. Include --etch or set CONVEX_ETCH_FILE.");
		}
		return FileUtils.getFile(etchStoreFilename);
	}

	public List<AccountKey> getPeerList() {
		EtchStore etchStore=getEtchStore();

		try {
			List<AccountKey> keys=API.listPeers(etchStore);
			return keys;
		} catch (IOException e) {
			throw new CLIError("Unable to list peers in store: "+etchStore, e);
		}
	}
}
