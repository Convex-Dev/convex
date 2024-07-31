package convex.cli;

import java.io.File;

import convex.core.util.Utils;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class StoreMixin {

	@Option(names = { "--keystore" }, 
			defaultValue = "${env:CONVEX_KEYSTORE:-" + Constants.KEYSTORE_FILENAME+ "}", 
			scope = ScopeType.INHERIT, 
			description = "Keystore filename. Default: ${DEFAULT-VALUE}")
	private String keyStoreFilename;

	/**
	 * Password for keystore. Option named to match Java keytool
	 */
	@Option(names = {"--storepass" }, 
			scope = ScopeType.INHERIT, 
			defaultValue = "${env:CONVEX_KEYSTORE_PASSWORD}", 
			description = "Password to read/write to the Keystore") 
	String keystorePassword;
	
	/**
	 * Gets the keystore file name currently used for the CLI
	 * 
	 * @return File name, or null if not specified
	 */
	public File getKeyStoreFile() {
		if (keyStoreFilename != null) {
			File f = Utils.getPath(keyStoreFilename);
			return f;
		}
		return null;
	}

}
