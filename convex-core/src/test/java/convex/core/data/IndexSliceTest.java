package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.store.MemoryStore;

class IndexSliceTest {

	/** Rebuild from independently sorted input, checking canonical shape as well as contents. */
	private static <K extends ABlobLike<?>> void check(Index<K,CVMLong> index,
			List<MapEntry<K,CVMLong>> ordered,int start,int end) throws Exception {
		Index<K,CVMLong> expected=Index.none();
		for (var entry:ordered.subList(start,end)) expected=expected.assoc(entry.getKey(),entry.getValue());
		Index<K,CVMLong> actual=index.slice(start,end);
		assertEquals(end-start,actual.count());
		actual.validate();
		assertEquals(expected.getEncoding(),actual.getEncoding());
		assertEquals(expected.getHash(),actual.getHash());
		for (int i=start;i<end;i++) assertEquals(ordered.get(i),actual.entryAt(i-start));
		if (start==end) assertSame(Index.EMPTY,actual);
		else if (start==0 && end==index.count()) assertSame(index,actual);
		// Slicing composes, including empty suffixes.
		int middle=(end-start)/2;
		assertEquals(index.slice(start+middle,end),actual.slice(middle));
	}

	@Test void everyRangeAcrossDenseSparseAndPrefixShapes() throws Exception {
		for (boolean sparse:new boolean[] {false,true}) {
			Index<Blob,CVMLong> index=Index.none();
			var ordered=new TreeMap<Blob,CVMLong>();
			for (String prefix:List.of("","12","1234")) {
				Blob key=Blob.fromHex(prefix);
				ordered.put(key,CVMLong.ZERO);
				for (int digit=0;digit<16;digit++) {
					if (sparse && (digit&1)==0) continue;
					ordered.put(key.append(Blob.wrap(new byte[] {(byte)(digit<<4)})).toFlatBlob(),CVMLong.create(digit));
				}
			}
			var entries=new ArrayList<MapEntry<Blob,CVMLong>>();
			for (var e:ordered.entrySet()) {
				index=index.assoc(e.getKey(),e.getValue());
				entries.add(MapEntry.create(e.getKey(),e.getValue()));
			}
			for (int start=0;start<=entries.size();start++) {
				for (int end=start;end<=entries.size();end++) check(index,entries,start,end);
			}
		}
	}

	@Test void randomRangesAndSeekCutsAgreeWithSortedInput() throws Exception {
		Random random=new Random(0x51ce);
		for (int prefixLength:new int[] {0,8,240}) {
			Index<Blob,CVMLong> index=Index.none();
			var ordered=new TreeMap<Blob,MapEntry<Blob,CVMLong>>();
			for (int i=0;i<256;i++) {
				byte[] bytes=new byte[prefixLength+random.nextInt(33)];
				random.nextBytes(bytes);
				Arrays.fill(bytes,0,prefixLength,(byte)0x35);
				Blob key=Blob.wrap(bytes);
				CVMLong value=i%7==0?null:CVMLong.create(i);
				index=index.assoc(key,value);
				ordered.put(key.slice(0,Math.min(255,key.count())),MapEntry.create(key,value));
			}
			var entries=new ArrayList<>(ordered.values());
			for (int i=0;i<100;i++) {
				int a=random.nextInt(entries.size()+1), b=random.nextInt(entries.size()+1);
				check(index,entries,Math.min(a,b),Math.max(a,b));
			}
			var store=new MemoryStore();
			Cells.persist(index,store);
			@SuppressWarnings("unchecked")
			Index<Blob,CVMLong> decoded=(Index<Blob,CVMLong>)store.decode(Blob.create(index.getEncoding().getBytes()));
			for (int i=0;i<20;i++) {
				Blob key=entries.get(random.nextInt(entries.size())).getKey();
				// Exact keys, prefixes, and potentially absent bounds all cut at the lower bound.
				for (Blob bound:List.of(key,key.slice(0,key.count()/2),key.append(Blob.fromHex("ff")).toFlatBlob())) {
					Blob effective=bound.slice(0,Math.min(255,bound.count()));
					int cut=ordered.headMap(effective,false).size();
					assertEquals(cut,decoded.seek(bound));
					check(decoded,entries,0,cut);
					check(decoded,entries,cut,entries.size());
				}
			}
		}
	}

	@Test void wholeSubtreesAndInteriorRefsAreShared() {
		Index<Blob,CVMLong> index=Index.none();
		for (int digit=0;digit<16;digit++) {
			for (int i=0;i<32;i++) {
				index=index.assoc(Blob.wrap(new byte[] {(byte)(digit<<4),(byte)i}),CVMLong.create(i));
			}
		}
		assertEquals(16,index.getRefCount());
		for (int digit=0;digit<16;digit++) {
			assertSame(index.getRef(digit).getValue(),index.slice(digit*32,(digit+1)*32));
		}
		var middle=index.slice(33,15*32-1);
		assertEquals(14,middle.getRefCount());
		for (int i=1;i<13;i++) assertSame(index.getRef(i+1),middle.getRef(i));
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Test void slicingWholeSubtreesDoesNotLoadTheirContents() {
		// The child roots are available, but none of their descendants are. This
		// makes accidental traversal of retained OR discarded contents fail.
		Ref<Index>[] children=new Ref[3];
		for (int digit=0;digit<3;digit++) {
			Index<Blob,CVMLong> child=Index.none();
			for (int i=0;i<64;i++) {
				byte[] bytes=new byte[32];
				bytes[0]=(byte)(digit<<4);
				bytes[1]=(byte)i;
				child=child.assoc(Blob.wrap(bytes),CVMLong.ONE);
			}
			Index<Blob,CVMLong> opaque=child.updateRefs(ref -> {
				assertFalse(ref.isEmbedded());
				return RefSoft.createForHash(ref.getHash(),null).withFlags(ref.getFlags());
			});
			assertThrows(MissingDataException.class,() -> opaque.entryAt(0));
			children[digit]=(Ref)opaque.getRef();
		}
		Index<Blob,CVMLong> index=Index.unsafeCreate(0,null,children,7,192);
		assertSame(index,index.slice(0));
		assertSame(Index.EMPTY,index.slice(192));
		assertSame(children[1].getValue(),index.slice(64,128));
		var suffix=index.slice(64);
		assertEquals(128,suffix.count());
		assertSame(children[1],suffix.getRef(0));
		assertSame(children[2],suffix.getRef(1));
		// A prefix entry can be retained without even loading the first child root.
		Ref<Index>[] unavailable=children.clone();
		for (int i=0;i<unavailable.length;i++) unavailable[i]=RefSoft.createForHash(children[i].getHash(),null);
		Index<Blob,CVMLong> withEntry=Index.unsafeCreate(0,MapEntry.create(Blob.EMPTY,CVMLong.ZERO),unavailable,7,193);
		assertEquals(Index.of(Blob.EMPTY,0),withEntry.slice(0,1));
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Test void malformedBoundaryPathsTerminate() {
		Index<Blob,CVMLong> leaf=Index.of(Blob.fromHex("10"),1,Blob.fromHex("11"),2);
		Index<Blob,CVMLong> badDepth=Index.unsafeCreate(2,null,new Ref[] {leaf.getRef()},2,2);
		assertThrows(IllegalStateException.class,() -> badDepth.slice(0,1));
		Index<Blob,CVMLong> badCount=Index.unsafeCreate(0,null,new Ref[] {leaf.getRef()},2,4);
		assertThrows(IllegalStateException.class,() -> badCount.slice(1,3));
	}

	@Test void emptySingletonAndInvalidBounds() throws Exception {
		for (Index<Blob,CVMLong> index:List.of(Index.<Blob,CVMLong>none(),Index.<Blob,CVMLong>of(Blob.EMPTY,0))) {
			long count=index.count();
			assertSame(index,index.slice(0));
			assertSame(Index.EMPTY,index.slice(count));
			for (long start:new long[] {Long.MIN_VALUE,-1,0,1,2,Long.MAX_VALUE}) {
				for (long end:new long[] {Long.MIN_VALUE,-1,0,1,2,Long.MAX_VALUE}) {
					if (start<0 || end<start || end>count) assertNull(index.slice(start,end));
				}
			}
		}
	}

	@Test void preservesBlobLikeKeyTypesAndOversizedAliases() throws Exception {
		Blob longKey=Blobs.createFilled(0xff,300).toFlatBlob();
		Index<ABlobLike<?>,CVMLong> index=Index.of(Strings.intern("a"),1,Keyword.intern("b"),2,
			Symbol.create("c"),3,longKey,4);
		var entries=List.of(MapEntry.<ABlobLike<?>,CVMLong>create(Strings.intern("a"),CVMLong.ONE),
			MapEntry.<ABlobLike<?>,CVMLong>create(Keyword.intern("b"),CVMLong.create(2)),
			MapEntry.<ABlobLike<?>,CVMLong>create(Symbol.create("c"),CVMLong.create(3)),
			MapEntry.<ABlobLike<?>,CVMLong>create(longKey,CVMLong.create(4)));
		for (int start=0;start<=4;start++) for (int end=start;end<=4;end++) check(index,entries,start,end);
		assertEquals(3,index.seek(longKey.slice(0,255)));
	}

	@Test void maximumDepthSlicesAreStackSafe() throws Exception {
		Index<Blob,CVMLong> built=Index.of(Blob.wrap(new byte[255]),0);
		for (int digit=509;digit>=0;digit--) {
			byte[] bytes=new byte[255];
			bytes[digit>>>1]=(byte)((digit&1)==0?0x10:0x01);
			built=built.assoc(Blob.wrap(bytes),CVMLong.create(digit));
		}
		final var index=built;
		var failure=new AtomicReference<Throwable>();
		// The recursive fast path must recover through the iterative fallback
		// when the worker's small stack cannot hold the maximum-depth boundary.
		Thread worker=new Thread(null,() -> {
			try {
				for (int start:new int[] {0,1,2,254,509,510,511}) {
					for (int end:new int[] {0,1,2,255,509,510,511}) {
						if (end<start) continue;
						var sliced=index.slice(start,end);
						assertEquals(end-start,sliced.count());
						for (int i=start;i<end;i++) assertEquals(index.entryAt(i),sliced.entryAt(i-start));
					}
				}
			} catch (Throwable t) { failure.set(t); }
		},"index-slice-small-stack",64*1024);
		worker.start();
		worker.join();
		if (failure.get()!=null) throw new AssertionError(failure.get());
		index.slice(1,510).validate();
	}
}
