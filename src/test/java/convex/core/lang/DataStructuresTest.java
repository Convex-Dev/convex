package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitConfigTest;
import convex.core.util.Utils;

/**
 * Tests for medium sized data structure operations.
 *
 */
public class DataStructuresTest {

	private static final State INITIAL = TestState.STATE;
	private static final long INITIAL_JUICE = 100000;
	private static final Context<?> INITIAL_CONTEXT;

	static {
		try {
			INITIAL_CONTEXT = Context.createInitial(INITIAL, InitConfigTest.HERO_ADDRESS, INITIAL_JUICE);
		} catch (Throwable e) {
			throw new Error(e);
		}
	}

	public <T extends ACell> T eval(String source) {
		try {
			Context<?> c = INITIAL_CONTEXT;
			AOp<T> op = TestState.compile(c, source);
			Context<T> rc = c.execute(op);
			return rc.getResult();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test
	public void testSetRoundTripRegression() {
		ASet<CVMLong> a = eval("#{1,8,0,4,9,5,2,3,7,6}");
		assertEquals(10, a.count());
		ASequence<CVMLong> b = RT.sequence(a);
		assertEquals(10, b.count());
		ASet<CVMLong> c = RT.castSet(b);
		assertEquals(a, c);
	}

	@Test
	public void testRefCounts() {
		assertEquals(0, Utils.totalRefCount(Vectors.empty()));
		assertEquals(2, Utils.totalRefCount(Vectors.of(1, 2)));
		assertEquals(1, Utils.totalRefCount(eval("(fn [a] a)"))); // 1 Ref in params [symbol]
		assertEquals(6, Utils.totalRefCount(eval("[[1 2] [3 4]]"))); // 6 vector element Refs
	}

}
