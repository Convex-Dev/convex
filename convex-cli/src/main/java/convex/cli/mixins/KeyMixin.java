package convex.cli.mixins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class KeyMixin extends AMixin {

	@Option(names = { "-k","--key" }, 
			defaultValue = "${env:CONVEX_KEY}", 
			scope = ScopeType.INHERIT, 
			description = "Key pair to use from keystore. Supports a hex prefix. Can specify with CONVEX_KEY.")
	protected String publicKey;

	@Option(names = { "-p","--keypass" }, 
			defaultValue = "${env:CONVEX_KEY_PASSWORD}", 
			scope = ScopeType.INHERIT, 
			description = "Key pair password in keystore. Can specify with CONVEX_KEY_PASSWORD.")
	protected char[] keyPassword;

	public String getPublicKey() {
		return publicKey;
	}

	
	static Logger log = LoggerFactory.getLogger(KeyMixin.class);

	/**
	 * Gets the password for the current key. Prompts for missing password in interactive mode.
	 * 
	 * @return password
	 */
	public char[] getKeyPassword() {
		if (this.keyPassword!=null) return keyPassword;
		
		if (isInteractive()) {
			keyPassword = readPassword("Private Key Encryption Password: ");
		}
		
		if (keyPassword.length == 0) {
			paranoia("Cannot use an empty password in --strict-security mode");
		}
		return keyPassword;
	}
}
