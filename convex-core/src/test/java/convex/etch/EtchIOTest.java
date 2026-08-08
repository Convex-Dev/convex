package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.util.Utils;

public class EtchIOTest {
	@Test
	public void testCompleteLogicalRangeChecks() throws Exception {
		File file=File.createTempFile("etch-access-range", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(128L);
			AFileMapper mapper=new MBBFileMapper(data.getChannel(),false,64L,file.getName());
			try {
				mapper.read(63L,new byte[1],0,1,null);
				assertThrows(EtchCorruptionError.class,()->mapper.read(63L,new byte[2],0,2,null));
				assertThrows(EtchCorruptionError.class,()->mapper.write(63L,new byte[2],0,2,null));
				assertEquals(128L,data.length());
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testSemanticAppendsPublishLogicalEnd() throws Exception {
		File file=File.createTempFile("etch-access-append", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			try {
				Blob key=Blob.wrap(new byte[] { 1,2,3,4 });
				byte[] header=new byte[] { 5,6 };
				Blob encoding=Blob.wrap(new byte[] { 7,8,9 });
				assertEquals(0L,mapper.append(key,header,header.length,encoding,null));
				assertEquals(9L,mapper.length());
				byte[] actual=new byte[9];
				mapper.read(0L,actual,0,actual.length,null);
				assertArrayEquals(new byte[] { 1,2,3,4,5,6,7,8,9 },actual);

				byte[] second=new byte[] { 5,6,7,8 };
				assertEquals(16L,mapper.append(second,0,second.length,8,null));
				assertEquals(20L,mapper.length());
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testRejectsLogicalEndBeyondPhysicalFile() throws Exception {
		File file=File.createTempFile("etch-access-length", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			assertThrows(EtchCorruptionError.class,
					()->new MBBFileMapper(data.getChannel(),false,1L,file.getName()));
			mapper.close();
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testFailedMultipartAppendDoesNotPublishLogicalEnd() throws Exception {
		FailingWriteMapper mapper=new FailingWriteMapper();
		try {
			Blob key=Blob.wrap(new byte[] { 1,2,3,4 });
			byte[] header=new byte[] { 5,6 };
			Blob encoding=Blob.wrap(new byte[] { 7,8,9 });
			assertThrows(java.io.IOException.class,
					()->mapper.append(key,header,header.length,encoding,null));
			assertEquals(1,mapper.capacityChecks);
			assertEquals(3,mapper.puts);
			assertEquals(0L,mapper.preparedPosition);
			assertEquals(9L,mapper.preparedLength);
			assertEquals(0L,mapper.length());
		} finally {
			mapper.close();
		}
	}

	@Test
	public void testEncryptedDataRecordUsesOneCipherInitialisation() throws Exception {
		File file=File.createTempFile("etch-access-encrypted-data", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			try {
				byte[] plainHeader=new byte[] { 11,12,13,14 };
				assertEquals(0L,mapper.append(plainHeader,0,plainHeader.length,1,null));
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
				long position=mapper.append(key,recordHeader,recordHeader.length,encoding,cipher);
				assertEquals(1,cipher.starts);

				byte[] raw=new byte[keyBytes.length+recordHeader.length+encodingBytes.length];
				mapper.read(position,raw,0,raw.length,null);
				byte[] plain=new byte[raw.length];
				System.arraycopy(keyBytes,0,plain,0,keyBytes.length);
				System.arraycopy(recordHeader,0,plain,keyBytes.length,recordHeader.length);
				System.arraycopy(encodingBytes,0,plain,keyBytes.length+recordHeader.length,encodingBytes.length);
				assertFalse(java.util.Arrays.equals(plain,raw));

				cipher.reset();
				byte[] stored=new byte[plain.length];
				mapper.read(position,stored,0,stored.length,cipher);
				assertEquals(1,cipher.starts);
				assertArrayEquals(plain,stored);
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testSequentialDataOperationsReuseCipherPosition() throws Exception {
		File file=File.createTempFile("etch-io-sequential", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(8L);
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			try {
				byte[] first=new byte[] { 1,2,3,4 };
				byte[] second=new byte[] { 5,6,7,8 };
				mapper.write(0L,first,0,first.length,cipher);
				mapper.write(4L,second,0,second.length,cipher);
				assertEquals(1,cipher.starts);

				cipher.reset();
				byte[] actual=new byte[8];
				mapper.read(0L,actual,0,first.length,cipher);
				mapper.read(4L,actual,first.length,second.length,cipher);
				assertEquals(1,cipher.starts);
				assertArrayEquals(new byte[] { 1,2,3,4,5,6,7,8 },actual);
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testEncryptedIndexRemainsAtomicAndOptional() throws Exception {
		File file=File.createTempFile("etch-access-encrypted-index", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			try {
				long indexPosition=mapper.appendZeros(2*Long.BYTES,Long.BYTES,cipher);
				assertEquals(1,cipher.starts);
				assertEquals(0L,mapper.readLongAcquire(indexPosition,cipher));
				long value=0x0123456789abcdefL;
				mapper.writeLongRelease(indexPosition+Long.BYTES,value,cipher);
				assertEquals(value,mapper.readLongAcquire(indexPosition+Long.BYTES,cipher));
				assertFalse(value==mapper.readLongAcquire(indexPosition+Long.BYTES,null));
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testPlainIndexFastPathDoesNotTouchCipher() throws Exception {
		File file=File.createTempFile("etch-access-plain-index", ".dat");
		CountingCipher cipher=new CountingCipher(AES256CTREtchCipher.fromKey(new byte[32]));
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			AFileMapper mapper=new MBBFileMapper(data.getChannel());
			mapper.appendZeros(Long.BYTES,Long.BYTES,null);
			mapper.writeLongRelease(0L,0x0123456789abcdefL,null);
			try {
				cipher.reset();
				assertEquals(0x0123456789abcdefL,mapper.readLongAcquire(0L,null));
				mapper.writeLongRelease(0L,0x1020304050607080L,null);
				assertEquals(0x1020304050607080L,mapper.readLongAcquire(0L,null));
				assertEquals(0,cipher.starts);
				assertEquals(0,cipher.slotTransforms);
			} finally {
				mapper.close();
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	private static final class FailingWriteMapper extends AFileMapper {
		private int capacityChecks;
		private int puts;
		private long preparedPosition;
		private long preparedLength;

		private FailingWriteMapper() {
			super("failing",0L,0L,false);
		}

		@Override
		void ensureMapped(long position, long length) {
			capacityChecks++;
			preparedPosition=position;
			preparedLength=length;
		}

		@Override
		void writeMapped(long position, byte[] source, int offset, int length)
				throws java.io.IOException {
			if (++puts==3) throw new java.io.IOException("Expected test failure");
		}

		@Override
		void readMapped(long position, byte[] destination, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		boolean matchesMapped(long position, byte[] expected, int offset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		void readTransformedMapped(long position, byte[] destination, int offset, int length,
				EtchFileCipher cipher) {
			throw new UnsupportedOperationException();
		}

		@Override
		void writeTransformedMapped(long position, byte[] source, int offset, int length,
				EtchFileCipher cipher) throws java.io.IOException {
			writeMapped(position,source,offset,length);
		}

		@Override
		long readLongAcquireMapped(long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		void writeLongReleaseMapped(long position, long value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void force() {
		}

		@Override
		void forceRangeMapped(long position, long length) {
		}

		@Override
		public String implementationName() {
			return "failing-test";
		}

		@Override
		public void close() {
		}
	}

	private static final class CountingCipher extends EtchFileCipher {
		private final EtchFileCipher delegate;
		private int starts;
		private int slotTransforms;
		private long position=-1L;

		private CountingCipher(EtchFileCipher delegate) {
			this.delegate=delegate;
		}

		@Override
		public void initialise(long fileOffset) throws java.io.IOException {
			if (position==fileOffset) return;
			starts++;
			delegate.initialise(fileOffset);
			position=fileOffset;
		}

		@Override
		public void decrypt(java.nio.ByteBuffer input, byte[] destination, int destinationOffset)
				throws java.io.IOException {
			int length=input.remaining();
			delegate.decrypt(input,destination,destinationOffset);
			position+=length;
		}

		@Override
		public void encrypt(byte[] source, int sourceOffset, java.nio.ByteBuffer output)
				throws java.io.IOException {
			int length=output.remaining();
			delegate.encrypt(source,sourceOffset,output);
			position+=length;
		}

		@Override
		public long transformLong(long fileOffset, long value) throws java.io.IOException {
			slotTransforms++;
			long result=delegate.transformLong(fileOffset,value);
			position=fileOffset+Long.BYTES;
			return result;
		}

		private void reset() {
			starts=0;
			slotTransforms=0;
		}
	}
}
