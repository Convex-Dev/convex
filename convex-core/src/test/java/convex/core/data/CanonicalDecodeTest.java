package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.BlockResult;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.State;
import convex.core.cvm.Syntax;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Multi;
import convex.core.data.AEncoder.DecodeState;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.test.Samples;

/**
 * Bytes that decode as a whole cell re-encode to exactly those bytes, as CAD003 requires
 * of a valid encoding. Decoding relies on this: a decoded child keeps its received bytes
 * as its encoding, and an inline cell's encoding length is the bytes it consumed.
 *
 * Seeds are every cell of the committed genesis, Belief and DLFS snapshots plus fixed
 * sample values. Each seed, its mutations and every short byte string are checked with
 * the CVM and the plain CAD3 decoder.
 */
public class CanonicalDecodeTest {

	private static final AStore STORE = new MemoryStore();
	private static final CAD3Encoder[] ENCODERS = { new CVMEncoder(STORE), new CAD3Encoder(STORE) };

	/** Seeds per decoded class taken for mutation, spread over that class's encoding lengths */
	private static final int SEEDS_PER_CLASS = 16;
	/** Leading bytes mutated exhaustively: tags, counts and the first fields */
	private static final int HEADER_POSITIONS = 8;
	private static final int RANDOM_MUTANTS_PER_SEED = 200;

	/** Encodings of every seed cell, in a fixed order */
	private static final List<Blob> ALL_SEEDS = new ArrayList<>();
	/** The seeds mutated, a few of each decoded class */
	private static final List<Blob> MUTATION_SEEDS = new ArrayList<>();

	static {
		try {
			collectSeeds();
		} catch (IOException | BadFormatException e) {
			throw new IllegalStateException("Cannot load seed snapshots", e);
		}
	}

	@Test
	public void testSeedsRoundTrip() throws BadFormatException {
		assertFalse(ALL_SEEDS.isEmpty());
		for (CAD3Encoder enc : ENCODERS) {
			for (Blob seed : ALL_SEEDS) {
				// A valid encoding must decode, as well as re-encode identically
				DecodeState ds = new DecodeState(seed);
				enc.read(ds);
				assertTrue(ds.isConsumed(), "Seed not fully consumed: " + seed.toHexString());
				checkCanonical(enc, seed.getBytes());
			}
		}
	}

	/** Each seed is independent; let JUnit share the exhaustive work across its workers. */
	static Stream<Blob> mutationSeeds() {
		return MUTATION_SEEDS.stream();
	}

	@ParameterizedTest(name="header mutations for seed {index}")
	@MethodSource("mutationSeeds")
	public void testHeaderMutations(Blob seed) {
		for (CAD3Encoder enc : ENCODERS) {
			byte[] orig = seed.getBytes();
			int lim = Math.min(HEADER_POSITIONS, orig.length);
			for (int p = 0; p < lim; p++) {
				for (int v = 0; v < 256; v++) {
					if ((byte) v == orig[p]) continue;
					byte[] bs = orig.clone();
					bs[p] = (byte) v;
					checkCanonical(enc, bs);
				}
				// Padding and truncation of VLQ counts and integers
				checkCanonical(enc, insert(orig, p, (byte) 0x80));
				checkCanonical(enc, insert(orig, p, (byte) 0x00));
				checkCanonical(enc, delete(orig, p));
			}
		}
	}

	@Test
	public void testRandomMutations() {
		for (CAD3Encoder enc : ENCODERS) {
			Random r = new Random(99);
			for (Blob seed : MUTATION_SEEDS) {
				byte[] orig = seed.getBytes();
				for (int k = 0; k < RANDOM_MUTANTS_PER_SEED; k++) {
					int pos = r.nextInt(orig.length);
					byte[] bs = switch (r.nextInt(6)) {
						case 0 -> replace(orig, pos, (byte) r.nextInt(256));
						case 1 -> replace(orig, pos, (byte) (orig[pos] ^ (1 << r.nextInt(8))));
						case 2 -> insert(orig, pos, (byte) r.nextInt(256));
						case 3 -> insert(orig, pos, (byte) 0x80);
						case 4 -> insert(orig, pos, (byte) 0x00);
						default -> delete(orig, pos);
					};
					checkCanonical(enc, bs);
				}
			}
		}
	}

	@Test
	public void testShortByteStrings() {
		for (CAD3Encoder enc : ENCODERS) {
			for (int v = 0; v < 256; v++) {
				checkCanonical(enc, new byte[] { (byte) v });
			}
			for (int v = 0; v < 65536; v++) {
				checkCanonical(enc, new byte[] { (byte) (v >>> 8), (byte) v });
			}
		}
	}

	/**
	 * Fails if the bytes decode as a whole cell whose fresh encoding differs from them.
	 * Rejected bytes pass.
	 */
	private static void checkCanonical(CAD3Encoder enc, byte[] bs) {
		Blob input = Blob.wrap(bs);
		ACell c;
		try {
			DecodeState ds = new DecodeState(input);
			c = enc.read(ds);
			if (!ds.isConsumed()) return;
		} catch (BadFormatException | IndexOutOfBoundsException e) {
			return;
		}
		Blob fresh;
		try {
			fresh = (c == null) ? Blob.NULL_ENCODING : freshEncoding(c);
		} catch (RuntimeException e) {
			throw new AssertionError("Cannot re-encode " + Utils.getClassName(c) + " decoded from " + input.toHexString(), e);
		}
		if (!fresh.equalsBytes(input)) {
			fail(Utils.getClassName(c) + " decoded from " + input.toHexString() + " re-encodes as " + fresh.toHexString());
		}
	}

	/**
	 * Writes a cell's encoding from its fields. Readers may keep slices of their input as
	 * encodings, and writing a cell copies its embedded children's cached encodings, so
	 * each of those is first checked against one written the same way. Cells are left
	 * untouched: decoders return shared instances, and tests run in parallel.
	 */
	private static Blob freshEncoding(ACell c) {
		int n = c.getRefCount();
		for (int i = 0; i < n; i++) {
			Ref<ACell> ref = c.getRef(i);
			if (!ref.isDirect()) continue;
			ACell child = ref.getValue();
			if (child == null) continue;
			Blob fresh = freshEncoding(child);
			Blob cached = child.cachedEncoding();
			if ((cached != null) && !cached.equalsBytes(fresh)) {
				fail(Utils.getClassName(child) + " holds encoding " + cached.toHexString() + " but re-encodes as " + fresh.toHexString());
			}
		}
		return c.createEncoding();
	}

	private static void collectSeeds() throws IOException, BadFormatException {
		List<ACell> roots = new ArrayList<>();
		for (String res : new String[] { "/genesis.cad3", "/belief-1772448810106.cad3", "/dlfs-0.8.18.cad3" }) {
			roots.add(new MemoryStore().decodeMultiCell(load(res)));
		}
		roots.addAll(sampleValues());
		Result[] results = { Result.create(CVMLong.ONE, Samples.INT_VECTOR_10), Result.error(ErrorCodes.FUNDS, Samples.RUSSIAN_STRING) };
		roots.add(BlockResult.create((State) roots.get(0), results));

		// Every cell reachable through direct refs, once per encoding
		Map<Blob, ACell> cells = new LinkedHashMap<>();
		Set<ACell> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ACell root : roots) collect(root, cells, seen);
		ALL_SEEDS.addAll(cells.keySet());

		// Group by the class each seed decodes as, ordered by length then bytes
		TreeMap<String, List<Blob>> byClass = new TreeMap<>();
		for (Blob seed : ALL_SEEDS) {
			Class<?> k = ENCODERS[0].read(new DecodeState(seed)).getClass();
			// Each core function is its own anonymous class, with one shape of encoding
			while (k.isAnonymousClass()) k = k.getSuperclass();
			byClass.computeIfAbsent(k.getName(), x -> new ArrayList<>()).add(seed);
		}
		for (List<Blob> group : byClass.values()) {
			group.sort((a, b) -> (a.count() != b.count()) ? Long.compare(a.count(), b.count()) : a.compareTo(b));
			int n = group.size();
			int take = Math.min(SEEDS_PER_CLASS, n);
			for (int i = 0; i < take; i++) {
				MUTATION_SEEDS.add(group.get((take == 1) ? 0 : (int) ((long) i * (n - 1) / (take - 1))));
			}
		}
	}

	private static void collect(ACell c, Map<Blob, ACell> out, Set<ACell> seen) {
		if (c == null || !seen.add(c)) return;
		out.putIfAbsent(Cells.encode(c), c);
		int n = c.getRefCount();
		for (int i = 0; i < n; i++) {
			Ref<ACell> ref = c.getRef(i);
			if (ref.isDirect()) collect(ref.getValue(), out, seen);
		}
	}

	/** Fixed values for kinds of cell the snapshots hold few or none of */
	private static List<ACell> sampleValues() {
		List<ACell> vs = new ArrayList<>();
		vs.add(Samples.INT_VECTOR_300);
		vs.add(Samples.INT_LIST_300);
		vs.add(Samples.INT_SET_10);
		vs.add(Samples.INT_SET_300);
		vs.add(Samples.LONG_MAP_100);
		vs.add(Samples.INT_INDEX_7);
		vs.add(Samples.INT_INDEX_256);
		// Random content from a fixed seed, so every run has the same seeds
		Random r = new Random(1234);
		int maxEmbeddedBlobLength = Format.MAX_EMBEDDED_LENGTH - Format.getVLQCountLength(Format.MAX_EMBEDDED_LENGTH) - 1;
		vs.add(Blobs.createRandom(r, 10000));
		vs.add(Blobs.createRandom(r, Blob.CHUNK_LENGTH + 1));
		vs.add(Blobs.createRandom(r, maxEmbeddedBlobLength));
		vs.add(Blobs.createRandom(r, maxEmbeddedBlobLength + 1));
		vs.add(Vectors.of(Blobs.createRandom(r, 106), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
		vs.add(Samples.MAX_EMBEDDED_STRING);
		vs.add(Samples.NON_EMBEDDED_STRING);
		vs.add(Samples.MAX_SHORT_STRING);
		vs.add(Samples.MIN_TREE_STRING);
		vs.add(Samples.RUSSIAN_STRING);
		vs.add(Samples.MAX_BIGINT);
		vs.add(Samples.MIN_BIGINT);
		vs.add(Samples.INVOKE_TRANSACTION);
		vs.add(Samples.TRANSFER_TRANSACTION);
		Call call = Call.create(Address.create(1), 2, Address.create(8), 100, Symbol.create("foo"), Vectors.of(1, Samples.FOO));
		vs.add(call);
		vs.add(Multi.create(Address.create(1), 3, Multi.MODE_UNTIL, call, Samples.TRANSFER_TRANSACTION));
		vs.add(Samples.EMPTY_ORDER);
		vs.add(Samples.KEY_PAIR.signData(Samples.INT_VECTOR_10));
		vs.add(Symbol.create(Samples.MAX_SYMBOLIC));
		vs.add(Keyword.create(Samples.MAX_SYMBOLIC));
		vs.add(Syntax.create(Samples.FOO, Maps.of(Samples.BAR, Samples.INT_VECTOR_10)));
		vs.add(Vectors.of(Samples.CHAR_UTF_1, Samples.CHAR_UTF_2, Samples.CHAR_UTF_3, Samples.CHAR_UTF_4));
		vs.add(Vectors.of(CVMLong.ZERO, CVMLong.MINUS_ONE, CVMLong.MAX_VALUE, CVMLong.MIN_VALUE, 127, 128, -128, -129));
		vs.add(Vectors.of(CVMDouble.NaN, CVMDouble.create(Double.longBitsToDouble(0x7ff0000000000001L)), CVMDouble.create(-0.0),
				CVMDouble.create(Double.MIN_VALUE), CVMDouble.POSITIVE_INFINITY, CVMBool.TRUE, CVMBool.FALSE));
		vs.add(Vectors.of(Address.create(0), Address.create(Long.MAX_VALUE), Samples.ACCOUNT_KEY));
		// Tags no CVM type claims
		vs.add(Vectors.of(ExtensionValue.create((byte) 0xE1, 1234), CodedValue.create(0xC8, Samples.FOO, Samples.INT_VECTOR_10)));
		return vs;
	}

	private static Blob load(String resource) throws IOException {
		try (InputStream is = CanonicalDecodeTest.class.getResourceAsStream(resource)) {
			assertNotNull(is, resource);
			return Blob.wrap(is.readAllBytes());
		}
	}

	private static byte[] replace(byte[] bs, int pos, byte b) {
		byte[] r = bs.clone();
		r[pos] = b;
		return r;
	}

	private static byte[] insert(byte[] bs, int pos, byte b) {
		byte[] r = new byte[bs.length + 1];
		System.arraycopy(bs, 0, r, 0, pos);
		r[pos] = b;
		System.arraycopy(bs, pos, r, pos + 1, bs.length - pos);
		return r;
	}

	private static byte[] delete(byte[] bs, int pos) {
		byte[] r = new byte[bs.length - 1];
		System.arraycopy(bs, 0, r, 0, pos);
		System.arraycopy(bs, pos + 1, r, pos, bs.length - pos - 1);
		return r;
	}
}
