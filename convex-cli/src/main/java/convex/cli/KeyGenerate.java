package convex.cli;

import java.util.List;

import convex.core.crypto.AKeyPair;

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
	aliases={"ge"},
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
			log.warn("You to provide 1 or more count of keys to generate");
			return;
		}
		log.info("Generating {} keys",count);

		try {
			List<AKeyPair> keyPairList = mainParent.generateKeyPairs(count);
			for ( int index = 0; index < keyPairList.size(); index ++) {
                String publicKeyHexString =  keyPairList.get(index).getAccountKey().toHexString();
				mainParent.output.setField("Index", String.format("%5d", index));
				mainParent.output.setField("Public Key", publicKeyHexString);
				mainParent.output.addRow();
			}
		} catch (Error e) {
			mainParent.showError(e);
		}
	}
}
