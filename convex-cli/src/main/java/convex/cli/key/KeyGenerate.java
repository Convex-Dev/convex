package convex.cli.key;

import java.util.Arrays;

import convex.cli.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.data.Blob;
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
	description="Generate private key pair(s) in the currently configured keystore.")
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
			println(mnemonic);
			if (passphrase==null) {
				if (isInteractive()) {
					passphrase=new String(readPassword("Enter BIP39 passphrase: "));
				} else {
					paranoia("Passphrase must be explicity provided");
					passphrase="";
				}
			}
			if (passphrase.isBlank()) {
				paranoia("Cannot use an empty BIP39 passphrase for key generation with strict security");
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
			informWarning("No keys generated. Perhaps specify a positive --count ?");
			return;
		}
		
		char[] storePass=storeMixin.getStorePassword();
		storeMixin.ensureKeyStore();

		for ( int index = 0; index < count; index ++) {
			AKeyPair kp=generateKeyPair();
            String publicKeyHexString =  kp.getAccountKey().toHexString();
			char[] keyPassword=keyMixin.getKeyPassword();
			storeMixin.addKeyPairToStore(kp, keyPassword); 
			println(publicKeyHexString); // Output generated public key		
			Arrays.fill(keyPassword, 'p');
		}
		informSuccess(count+ " key(s) generated");
		storeMixin.saveKeyStore(storePass);
	}
}
