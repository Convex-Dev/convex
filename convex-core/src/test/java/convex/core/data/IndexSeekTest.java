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
import convex.core.store.MemoryStore;

class IndexSeekTest {

	private static Blob effective(ABlobLike<?> key) {
		return key.toBlob().slice(0,Math.min(255,key.count())).toFlatBlob();
	}

	/** Independent sorted-map oracle, including Index's effective-key alias rule. */
	private static void check(Index<?,?> index,TreeMap<Blob,CVMLong> keys,ABlobLike<?> query) {
		Blob key=effective(query);
		long expected=keys.headMap(key,false).size();
		long actual=index.seek(query);
		assertEquals(expected,actual,() -> "lower bound of "+query);
		if (actual>0) assertTrue(effective(index.entryAt(actual-1).getKey()).compareTo(key)<0);
		if (actual<index.count()) assertTrue(effective(index.entryAt(actual).getKey()).compareTo(key)>=0);
	}

	@Test void lowerBoundsAgreeWithSortedKeysAcrossShapesAndQueries() throws Exception {
		Random random=new Random(0x5ee123);
		for (int prefixLength:new int[] {0,8,240}) {
			for (int size:new int[] {0,1,2,31,256}) {
				Index<Blob,CVMLong> index=Index.none();
				var keys=new TreeMap<Blob,CVMLong>();
				for (int i=0;i<size;i++) {
					byte[] bytes=new byte[prefixLength+random.nextInt(33)];
					random.nextBytes(bytes);
					Arrays.fill(bytes,0,prefixLength,(byte)0x35);
					Blob key=Blob.wrap(bytes);
					index=index.assoc(key,CVMLong.create(i));
					keys.put(effective(key),CVMLong.create(i));
				}
				assertEquals(keys.size(),index.count());
				for (Blob key:keys.keySet()) {
					check(index,keys,key);
					check(index,keys,key.slice(0,key.count()/2));
				}
				for (int i=0;i<128;i++) {
					byte[] bytes=new byte[prefixLength+random.nextInt(35)];
					random.nextBytes(bytes);
					Arrays.fill(bytes,0,prefixLength,(byte)0x35);
					check(index,keys,Blob.wrap(bytes));
				}
				check(index,keys,Blob.EMPTY);
				check(index,keys,Blob.fromHex("ff"));
				// Decode with an independent root so lazy depths and cold prefix caches participate.
				var store=new MemoryStore();
				Cells.persist(index,store);
				Index<?,?> decoded=(Index<?,?>)store.decode(Blob.create(index.getEncoding().getBytes()));
				for (Blob key:keys.keySet()) check(decoded,keys,key);
			}
		}
	}

	@Test void everyRadixSlotAndMissingBranchHasTheCorrectRank() {
		for (boolean sparse:new boolean[] {false,true}) {
			Index<Blob,CVMLong> index=Index.of(Blob.fromHex("1234"),0);
			var keys=new TreeMap<Blob,CVMLong>();
			keys.put(Blob.fromHex("1234"),CVMLong.ZERO);
			for (int digit=0;digit<16;digit++) {
				if (sparse && (digit&1)==0) continue;
				// Unequal subtree sizes make count accumulation distinguishable from child count.
				for (int j=0;j<=digit;j++) {
					Blob key=Blob.wrap(new byte[] {0x12,0x34,(byte)(digit<<4),(byte)j});
					index=index.assoc(key,CVMLong.ZERO);
					keys.put(key,CVMLong.ZERO);
				}
			}
			for (int b=0;b<256;b++) check(index,keys,Blob.wrap(new byte[] {0x12,0x34,(byte)b}));
			for (String hex:List.of("","12","1233","1234","1235","13")) check(index,keys,Blob.fromHex(hex));
		}
	}

	@Test void blobLikeRepresentationsAndLongAliasesUseTheSamePosition() {
		Index<ABlobLike<?>,CVMLong> index=Index.of(Strings.intern("alpha"),0,Keyword.intern("beta"),1);
		assertEquals(0,index.seek(Symbol.create("alpha")));
		assertEquals(1,index.seek(Strings.intern("beta")));
		assertEquals(1,index.seek(Keyword.intern("b")));
		assertEquals(2,index.seek(Strings.intern("gamma")));
		index=Index.of(Address.create(42),0);
		assertEquals(0,index.seek(LongBlob.create(42)));
		assertEquals(1,index.seek(LongBlob.create(43)));
		Blob longKey=Blobs.createFilled(0x55,300).toFlatBlob();
		index=Index.of(longKey,0);
		assertEquals(0,index.seek(longKey.slice(0,255)));
		assertEquals(0,index.seek(longKey.slice(0,255).append(Blob.fromHex("ff"))));
		assertEquals(1,index.seek(Blobs.createFilled(0x56,255)));
		assertThrows(NullPointerException.class,() -> Index.none().seek(null));
	}

	@Test void maximumDepthSearchUsesNoJavaRecursion() throws Exception {
		var keys=new TreeMap<Blob,CVMLong>();
		Blob zero=Blob.wrap(new byte[255]);
		Index<Blob,CVMLong> built=Index.of(zero,0);
		keys.put(zero,CVMLong.ZERO);
		for (int digit=509;digit>=0;digit--) {
			byte[] bytes=new byte[255];
			bytes[digit>>>1]=(byte)((digit&1)==0?0x10:0x01);
			Blob key=Blob.wrap(bytes);
			built=built.assoc(key,CVMLong.ZERO);
			keys.put(key,CVMLong.ZERO);
		}
		// Decode to start with cold prefix caches, including the deepest representative path.
		var store=new MemoryStore();
		Cells.persist(built,store);
		Index<?,?> index=(Index<?,?>)store.decode(Blob.create(built.getEncoding().getBytes()));
		var failure=new AtomicReference<Throwable>();
		Thread worker=new Thread(null,() -> {
			try {
				for (Blob key:keys.keySet()) check(index,keys,key);
			} catch (Throwable t) { failure.set(t); }
		},"index-seek-small-stack",64*1024);
		worker.start();
		worker.join();
		if (failure.get()!=null) throw new AssertionError(failure.get());
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	@Test void malformedPrefixesAndChildrenTerminateDeterministically() {
		var leaf=Index.of(Blob.fromHex("10"),1);
		var cases=new ArrayList<Index>();
		cases.add(Index.unsafeCreate(20,null,new Ref[] {leaf.getRef()},1,2));
		cases.add(Index.unsafeCreate(0,null,new Ref[] {CVMLong.ONE.getRef()},2,2));
		cases.add(Index.unsafeCreate(0,null,Index.EMPTY_CHILDREN,0xffff,2));
		for (Index malformed:cases) {
			for (String hex:List.of("","10","11","ff")) {
				Blob key=Blob.fromHex(hex);
				long first=assertDoesNotThrow(() -> malformed.seek(key));
				assertEquals(first,malformed.seek(key));
			}
		}
	}
}
