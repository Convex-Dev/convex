package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;
import convex.core.util.Utils;

/** Byte-exact tests for canonical legacy Etch files, without filesystem I/O. */
public class EtchHeaderTest {
	@Test
	public void testCanonicalV1File() throws Exception {
		assertCanonicalLegacyFile(EtchConstants.VERSION_1,EtchConstants.V1_INDEX_START);
	}

	@Test
	public void testCanonicalV2File() throws Exception {
		assertCanonicalLegacyFile(EtchConstants.VERSION_2,EtchConstants.V2_INDEX_START);
	}

	private static void assertCanonicalLegacyFile(short version, long expectedIndexStart)
			throws Exception {
		InMemoryMapper mapper=new InMemoryMapper();
		try (EtchFileAccess access=new EtchFileAccess(mapper,"memory-v"+version,0L,0L)) {
			EtchHeader header=EtchHeader.create(EtchConfig.create(version));
			header.initialise(access);

			long expectedLength=expectedIndexStart
					+(long)EtchConstants.ROOT_INDEX_SIZE*EtchConstants.POINTER_SIZE;
			assertEquals(expectedLength,access.getDataLength());
			byte[] file=mapper.copyOf(expectedLength);

			assertEquals(EtchConstants.MAGIC_NUMBER,Utils.readShort(file,0)&0xffff);
			assertEquals(version,Utils.readShort(file,(int)EtchConstants.VERSION_OFFSET));
			assertEquals(expectedLength,Utils.readLong(file,
					(int)EtchConstants.DATA_LENGTH_OFFSET,Long.BYTES));
			assertZero(file,(int)EtchConstants.ROOT_HASH_OFFSET,Math.toIntExact(expectedIndexStart));
			assertZero(file,Math.toIntExact(expectedIndexStart),file.length);

			LegacyEtchHeader decoded=LegacyEtchHeader.decode(file,"memory-v"+version);
			assertEquals(version,decoded.version());
			assertEquals(expectedIndexStart,decoded.indexStart());
			assertEquals(expectedLength,decoded.storedLength());
			assertEquals(Hash.UNSET_HASH,decoded.getRootHash(access));

			byte[] rootBytes=new byte[Hash.LENGTH];
			for (int i=0;i<rootBytes.length;i++) rootBytes[i]=(byte)(i+1);
			Hash root=Hash.wrap(rootBytes);
			decoded.setRootHash(access,root);
			assertEquals(root,decoded.getRootHash(access));
			assertArrayEquals(rootBytes,Arrays.copyOfRange(mapper.bytes,
					(int)EtchConstants.ROOT_HASH_OFFSET,
					(int)EtchConstants.ROOT_HASH_OFFSET+Hash.LENGTH));
		}
	}

	private static void assertZero(byte[] data, int start, int end) {
		for (int i=start;i<end;i++) {
			if (data[i]!=0) fail("Expected canonical zero byte at file offset "+i);
		}
	}

	private static final class InMemoryMapper implements EtchFileMapper {
		private byte[] bytes=new byte[0];

		byte[] copyOf(long length) {
			return Arrays.copyOf(bytes,Math.toIntExact(length));
		}

		@Override
		public void get(long position, byte[] destination, int offset, int length) {
			System.arraycopy(bytes,Math.toIntExact(position),destination,offset,length);
		}

		@Override
		public void getTransformed(long position, byte[] destination, int offset, int length,
				EtchCipherCursor cursor) throws IOException {
			cursor.transform(ByteBuffer.wrap(bytes,Math.toIntExact(position),length),
					ByteBuffer.wrap(destination,offset,length));
		}

		@Override
		public void ensureWriteCapacity(long position, long length) {
			int required=Math.toIntExact(Math.addExact(position,length));
			if (required>bytes.length) bytes=Arrays.copyOf(bytes,required);
		}

		@Override
		public void put(long position, byte[] source, int offset, int length) {
			System.arraycopy(source,offset,bytes,Math.toIntExact(position),length);
		}

		@Override
		public void putTransformed(long position, byte[] source, int offset, int length,
				EtchCipherCursor cursor) throws IOException {
			cursor.transform(ByteBuffer.wrap(source,offset,length),
					ByteBuffer.wrap(bytes,Math.toIntExact(position),length));
		}

		@Override
		public long readIndexSlotAcquire(long position) {
			return Utils.readLong(bytes,Math.toIntExact(position),Long.BYTES);
		}

		@Override
		public void writeIndexSlotRelease(long position, long value) {
			Utils.writeLong(bytes,Math.toIntExact(position),value);
		}

		@Override
		public void force() {
		}

		@Override
		public String implementationName() {
			return "in-memory";
		}

		@Override
		public void close() {
		}
	}
}
