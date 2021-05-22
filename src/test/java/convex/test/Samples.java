package convex.test;

import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.stream.Stream;

import org.junit.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.BlobTree;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapLeaf;
import convex.core.data.MapTree;
import convex.core.data.Maps;
import convex.core.data.Set;
import convex.core.data.Sets;
import convex.core.data.StringShort;
import convex.core.data.StringTree;
import convex.core.data.Strings;
import convex.core.data.Syntax;
import convex.core.data.VectorLeaf;
import convex.core.data.VectorTree;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Do;

/**
 * Miscellaneous value objects for testing purposes
 *
 */
public class Samples {

	public static Hash BAD_HASH = Hash.fromHex("1234000012340000123400001234000012340000123400001234000012340000");
	
	/**
	 * An Address which cannot be valid
	 */
	public static final Address BAD_ADDRESS = Address.create(7777777777L);
	public static final AccountKey BAD_ACCOUNTKEY = AccountKey.dummy("bbbb");
	
	public static final AKeyPair KEY_PAIR=Ed25519KeyPair.createSeeded(13371337L);
	public static final AccountKey ACCOUNT_KEY = KEY_PAIR.getAccountKey();
	
	public static final ASignature BAD_SIGNATURE = Ed25519Signature.wrap(Blobs.createRandom(64).getBytes());
	


	public static final VectorLeaf<CVMLong> INT_VECTOR_10 = createTestIntVector(10);
	public static final VectorLeaf<CVMLong> INT_VECTOR_16 = createTestIntVector(16);
	public static final VectorLeaf<CVMLong> INT_VECTOR_23 = createTestIntVector(23);
	public static final VectorTree<CVMLong> INT_VECTOR_32 = createTestIntVector(32);
	public static final VectorTree<CVMLong> INT_VECTOR_256 = createTestIntVector(256);
	public static final VectorLeaf<CVMLong> INT_VECTOR_300 = createTestIntVector(300);

	public static final AVector<AVector<CVMLong>> VECTOR_OF_VECTORS = Vectors.of(INT_VECTOR_10, INT_VECTOR_16,
			INT_VECTOR_23);

	public static final List<CVMLong> INT_LIST_10 = Lists.create(INT_VECTOR_10);
	public static final List<CVMLong> INT_LIST_300 = Lists.create(INT_VECTOR_300);

	public static final Set<CVMLong> INT_SET_10 = Sets.create(INT_VECTOR_10);
	public static final Set<CVMLong> INT_SET_300 = Sets.create(INT_VECTOR_300);

	
	public static final MapLeaf<CVMLong, CVMLong> LONG_MAP_5 = createTestLongMap(5);
	public static final MapTree<CVMLong, CVMLong> LONG_MAP_10 = createTestLongMap(10);
	public static final MapTree<CVMLong, CVMLong> LONG_MAP_100 = createTestLongMap(100);

	public static final BlobMap<Blob, CVMLong> INT_BLOBMAP_7 = BlobMaps.of(Blob.fromHex(""), 0, Blob.fromHex("0001"), 1,
			Blob.fromHex("01"), 2, Blob.fromHex("010000"), 3, Blob.fromHex("010001"), 4, Blob.fromHex("ff0000"), 5,
			Blob.fromHex("ff0101"), 6);

	public static final ASet<CVMLong> LONG_SET_5 = Sets.create(LONG_MAP_5.keySet());
	public static final ASet<CVMLong> LONG_SET_10 = Sets.create(LONG_MAP_10.keySet());
	public static final ASet<CVMLong> LONG_SET_100 = Sets.create(LONG_MAP_100.keySet());

	public static final Blob ONE_ZERO_BYTE_DATA = Blob.fromHex("00");

	public static final Keyword FOO = Keyword.create("foo");
	public static final Keyword BAR = Keyword.create("bar");

	public static final AVector<ACell> DIABOLICAL_VECTOR_30_30;
	public static final AVector<ACell> DIABOLICAL_VECTOR_2_10000;
	public static final AMap<ACell, ACell> DIABOLICAL_MAP_30_30;
	public static final AMap<ACell, ACell> DIABOLICAL_MAP_2_10000;

	public static final Random rand = new Random(123);
	public static final long BIG_BLOB_LENGTH = 10000;
	public static final BlobTree BIG_BLOB_TREE = Blobs.createRandom(Samples.rand, BIG_BLOB_LENGTH);
	public static final Blob FULL_BLOB = Blobs.createRandom(Samples.rand, Blob.CHUNK_LENGTH);
	
	public static final ASignature FAKE_SIGNATURE = Ed25519Signature.wrap(new byte[Ed25519Signature.SIGNATURE_LENGTH]);

	public static final Blob MAX_EMBEDDED_BLOB = createTestBlob(Format.MAX_EMBEDDED_LENGTH-Format.getVLCLength(Format.MAX_EMBEDDED_LENGTH)-1);
	public static final Blob NON_EMBEDDED_BLOB = createTestBlob(MAX_EMBEDDED_BLOB.count()+1);

	public static final StringShort MAX_EMBEDDED_STRING= StringShort.create("[0x1234567812345678123456781234567812345678123456781234567812345678]");
	public static final StringShort NON_EMBEDDED_STRING= StringShort.create(MAX_EMBEDDED_STRING.toString()+" ");
	public static final StringShort MAX_SHORT_STRING= StringShort.create(createRandomString(StringShort.MAX_LENGTH));
	public static final StringTree MIN_TREE_STRING= StringTree.create(createRandomString(StringTree.MINIMUM_LENGTH));


	
	static {
		try {
			// we should be able to actually build these, thanks to structural sharing.
			DIABOLICAL_VECTOR_30_30 = createNastyNestedVector(30, 30);
			DIABOLICAL_VECTOR_2_10000 = createNastyNestedVector(2, 10000);
			DIABOLICAL_MAP_30_30 = createNastyNestedMap(30, 30);
			DIABOLICAL_MAP_2_10000 = createNastyNestedMap(2, 10000);
		} catch (Throwable t) {
			t.printStackTrace();
			throw t;
		}
	}
	

	/**
	 * Create a random test Blob of the given size
	 * @param size
	 * @return
	 */
	static Blob createTestBlob(long size) {
		Blob b=Blob.createRandom(new Random(), size);
		return b;
	}

	private static String createRandomString(int n) {
		char [] cs=new char[n];
		for (int i=0; i<n; i++) {
			cs[i]=(char)rand.nextInt();
		}
		return new String(cs);
	}

	@SuppressWarnings("unchecked")
	static <T extends AVector<CVMLong>> T createTestIntVector(int size) {
		AVector<CVMLong> v = Vectors.empty();
		for (int i = 0; i < size; i++) {
			v = v.append(CVMLong.create(i));
		}
		return (T) v;
	}

	private static AMap<ACell, ACell> createNastyNestedMap(int fanout, int depth) {
		AMap<ACell, ACell> m = Maps.empty();
		for (long i = 0; i < depth; i++) {
			m = createRepeatedValueMap(m, fanout);
			m.getHash(); // needed to to stop hash calculations getting too deep
		}
		return m;
	}

	private static AMap<ACell, ACell> createRepeatedValueMap(Object v, int count) {
		Object[] obs = new Object[count * 2];
		for (int i = 0; i < count; i++) {
			obs[i * 2] = RT.cvm(i);
			obs[i * 2 + 1] = RT.cvm(v);
		}
		return Maps.of(obs);
	}
	
	@SuppressWarnings("unchecked")
	public static <R,T extends ACell> R createRandomSubset(ADataStructure<T> v, double prob, int seed) {
		ADataStructure<T> result=v.empty();
		
		Random r=new Random(seed);
		for (T o: (ASequence<T>)RT.sequence(v)) {
			if (r.nextDouble()<=prob) {
				result=result.conj(o);
			}
		}
		return (R) result;
	}

	private static AVector<ACell> createNastyNestedVector(int fanout, int depth) {
		AVector<ACell> m = Vectors.empty();
		for (int i = 0; i < depth; i++) {
			m = Vectors.repeat(m, fanout);
			m.getHash(); // needed to to stop hash calculations getting too deep
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static <T extends AMap<CVMLong, CVMLong>> T createTestLongMap(int n) {
		AMap<CVMLong, CVMLong> a = Maps.empty();
		for (long i = 0; i < n; i++) {
			CVMLong cl=CVMLong.create(i);
			a = a.assoc(cl, cl);
		}
		return (T) a;
	}

	@Test
	public void validateDataObjects() throws InvalidDataException, ValidationException {
		INT_VECTOR_300.validate();
		assertTrue(INT_VECTOR_300.isCanonical());
		INT_VECTOR_10.validate();
		assertTrue(INT_VECTOR_10.isCanonical());
		BAD_HASH.validate();
	}

	public static ACell[] VALUES=new ACell[] {
			null,
			Keywords.FOO,
			FULL_BLOB,
			MAX_EMBEDDED_BLOB,
			INT_LIST_10,
			Lists.empty(),
			INT_VECTOR_300,
			Vectors.empty(),
			LONG_MAP_100,
			Syntax.of(1),
			Syntax.create(Vectors.empty(),Maps.of(1,2)),
			Maps.empty(),
			LONG_SET_10,
			Sets.empty(),
			CVMDouble.ONE,
			CVMDouble.NaN,
			CVMLong.MAX_VALUE,
			CVMLong.MIN_VALUE,
			CVMByte.ZERO,
			CVMBool.TRUE,
			CVMBool.FALSE,
			MAX_SHORT_STRING,
			BAD_HASH,
			Symbols.FOO,
			Strings.EMPTY,
			MAX_EMBEDDED_STRING,
			Do.EMPTY,
			Address.ZERO,
			AccountKey.ZERO,
			CVMChar.A
	};
	
	public static class ValueArgumentsProvider implements ArgumentsProvider {
	    @Override
	    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
	    	return Stream.of(VALUES).map(cell -> Arguments.of(cell));
	    }
	}
}
