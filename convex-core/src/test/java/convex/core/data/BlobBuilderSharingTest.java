package convex.core.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.util.BlobBuilder;

public class BlobBuilderSharingTest {
	@Test void boundariesAndRepresentations() throws Exception {
		byte[] source=new byte[131074];
		new Random(8127).nextBytes(source);
		for (int prefix:new int[] {0,1,4095,4096,8192,65536}) {
			for (int length:new int[] {0,1,4095,4096,4097,8192,65535,65536,65537}) {
				Blob flat=Blob.wrap(source,1,length); // Include a nonzero backing-array offset.
				for (ABlob input:new ABlob[] {flat,flat.getCanonical()}) {
					BlobBuilder builder=new BlobBuilder();
					builder.append(source,0,prefix);
					ABlob snapshot=builder.toBlob();
					builder.append(input);
					byte[] expected=new byte[prefix+length];
					System.arraycopy(source,0,expected,0,prefix);
					System.arraycopy(source,1,expected,prefix,length);
					ABlob result=builder.toBlob();
					assertArrayEquals(expected,result.getBytes());
					assertEquals(expected.length,builder.count());
					assertEquals(Blob.wrap(expected).getEncoding(),result.getEncoding());
					result.validate();
					builder.append((byte)42);
					builder.append(ByteBuffer.wrap(new byte[] {43,44}));
					assertArrayEquals(expected,result.getBytes());
					assertArrayEquals(Arrays.copyOf(source,prefix),snapshot.getBytes());
					assertEquals(Blob.fromHex("2a2b2c"),builder.slice(expected.length,builder.count()));
					builder.clear();
					assertSame(Blob.EMPTY,builder.toBlob());
					assertArrayEquals(expected,result.getBytes());
				}
			}
		}
	}

	@Test void sharesCompleteChunksAndKeepsTailWritable() {
		ABlob source=Blob.createRandom(new Random(123),65536).getCanonical();
		BlobBuilder builder=new BlobBuilder(source);
		assertSame(source,builder.toBlob());
		ABlob snapshot=builder.toBlob();
		builder.append(source);
		ABlob doubled=builder.toBlob();
		assertSame(source,doubled.getRef(0).getValue());
		assertSame(source,doubled.getRef(1).getValue());
		builder.append((byte)1);
		assertSame(source,snapshot);
		assertEquals(131072,doubled.count());
		assertEquals(131073,builder.count());
		assertEquals(1,builder.toBlob().byteAt(131072));
	}

	@Test void fullOpaqueTreeCanBeAppendedWithoutLoadingChunks() {
		ABlob tree=Blob.createRandom(new Random(124),65536).getCanonical();
		ABlob opaque=tree.updateRefs(ref -> RefSoft.createForHash(ref.getHash(),null).withFlags(ref.getFlags()));
		assertSame(opaque,new BlobBuilder(opaque).toBlob());
	}

	@Test void rejectsOverflowBeforeChangingBuilder() {
		// A logarithmic-size DAG represents this without allocating the byte content.
		ABlob huge=Blobs.createFilled(0,Long.MAX_VALUE-(Blob.CHUNK_LENGTH-1));
		BlobBuilder builder=new BlobBuilder(huge);
		assertThrows(IllegalArgumentException.class,() -> builder.append(Blob.EMPTY_CHUNK));
		assertSame(huge,builder.toBlob());
		assertEquals(huge.count(),builder.count());
		builder.append(Blob.EMPTY_CHUNK.slice(0,4095));
		assertEquals(Long.MAX_VALUE,builder.count());
		assertThrows(IllegalArgumentException.class,() -> builder.append(Blob.fromHex("00")));
		assertEquals(Long.MAX_VALUE,builder.count());
	}
}
