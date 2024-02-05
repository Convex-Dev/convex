package convex.cli.etch;

import java.io.IOException;

import convex.core.util.Text;
import convex.core.util.Utils;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="info",
mixinStandardHelpOptions=true,
description="Dumps Etch data to an exported format. Defaults to CSV for value IDs and encodings")
public class EtchInfo extends AEtchCommand{
	
	@Option(names={"-o", "--output-file"},
			description="Output file for the the Etch info.")
		private String outputFilename;

	@Override
	public void run() {
		cli().setOut(outputFilename);
		
		try {
		
			EtchStore store=store();
			etch.Etch etch=store.getEtch();
			cli().println("Etch file:    "+store.getFileName());
			
			cli().println("Etch version: 0x"+Utils.toHexString(etch.getVersion()));
			cli().println("Data length:  "+Text.toFriendlyNumber(etch.getDataLength()));
			cli().println("Data root:    "+etch.getRootHash());
		} catch (IOException e) {
			cli().printErr("IO Error: "+e.getMessage());
		}
	}
}
