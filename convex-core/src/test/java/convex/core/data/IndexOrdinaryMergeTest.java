package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;

/** Ordinary merge must match whole-entry insertion, including nil and key aliases. */
public class IndexOrdinaryMergeTest {
	private static <K extends ABlobLike<?>> Index<K,CVMLong> insertionLoop(Index<K,CVMLong> a, AMap<K,CVMLong> b) {
		for (long i=0;i<b.count();i++) a=a.assocEntry(b.entryAt(i));
		return a;
	}

	@Test void shapesAndNilValues() throws Exception {
		List<Index<ABlob,CVMLong>> shapes=new ArrayList<>();
		shapes.add(Index.none());
		shapes.add(Index.of(Blob.EMPTY,null));
		shapes.add(Index.of(Blob.fromHex("10"),null));
		shapes.add(Index.of(Blob.EMPTY,1,Blob.fromHex("10"),null,Blob.fromHex("1000"),2));
		shapes.add(Index.of(Blob.fromHex("10"),3,Blob.fromHex("1000"),null,Blob.fromHex("1001"),4));
		shapes.add(Index.of(Blob.fromHex("f0"),null,Blob.fromHex("ff"),1));
		for (var a:shapes) for (var b:shapes) check(a,b);
	}

	private static <K extends ABlobLike<?>> void check(Index<K,CVMLong> a, Index<K,CVMLong> b) throws Exception {
		var expected=insertionLoop(a,b);
		AMap<K,CVMLong> receiver=a; // Exercise ordinary AMap dispatch.
		var actual=receiver.merge(b);
		assertEquals(expected,actual);
		assertEquals(expected.getEncoding(),actual.getEncoding());
		assertEquals(expected.getHash(),actual.getHash());
		actual.validate();
	}

	@Test void randomOverlappingPrefixes() throws Exception {
		for (int seed=0;seed<200;seed++) {
			Random random=new Random(seed);
			Index<ABlob,CVMLong> a=Index.none(),b=Index.none();
			for (int i=0;i<40;i++) {
				ABlob key=IndexMergeTest.randomKey(random);
				if (random.nextBoolean()) a=a.assoc(key,random.nextBoolean()?null:CVMLong.ONE);
				if (random.nextBoolean()) b=b.assoc(key,random.nextBoolean()?null:CVMLong.ZERO);
			}
			check(a,b);
			assertSame(a,a.merge(a));
			assertSame(a,a.merge(Index.none()));
			assertSame(a,Index.<ABlob,CVMLong>none().merge(a));
		}
	}

	@Test void replacesStoredKeyForByteAliases() throws Exception {
		Blob longKey=Blobs.createFilled(0xff,300).toFlatBlob();
		Index<ABlobLike<?>,CVMLong> a=Index.of(Strings.create("a"),1,Keyword.intern("b"),null,longKey,2);
		Index<ABlobLike<?>,CVMLong> b=Index.of(Blob.fromHex("61"),1,Symbol.create("b"),null,longKey.slice(0,255),2);
		check(a,b);
		check(b,a);
		assertEquals(b.getEncoding(),a.merge(b).getEncoding());
		assertSame(b.entryAt(0).getKey(),a.merge(b).entryAt(0).getKey());
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Test void mixedMapFallback() {
		Index a=Index.of(Blob.fromHex("10"),1);
		AMap valid=Maps.of(Blob.fromHex("10"),null,Blob.fromHex("ff"),2);
		assertEquals(insertionLoop(a,valid),a.merge(valid));
		assertNull(a.merge(Maps.of(CVMLong.ONE,CVMLong.ZERO)));
	}
}
