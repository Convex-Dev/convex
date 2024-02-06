package convex.cli.etch;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.lang.RT;
import etch.EtchStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name="read",
mixinStandardHelpOptions=true,
description="Reads data values from the Etch store.")
public class EtchRead extends AEtchCommand{
	
	@Option(names={"-o", "--output-file"},
			description="Output file for the the Etch data. Defaults to STDOUT.")
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
	public void run() {
		cli().setOut(outputFilename);
		
		if ((hash==null)|| hash.length==0) {
			cli().printErr("No hash(es) provided to read. Suggestion: list one or more hashes at end of the command.");
			return;
		}
		
		EtchStore store=store();
		for (String hs:hash) {
			Hash h=Hash.parse(hs);
			if (h==null) {
				cli().printErr("Parameter ["+hs+"] not valid - should be 32-byte hash value");
				continue;
			}
			
			Ref<ACell> r=store.refForHash(h);
			if (r==null) {
				cli().printErr("Hash not found ["+hs+"]");
				continue;				
			}
			
			long limit=(printLimit==null)?Long.MAX_VALUE:printLimit;
			String s=RT.toString(r.getValue(),limit);
			cli().println(s);
		}
	}
}
