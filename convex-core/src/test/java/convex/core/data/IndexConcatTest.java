package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.data.impl.LongBlob;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.store.MemoryStore;

class IndexConcatTest {
	private static Index<Blob,CVMLong> build(List<MapEntry<Blob,CVMLong>> entries,int start,int end) {
		Index<Blob,CVMLong> result=Index.none();
		for (var e:entries.subList(start,end)) result=result.assoc(e.getKey(),e.getValue());
		return result;
	}

	private static <K extends ABlobLike<?>> void check(Index<K,CVMLong> expected,Index<K,CVMLong> left,Index<K,CVMLong> right) throws Exception {
		var actual=left.concat(right);
		actual.validate();
		assertEquals(left.count()+right.count(),actual.count());
		assertEquals(expected.getEncoding(),actual.getEncoding());
		assertEquals(expected.getHash(),actual.getHash());
		assertEquals(left,actual.slice(0,left.count()));
		assertEquals(right,actual.slice(left.count()));
		if (left.isEmpty()) assertSame(right,actual);
		if (right.isEmpty()) assertSame(left,actual);
	}

	@Test void independentlyBuiltRangesJoinAtEveryCut() throws Exception {
		for (boolean sparse:new boolean[] {false,true}) {
			var sorted=new TreeMap<Blob,CVMLong>();
			for (String prefix:List.of("","12","1234","ff")) {
				Blob key=Blob.fromHex(prefix);
				sorted.put(key,null);
				for (int digit=0;digit<16;digit++) {
					if (sparse && (digit&1)==0) continue;
					for (int i=0;i<1+digit%3;i++) {
						sorted.put(key.append(Blob.wrap(new byte[] {(byte)(digit<<4),(byte)i})).toFlatBlob(),CVMLong.create(i));
					}
				}
			}
			var entries=new ArrayList<MapEntry<Blob,CVMLong>>();
			sorted.forEach((k,v) -> entries.add(MapEntry.create(k,v)));
			var expected=build(entries,0,entries.size());
			for (int cut=0;cut<=entries.size();cut++) {
				check(expected,build(entries,0,cut),build(entries,cut,entries.size()));
				check(expected,expected.slice(0,cut),expected.slice(cut));
			}
		}
	}

	@Test void randomPartitionsAreAssociativeAndPreserveCanonicalEncoding() throws Exception {
		Random random=new Random(0xc0ca7);
		for (int prefixLength:new int[] {0,8,240}) {
			var sorted=new TreeMap<Blob,CVMLong>();
			for (int i=0;i<256;i++) {
				byte[] bytes=new byte[prefixLength+random.nextInt(256-prefixLength)];
				random.nextBytes(bytes);
				Arrays.fill(bytes,0,prefixLength,(byte)0x35);
				sorted.put(Blob.wrap(bytes),i%7==0?null:CVMLong.create(i));
			}
			var entries=new ArrayList<MapEntry<Blob,CVMLong>>();
			sorted.forEach((k,v) -> entries.add(MapEntry.create(k,v)));
			var expected=build(entries,0,entries.size());
			for (int i=0;i<40;i++) {
				int x=random.nextInt(entries.size()+1), y=random.nextInt(entries.size()+1);
				int lo=Math.min(x,y), hi=Math.max(x,y);
				var a=build(entries,0,lo);
				var b=build(entries,lo,hi);
				var c=build(entries,hi,entries.size());
				check(expected,a.concat(b),c);
				check(expected,a,b.concat(c));
				// Gaps are permitted: concat needs ordered ranges, not adjacent keys.
				var gapped=build(entries,0,lo);
				for (var e:entries.subList(hi,entries.size())) gapped=gapped.assoc(e.getKey(),e.getValue());
				check(gapped,a,c);
			}
			var store=new MemoryStore();
			Cells.persist(expected,store);
			@SuppressWarnings("unchecked")
			var decoded=(Index<Blob,CVMLong>)store.decode(Blob.create(expected.getEncoding().getBytes()));
			int cut=entries.size()/2;
			check(expected,decoded.slice(0,cut),decoded.slice(cut));
		}
	}

	@Test void emptyIndexesAreIdentitiesAndNullIsRejected() {
		Index<Blob,CVMLong> empty=Index.none(), one=Index.of(Blob.EMPTY,0);
		assertSame(empty,empty.concat(empty));
		assertSame(one,empty.concat(one));
		assertSame(one,one.concat(empty));
		assertThrows(NullPointerException.class,() -> empty.concat(null));
		assertThrows(NullPointerException.class,() -> one.concat(null));
	}

	@Test void rejectsOverlappingReversedAndAliasedRanges() {
		Index<ABlobLike<?>,CVMLong> left=Index.of(Blob.fromHex("10"),1,Blob.fromHex("30"),3);
		for (Index<ABlobLike<?>,CVMLong> right:List.of(
			Index.<ABlobLike<?>,CVMLong>of(Blob.fromHex("00"),0),
			Index.<ABlobLike<?>,CVMLong>of(Blob.fromHex("20"),2,Blob.fromHex("40"),4),
			Index.<ABlobLike<?>,CVMLong>of(Blob.fromHex("30"),null))) {
			assertThrows(IllegalArgumentException.class,() -> left.concat(right));
		}
		assertThrows(IllegalArgumentException.class,() -> left.concat(left));
		assertThrows(IllegalArgumentException.class,() -> Index.of(Blob.fromHex("1000"),0).concat(Index.of(Blob.fromHex("10"),0)));
		for (ABlobLike<?> key:List.<ABlobLike<?>>of(Strings.intern("a"),Keyword.intern("a"),Symbol.create("a"))) {
			assertThrows(IllegalArgumentException.class,() -> Index.of(key,1).concat(Index.of(Blob.fromHex("61"),2)));
		}
		assertThrows(IllegalArgumentException.class,() -> Index.of(Address.create(42),0).concat(Index.of(LongBlob.create(42),1)));
		Blob prefix=Blobs.createFilled(0xff,255).toFlatBlob();
		Index<Blob,CVMLong> oversized=Index.of(prefix.append(Blob.fromHex("00")),0);
		assertThrows(IllegalArgumentException.class,() -> oversized.concat(Index.of(prefix,1)));
		assertThrows(IllegalArgumentException.class,() -> oversized.concat(Index.of(prefix.append(Blob.fromHex("ff")),2)));
	}

	@Test void preservesOriginalKeyTypesAndLongKeys() throws Exception {
		Index<ABlobLike<?>,CVMLong> a=Index.of(Strings.intern("a"),null,Keyword.intern("b"),2);
		Blob longKey=Blobs.createFilled(0xff,300).toFlatBlob();
		Index<ABlobLike<?>,CVMLong> b=Index.of(Symbol.create("c"),3,longKey,4);
		check(Index.of(Strings.intern("a"),null,Keyword.intern("b"),2,Symbol.create("c"),3,longKey,4),a,b);
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Test void retainsOuterRefsWithoutLoadingTheirSubtrees() {
		Index<Blob,CVMLong> low=Index.none(), high=Index.none();
		for (int i=0;i<16;i++) {
			byte[] bytes=new byte[32];
			bytes[1]=(byte)(i<<4);
			low=low.assoc(Blob.create(bytes),CVMLong.ZERO);
			bytes[0]=(byte)0xff;
			high=high.assoc(Blob.create(bytes),CVMLong.ONE);
		}
		assertFalse(low.getRef().isEmbedded());
		assertFalse(high.getRef().isEmbedded());
		Ref<Index> missingLow=RefSoft.<Index>createForHash(low.getHash(),null).withFlags(low.getRef().getFlags());
		Ref<Index> missingHigh=RefSoft.<Index>createForHash(high.getHash(),null).withFlags(high.getRef().getFlags());
		assertThrows(MissingDataException.class,missingLow::getValue);
		Index<Blob,CVMLong> boundaryLeft=Index.of(Blob.fromHex("7010"),1,Blob.fromHex("7020"),2);
		Index<Blob,CVMLong> boundaryRight=Index.of(Blob.fromHex("7030"),3,Blob.fromHex("7040"),4);
		Index<Blob,CVMLong> a=Index.unsafeCreate(0,null,new Ref[] {missingLow,boundaryLeft.getRef()},0x81,18);
		Index<Blob,CVMLong> b=Index.unsafeCreate(0,null,new Ref[] {boundaryRight.getRef(),missingHigh},0x8080,18);
		var joined=a.concat(b);
		assertEquals(36,joined.count());
		assertSame(missingLow,joined.getRef(0));
		assertSame(missingHigh,joined.getRef(2));
		var expected=low.merge(boundaryLeft).merge(boundaryRight).merge(high);
		assertEquals(expected.getEncoding(),joined.getEncoding());
		assertSame(a,Index.<Blob,CVMLong>none().concat(a));
		assertSame(b,b.concat(Index.none()));
	}

	@Test void disjointPrefixesShareWholeInputs() {
		Index<Blob,CVMLong> a=Index.of(Blob.fromHex("0010"),1,Blob.fromHex("0020"),2);
		Index<Blob,CVMLong> b=Index.of(Blob.fromHex("ff10"),3,Blob.fromHex("ff20"),4);
		var joined=a.concat(b);
		assertSame(a,joined.getRef(0).getValue());
		assertSame(b,joined.getRef(1).getValue());
	}

	@Test void maximumDepthJoinIsStackSafe() throws Exception {
		Index<Blob,CVMLong> built=Index.of(Blob.wrap(new byte[255]),0);
		for (int digit=509;digit>=0;digit--) {
			byte[] bytes=new byte[255];
			bytes[digit>>>1]=(byte)((digit&1)==0?0x10:0x01);
			built=built.assoc(Blob.wrap(bytes),CVMLong.create(digit));
		}
		final var index=built;
		var failure=new AtomicReference<Throwable>();
		var results=new ArrayList<Index<Blob,CVMLong>>();
		// Both slicing and joining must recover from recursive overflow without
		// publishing partially constructed nodes or changing the input Index.
		Thread worker=new Thread(null,() -> {
			try {
				for (int cut:new int[] {0,1,2,254,509,510,511}) {
					var joined=index.slice(0,cut).concat(index.slice(cut));
					assertEquals(index.count(),joined.count());
					for (int i=0;i<511;i++) assertEquals(index.entryAt(i),joined.entryAt(i));
					results.add(joined);
				}
			} catch (Throwable t) { failure.set(t); }
		},"index-concat-small-stack",64*1024);
		worker.start();
		worker.join();
		if (failure.get()!=null) throw new AssertionError(failure.get());
		for (var result:results) {
			result.validate();
			assertEquals(index.getEncoding(),result.getEncoding());
		}
	}
}
