package convex.core.lang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.cvm.Symbols;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Local;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;
import convex.core.lang.impl.MultiFn;
import convex.test.Samples;

public class FunctionTest {

	State STATE=State.EMPTY.addActor();

	@Test public void testFnApply() {
		Context ctx=Context.create(STATE);
		Fn<CVMLong> f0=Fn.create(Vectors.empty(), Constant.create(CVMLong.ONE));
		assertEquals(CVMLong.ONE,f0.invoke(ctx, new ACell[0]).getResult());

		Fn<CVMLong> f1=Fn.create(Vectors.of(Symbols.FOO), Local.create(0));
		assertEquals(CVMLong.ZERO,f1.invoke(ctx, new ACell[] {CVMLong.ZERO}).getResult());
	}

	/**
	 * Regression test: MultiFn decoded via decodeMultiCell must retain its type.
	 *
	 * A MultiFn's first vector element is a non-embedded Fn (not a ByteFlag).
	 * During multi-cell child decode, refs are unresolvable (no store). If the
	 * decoder tries to dereference the first element to distinguish Fn vs MultiFn,
	 * it throws MissingDataException, which the catch-all silently converts to a
	 * DenseRecord. The cell then has the correct encoding/hash but wrong Java type,
	 * causing CVM execution to diverge.
	 */
	@Test public void testMultiFnMultiCellDecode() throws Exception {
		// Use a Constant wrapping a large Blob as the body, so each Fn's
		// encoding exceeds MAX_EMBEDDED_LENGTH (140 bytes). A Blob of 132 bytes
		// encodes to 135 bytes (tag + 2-byte VLQ + data), the Constant to 137,
		// and the Fn to ~143 bytes total — safely non-embedded.
		Blob bigBlob = Blob.wrap(new byte[132]);
		Fn<ACell> fn0 = Fn.create(Vectors.empty(), Constant.create(bigBlob));
		assertTrue(fn0.getEncodingLength() > Format.MAX_EMBEDDED_LENGTH,
			"Fn must be non-embedded for this test (actual=" + fn0.getEncodingLength() + ")");

		Fn<ACell> fn1 = Fn.create(Vectors.of(Symbols.FOO), Constant.create(bigBlob));
		assertTrue(fn1.getEncodingLength() > Format.MAX_EMBEDDED_LENGTH,
			"Fn1 must also be non-embedded for this test");

		MultiFn<ACell> mfn = MultiFn.create(Vectors.of(fn0, fn1));
		assertInstanceOf(MultiFn.class, mfn);

		// Encode as multi-cell (children include the non-embedded Fn objects)
		Blob encoded = Format.encodeMultiCell(mfn, true);
		ACell decoded = Samples.TEST_STORE.decodeMultiCell(encoded);

		// Must be MultiFn, not DenseRecord
		assertInstanceOf(MultiFn.class, decoded,
			"MultiFn must survive multi-cell round-trip (not degrade to DenseRecord)");
		assertEquals(mfn.getHash(), decoded.getHash());
	}
}
