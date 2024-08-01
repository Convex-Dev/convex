package convex.cli.key;

import java.security.KeyStore;
import java.util.Arrays;

import convex.cli.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PFXTools;
import convex.core.data.Blob;
import convex.core.util.Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.generate
 *
 *
 */
@Command(name="generate",
	aliases={"gen"},
	mixinStandardHelpOptions=true,
	description="Generate private key pair(s) in the currently configured keystore. Will create a keystore if it does not exist.")
public class KeyGenerate extends AKeyCommand {

	@Option(names="--count",
		defaultValue="" + Constants.KEY_GENERATE_COUNT,
		description="Number of keys to generate. Default: ${DEFAULT-VALUE}")
	private int count;
	
	@Option(names="--bip39",
			description="Generate BIP39 mnemonic seed phrases and passphrase")
	private boolean bip39;
	
	@Option(names="--passphrase",
			description="BIP39 passphrase. If not provided, will be requested from user (or assumed blank in non-interactive mode).")
	private String passphrase;

	private AKeyPair generateKeyPair() {	
		if (bip39) {
			String mnemonic=BIP39.createSecureMnemonic(12);
			cli().println(mnemonic);
			if (passphrase==null) {
				if (cli().isInteractive()) {
					passphrase=new String(cli().readPassword("Enter BIP39 passphrase: "));
				} else {
					cli().paranoia("Passphrase must be explicity provided");
					passphrase="";
				}
			}
			if (passphrase.isBlank()) {
				cli().paranoia("Cannot use an empty BIP39 passphrase for secure key generation");
			}
			Blob bipseed = BIP39.getSeed(mnemonic, passphrase);
			AKeyPair result= BIP39.seedToKeyPair(bipseed);
			return result;
		} else {
			return AKeyPair.generate();
		}
	}
	
	@Override
	public void run() {
		// check the number of keys to generate.
		if (count <= 0) {
			cli().inform("No keys generated. Perhaps specify a positive --count ?");
			return;
		}
		
		char[] storePass=cli().storeMixin.getStorePassword();
		try {
			KeyStore ks=cli().storeMixin.loadKeyStore(true,storePass);
			for ( int index = 0; index < count; index ++) {
				AKeyPair kp=generateKeyPair();
                String publicKeyHexString =  kp.getAccountKey().toHexString();
				cli().println(publicKeyHexString); // Output generated public key		
				char[] keyPassword=cli().getKeyPassword();
				PFXTools.setKeyPair(ks, kp, keyPassword); 
				Arrays.fill(keyPassword, 'p');
			}
			cli().storeMixin.saveKeyStore(storePass);
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
		} finally {
			Arrays.fill(storePass,'z');
		}
	}




}
