package convex.core.data.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.BlobTree;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.MemoryStore;

public class BlobChannelTest {

	private static final int CHUNK=Blob.CHUNK_LENGTH;

	/** Sizes from empty up to a two-level tree, with partial last chunks */
	private static final long[] SIZES={0,1,CHUNK-1,CHUNK,CHUNK+1,3*CHUNK+100,18*CHUNK+77};

	private static ABlob randomBlob(long length) {
		return Blobs.createRandom(new Random(length*31+7),length);
	}

	@Test public void testSequentialReads() throws IOException {
		for (long n: SIZES) {
			ABlob blob=randomBlob(n);
			try (BlobChannel channel=BlobChannel.create(blob)) {
				assertEquals(n,channel.size());
				ByteBuffer all=ByteBuffer.allocate((int)n);
				ByteBuffer step=ByteBuffer.allocate(1000);
				while (channel.read(step.clear())>=0) {
					all.put(step.flip());
				}
				assertEquals(n,channel.position());
				assertEquals(blob,Blob.wrap(all.array()));
			}
		}
	}

	@Test public void testPositionedReads() throws IOException {
		for (long n: SIZES) {
			ABlob blob=randomBlob(n);
			long[] positions={0,1,CHUNK-1,CHUNK,CHUNK+1,2*CHUNK-1,n-1,n,n+5};
			try (BlobChannel channel=BlobChannel.create(blob)) {
				for (long pos: positions) {
					if (pos<0) continue;
					ByteBuffer buf=ByteBuffer.allocate(300);
					channel.position(pos);
					int read=channel.read(buf);
					if (pos>=n) {
						assertEquals(-1,read);
						assertEquals(pos,channel.position());
					} else {
						long expected=Math.min(300,n-pos);
						assertEquals(expected,read);
						assertEquals(blob.slice(pos,pos+expected),Blob.wrap(buf.array(),0,read));
						assertEquals(pos+read,channel.position());
					}
				}

				// A full destination reads nothing, even mid-blob
				channel.position(0);
				assertEquals((n==0)?-1:0,channel.read(ByteBuffer.allocate(0)));
			}
		}
	}

	@Test public void testStreamSkip() throws IOException {
		ABlob blob=randomBlob(18*CHUNK+77);
		long n=blob.count();
		for (long skip: new long[] {0,1,CHUNK,5*CHUNK+3,n}) {
			try (InputStream in=blob.getInputStream()) {
				in.skipNBytes(skip);
				assertEquals(n-skip,in.available());
				assertEquals(blob.slice(skip,n),Blob.wrap(in.readAllBytes()));
			}
		}
	}

	@Test public void testRangeReadSkipsEarlierChunks() throws IOException, BadFormatException {
		ABlob blob=randomBlob(3*CHUNK+100);
		assertTrue(blob instanceof BlobTree);

		// A fresh store holding every chunk except the first
		MemoryStore store=new MemoryStore();
		for (long i=1; i*CHUNK<blob.count(); i++) {
			Cells.store(blob.getChunk(i),store);
		}
		ABlob partial=store.decode(blob.getEncoding());
		assertEquals(blob.count(),partial.count());

		try (BlobChannel channel=BlobChannel.create(partial)) {
			channel.position(CHUNK);
			ByteBuffer rest=ByteBuffer.allocate((int)(blob.count()-CHUNK));
			assertEquals(rest.capacity(),channel.read(rest));
			assertEquals(blob.slice(CHUNK,blob.count()),Blob.wrap(rest.array()));

			channel.position(0);
			assertThrows(MissingDataException.class,()->channel.read(ByteBuffer.allocate(10)));
		}

		try (InputStream in=partial.getInputStream()) {
			in.skipNBytes(CHUNK+10);
			assertEquals(blob.slice(CHUNK+10,blob.count()),Blob.wrap(in.readAllBytes()));
		}
	}

	@Test public void testReadOnlyAndClose() throws IOException {
		BlobChannel channel=BlobChannel.create(randomBlob(10));
		assertThrows(NonWritableChannelException.class,()->channel.write(ByteBuffer.allocate(1)));
		assertThrows(NonWritableChannelException.class,()->channel.truncate(0));
		assertThrows(IllegalArgumentException.class,()->channel.position(-1));
		assertThrows(IllegalArgumentException.class,()->BlobChannel.create(null));

		assertTrue(channel.isOpen());
		channel.close();
		assertFalse(channel.isOpen());
		assertThrows(ClosedChannelException.class,()->channel.read(ByteBuffer.allocate(1)));
		assertThrows(ClosedChannelException.class,()->channel.position());
		assertThrows(ClosedChannelException.class,()->channel.size());
	}
}
