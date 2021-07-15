package convex.cli;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
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
	aliases={"ge"},
	mixinStandardHelpOptions=true,
	description="Generate 1 or more private key pairs.")
public class KeyGenerate implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(KeyGenerate.class.getName());

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
			System.out.println("You to provide 1 or more count of keys to generate");
			return;
		}
		log.info("Generating {} keys",count);

		try {
			List<AKeyPair> keyPairList = mainParent.generateKeyPairs(count);
			for ( int index = 0; index < keyPairList.size(); index ++) {
				System.out.println("generated #"+(index+1)+" public key: " + keyPairList.get(index).getAccountKey().toHexString());
			}
		} catch (Error e) {
			log.error("Key generate error: ", e.getMessage());
			return;
		}
	}
}
