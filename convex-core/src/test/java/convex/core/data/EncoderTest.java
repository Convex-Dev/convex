package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Order;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.CVMTag;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.AEncoder.DecodeState;
import convex.core.data.prim.CVMBigInteger;
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

	// ==================== No-store multi-cell decode validation ====================

	@Test public void testMultiCellNoStoreComplete() throws BadFormatException {
		// Complete multi-cell message decoded with no store should succeed
		// and produce 100% RefDirect for all non-embedded refs
		ACell big = Samples.INT_VECTOR_300;
		Blob enc = Format.encodeMultiCell(big, true);

		// Verify it's actually multi-cell (has children)
		assertTrue(enc.count() > Cells.encode(big).count(), "Should be multi-cell encoding");

		// Decode with no store
		assertNull(Stores.current());
		ACell decoded = CVM.decodeMultiCell(enc);
		assertEquals(big, decoded);

		// All refs must be direct (no RefSoft surviving)
		assertTrue(Refs.allRefsDirect(decoded), "All refs should be direct after complete message decode");

		// Double-check with visitAllRefs
		Refs.visitAllRefs(decoded.getRef(), r -> {
			if (!r.isEmbedded()) {
				assertTrue(r.isDirect(), "Non-embedded ref should be direct: " + r);
			}
		});

		// Also verify via CAD3Encoder
		ACell fromCAD3 = CAD3.decodeMultiCell(enc);
		assertEquals(big, fromCAD3);
		assertTrue(Refs.allRefsDirect(fromCAD3));
	}

	@Test public void testMultiCellNoStorePartialTopOnly() throws BadFormatException {
		// Encoding just the top cell of a multi-cell value (children not included).
		// With no store set, unresolved refs remain as RefSoft pointing to dead MessageStore.
		ACell big = Samples.INT_VECTOR_300;
		Blob topOnly = Cells.encode(big);

		// Confirm the original value has non-embedded child refs
		assertTrue(big.getRefCount() > 0, "Test value should have child refs");

		assertNull(Stores.current());

		// Decode succeeds but the result has non-direct refs (RefSoft to dead MessageStore)
		ACell decoded = CVM.decodeMultiCell(topOnly);
		assertNotNull(decoded);
		assertFalse(Refs.allRefsDirect(decoded),
			"Partial message decode should leave non-direct refs");

		// Same for CAD3Encoder
		ACell fromCAD3 = CAD3.decodeMultiCell(topOnly);
		assertNotNull(fromCAD3);
		assertFalse(Refs.allRefsDirect(fromCAD3),
			"Partial message decode should leave non-direct refs");
	}

	@Test public void testMultiCellNoStoreSingleEmbedded() throws BadFormatException {
		// Single embedded cell (no children needed) should decode fine with no store
		ACell small = Vectors.of(1, 2, 3);
		assertTrue(small.isEmbedded(), "Test value should be embedded");
		Blob enc = Cells.encode(small);

		assertNull(Stores.current());
		ACell decoded = CVM.decodeMultiCell(enc);
		assertEquals(small, decoded);
		assertTrue(Refs.allRefsDirect(decoded));
	}

	// ==================== Adversarial decodeMultiCell tests ====================

	/**
	 * Helper: builds a multi-cell message from a top cell and explicit children.
	 * Each child is preceded by its VLQ-encoded length.
	 */
	private static Blob buildMultiCell(ACell topCell, ACell... children) {
		Blob topEnc = Cells.encode(topCell);
		int totalLen = topEnc.size();
		Blob[] childEncs = new Blob[children.length];
		for (int i = 0; i < children.length; i++) {
			childEncs[i] = Cells.encode(children[i]);
			totalLen += Format.getVLQCountLength(childEncs[i].size()) + childEncs[i].size();
		}
		byte[] msg = new byte[totalLen];
		int ix = topEnc.getBytes(msg, 0);
		for (Blob childEnc : childEncs) {
			ix = Format.writeVLQCount(msg, ix, childEnc.size());
			ix = childEnc.getBytes(msg, ix);
		}
		return Blob.wrap(msg);
	}

	@Test public void testAdversarialEmptyBlob() {
		// Empty input must be rejected
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(Blob.EMPTY));
		assertThrows(BadFormatException.class, () -> CAD3.decodeMultiCell(Blob.EMPTY));
	}

	@Test public void testAdversarialExtraTrailingGarbage() {
		// Valid single-cell encoding with garbage bytes appended.
		// Decoder sees rl < ml, tries to parse children from garbage, must reject.
		ACell small = Vectors.of(1, 2, 3);
		Blob enc = Cells.encode(small);
		Blob bad = enc.append(Blob.wrap(new byte[]{(byte)0xFF, 0x01, 0x02})).toFlatBlob();

		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Trailing garbage after single cell should be rejected");
	}

	@Test public void testAdversarialExtraBytesAfterNil() {
		// Nil encoding (0x00) followed by extra bytes
		Blob bad = Blob.wrap(new byte[]{0x00, 0x01});
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Extra bytes after nil should be rejected");
	}

	@Test public void testAdversarialExtraUnreferencedChildren() throws BadFormatException {
		// Valid multi-cell message with additional children that are NOT referenced
		// by the top cell. These are extra payload the sender injected.
		ACell big = Samples.INT_VECTOR_300;
		Blob validEnc = Format.encodeMultiCell(big, true);

		// Use a large blob as unreferenced child — must be non-embedded
		ACell unreferenced = Blob.createRandom(new java.util.Random(1234), 200);
		assertFalse(unreferenced.isEmbedded(), "unreferenced cell must be non-embedded");

		Blob unreferencedEnc = Cells.encode(unreferenced);
		byte[] vlq = new byte[Format.getVLQCountLength(unreferencedEnc.size())];
		Format.writeVLQCount(vlq, 0, unreferencedEnc.size());

		Blob extraMessage = validEnc.append(Blob.wrap(vlq)).append(unreferencedEnc).toFlatBlob();

		// Decode should succeed — extra children are silently ignored by resolveRefs
		// (they go into the HashMap but are never looked up)
		assertNull(Stores.current());
		ACell decoded = CVM.decodeMultiCell(extraMessage);
		assertEquals(big, decoded);
	}

	@Test public void testAdversarialTruncatedChild() {
		// Multi-cell message where VLQ length claims N bytes but fewer are available
		ACell big = Samples.INT_VECTOR_300;
		Blob validEnc = Format.encodeMultiCell(big, true);

		// Chop off last 10 bytes — truncates the final child encoding
		Blob truncated = validEnc.slice(0, validEnc.count() - 10);
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(truncated),
			"Truncated child should be rejected");
	}

	@Test public void testAdversarialBadVLQLength() {
		// Multi-cell message with a VLQ child length that exceeds remaining data
		ACell small = Vectors.of(1, 2, 3);
		Blob topEnc = Cells.encode(small);

		// Append VLQ claiming 9999 bytes followed by only 2 bytes
		byte[] vlq = new byte[Format.getVLQCountLength(9999)];
		Format.writeVLQCount(vlq, 0, 9999);
		Blob bad = topEnc.append(Blob.wrap(vlq)).append(Blob.wrap(new byte[]{0x10, 0x01})).toFlatBlob();

		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"VLQ length exceeding remaining data should be rejected");
	}

	@Test public void testAdversarialZeroLengthChild() {
		// Multi-cell message with a zero-length child encoding (VLQ = 0)
		ACell small = Vectors.of(1, 2, 3);
		Blob topEnc = Cells.encode(small);

		// VLQ(0) followed by nothing — the child encoding is empty
		Blob bad = topEnc.append(Blob.wrap(new byte[]{0x00})).toFlatBlob();

		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Zero-length child encoding should be rejected");
	}

	@Test public void testAdversarialEmbeddedCellAsChild() {
		// A child that is embedded (small value) — readChildCells must reject this
		ACell big = Samples.INT_VECTOR_300;
		Blob topEnc = Cells.encode(big);

		// Use CVMLong.ONE as "child" — it's embedded (1 byte encoding)
		ACell embedded = Vectors.of(1); // small vector, should be embedded
		assertTrue(embedded.isEmbedded(), "Test child must be embedded");

		Blob msg = buildMultiCell(big, embedded);
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(msg),
			"Embedded cell as child should be rejected");
	}

	@Test public void testAdversarialNullChildEncoding() {
		// A child that decodes to null (0x00 tag)
		ACell big = Samples.INT_VECTOR_300;
		Blob topEnc = Cells.encode(big);

		// Manually append VLQ(1) + 0x00 (null encoding)
		Blob bad = topEnc.append(Blob.wrap(new byte[]{0x01, 0x00})).toFlatBlob();
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Null child encoding should be rejected");
	}

	@Test public void testAdversarialCorruptedChildEncoding() {
		// Valid VLQ length but garbage bytes for the child encoding
		ACell big = Samples.INT_VECTOR_300;
		Blob topEnc = Cells.encode(big);

		// VLQ(5) + 5 bytes of 0xFF garbage — invalid tag
		Blob bad = topEnc.append(Blob.wrap(new byte[]{
			0x05, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF
		})).toFlatBlob();
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Corrupted child encoding should be rejected");
	}

	@Test public void testAdversarialDuplicateChildren() throws BadFormatException {
		// Complete message with the same child included twice.
		// HashMap.put overwrites, so this should be harmless.
		ACell big = Samples.INT_VECTOR_300;
		Blob validEnc = Format.encodeMultiCell(big, true);
		Blob topEnc = Cells.encode(big);

		// Get the children portion from the valid encoding
		Blob childrenPortion = validEnc.slice(topEnc.size(), validEnc.count());

		// Append children twice
		Blob doubled = topEnc.append(childrenPortion).append(childrenPortion).toFlatBlob();

		assertNull(Stores.current());
		ACell decoded = CVM.decodeMultiCell(doubled);
		assertEquals(big, decoded);
	}

	@Test public void testAdversarialHugeVLQCount() {
		// VLQ encoding a massive count (Integer.MAX_VALUE) — should fail gracefully
		ACell small = Vectors.of(1, 2, 3);
		Blob topEnc = Cells.encode(small);

		// Encode Integer.MAX_VALUE as VLQ
		long hugeCount = Integer.MAX_VALUE;
		byte[] vlq = new byte[Format.getVLQCountLength(hugeCount)];
		Format.writeVLQCount(vlq, 0, hugeCount);

		Blob bad = topEnc.append(Blob.wrap(vlq)).toFlatBlob();
		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Huge VLQ count should be rejected when data is insufficient");
	}

	@Test public void testAdversarialSingleTrailingByte() {
		// One extra byte after valid encoding — not enough for VLQ + child
		ACell small = Vectors.of(1, 2, 3);
		Blob enc = Cells.encode(small);
		Blob bad = enc.append(Blob.wrap(new byte[]{0x42})).toFlatBlob();

		assertThrows(BadFormatException.class, () -> CVM.decodeMultiCell(bad),
			"Single trailing byte should be rejected");
	}

	// ==================== DecodeState round-trip tests ====================

	/**
	 * Helper: encode a value, decode via DecodeState using the given encoder,
	 * assert result equals original and pos advanced correctly.
	 */
	private void doDecodeStateRoundTrip(ACell value, CAD3Encoder enc) throws BadFormatException {
		Blob encoding = Cells.encode(value);
		DecodeState ds = new DecodeState(encoding);
		ACell decoded = enc.read(ds);
		assertEquals(value, decoded, "DecodeState round-trip mismatch for: " + value);
		assertEquals(ds.limit, ds.pos, "DecodeState pos should be at limit after reading complete encoding");
		// Re-encoding must match
		if (decoded != null) {
			assertEquals(encoding, Cells.encode(decoded), "Re-encoding mismatch after DecodeState read");
		}
	}

	@Test public void testDecodeStateCVMLong() throws BadFormatException {
		CVMLong[] values = {
			CVMLong.ZERO, CVMLong.ONE, CVMLong.MINUS_ONE,
			CVMLong.create(42), CVMLong.create(-42),
			CVMLong.create(127), CVMLong.create(128),
			CVMLong.create(-128), CVMLong.create(-129),
			CVMLong.MAX_VALUE, CVMLong.MIN_VALUE,
		};
		for (CVMLong v : values) {
			doDecodeStateRoundTrip(v, CVM);
			doDecodeStateRoundTrip(v, CAD3);
		}
	}

	@Test public void testDecodeStateCVMBigInteger() throws BadFormatException {
		CVMBigInteger[] values = {
			CVMBigInteger.MIN_POSITIVE,  // Long.MAX_VALUE + 1
			CVMBigInteger.MIN_NEGATIVE,  // Long.MIN_VALUE - 1
		};
		for (CVMBigInteger v : values) {
			doDecodeStateRoundTrip(v, CVM);
			doDecodeStateRoundTrip(v, CAD3);
		}
	}

	@Test public void testDecodeStateVectorLeaf() throws BadFormatException {
		AVector<?>[] values = {
			Vectors.empty(),
			Vectors.of(1),
			Vectors.of(1, 2, 3),
			Vectors.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), // MAX_SIZE
		};
		for (AVector<?> v : values) {
			doDecodeStateRoundTrip(v, CVM);
			doDecodeStateRoundTrip(v, CAD3);
		}
	}

	@Test public void testDecodeStateVectorTree() throws BadFormatException {
		// INT_VECTOR_256 is a VectorTree (256 elements = 16 chunks of 16)
		Stores.setCurrent(Samples.TEST_STORE);
		try {
			doDecodeStateRoundTrip(Samples.INT_VECTOR_256, CVM);
			doDecodeStateRoundTrip(Samples.INT_VECTOR_256, CAD3);
		} finally {
			Stores.setCurrent(null);
		}
	}

	@Test public void testDecodeStateVectorWithPrefix() throws BadFormatException {
		// INT_VECTOR_300 is a VectorLeaf with a prefix (300 = 256 + 44, tail has 44 mod 16 = 12 items)
		Stores.setCurrent(Samples.TEST_STORE);
		try {
			doDecodeStateRoundTrip(Samples.INT_VECTOR_300, CVM);
			doDecodeStateRoundTrip(Samples.INT_VECTOR_300, CAD3);
		} finally {
			Stores.setCurrent(null);
		}
	}
}
