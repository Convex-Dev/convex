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
			AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_2);
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
				AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_2)) {
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
				AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),EtchConstants.VERSION_1)) {
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
	public void testAppendOwnsGrowthAndLogicalLength() throws Exception {
		assertAppendOwnsGrowth(EtchConfig.MappingMode.MAPPED_BYTE_BUFFER);
		if (EtchFileMapperFactory.isFFMAvailable()) {
			assertAppendOwnsGrowth(EtchConfig.MappingMode.MEMORY_SEGMENT);
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
				AFileMapper mapper=EtchFileMapperFactory.createReadOnly(reader.getChannel(),mode,
						file.getName())) {
			byte[] actual=new byte[expected.length];
			mapper.read(0L,actual,0,actual.length,null);
			assertArrayEquals(expected,actual);
			assertThrows(java.io.IOException.class,
					()->mapper.write(0L,new byte[1],0,1,null));
			assertThrows(java.io.IOException.class,()->mapper.writeLongRelease(0L,42L,null));
			assertThrows(java.io.IOException.class,
					()->mapper.append(new byte[1],0,1,1,null));
			mapper.force();
			mapper.forceRange(0L,expected.length);
		}
		assertEquals(expected.length,file.length());
		if (!file.delete()) file.deleteOnExit();
	}

	private static void assertAppendOwnsGrowth(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-mapper-capacity", ".dat");
		byte[] value=new byte[] { 1,2,3,4,5,6,7,8 };
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),mode)) {
			mapper.appendZeros(Math.toIntExact(GROWN_POSITION),1,null);
			long position=mapper.append(value,0,value.length,1,null);
			assertEquals(GROWN_POSITION,position);
			assertEquals(position+value.length,mapper.length());
			assertTrue(data.length()>=mapper.length(),"Append did not provide physical capacity");
			byte[] actual=new byte[value.length];
			mapper.read(position,actual,0,actual.length,null);
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
			try (AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),mode)) {
				implementation=mapper.implementationName();
				mapper.read(existingLength-1L,new byte[1],0,1,null);
				assertEquals(existingLength,data.length(),"Reading an existing file changed its length");
				assertThrows(EtchCorruptionError.class,
						()->mapper.read(existingLength,new byte[1],0,1,null));
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

	private static void assertRoundTripAndGrowth(AFileMapper mapper) throws Exception {
		byte[] prefix=new byte[] { 0x12,0x34,0x56,0x78,0x09,0x0a,0x0b,0x0c };
		assertEquals(0L,mapper.append(prefix,0,prefix.length,1,null));
		assertEquals(16L,mapper.appendZeros(Long.BYTES,16,null));
		mapper.writeLongRelease(16L,0x0102030405060708L,null);
		byte[] source=new byte[] { 9,8,7,6,5 };
		assertEquals(24L,mapper.append(source,1,3,1,null));

		AES256CTREtchCipher cipher=AES256CTREtchCipher.fromKey(new byte[32]);
		byte[] transformed=new byte[] { 21,22,23,24,25 };
		assertEquals(32L,mapper.append(transformed,0,transformed.length,16,cipher));

		// Force a remap, then confirm old positions remain accessible.
		byte[] grown=new byte[] { 0x11,0x22,0x33,0x44,0x55,0x66,0x77,(byte)0x88 };
		mapper.appendZeros(Math.toIntExact(GROWN_POSITION-mapper.length()),1,null);
		assertEquals(GROWN_POSITION,mapper.append(grown,0,grown.length,1,null));

		byte[] actualPrefix=new byte[prefix.length];
		mapper.read(0L,actualPrefix,0,actualPrefix.length,null);
		assertArrayEquals(prefix,actualPrefix);
		assertTrue(mapper.matches(0L,prefix,0,prefix.length));
		byte[] different=prefix.clone();
		different[different.length-1]^=1;
		assertFalse(mapper.matches(0L,different,0,different.length));
		assertEquals(0x0102030405060708L,mapper.readLongAcquire(16L,null));
		byte[] destination=new byte[3];
		mapper.read(24L,destination,0,destination.length,null);
		assertArrayEquals(new byte[] { 8,7,6 },destination);
		byte[] transformedResult=new byte[transformed.length];
		mapper.read(32L,transformedResult,0,transformedResult.length,cipher);
		assertArrayEquals(transformed,transformedResult);
		byte[] actualGrown=new byte[grown.length];
		mapper.read(GROWN_POSITION,actualGrown,0,actualGrown.length,null);
		assertArrayEquals(grown,actualGrown);

		mapper.force();
	}

	private static boolean ffmBackendIsPackaged() {
		try {
			Class.forName("convex.etch.FFMFileMapper");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
