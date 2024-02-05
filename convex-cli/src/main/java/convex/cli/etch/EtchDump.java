package convex.cli.etch;

import convex.cli.Main;
import convex.core.data.ACell;
import etch.EtchStore;
import etch.EtchUtils.EtchCellVisitor;
import picocli.CommandLine.Command;

@Command(name="dump",
mixinStandardHelpOptions=true,
description="Dumps Etch data to an exported format. Defaults to CSV for value IDs and encodings")
public class EtchDump extends AEtchCommand{
	

	public class DumpVisitor extends EtchCellVisitor {
		protected Main cli;

		public DumpVisitor(Main cli) {
			this.cli=cli;
		}

		@Override
		public void visitCell(ACell cell) {
			String hash=cell.getHash().toHexString();
			String encoding = cell.getEncoding().toHexString();
			
			cli().println(hash+","+encoding);
		}

	}

	@Override
	public void run() {
		EtchStore store=store();
		
		store.getEtch().visitIndex(new DumpVisitor(cli()));
	}


}
