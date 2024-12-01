package convex.test;

import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.stream.Stream;

import org.junit.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.cvm.Syntax;
import convex.core.cvm.ops.Do;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.BlobTree;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.SetLeaf;
import convex.core.data.SetTree;
import convex.core.data.Sets;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.VectorLeaf;
import convex.core.data.VectorTree;
import convex.core.data.Vectors;
import convex.core.data.impl.LongBlob;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.init.Init;
import convex.core.lang.RT;

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
	public static final AccountKey ZERO_ACCOUNTKEY = AccountKey.dummy("0");
	
	public static final AKeyPair KEY_PAIR=AKeyPair.createSeeded(13371337L);
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

	public static final SetLeaf<CVMLong> INT_SET_10 = (SetLeaf<CVMLong>) Sets.create(INT_VECTOR_10);
	public static final SetTree<CVMLong> INT_SET_300 = (SetTree<CVMLong>) Sets.create(INT_VECTOR_300);

	
	public static final AHashMap<CVMLong, CVMLong> LONG_MAP_5 = createTestLongMap(5);
	public static final AHashMap<CVMLong, CVMLong> LONG_MAP_10 = createTestLongMap(10);
	public static final AHashMap<CVMLong, CVMLong> LONG_MAP_100 = createTestLongMap(100);

	public static final Index<ABlobLike<?>, CVMLong> INT_INDEX_7 = Index.of(Strings.EMPTY, 0, Blob.fromHex("0001"), 1,
			Blob.fromHex("01"), 2, Blob.fromHex("010000"), 3, Blob.fromHex("010001"), 4, Blob.fromHex("ff0000"), 5,
			Blob.fromHex("ff0101"), 6);
	
	public static final Index<ABlob, CVMLong> INT_INDEX_256 = createTestIndex(256);

	public static final ASet<CVMLong> LONG_SET_5 = Sets.of(1,2,3,4,5);
	public static final ASet<CVMLong> LONG_SET_10 = Sets.create(INT_VECTOR_10);
	public static final ASet<CVMLong> LONG_SET_100 = Sets.create(INT_VECTOR_300);

	public static final Blob ONE_ZERO_BYTE_DATA = Blob.SINGLE_ZERO;

	public static final Keyword FOO = Keyword.create("foo");
	public static final Keyword BAR = Keyword.create("bar");

	public static final AVector<ACell> DIABOLICAL_VECTOR_30_30;
	public static final AVector<ACell> DIABOLICAL_VECTOR_2_10000;
	public static final AMap<ACell, ACell> DIABOLICAL_MAP_30_30;
	public static final AMap<ACell, ACell> DIABOLICAL_MAP_2_10000;
	
	public static final CVMChar CHAR_UTF_1=CVMChar.create('z');
	public static final CVMChar CHAR_UTF_2=CVMChar.create('\u0444');
	public static final CVMChar CHAR_UTF_3=CVMChar.create('\u1234');
	public static final CVMChar CHAR_UTF_4=CVMChar.create(0x12345);

	public static final Random rand = new Random(123);
	
	public static final long BIG_BLOB_LENGTH = 10000;
	public static final BlobTree BIG_BLOB_TREE = Blobs.createRandom(Samples.rand, BIG_BLOB_LENGTH);
	public static final Blob FULL_BLOB = Blobs.createRandom(Samples.rand, Blob.CHUNK_LENGTH);
	public static final ABlob FULL_BLOB_PLUS = Blobs.createRandom(Samples.rand, Blob.CHUNK_LENGTH+1);

	public static final BlobTree DIABOLICAL_BLOB_TREE = (BlobTree)Blobs.createFilled(1,Long.MAX_VALUE);
	
	public static final ASignature FAKE_SIGNATURE = Ed25519Signature.wrap(new byte[Ed25519Signature.SIGNATURE_LENGTH]);

	public static final Blob MAX_EMBEDDED_BLOB = createTestBlob(Format.MAX_EMBEDDED_LENGTH-Format.getVLQCountLength(Format.MAX_EMBEDDED_LENGTH)-1);
	public static final Blob NON_EMBEDDED_BLOB = createTestBlob(MAX_EMBEDDED_BLOB.count()+1);

	public static final StringShort MAX_EMBEDDED_STRING= (StringShort) Strings.create("This is a string containing exactly 137 characters. This is just right for a maximum embedded string in Convex. How lucky is that, eh????");
	public static final AString NON_EMBEDDED_STRING= Strings.create("This is a string containing exactly 138 characters. This is slightly too long to be an embedded Cell within a Convex encoding. Watch out!!");
	public static final StringShort MAX_SHORT_STRING= (StringShort) Strings.create(createASCIIString(StringShort.MAX_LENGTH));
	public static final AString MIN_TREE_STRING= Strings.create(createASCIIString(StringShort.MAX_LENGTH+1));

	public static final String IPSUM="Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. ";
	public static final String SPANISH="El veloz murciélago hindú comía feliz cardillo y kiwi. La cigüeña tocaba el saxofón detrás del palenque de paja.";
	public static final String RUSSIAN="Съешь же ещё этих мягких французских булок, да выпей чаю.";
	public static final AString RUSSIAN_STRING=Strings.create(RUSSIAN);

	
	public static final String MAX_SYMBOLIC="abcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefghijklmnop";
	public static final String TOO_BIG_SYMBOLIC=MAX_SYMBOLIC+"a";
	
	public static final AVector<ACell> MAX_EMBEDDED_VECTOR = Vectors.of(Blobs.createRandom(106),1,2,3,4,5,6,7,8,9,10,11,12,13,14,15);

	public static final Blob SMALL_BLOB = Blob.fromHex("cafebabe");
	
	public static final CVMBigInteger MAX_BIGINT;
	public static final CVMBigInteger MIN_BIGINT;

	public static final ACell NIL = null;
	
	static {
		// we should be able to actually build these, thanks to structural sharing.
		DIABOLICAL_VECTOR_30_30 = createNastyNestedVector(30, 30);
		DIABOLICAL_VECTOR_2_10000 = createNastyNestedVector(2, 10000);
		DIABOLICAL_MAP_30_30 = createNastyNestedMap(30, 30);
		DIABOLICAL_MAP_2_10000 = createNastyNestedMap(2, 10000);
		 
		{
			byte [] bs=new byte[Constants.MAX_BIG_INTEGER_LENGTH];
			bs[0]=-128; // set sign bit for max sized negative number
			ABlob blob=Blob.wrap(bs);
			CVMBigInteger b=CVMBigInteger.create(blob);
			MIN_BIGINT=b;
			MAX_BIGINT=(CVMBigInteger) b.inc().negate();
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

	/**
	 * Create a test Index of the given size
	 * @param size
	 * @return
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	static Index<ABlob,CVMLong> createTestIndex(long size) {
		Index bm=Index.EMPTY;
		for (long i=0; i<size; i++) {
			CVMLong val=CVMLong.create(i);
			bm=bm.assoc(val.getHash(), val);
		}
		return bm;
	}

	// Creates a valid ASCII string of the given length.
	public static String createASCIIString(int n) {
		char[] cs=new char[n];
		for (int i=0; i<n; i++) {
			cs[i]=IPSUM.charAt(i%IPSUM.length());
		}
		return new String(cs);
	}

	public static String createRandomString(int n) {
		char [] cs=new char[n];
		for (int i=0; i<n; i++) {
			cs[i]=(char)rand.nextInt();
		}
		return new String(cs);
	}

	@SuppressWarnings("unchecked")
	public static <T extends AVector<CVMLong>> T createTestIntVector(int size) {
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
			LongBlob.create(-1),
			LongBlob.ZERO,
			INT_LIST_10,
			Lists.empty(),
			INT_VECTOR_300,
			Vectors.empty(),
			LONG_MAP_100,
			Syntax.of(1),
			Syntax.create(Vectors.empty(),Maps.of(1,2)),
			Invoke.create(Init.GENESIS_ADDRESS, 0, (ACell)null),
			Maps.empty(),
			LONG_SET_10,
			Sets.empty(),
			Sets.of(1,2,3),
			CVMDouble.ONE,
			CVMDouble.NaN,
			CVMLong.MAX_VALUE,
			CVMLong.MIN_VALUE,
			CVMLong.ZERO,
			CVMBool.TRUE,
			CVMBool.FALSE,
			MAX_SHORT_STRING,
			BAD_HASH,
			Symbols.FOO,
			StringShort.EMPTY,
			MAX_EMBEDDED_STRING,
			Do.EMPTY,
			Address.ZERO,
			Address.create(666666),
			Samples.ZERO_ACCOUNTKEY,
			CVMChar.ZERO
	};
	
	public static class ValueArgumentsProvider implements ArgumentsProvider {
	    @Override
	    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
	    	return Stream.of(VALUES).map(cell -> Arguments.of(cell));
	    }
	}
}
