package convex.cli.etch;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name="read",
mixinStandardHelpOptions=true,
description="Reads data values from the Etch store.")
public class EtchRead extends AEtchCommand{
	
	@Option(names={"-o", "--output-file"},
			description="Output file for the retreived data. Defaults to STDOUT.")
	private String outputFilename;

	@Option(names={"--limit"},
			description="Print length limit for each value. Default is unlimited.")
	private Long printLimit;

	@Parameters(
			index = "0", 
			arity = "0..*", 
			description = "Hash(es) of data values to read.")
    private String[] hash;

	@Override
	public void execute() {
		if (outputFilename!=null) {
			cli().setOut(outputFilename);
		}
		
		if ((hash==null)|| hash.length==0) {
			inform("No hash(es) provided to read. Suggestion: list one or more hashes at end of the command.");
			return;
		}
		
		EtchStore store=store();
		try {
			for (String hs:hash) {
				Hash h=Hash.parse(hs);
				if (h==null) {
					throw new CLIError(ExitCodes.DATAERR,"Parameter ["+hs+"] not valid - should be 32-byte hash value");
				}
				
				Ref<ACell> r=store.refForHash(h);
				if (r==null) {
					inform("Hash not found ["+hs+"]");
					println("");
					continue;				
				}
				
				long limit=(printLimit==null)?Long.MAX_VALUE:printLimit;
				String s=RT.toString(r.getValue(),limit);
				println(s);
			}
		} finally {
			store.close();
		}
	}
}
