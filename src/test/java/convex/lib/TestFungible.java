package convex.lib;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class TestFungible {
	private static final Symbol fSym=Symbol.create("fungible");
	
	private static Context<?> loadFungible() {
		Context<?> ctx=TestState.INITIAL_CONTEXT;
		try {
			ctx=ctx.deployActor(Reader.readAll(Utils.readResourceAsString("libraries/fungible.con")), true);
		} catch (IOException e) {
			throw new Error(e);
		}
		ctx=ctx.define(fSym, Syntax.create(ctx.getResult()));
		
		return ctx;
	}
	
	private static final Context<?> ctx=loadFungible();
	private static final Address fungible=(Address) ctx.lookup(fSym).getResult();
	
	@Test public void testLibraryProperties() {
		assertTrue(ctx.getAccountStatus(fungible).isActor());
	}
}
