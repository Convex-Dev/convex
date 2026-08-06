package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

public class EtchFileAccessTest {
	@Test
	public void testCompleteLogicalRangeChecks() throws Exception {
		File file=File.createTempFile("etch-access-range", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(128L);
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),64L,data.length())) {
				access.getByte(63L);
				assertThrows(EtchCorruptionError.class,()->access.get(63L,new byte[2],0,2));
				assertThrows(EtchCorruptionError.class,()->access.put(63L,new byte[2],0,2));
				assertEquals(128L,data.length());
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testAppendPublishesLogicalEndOnCommit() throws Exception {
		File file=File.createTempFile("etch-access-append", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=new MappedByteBufferEtchFileMapper(data.getChannel());
			try (EtchFileAccess access=new EtchFileAccess(mapper,file.getName(),0L,0L)) {
				byte[] first=new byte[] { 1,2,3,4 };
				long firstPosition=access.beginAppend(first.length,1);
				access.putAppend(firstPosition,first,0,first.length);
				assertEquals(0L,access.getDataLength());
				access.commitAppend();
				assertEquals(4L,access.getDataLength());

				byte[] second=new byte[] { 5,6,7,8 };
				assertEquals(8L,access.append(second,0,second.length,8));
				assertEquals(12L,access.getDataLength());
				byte[] actual=new byte[second.length];
				access.get(8L,actual,0,actual.length);
				assertArrayEquals(second,actual);
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
}
