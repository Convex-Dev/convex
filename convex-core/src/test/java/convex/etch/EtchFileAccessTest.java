package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.util.Utils;

public class EtchFileAccessTest {
	@Test
	public void testCompleteLogicalRangeChecks() throws Exception {
		File file=File.createTempFile("etch-access-range", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(128L);
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),64L,data.length())) {
				access.readData(63L,new byte[1],0,1);
				assertThrows(EtchCorruptionError.class,()->access.readData(63L,new byte[2],0,2));
				assertThrows(EtchCorruptionError.class,()->access.writeData(63L,new byte[2],0,2));
				assertEquals(128L,data.length());
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testSemanticAppendsPublishLogicalEnd() throws Exception {
		File file=File.createTempFile("etch-access-append", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),0L,0L)) {
				Blob key=Blob.wrap(new byte[] { 1,2,3,4 });
				byte[] header=new byte[] { 5,6 };
				Blob encoding=Blob.wrap(new byte[] { 7,8,9 });
				assertEquals(0L,access.appendDataRecord(key,header,header.length,encoding));
				assertEquals(9L,access.getDataLength());
				byte[] actual=new byte[9];
				access.readData(0L,actual,0,actual.length);
				assertArrayEquals(new byte[] { 1,2,3,4,5,6,7,8,9 },actual);

				byte[] second=new byte[] { 5,6,7,8 };
				assertEquals(16L,access.appendIndex(second,0,second.length,8));
				assertEquals(20L,access.getDataLength());
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testRejectsLogicalEndBeyondPhysicalFile() throws Exception {
		File file=File.createTempFile("etch-access-length", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			assertThrows(EtchCorruptionError.class,
					()->new EtchFileAccess(mapper,file.getName(),1L,data.length()));
			mapper.close();
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testFailedMultipartAppendDoesNotPublishLogicalEnd() throws Exception {
		FailingWriteMapper mapper=new FailingWriteMapper();
		try (EtchFileAccess access=new EtchFileAccess(mapper,"failing",0L,0L)) {
			Blob key=Blob.wrap(new byte[] { 1,2,3,4 });
			byte[] header=new byte[] { 5,6 };
			Blob encoding=Blob.wrap(new byte[] { 7,8,9 });
			assertThrows(java.io.IOException.class,
					()->access.appendDataRecord(key,header,header.length,encoding));
			assertEquals(1,mapper.capacityChecks);
			assertEquals(3,mapper.puts);
			assertEquals(0L,mapper.preparedPosition);
			assertEquals(9L,mapper.preparedLength);
			assertEquals(0L,access.getDataLength());
		}
	}

	@Test
	public void testEncryptedDataRecordUsesOneCipherInitialisation() throws Exception {
		File file=File.createTempFile("etch-access-encrypted-data", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),0L,0L,cipher,false)) {
				byte[] plainHeader=new byte[] { 11,12,13,14 };
				assertEquals(0L,access.appendHeader(plainHeader,0,plainHeader.length));
				assertEquals(0,cipher.starts);

				byte[] keyBytes=new byte[EtchConstants.KEY_SIZE];
				for (int i=0;i<keyBytes.length;i++) keyBytes[i]=(byte)(i+1);
				Blob key=Blob.wrap(keyBytes);
				byte[] encodingBytes=new byte[] { 41,42,43,44,45 };
				Blob encoding=Blob.wrap(encodingBytes);
				byte[] recordHeader=new byte[EtchConstants.LABEL_SIZE+EtchConstants.ENCODING_LENGTH_SIZE];
				recordHeader[0]=7;
				Utils.writeLong(recordHeader,1,1234L);
				Utils.writeShort(recordHeader,EtchConstants.LABEL_SIZE,(short)encodingBytes.length);

				cipher.reset();
				long position=access.appendDataRecord(key,recordHeader,recordHeader.length,encoding);
				assertEquals(1,cipher.starts);

				byte[] raw=new byte[keyBytes.length+recordHeader.length+encodingBytes.length];
				mapper.get(position,raw,0,raw.length);
				byte[] plain=new byte[raw.length];
				System.arraycopy(keyBytes,0,plain,0,keyBytes.length);
				System.arraycopy(recordHeader,0,plain,keyBytes.length,recordHeader.length);
				System.arraycopy(encodingBytes,0,plain,keyBytes.length+recordHeader.length,encodingBytes.length);
				assertFalse(java.util.Arrays.equals(plain,raw));

				cipher.reset();
				EtchFileAccess.DataRecord stored=access.readDataRecord(position,key);
				assertEquals(1,cipher.starts);
				assertArrayEquals(java.util.Arrays.copyOf(plain,keyBytes.length+recordHeader.length),stored.header());
				assertArrayEquals(encodingBytes,stored.encoding());

				byte[] wrongKeyBytes=keyBytes.clone();
				wrongKeyBytes[0]^=1;
				cipher.reset();
				assertNull(access.readDataRecord(position,Blob.wrap(wrongKeyBytes)));
				assertEquals(1,cipher.starts);
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testEncryptedIndexRemainsAtomicAndOptional() throws Exception {
		File file=File.createTempFile("etch-access-encrypted-index", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),0L,0L,cipher,true)) {
				long indexPosition=access.appendZeroIndex(2*Long.BYTES,Long.BYTES);
				assertEquals(1,cipher.starts);
				assertEquals(0L,access.readIndexSlotAcquire(indexPosition));
				long value=0x0123456789abcdefL;
				access.writeIndexSlotRelease(indexPosition+Long.BYTES,value);
				assertEquals(value,access.readIndexSlotAcquire(indexPosition+Long.BYTES));
				assertFalse(value==mapper.readIndexSlotAcquire(indexPosition+Long.BYTES));
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	private static final class FailingWriteMapper implements EtchFileMapper {
		private int capacityChecks;
		private int puts;
		private long preparedPosition;
		private long preparedLength;

		@Override
		public void ensureWriteCapacity(long position, long length) {
			capacityChecks++;
			preparedPosition=position;
			preparedLength=length;
		}

		@Override
		public void put(long position, byte[] source, int offset, int length) throws java.io.IOException {
			if (++puts==3) throw new java.io.IOException("Expected test failure");
		}

		@Override
		public void get(long position, byte[] destination, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean matches(long position, byte[] expected, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void getTransformed(long position, byte[] destination, int offset, int length,
				EtchCipherCursor cursor) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void putTransformed(long position, byte[] source, int offset, int length,
				EtchCipherCursor cursor) throws java.io.IOException {
			put(position,source,offset,length);
		}

		@Override
		public long readIndexSlotAcquire(long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void writeIndexSlotRelease(long position, long value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void force() {
		}

		@Override
		public void forceRange(long position, long length) {
		}

		@Override
		public String implementationName() {
			return "failing-test";
		}

		@Override
		public void close() {
		}
	}

	private static final class CountingCipher implements EtchFileCipher {
		private final EtchFileCipher delegate;
		private int starts;

		private CountingCipher(EtchFileCipher delegate) {
			this.delegate=delegate;
		}

		@Override
		public EtchCipherCursor start(long fileOffset) throws java.io.IOException {
			starts++;
			return delegate.start(fileOffset);
		}

		private void reset() {
			starts=0;
		}
	}
}
