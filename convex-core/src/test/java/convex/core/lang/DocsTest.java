package convex.core.lang;

import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Symbol;

public class DocsTest {
	public static final boolean PRINT_MISSING=true;
	
	@Test public void testDocs() {
		AHashMap<Symbol, AHashMap<ACell, ACell>> coreMeta=Core.buildCoreAccount().getMetadata();
		for (Entry<Symbol, AHashMap<ACell, ACell>> me: coreMeta.entrySet()) {
			Symbol sym=me.getKey();
			AHashMap<ACell,ACell> meta = me.getValue();
			if (meta.isEmpty()) {
				if (PRINT_MISSING) System.err.println("Empty metadata in Core: "+sym);
			} else {
				@SuppressWarnings("unchecked")
				AHashMap<ACell,ACell> doc=(AHashMap<ACell, ACell>) meta.get(Keywords.DOC);
				if (doc==null) {
					if (PRINT_MISSING) System.err.println("No documentation in Core: "+sym);
				} else {
					doDocTest(sym,doc);
				}
			}
		}
	}
	
	public void doDocTest(Symbol sym,AHashMap<ACell,ACell> doc) {
		String desc=RT.jvm(doc.get(Keywords.DESCRIPTION));
		if (desc==null) {
			if (PRINT_MISSING) System.err.println("No description on Core def: "+sym);
		}
		
		@SuppressWarnings("unchecked")
		AVector<AHashMap<ACell,ACell>> examples=(AVector<AHashMap<ACell, ACell>>) doc.get(Keywords.EXAMPLES);
		if (examples!=null) {
			for (AHashMap<ACell,ACell> ex:examples) {
				doExampleTest(sym,ex);
			}
		}
	}

	private void doExampleTest(Symbol sym, AHashMap<ACell, ACell> ex) {
		String code=RT.jvm( ex.get(Keywords.CODE));
		
		Context<?> ctx=step(code);
		assertNotNull(ctx);
	}
}
