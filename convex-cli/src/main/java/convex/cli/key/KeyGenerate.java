package convex.cli.key;

import java.io.Console;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.CLIError;
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

	private static final Logger log = LoggerFactory.getLogger(KeyGenerate.class);

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
		try {
			if (bip39) {
				String mnemonic=BIP39.createSecureRandom(12);
				cli().println(mnemonic);
				if (passphrase==null) {
					if (cli().isInteractive()) {
						passphrase=new String(cli().readPassword("Enter BIP39 passphrase: "));
					} else {
						passphrase="";
					}
				}
				Blob bipseed;
					bipseed = BIP39.getSeed(mnemonic, passphrase);
				AKeyPair result= BIP39.seedToKeyPair(bipseed);
				return result;
			} else {
				return AKeyPair.generate();
			}
		} catch (GeneralSecurityException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Override
	public void run() {
		// check the number of keys to generate.
		if (count <= 0) {
			log.warn("No keys to generate: count = "+count);
			return;
		}
		log.debug("Generating {} keys",count);
		
		char[] storePass=cli().getStorePassword();
		try {
			KeyStore ks=cli().loadKeyStore(true,storePass);
			for ( int index = 0; index < count; index ++) {
				AKeyPair kp=generateKeyPair();
                String publicKeyHexString =  kp.getAccountKey().toHexString();
				cli().println(publicKeyHexString); // Output generated public key		
				char[] keyPassword=cli().getKeyPassword();
				PFXTools.setKeyPair(ks, kp, keyPassword); 
				Arrays.fill(keyPassword, 'p');
			}
			log.debug(count+ " keys successfully generated");
			cli().saveKeyStore(storePass);
			log.trace("Keystore saved successfully");
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
		} finally {
			Arrays.fill(storePass,'z');
		}
	}




}
