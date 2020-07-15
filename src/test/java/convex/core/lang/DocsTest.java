package convex.core.lang;

import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Symbol;
import convex.core.data.Syntax;

public class DocsTest {
	public static final boolean PRINT_MISSING=true;
	
	@Test public void testDocs() {
		for (Map.Entry<Symbol,Syntax> me: Core.ENVIRONMENT.entrySet()) {
			Symbol sym=me.getKey();
			Syntax syntax=me.getValue();
			AHashMap<Object, Object> meta = syntax.getMeta();
			if (meta.isEmpty()) {
				if (PRINT_MISSING) System.err.println("Empty metadata in Core: "+sym);
			} else {
				@SuppressWarnings("unchecked")
				AHashMap<Object,Object> doc=(AHashMap<Object, Object>) meta.get(Keywords.DOC);
				if (doc==null) {
					if (PRINT_MISSING) System.err.println("No documentation in Core: "+sym);
				} else {
					doDocTest(sym,doc);
				}
			}
		}
	}
	
	public void doDocTest(Symbol sym,AHashMap<Object,Object> doc) {
		String desc=(String) doc.get(Keywords.DESCRIPTION);
		if (desc==null) {
			if (PRINT_MISSING) System.err.println("No description on Core def: "+sym);
		}
		
		@SuppressWarnings("unchecked")
		AVector<AHashMap<Object,Object>> examples=(AVector<AHashMap<Object, Object>>) doc.get(Keywords.EXAMPLES);
		if (examples!=null) {
			for (AHashMap<Object,Object> ex:examples) {
				doExampleTest(sym,ex);
			}
		}
	}

	private void doExampleTest(Symbol sym, AHashMap<Object, Object> ex) {
		String code=(String) ex.get(Keywords.CODE);
		
		Context<?> ctx=step(code);
		assertNotNull(ctx);
	}
}
