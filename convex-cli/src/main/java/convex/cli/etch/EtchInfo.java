package convex.cli.etch;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.exceptions.MissingDataException;
import convex.core.util.Text;
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
			cli().println("Etch file:        "+store.getFileName());
			
			cli().println("Etch version:     0x"+Utils.toHexString(etch.getVersion()));
			cli().println("Data length:      "+Text.toFriendlyNumber(etch.getDataLength()));
			cli().println("Data root:        "+etch.getRootHash());
			
			try {
				ACell root=store.getRootData();
				cli().println("Root memory size: "+root.getMemorySize());
			} catch (MissingDataException e) {
				cli().println("Root data missing");
			}
		} catch (IOException e) {
			cli().printErr("IO Error: "+e.getMessage());
		}
	}
}
