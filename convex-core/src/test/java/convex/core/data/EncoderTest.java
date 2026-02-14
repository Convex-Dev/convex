package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Order;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.CVMTag;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Core;
import convex.core.store.Stores;
import convex.test.Samples;

/**
 * Cross-encoder tests verifying the encoder hierarchy contract:
 *
 * 1. Any encoding produced by CVMEncoder is decodable by CAD3Encoder.
 *    CVM-specific types (transactions, ops, consensus records) decode to
 *    generic CAD3 representations (DenseRecord, CodedValue, ExtensionValue).
 *
 * 2. CVMEncoder produces the specific CVM Java class for known CVM tags,
 *    but falls back to generic CAD3 types for unrecognised data.
 *
 * 3. Both decodings are always equal to each other and produce identical
 *    re-encodings, ensuring the CAD3 format is the canonical representation.
 */
public class EncoderTest {

	static final CAD3Encoder CAD3 = CAD3Encoder.INSTANCE;
	static final CVMEncoder CVM = CVMEncoder.INSTANCE;

	/**
	 * Verifies a value decodes through both encoders producing equal results
	 * with identical re-encodings.
	 */
	private void doCrossEncoderTest(ACell value) throws BadFormatException {
		Blob enc = Cells.encode(value);

		ACell fromCVM = CVM.decode(enc);
		ACell fromCAD3 = CAD3.decode(enc);

		assertNotNull(fromCVM);
		assertNotNull(fromCAD3);

		// Both decodings must be equal
		assertEquals(fromCVM, fromCAD3);
		assertEquals(fromCAD3, fromCVM);

		// Re-encoding must reproduce the original bytes
		assertEquals(enc, Cells.encode(fromCVM));
		assertEquals(enc, Cells.encode(fromCAD3));
	}

	/**
	 * Verifies a CVM-specific value decodes to the expected CVM type through
	 * CVMEncoder, but to the expected generic CAD3 type through CAD3Encoder.
	 * Both decodings must be equal.
	 */
	private void doCVMTypeTest(ACell value, Class<?> cvmClass, Class<?> cad3Class) throws BadFormatException {
		Blob enc = Cells.encode(value);

		ACell fromCVM = CVM.decode(enc);
		ACell fromCAD3 = CAD3.decode(enc);

		// CVMEncoder produces the specific CVM Java class
		assertInstanceOf(cvmClass, fromCVM, "CVMEncoder should produce " + cvmClass.getSimpleName());

		// CAD3Encoder produces the generic CAD3 representation
		assertInstanceOf(cad3Class, fromCAD3, "CAD3Encoder should produce " + cad3Class.getSimpleName());

		// Both are equal despite being different Java types
		assertEquals(fromCVM, fromCAD3);
		assertEquals(fromCAD3, fromCVM);

		// Re-encoding is identical
		assertEquals(enc, Cells.encode(fromCVM));
		assertEquals(enc, Cells.encode(fromCAD3));
	}

	// ==================== Shared CAD3 types: identical through both encoders ====================

	@Test public void testSharedTypes() throws BadFormatException {
		// Standard CAD3 types decode identically through both encoders
		ACell[] shared = new ACell[] {
			CVMLong.ZERO, CVMLong.ONE, CVMLong.create(-1), CVMLong.create(Long.MAX_VALUE),
			CVMDouble.ZERO, CVMDouble.ONE, CVMDouble.NaN,
			CVMBool.TRUE, CVMBool.FALSE,
			Address.ZERO, Address.create(999),
			Keyword.create("test"), Symbol.create("foo"),
			Blob.EMPTY, Samples.SMALL_BLOB,
			Strings.create("hello"), Strings.EMPTY,
			Vectors.empty(), Vectors.of(1, 2, 3),
			Lists.empty(), Lists.of(1, 2),
			Sets.empty(), Sets.of(1, 2, 3),
			Maps.empty(), Maps.of(1, 2),
			Index.none(),
		};
		for (ACell v : shared) {
			doCrossEncoderTest(v);
		}
	}

	@Test public void testNil() throws BadFormatException {
		Blob nilEnc = Blob.wrap(new byte[] { 0x00 });
		assertNull(CAD3.decode(nilEnc));
		assertNull(CVM.decode(nilEnc));
	}

	// ==================== CVM transactions: dense records (0xD0 range) ====================

	@Test public void testTransactionInvoke() throws BadFormatException {
		// Invoke transaction (tag 0xD0) is a CVM dense record.
		// CVMEncoder → Invoke, CAD3Encoder → DenseRecord
		doCVMTypeTest(Samples.INVOKE_TRANSACTION, Invoke.class, DenseRecord.class);
	}

	@Test public void testTransactionTransfer() throws BadFormatException {
		// Transfer transaction (tag 0xD1)
		doCVMTypeTest(Samples.TRANSFER_TRANSACTION, Transfer.class, DenseRecord.class);
	}

	// ==================== CVM consensus records: dense records (0xD0 range) ====================

	@Test public void testOrder() throws BadFormatException {
		// Order (tag 0xD7) is a CVM consensus record
		doCVMTypeTest(Samples.EMPTY_ORDER, Order.class, DenseRecord.class);
	}

	@Test public void testEmptyState() throws BadFormatException {
		// State (tag 0xD5) is a large CVM consensus record
		Stores.setCurrent(Samples.TEST_STORE);
		try {
			doCVMTypeTest(State.EMPTY, State.class, DenseRecord.class);
		} finally {
			Stores.setCurrent(null);
		}
	}

	// ==================== CVM extension values (0xE0 range) ====================

	@Test public void testCoreFunctions() throws BadFormatException {
		// Core defs (tag 0xED) decode to the actual core function via CVMEncoder,
		// but to a generic ExtensionValue via CAD3Encoder
		ACell[] coreFns = new ACell[] { Core.VECTOR, Core.PLUS, Core.COUNT, Core.MAP, Core.QUOTE };
		for (ACell fn : coreFns) {
			doCVMTypeTest(fn, fn.getClass(), ExtensionValue.class);
		}
	}

	@Test public void testUnknownExtension() throws BadFormatException {
		// Extension value with a tag not claimed by CVM — both encoders produce
		// identical ExtensionValue instances
		ExtensionValue ev = ExtensionValue.create((byte) 0xE3, 42);
		Blob enc = Cells.encode(ev);

		ACell fromCVM = CVM.decode(enc);
		ACell fromCAD3 = CAD3.decode(enc);

		// Both produce ExtensionValue — no CVM type claims this tag
		assertInstanceOf(ExtensionValue.class, fromCVM);
		assertInstanceOf(ExtensionValue.class, fromCAD3);
		assertEquals(ev, fromCVM);
		assertEquals(ev, fromCAD3);
	}

	// ==================== CVMEncoder fallback to generic CAD3 types ====================

	@Test public void testCVMFallbackDenseRecord() throws BadFormatException {
		// A DenseRecord with a CVM tag (e.g. 0xD4 = BELIEF) but data that doesn't
		// match the CVM type's format. CVMEncoder should try Belief.read, fail,
		// and fall back to DenseRecord — same as CAD3Encoder.
		AVector<ACell> wrongData = Vectors.of(Keyword.create("not"), Keyword.create("a"), Keyword.create("belief"));
		DenseRecord dr = DenseRecord.create(CVMTag.BELIEF, wrongData);
		Blob enc = Cells.encode(dr);

		ACell fromCVM = CVM.decode(enc);
		ACell fromCAD3 = CAD3.decode(enc);

		// Both fall back to DenseRecord since data doesn't match Belief format
		assertInstanceOf(DenseRecord.class, fromCVM, "CVMEncoder should fall back to DenseRecord for malformed CVM data");
		assertInstanceOf(DenseRecord.class, fromCAD3);
		assertEquals(dr, fromCVM);
		assertEquals(dr, fromCAD3);
	}

	@Test public void testCVMFallbackCodedValue() throws BadFormatException {
		// A CodedValue with the OP_CODED tag but a code whose first byte isn't a
		// recognised opcode (0x00-0x03 or 0x10-0x18). CVMBool.TRUE has tag 0xB1,
		// which causes Ops.readCodedOp to throw, triggering CVMEncoder's fallback.
		CodedValue cv = CodedValue.create(CVMTag.OP_CODED, CVMBool.TRUE, Vectors.of(1, 2, 3));
		Blob enc = Cells.encode(cv);

		ACell fromCVM = CVM.decode(enc);
		ACell fromCAD3 = CAD3.decode(enc);

		// Both produce CodedValue since the opcode doesn't match a known CVM op
		assertInstanceOf(CodedValue.class, fromCVM, "CVMEncoder should fall back to CodedValue for unknown coded op");
		assertInstanceOf(CodedValue.class, fromCAD3);
		assertEquals(cv, fromCVM);
		assertEquals(cv, fromCAD3);
	}

	// ==================== Multi-cell encoding ====================

	@Test public void testMultiCellCrossEncoder() throws BadFormatException {
		// Multi-cell encoding with non-embedded children
		Stores.setCurrent(Samples.TEST_STORE);
		try {
			ACell big = Samples.INT_VECTOR_300;
			Blob enc = Format.encodeMultiCell(big, true);

			ACell fromCVM = CVM.decodeMultiCell(enc);
			ACell fromCAD3 = CAD3.decodeMultiCell(enc);

			assertEquals(big, fromCVM);
			assertEquals(big, fromCAD3);
			assertEquals(fromCVM, fromCAD3);
		} finally {
			Stores.setCurrent(null);
		}
	}

	// ==================== Encoding consistency ====================

	@Test public void testEncodingConsistency() throws BadFormatException {
		// CAD3Encoder.encode and CVMEncoder.encode must produce identical bytes
		// for the same value, since encoding is type-independent
		ACell[] values = new ACell[] {
			CVMLong.create(42), Keyword.create("test"),
			Vectors.of(1, 2), Address.create(7),
			Samples.INVOKE_TRANSACTION, Samples.EMPTY_ORDER,
		};
		for (ACell v : values) {
			assertEquals(CAD3.encode(v), CVM.encode(v), "Encoding mismatch for: " + v);
		}
	}

	@Test public void testCAD3NeverProducesCVMTypes() throws BadFormatException {
		// CAD3Encoder must never produce CVM-specific Java types for dense records,
		// coded data, or extension values. These must always be generic CAD3 types.
		ACell[] cvmValues = new ACell[] {
			Samples.INVOKE_TRANSACTION, Samples.TRANSFER_TRANSACTION,
			Samples.EMPTY_ORDER, Core.VECTOR, Core.PLUS,
		};
		for (ACell v : cvmValues) {
			Blob enc = Cells.encode(v);
			ACell fromCAD3 = CAD3.decode(enc);
			byte tag = enc.byteAt(0);
			int category = (tag & 0xFF) >> 4;

			if (category == 0xD) {
				assertInstanceOf(DenseRecord.class, fromCAD3,
					"CAD3 should produce DenseRecord for tag 0x" + Integer.toHexString(tag & 0xFF));
				assertFalse(fromCAD3 instanceof Invoke);
				assertFalse(fromCAD3 instanceof Transfer);
				assertFalse(fromCAD3 instanceof Order);
			} else if (category == 0xE) {
				assertInstanceOf(ExtensionValue.class, fromCAD3,
					"CAD3 should produce ExtensionValue for tag 0x" + Integer.toHexString(tag & 0xFF));
			}

			// But equality must still hold
			assertEquals(v, fromCAD3);
		}
	}

	@Test public void testCVMTypeIdentity() throws BadFormatException {
		// CVMEncoder must produce the specific CVM Java type for known CVM values
		Stores.setCurrent(Samples.TEST_STORE);
		try {
			Object[][] cases = new Object[][] {
				{ Samples.INVOKE_TRANSACTION, Invoke.class },
				{ Samples.TRANSFER_TRANSACTION, Transfer.class },
				{ Samples.EMPTY_ORDER, Order.class },
				{ State.EMPTY, State.class },
				{ Core.VECTOR, Core.VECTOR.getClass() },
			};
			for (Object[] c : cases) {
				ACell value = (ACell) c[0];
				Class<?> expected = (Class<?>) c[1];
				Blob enc = Cells.encode(value);
				ACell decoded = CVM.decode(enc);
				assertInstanceOf(expected, decoded,
					"CVMEncoder should produce " + expected.getSimpleName() + " for " + value);
				assertTrue(decoded.isCVMValue(),
					"CVMEncoder-decoded value should be a CVM value: " + decoded);
			}
		} finally {
			Stores.setCurrent(null);
		}
	}
}
