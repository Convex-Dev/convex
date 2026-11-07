package convex.etch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;

/**
 * An interrupted caller must not close an Etch file's channel (#776). Java closes a
 * FileChannel when a thread in one of its blocking calls is interrupted, or enters
 * one already interrupted, so Etch runs those calls on threads nothing interrupts.
 */
public class EtchInterruptTest {
	private static final long GROWN_POSITION=2_000_000L;

	static Stream<EtchConfig.MappingMode> modes() {
		return Stream.of(EtchConfig.MappingMode.values()).filter(mode ->
			(mode!=EtchConfig.MappingMode.MEMORY_SEGMENT)||EtchFileMapperFactory.isFFMAvailable());
	}

	@AfterEach
	public void clearInterrupt() {
		Thread.interrupted();
	}

	/** Growth and force by an interrupted caller leave the mapper's channel open. */
	@ParameterizedTest
	@MethodSource("modes")
	public void testInterruptedGrowthAndForce(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-interrupt", ".dat");
		byte[] value=new byte[] { 1,2,3,4,5,6,7,8 };
		try (RandomAccessFile data=new RandomAccessFile(file,"rw");
				AFileMapper mapper=EtchFileMapperFactory.create(data.getChannel(),mode)) {
			Thread.currentThread().interrupt();
			mapper.appendZeros(Math.toIntExact(GROWN_POSITION),1,null);
			long position=mapper.append(value,0,value.length,1,null);
			mapper.force();
			assertTrue(Thread.interrupted(),"Caller interrupt was not restored");
			assertTrue(data.getChannel().isOpen(),"Interrupted caller closed the channel");

			mapper.write(position,value,0,value.length,null);
			mapper.force();
			byte[] actual=new byte[value.length];
			mapper.read(position,actual,0,actual.length,null);
			assertArrayEquals(value,actual);
		}
		if (!file.delete()) file.deleteOnExit();
	}

	/** A read that maps an existing file for an interrupted caller leaves the channel open. */
	@ParameterizedTest
	@MethodSource("modes")
	public void testInterruptedReadMapping(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-interrupt-read", ".dat");
		byte[] expected=new byte[] { 8,7,6,5,4,3,2,1 };
		try (RandomAccessFile writer=new RandomAccessFile(file,"rw")) {
			writer.write(expected);
		}
		try (RandomAccessFile reader=new RandomAccessFile(file,"r");
				AFileMapper mapper=EtchFileMapperFactory.createReadOnly(reader.getChannel(),mode,file.getName())) {
			Thread.currentThread().interrupt();
			byte[] actual=new byte[expected.length];
			mapper.read(0L,actual,0,actual.length,null);
			assertTrue(Thread.interrupted(),"Caller interrupt was not restored");
			assertTrue(reader.getChannel().isOpen(),"Interrupted caller closed the channel");
			assertArrayEquals(expected,actual);
		}
		if (!file.delete()) file.deleteOnExit();
	}

	/** An interrupted checkpoint completes, and the store stays usable for every thread. */
	@ParameterizedTest
	@MethodSource("modes")
	public void testInterruptedCheckpoint(EtchConfig.MappingMode mode) throws Exception {
		File file=File.createTempFile("etch-interrupt-store", ".etch");
		if (!file.delete()) throw new IOException("Unable to prepare "+file);
		EtchConfig config=EtchConfig.create(EtchConstants.VERSION_3,mode,true);
		ACell first=EtchGCLifecycleTest.tree(35001);
		ACell second=EtchGCLifecycleTest.tree(35002);
		try (EtchStore store=EtchStore.create(file,config)) {
			Thread.currentThread().interrupt();
			store.setRootData(first);
			store.flush();
			assertTrue(Thread.interrupted(),"Caller interrupt was not restored");

			store.setRootData(second);
			store.flush();
		}
		try (EtchStore reopened=EtchStore.create(file,config)) {
			assertEquals(second,reopened.getRootData());
		}
		if (!file.delete()) file.deleteOnExit();
	}
}
