package convex.cli.key;

import java.security.KeyStore;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.cli.Constants;
import convex.cli.Main;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.util.Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


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
	description="Generate private key pairs in the currently configured keystore. Will create a keystore if it does not exist.")
public class KeyGenerate extends AKeyCommand {

	private static final Logger log = LoggerFactory.getLogger(KeyGenerate.class);

	@Parameters(paramLabel="count",
		defaultValue="" + Constants.KEY_GENERATE_COUNT,
		description="Number of keys to generate. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = cli();
		
		// check the number of keys to generate.
		if (count < 0) {
			log.warn("Unlikely count of keys to generate: "+count);
			count=0;
		}
		log.debug("Generating {} keys",count);
		char[] keyPassword=mainParent.getKeyPassword();
		
		try {
			KeyStore ks=loadKeyStore(true);
			List<AKeyPair> keyPairList = mainParent.generateKeyPairs(count,keyPassword);
			for ( int index = 0; index < count; index ++) {
				AKeyPair kp=keyPairList.get(index);
                String publicKeyHexString =  kp.getAccountKey().toHexString();
				mainParent.println(publicKeyHexString); // Output generated public key
				PFXTools.setKeyPair(ks, kp, keyPassword); // TODO: key password?
			}
			log.debug(count+ " keys successfully generated");
			saveKeyStore();
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
		}
	}


}
