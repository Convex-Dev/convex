package convex.cli.etch;

import convex.cli.Main;
import convex.core.data.ACell;
import convex.core.data.type.AType;
import convex.core.lang.RT;
import etch.EtchStore;
import etch.EtchUtils.EtchCellVisitor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="dump",
mixinStandardHelpOptions=true,
description="Dumps Etch data to an exported format. Defaults to CSV with value IDs, Types and encodings")
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
			
			cli().println(hash+","+type+","+encoding);
		}
	}

	@Override
	public void run() {
		cli().setOut(outputFilename);
		
		EtchStore store=store();
		store.getEtch().visitIndex(new DumpVisitor(cli()));
	}
}
