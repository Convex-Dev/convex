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
	protected String keyPassword;

	public String getPublicKey() {
		return publicKey;
	}

	
	static Logger log = LoggerFactory.getLogger(KeyMixin.class);

	/**
	 * Keys the password for the current key
	 * 
	 * @return password
	 */
	public char[] getKeyPassword() {
		char[] keypass = null;

		if (this.keyPassword != null) {
			keypass = this.keyPassword.toCharArray();
		} else {
			if (isInteractive()) {
				keypass = readPassword("Private Key Encryption Password: ");
			}

			if (keypass == null) {
				log.warn("No password for key: defaulting to blank password");
				keypass = new char[0];
			}
			
			this.keyPassword=new String(keypass);
		}
		
		if (keypass.length == 0) {
			paranoia("Cannot use an empty password in --strict-security mode");
		}
		return keypass;
	}
}
