package convex.cli.key;

import java.io.IOException;
import java.nio.file.Paths;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.cli.mixins.KeyMixin;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;


/**
 *
 * Convex key sub commands
 *
 *		convex.key.export
 *
 *
 */
@Command(name="sign",
	mixinStandardHelpOptions=false,
	description="Sign some data using a key from the store.")
public class KeySign extends AKeyCommand {

	@ParentCommand
	protected Key keyParent;
	
	@Mixin
	protected KeyMixin keyMixin;

	@Option(names={"-o", "--output-file"},
			description="Output file for the signature. Use '-' for STDOUT (default).")
	private String outputFilename;
	
	@Option(names={"-i", "--input-file"},
			description="Output file for the signature. Use '-' for STDIN.")
	private String inputFilename;
	
	@Option(names={"--hex"},
			description="Hex data to sign. Used instead of --input-file if specified")
	private String dataString;
	
//	@Option(names={"--raw"},
//			description="Specify this option to use raw byte input instead of hex.")
//	private boolean rawdata;
	
	@Override
	public void execute() {
		String keystorePublicKey=keyMixin.getPublicKey();
		if ((keystorePublicKey == null)||(keystorePublicKey.isEmpty())) {
			if (!isInteractive()) {
				cli().inform("You must provide a --key parameter for signing");
				showUsage();
				return;
			} else {
				keystorePublicKey=cli().prompt("Specify key to use for signature: ");
			}
		}

		String publicKey = keystorePublicKey;
		AKeyPair keyPair = storeMixin.loadKeyFromStore(publicKey,()->keyMixin.getKeyPassword());
		if (keyPair==null) {
			throw new CLIError(ExitCodes.NOUSER,"Key pair not found for requested signing key: "+keystorePublicKey);
		}
		
		if (dataString==null) {
			// Ensure importText is filled
			if (inputFilename != null && inputFilename.length() > 0) {
				try {
					dataString=FileUtils.loadFileAsString(inputFilename);
				} catch (IOException ex) {
					throw new CLIError(ExitCodes.IOERR,ex.getMessage());
				}
			} else if (isInteractive()) {
				dataString=prompt("Enter hex data to sign: ");
			} else {
				throw new CLIError("No input file specified");
			}
		}
		
		// Try to remove all whitespace and parse
		dataString=dataString.replaceAll("\\s+","");
		ABlob data=Blobs.parse(dataString);
		if (data==null) {
			throw new CLIError(ExitCodes.DATAERR,"Can't parse data, expecting a hex string.");
		}
		
		ASignature sig=keyPair.sign(data.toFlatBlob());
		String output=sig.toHexString();

		if ((outputFilename==null)||("-".equals(outputFilename.trim()))) {
			println(output);
		} else {
			try {
				FileUtils.writeFileAsString(Paths.get(outputFilename),output);
			} catch (IOException e) {
				throw new CLIError("Failed to write output file: "+e.getMessage());
			}
		}
	}



}
