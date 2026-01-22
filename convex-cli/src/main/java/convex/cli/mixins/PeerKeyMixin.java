package convex.cli.mixins;

import convex.core.data.AccountKey;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public class PeerKeyMixin extends AMixin {

	@Option(names = { "--peer-key" },
			defaultValue = "${env:CONVEX_PEER_KEY}",
			scope = ScopeType.INHERIT,
			description = "Peer key. Allows a hex prefix of a public key in keystore. "+
			   "Can also specify with CONVEX_PEER_KEY.")
	protected String publicKey;

	@Option(names = { "--peer-keypass" },
			defaultValue = "${env:CONVEX_PEER_KEY_PASSWORD}",
			scope = ScopeType.INHERIT,
			description = "Peer key password in keystore. Can also specify with CONVEX_PEER_KEY_PASSWORD.")
	protected String keyPassword;

	/**
	 * Gets the specified public key alias for the Peer
	 * @return Public key alias specified on CLI, or null if unspecified
	 */
	public String getPublicKey() {
		return publicKey;
	}

	/**
	 * Gets the password for the peer key. Prompts for missing password in interactive mode.
	 * In non-interactive mode:
	 * - Strict mode (-S): fails with error if password is empty
	 * - Non-strict mode: allows empty password with warning
	 *
	 * @return password (never null, may be empty array in non-strict mode)
	 */
	public char[] getKeyPassword() {
		char[] keypass = null;

		if (this.keyPassword != null) {
			keypass = this.keyPassword.toCharArray();
		} else {
			if (isInteractive()) {
				keypass = readPassword("Peer Key Encryption Password: ");
			}

			if (keypass == null) {
				informWarning("No password provided - using empty password for peer key encryption.");
				keypass = new char[0];
			}

			this.keyPassword=new String(keypass);
		}
		if (keypass.length == 0) {
			paranoia("Cannot use an empty password in --strict-security mode");
		}
		return keypass;
	}
	
	public AccountKey getAcountKey() {
		String ks=getPublicKey();
		AccountKey result= AccountKey.parse(ks);
		return result;
	}
	
	
}
