package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.core.data.ACell;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.core.util.Utils;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="info",
mixinStandardHelpOptions=true,
description="Outputs summary data about the Etch database.")
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
			println("Etch file:        "+store.getFileName());
			
			println("Etch version:     0x"+Utils.toHexString(etch.getVersion()));
			println("Data length:      "+Text.toFriendlyNumber(etch.getDataLength()));
			
			println("Data root:        "+etch.getRootHash());
			try {
				ACell root=store.getRootData();
				println("Root memory size: "+root.getMemorySize());
				println("Root data type:   "+RT.getType(root));
			} catch (MissingDataException e) {
				println("Root data missing");
			}
		} catch (IOException e) {
			throw new CLIError("IO Error accessing Etch database: "+e.getMessage());
		}
	}
}
