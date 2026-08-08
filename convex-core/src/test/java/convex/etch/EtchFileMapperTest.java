package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	public void testReadsDoNotExtendExistingFile() throws Exception {
		assertReadsDoNotExtend(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER);
		if (EtchFileMapperFactory.isFFMAvailable()) {
			assertReadsDoNotExtend(EtchConfig.MappingMode.MEMORY_SEGMENT);
		}
	}

	@Test
	public void testWriteCapacityIsPreparedBeforePut() throws Exception {
		assertWriteCapacityIsPrepared(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER);
		if (EtchFileMapperFactory.isFFMAvailable()) {
			assertWriteCapacityIsPrepared(EtchConfig.MappingMode.MEMORY_SEGMENT);
		}
	}

	@Test
	public void testReadOnlyMappingRejectsWritePreparation() throws Exception {
		assertReadOnlyMapping(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER);
		if (EtchFileMapperFactory.isFFMAvailable()) {
			assertReadOnlyMapping(EtchConfig.MappingMode.MEMORY_SEGMENT);
		}
	}

	private static void assertReadOnlyMapping(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-mapper-read-only", ".dat");
		byte[] expected=new byte[] { 1,2,3,4,5,6,7,8 };
		try (RandomAccessFile writer=new RandomAccessFile(file,"rw")) {
			writer.write(expected);
		}
		try (RandomAccessFile reader=new RandomAccessFile(file,"r");
				EtchFileMapper mapper=EtchFileMapperFactory.createReadOnly(reader.getChannel(),mode)) {
			byte[] actual=new byte[expected.length];
			mapper.get(0L,actual,0,actual.length);
			assertArrayEquals(expected,actual);
			assertThrows(java.io.IOException.class,()->mapper.ensureWriteCapacity(0L,1L));
			assertThrows(java.io.IOException.class,()->mapper.writeIndexSlotRelease(0L,42L));
			mapper.force();
			mapper.forceRange(0L,expected.length);
		}
		assertEquals(expected.length,file.length());
		if (!file.delete()) file.deleteOnExit();
	}

	private static void assertWriteCapacityIsPrepared(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-mapper-capacity", ".dat");
		long position=GROWN_POSITION;
		byte[] value=new byte[] { 1,2,3,4,5,6,7,8 };
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),mode)) {
			mapper.ensureWriteCapacity(position,value.length);
			assertTrue(data.length()>=position+value.length,
					"Write capacity was not present before put");
			mapper.put(position,value,0,value.length);
			byte[] actual=new byte[value.length];
			mapper.get(position,actual,0,actual.length);
			assertArrayEquals(value,actual);
		}
		if (!file.delete()) file.deleteOnExit();
	}

	private static void assertReadsDoNotExtend(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-mapper-existing", ".dat");
		long existingLength=1_003L;
		String implementation;
		try (RandomAccessFile data=new RandomAccessFile(file,"rw")) {
			data.setLength(existingLength);
			try (EtchFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),mode)) {
				implementation=mapper.implementationName();
				mapper.get(existingLength-1L,new byte[1],0,1);
				assertEquals(existingLength,data.length(),"Reading an existing file changed its length");
				assertThrows(java.io.IOException.class,()->mapper.get(existingLength,new byte[1],0,1));
				assertEquals(existingLength,data.length(),"An invalid read extended the file");
			}
		}
		boolean deleted=file.delete();
		if ("MemorySegment".equals(implementation)) {
			assertTrue(deleted,"FFM mapping backend did not release temporary file");
		} else if (!deleted) {
			file.deleteOnExit();
		}
	}

	private static void assertRoundTripAndGrowth(EtchFileMapper mapper) throws Exception {
		byte[] prefix=new byte[] { 0x12,0x34,0x56,0x78,0x09,0x0a,0x0b,0x0c };
		mapper.ensureWriteCapacity(3L,32L);
		mapper.put(3L,prefix,0,prefix.length);
		mapper.writeIndexSlotRelease(24L,0x0102030405060708L);
		byte[] source=new byte[] { 9,8,7,6,5 };
		mapper.put(32L,source,1,3);

		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		byte[] transformed=new byte[] { 21,22,23,24,25 };
		mapper.ensureWriteCapacity(48L,transformed.length);
		mapper.putTransformed(48L,transformed,0,transformed.length,cipher.start(48L));

		// Force a remap, then confirm old positions remain accessible.
		byte[] grown=new byte[] { 0x11,0x22,0x33,0x44,0x55,0x66,0x77,(byte)0x88 };
		mapper.ensureWriteCapacity(GROWN_POSITION,grown.length);
		mapper.put(GROWN_POSITION,grown,0,grown.length);

		byte[] actualPrefix=new byte[prefix.length];
		mapper.get(3L,actualPrefix,0,actualPrefix.length);
		assertArrayEquals(prefix,actualPrefix);
		assertTrue(mapper.matches(3L,prefix,0,prefix.length));
		byte[] different=prefix.clone();
		different[different.length-1]^=1;
		assertFalse(mapper.matches(3L,different,0,different.length));
		assertEquals(0x0102030405060708L,mapper.readIndexSlotAcquire(24L));
		byte[] destination=new byte[3];
		mapper.get(32L,destination,0,destination.length);
		assertArrayEquals(new byte[] { 8,7,6 },destination);
		byte[] transformedResult=new byte[transformed.length];
		mapper.getTransformed(48L,transformedResult,0,transformedResult.length,cipher.start(48L));
		assertArrayEquals(transformed,transformedResult);
		byte[] actualGrown=new byte[grown.length];
		mapper.get(GROWN_POSITION,actualGrown,0,actualGrown.length);
		assertArrayEquals(grown,actualGrown);

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
