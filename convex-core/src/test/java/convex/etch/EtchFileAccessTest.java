package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

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
		public String implementationName() {
			return "failing-test";
		}

		@Override
		public void close() {
		}
	}
}
