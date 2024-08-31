package convex.cli.etch;

import java.io.IOException;

import convex.cli.CLIError;
import convex.cli.Main;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.text.Text;
import convex.etch.EtchCorruptionError;
import convex.etch.EtchStore;
import convex.etch.EtchUtils.FullValidator;
import convex.core.exceptions.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name="validate",
mixinStandardHelpOptions=true,
description="Validates an Etch store")
public class EtchValidate extends AEtchCommand{
	
	@Option(names={"-m", "--max-failures"},
			description="Maximum number of failures to recognise before abort.")
	private Long maxFailures;
	
	public class ValidateVisitor extends FullValidator {
		protected Main cli;
		public long failures=0;
		public long encoded=0;

		public ValidateVisitor(Main cli) {
			this.cli=cli;
		}

		@Override
		public void visitHash(convex.etch.Etch e, Hash h) {
			try {
				Ref<ACell> r=e.read(h);
				ACell cell=r.getValue();
				cell.validate();
				
				Blob encoding =cell.getEncoding();
				encoded+=encoding.count();
			} catch (IOException | InvalidDataException e1) {
				fail("Failed to validate cell "+h+" cause:" + e1);
			}
		}
		
		@Override
		public void fail(String msg) {
			cli.inform(msg);
			failures++;
			if ((maxFailures!=null)&&(failures>=maxFailures)) throw new CLIError("Max Failures exceeded");
		}
	}

	@Override
	public void execute() {
		EtchStore store=store();
		try {
			ValidateVisitor visitor=new ValidateVisitor(cli());
			convex.etch.Etch etch=store.getEtch();
			etch.visitIndex(visitor);
			
			long fails=visitor.failures;
			if (fails>0) throw new CLIError("Etch validation failed!");
			
			long len=etch.getDataLength();
			long cellCount=visitor.values;
			
			cli().println("Etch validation completed with "+fails+" error(s)");
			cli().println("Index nodes:              "+Text.toFriendlyNumber(visitor.indexPtrs));
			cli().println("Cells:                    "+Text.toFriendlyNumber(cellCount));
			cli().println("Empty:                    "+Text.toFriendlyNumber(visitor.empty));
			cli().println("Database size:            "+Text.toFriendlyNumber(len));
			cli().println("Avg. Encoding Length:     "+Text.toFriendlyDecimal(((double)visitor.encoded)/cellCount));
			cli().println("Storage per Cell (bytes): "+Text.toFriendlyDecimal(((double)len)/cellCount));

		} catch (EtchCorruptionError e) {
			throw new CLIError("Etch file corrupt: "+store,e);
		} catch (IOException e) {
			throw new CLIError("IO Error traversing etch store: "+store,e);
		}
	}
}
