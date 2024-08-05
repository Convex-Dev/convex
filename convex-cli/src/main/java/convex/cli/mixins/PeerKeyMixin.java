package convex.cli.mixins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class PeerKeyMixin extends AMixin {

	@Option(names = { "--peer-key" }, 
			defaultValue = "${env:CONVEX_PEER_KEY}", 
			scope = ScopeType.INHERIT, 
			description = "Peer Key pair. Specifiy with a hex prefix of a public key. "+
			   "Can ALSO specify with CONVEX_PEER_KEY environment variable.")
	protected String publicKey;

	@Option(names = { "--peer-keypass" }, 
			defaultValue = "${env:CONVEX_PEER_KEY_PASSWORD}", 
			scope = ScopeType.INHERIT, 
			description = "Peer Key pair password in key store. Can also specify with CONVEX_PEER_KEY_PASSWORD environment variable.")
	protected String keyPassword;

	public String getPublicKey() {
		return publicKey;
	}

	static Logger log = LoggerFactory.getLogger(PeerKeyMixin.class);

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
			paranoia("Cannot use an empty private key password");
		}
		return keypass;
	}
}
