package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.store.MemoryStore;

public class BlobOutputStreamTest {

	private static final int CHUNK=Blob.CHUNK_LENGTH;

	private static byte[] randomBytes(int n) {
		byte[] bs=new byte[n];
		new Random(n*31L+5).nextBytes(bs);
		return bs;
	}

	private static Hash chunkHash(byte[] bs, int chunk) {
		return Blob.wrap(bs,chunk*CHUNK,CHUNK).getHash();
	}

	/** Writes in uneven pieces: single bytes, and slices that straddle chunk edges */
	private static void writeInPieces(BlobOutputStream out, byte[] bs) throws IOException {
		int[] pieces={1,CHUNK-1,5000,1,9000};
		int pos=0;
		for (int i=0; pos<bs.length; i++) {
			int n=Math.min(pieces[i%pieces.length],bs.length-pos);
			if (n==1) {
				out.write(bs[pos]);
			} else {
				out.write(bs,pos,n);
			}
			pos+=n;
		}
	}

	@Test public void testInMemory() throws IOException {
		for (int n: new int[] {0,1,CHUNK,CHUNK+1,20*CHUNK+33}) {
			byte[] bs=randomBytes(n);
			BlobOutputStream out=BlobOutputStream.create(null);
			writeInPieces(out,bs);
			assertEquals(n,out.count());
			ABlob blob=out.toBlob();
			assertArrayEquals(bs,blob.getBytes());
			assertTrue(blob.isCanonical());
		}
	}

	@Test public void testSavesAtEachInterval() throws IOException {
		MemoryStore store=new MemoryStore();
		BlobOutputStream out=BlobOutputStream.create(store,2*CHUNK);
		byte[] bs=randomBytes(5*CHUNK+100);

		out.write(bs,0,2*CHUNK-1);
		assertNull(store.refForHash(chunkHash(bs,0)));
		out.write(bs,2*CHUNK-1,1);
		assertNotNull(store.refForHash(chunkHash(bs,0)));
		assertNotNull(store.refForHash(chunkHash(bs,1)));

		// One large write is saved at each interval it reaches
		out.write(bs,2*CHUNK,3*CHUNK+100);
		assertNotNull(store.refForHash(chunkHash(bs,3)));
		assertNull(store.refForHash(chunkHash(bs,4)));

		// Taking the Blob saves the rest
		ABlob blob=out.toBlob();
		assertArrayEquals(bs,blob.getBytes());
		assertTrue(blob.isCanonical());
		assertNotNull(store.refForHash(chunkHash(bs,4)));
		assertNotNull(store.refForHash(blob.getHash()));
	}

	@Test public void testFromStream() throws IOException {
		byte[] bs=randomBytes(3*CHUNK+7);
		MemoryStore store=new MemoryStore();
		ABlob saved=Blobs.fromStream(new ByteArrayInputStream(bs),store);
		assertArrayEquals(bs,saved.getBytes());
		assertNotNull(store.refForHash(saved.getHash()));
		assertEquals(Blobs.fromStream(new ByteArrayInputStream(bs)),saved);
	}

	@Test public void testArguments() {
		MemoryStore store=new MemoryStore();
		assertThrows(IllegalArgumentException.class,()->BlobOutputStream.create(store,0));
		assertThrows(IllegalArgumentException.class,()->BlobOutputStream.create(null,-1));
		BlobOutputStream out=BlobOutputStream.create(store);
		assertThrows(IndexOutOfBoundsException.class,()->out.write(new byte[10],5,6));
		assertThrows(IndexOutOfBoundsException.class,()->out.write(new byte[10],-1,2));
	}
}
