package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;

public class MapKeysTest {
	private static void check(AMap<?,?> map) {
		AVector<?> keys=map.getKeys();
		assertEquals(map.count(),keys.count());
		for (long i=0;i<map.count();i++) assertSame(map.entryAt(i).getKey(),keys.get(i));
	}

	@Test void preservesOrderAcrossShapes() {
		for (int size:new int[] {0,1,8,15,16,17,255,256,1024}) {
			AHashMap<ACell,ACell> map=Maps.empty();
			Index<ABlobLike<?>,ACell> index=Index.none();
			for (int i=0;i<size;i++) {
				map=map.assoc(i==0?null:CVMLong.create(i),null);
				index=index.assoc(CVMLong.create(i).getHash(),null);
			}
			check(map);
			check(index);
		}
		assertSame(Vectors.empty(),Maps.empty().getKeys());
		assertSame(Vectors.empty(),Index.none().getKeys());
		check(Index.of(Blob.EMPTY,0,Strings.create("a"),1,Keyword.intern("b"),2,Symbol.create("c"),3));
	}

	@Test void doesNotLoadValues() {
		Ref<ACell> missing=RefSoft.createForHash(Blobs.createFilled(7,1024).getHash(),null);
		assertThrows(MissingDataException.class,missing::getValue);
		AHashMap<ACell,ACell> map=Maps.empty();
		Index<Hash,ACell> index=Index.none();
		for (int i=0;i<100;i++) {
			Hash key=CVMLong.create(i).getHash();
			MapEntry<Hash,ACell> entry=MapEntry.fromRefs(key.getRef(),missing);
			map=map.assocEntry(MapEntry.fromRefs(key.getRef(),missing));
			index=index.assocEntry(entry);
			if (i==0 || i==14 || i==15 || i==99) {
				check(map);
				check(index);
			}
		}
	}

	@Test void maximumDepthWithSmallStack() throws Exception {
		Index<Blob,CVMLong> built=Index.of(Blob.wrap(new byte[255]),0);
		for (int digit=509;digit>=0;digit--) {
			byte[] bytes=new byte[255];
			bytes[digit>>>1]=(byte)((digit&1)==0?0x10:0x01);
			built=built.assoc(Blob.wrap(bytes),CVMLong.create(digit));
		}
		final var index=built;
		AtomicReference<Throwable> failure=new AtomicReference<>();
		Thread worker=new Thread(null,() -> {
			try { check(index); } catch (Throwable t) { failure.set(t); }
		},"index-keys-small-stack",64*1024);
		worker.start();
		worker.join(10000);
		assertFalse(worker.isAlive(),"Key traversal did not finish");
		if (failure.get()!=null) throw new AssertionError(failure.get());
	}
}
