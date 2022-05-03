package convex.cli;

import java.security.KeyStore;
import java.util.List;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.util.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;


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
	description="Generate 1 or more private key pairs.")
public class KeyGenerate implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyGenerate.class);

	@ParentCommand
	protected Key keyParent;


	@Parameters(paramLabel="count",
		defaultValue="" + Constants.KEY_GENERATE_COUNT,
		description="Number of keys to generate. Default: ${DEFAULT-VALUE}")
	private int count;

	@Override
	public void run() {
		// sub command to generate keys
		Main mainParent = keyParent.mainParent;
		
		// check the number of keys to generate.
		if (count <= 0) {
			log.warn("Unlikely count of keys to generate: "+count);
			count=0;
		}
		log.debug("Generating {} keys",count);
		String password=mainParent.getPassword();
		
		try {
			KeyStore ks=mainParent.loadKeyStore(true);
			List<AKeyPair> keyPairList = mainParent.generateKeyPairs(count);
			for ( int index = 0; index < count; index ++) {
				AKeyPair kp=keyPairList.get(index);
                String publicKeyHexString =  kp.getAccountKey().toHexString();
				mainParent.println(publicKeyHexString); // Output generated public key
				PFXTools.setKeyPair(ks, kp, password); // TODO: key password?
			}
			log.info(count+ " keys successfully generated");
			mainParent.saveKeyStore();
		} catch (Throwable e) {
			throw Utils.sneakyThrow(e);
		}
	}
}
