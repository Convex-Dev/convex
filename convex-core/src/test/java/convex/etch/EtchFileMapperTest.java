package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

/**
 * Contract tests shared by the Java 21 and FFM mapping backends.
 */
public class EtchFileMapperTest {
	@Test
	public void testMapperRoundTripAndGrowth() throws Exception {
		File file=File.createTempFile("etch-mapper", ".dat");
		String implementation;
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),Etch.ETCH_VERSION_2);
			implementation=mapper.implementationName();

			EtchCursor initial=mapper.cursor(3L,0L);
			initial.put((byte)0x12);
			initial.putShort((short)0x3456);
			initial.putLong(0x789abcdef0123456L);

			// Force a remap in the FFM backend and a region-0 remap in the base backend.
			long grownPosition=300_000L;
			mapper.cursor(grownPosition,grownPosition).putLong(0x1122334455667788L);
			initial.put((byte)0x5a); // a cursor obtained before growth remains safe

			EtchCursor readInitial=mapper.cursor(3L,grownPosition+Long.BYTES);
			assertEquals((byte)0x12,readInitial.get());
			assertEquals((short)0x3456,readInitial.getShort());
			assertEquals(0x789abcdef0123456L,readInitial.getLong());
			assertEquals((byte)0x5a,readInitial.get());
			assertEquals(0x1122334455667788L,
					mapper.cursor(grownPosition,grownPosition+Long.BYTES).getLong());

			mapper.force();
			mapper.close();
		}

		boolean deleted=file.delete();
		if ("MemorySegment".equals(implementation)) {
			assertTrue(deleted,"FFM mapping backend did not release temporary file");
		} else if (!deleted) {
			file.deleteOnExit();
		}
	}

	@Test
	public void testRuntimeBackendSelection() throws Exception {
		File file=File.createTempFile("etch-mapper-selection", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),Etch.ETCH_VERSION_2)) {
			String expected=(Runtime.version().feature()>=22)&&ffmBackendIsPackaged()
					?"MemorySegment":"MappedByteBuffer";
			assertEquals(expected,mapper.implementationName());
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testV1AlwaysUsesCompatibleBackend() throws Exception {
		File file=File.createTempFile("etch-mapper-v1", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),Etch.ETCH_VERSION_1)) {
			assertEquals("MappedByteBuffer",mapper.implementationName());
		}
		if (!file.delete()) file.deleteOnExit();
	}

	private static boolean ffmBackendIsPackaged() {
		try {
			Class.forName("convex.etch.FFMEtchFileMapper");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
