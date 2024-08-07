package convex.cli.key;

import java.util.Arrays;

import convex.cli.CLIError;
import convex.cli.Constants;
import convex.cli.ExitCodes;
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
	
	@Option(names="--words",
			defaultValue="12",
			description="Number of words in BIP39 mnemonic. Default: ${DEFAULT-VALUE}")
		private int words;
	
	@Option(names={"--type"},
			defaultValue="bip39",
			description="Type of key generation. Supports random, bip39")
	private String type;
	
	@Option(names="--passphrase",
			description="BIP39 passphrase. If not provided, will be requested from user (or assumed blank in non-interactive mode).")
	private String passphrase;

	private AKeyPair generateKeyPair() {	
		if ("bip39".equals(type)) {
			if (words<12) {
				paranoia("Can't use less than 12 BIP39 words in strict security mode");
			}
			
			String mnemonic=BIP39.createSecureMnemonic(words);
			inform("BIP39 mnemonic generated with "+words+" words:");
			inform(mnemonic);
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
		} else if ("random".equals(type)) {
			return AKeyPair.generate();
		} else {
			throw new CLIError(ExitCodes.USAGE,"Unsupprted key generation type: "+type);
		}
	}
	
	@Override
	public void run() {
		// check the number of keys to generate.
		if (count <= 0) {
			informWarning("No keys generated. Perhaps you want a positive --count ?");
			return;
		}
		
		for ( int index = 0; index < count; index ++) {
			AKeyPair kp=generateKeyPair();
			
            String publicKeyHexString =  kp.getAccountKey().toHexString();
			storeMixin.ensureKeyStore();
			char[] keyPassword=keyMixin.getKeyPassword();
			storeMixin.addKeyPairToStore(kp, keyPassword); 
			inform ("Public key added to store: "+kp.getAccountKey());
			println(publicKeyHexString); // Output generated public key		
			Arrays.fill(keyPassword, 'p');
		}
		storeMixin.saveKeyStore();
		informSuccess(count+ " key(s) generated and saved in store "+storeMixin.getStorePath());
	}
}
