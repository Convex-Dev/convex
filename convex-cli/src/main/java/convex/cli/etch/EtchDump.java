package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.cli.Main;
import convex.core.data.ACell;
import convex.core.data.type.AType;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.etch.EtchUtils.EtchCellVisitor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="dump",
mixinStandardHelpOptions=true,
description="Dumps Etch data to an exported format. Defaults to CSV with value ID, Type, Memory Size and encoding")
public class EtchDump extends AEtchCommand{
	
	@Option(names={"-o", "--output-file"},
			description="Output file for the the Etch data dump.")
		private String outputFilename;
	
	public class DumpVisitor extends EtchCellVisitor {
		protected Main cli;

		public DumpVisitor(Main cli) {
			this.cli=cli;
		}

		@Override
		public void visitCell(ACell cell) {
			String hash=cell.getHash().toHexString();
			String encoding = cell.getEncoding().toHexString();
			AType type=RT.getType(cell);
			long mem=ACell.getMemorySize(cell);
			
			cli().println(hash+","+type+","+mem+","+encoding);
		}
	}

	@Override
	public void execute() {
		cli().setOut(outputFilename);
		
		try (EtchStore store=store()) {
			store.getEtch().visitIndex(new DumpVisitor(cli()));
		} catch (IOException e) {
			throw new CLIError("IO Error traversing Etch store",e);
		}
	}
}
