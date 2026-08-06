package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.jupiter.api.Test;

/**
 * Contract tests shared by the Java 21 and FFM mapping backends.
 */
public class EtchFileMapperTest {
	private static final long GROWN_POSITION=2_000_000L;

	@Test
	public void testMapperRoundTripAndGrowth() throws Exception {
		File file=File.createTempFile("etch-mapper", ".dat");
		String implementation;
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_2);
			implementation=mapper.implementationName();
			assertRoundTripAndGrowth(mapper);
			if ("MemorySegment".equals(implementation)) {
				long allocated=data.length();
				assertTrue(allocated>=(64L<<20),"FFM growth used an unexpectedly small increment");
				assertTrue(allocated<=(70L<<20),"FFM growth allocated excessive file space");
			}
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
				EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_2)) {
			boolean ffmAvailable=(Runtime.version().feature()>=22)&&ffmBackendIsPackaged();
			if (Boolean.getBoolean("convex.etch.requireFFM")) {
				assertTrue(ffmAvailable,"JDK 22+ Maven build did not include the Etch FFM backend");
			}
			String expected=ffmAvailable
					?"MemorySegment":"MappedByteBuffer";
			assertEquals(expected,mapper.implementationName());
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testV1AlwaysUsesCompatibleBackend() throws Exception {
		File file=File.createTempFile("etch-mapper-v1", ".dat");
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_1)) {
			assertEquals("MappedByteBuffer",mapper.implementationName());
			assertRoundTripAndGrowth(mapper);
		}
		if (!file.delete()) file.deleteOnExit();
	}

	@Test
	public void testFFMDoesNotExtendExistingFileOnOpen() throws Exception {
		File file=File.createTempFile("etch-mapper-existing", ".dat");
		long existingLength=(10L<<20)+123L;
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(existingLength);
			try (EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_2)) {
				if ("MemorySegment".equals(mapper.implementationName())) {
					mapper.getByte(0L);
					assertEquals(existingLength,data.length(),"Opening an existing file changed its length");
				}
			}
		}
		if (!file.delete()) file.deleteOnExit();
	}

	private static void assertRoundTripAndGrowth(EtchFileMapper mapper) throws Exception {
		mapper.putByte(3L,(byte)0x12);
		mapper.putShort(4L,(short)0x3456);
		mapper.putLong(6L,0x789abcdef0123456L);
		mapper.putLongRelease(16L,0x0102030405060708L);
		byte[] source=new byte[] { 9,8,7,6,5 };
		mapper.put(32L,source,1,3);

		// Force a remap, then confirm old positions remain accessible.
		mapper.putLong(GROWN_POSITION,0x1122334455667788L);
		mapper.putByte(14L,(byte)0x5a);

		assertEquals((byte)0x12,mapper.getByte(3L));
		assertEquals((short)0x3456,mapper.getShort(4L));
		assertEquals(0x789abcdef0123456L,mapper.getLong(6L));
		assertEquals((byte)0x5a,mapper.getByte(14L));
		assertEquals(0x0102030405060708L,mapper.getLongAcquire(16L));
		byte[] destination=new byte[3];
		mapper.get(32L,destination,0,destination.length);
		assertArrayEquals(new byte[] { 8,7,6 },destination);
		assertEquals(0x1122334455667788L,mapper.getLong(GROWN_POSITION));

		mapper.force();
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
