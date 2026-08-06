package convex.etch;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

/**
 * FFM Etch mapping backend, loaded on JDK 22+ from the multi-release JAR.
 *
 * <p>A single shared-arena segment covers the file. Growth publishes a larger
 * mapping and closes the superseded arena, so Windows releases the old mapping
 * deterministically.</p>
 */
final class FFMEtchFileMapper implements EtchFileMapper {
	private static final ValueLayout.OfShort SHORT=
			ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final ValueLayout.OfLong LONG=
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
	private static final VarHandle ALIGNED_LONG=
			ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).varHandle();
	private static final long INITIAL_MAPPING_SIZE=1L<<17;

	private final FileChannel channel;
	private volatile Mapping mapping;
	private volatile boolean closed;

	FFMEtchFileMapper(FileChannel channel) {
		this.channel=channel;
	}

	@Override
	public byte getByte(long position) throws IOException {
		ensureMapped(position,Byte.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				return current.segment.get(ValueLayout.JAVA_BYTE,position);
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public short getShort(long position) throws IOException {
		ensureMapped(position,Short.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				return current.segment.get(SHORT,position);
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public long getLong(long position) throws IOException {
		ensureMapped(position,Long.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				return current.segment.get(LONG,position);
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public long getLongAcquire(long position) throws IOException {
		ensureMapped(position,Long.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				return (long)ALIGNED_LONG.getAcquire(current.segment,position);
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void get(long position, byte[] destination, int offset, int length) throws IOException {
		if (length==0) return;
		ensureMapped(position,length);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				MemorySegment.copy(current.segment,ValueLayout.JAVA_BYTE,position,
						destination,offset,length);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void putByte(long position, byte value) throws IOException {
		ensureMapped(position,Byte.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				current.segment.set(ValueLayout.JAVA_BYTE,position,value);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void putShort(long position, short value) throws IOException {
		ensureMapped(position,Short.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				current.segment.set(SHORT,position,value);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void putLong(long position, long value) throws IOException {
		ensureMapped(position,Long.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				current.segment.set(LONG,position,value);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void putLongRelease(long position, long value) throws IOException {
		ensureMapped(position,Long.BYTES);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				ALIGNED_LONG.setRelease(current.segment,position,value);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public void put(long position, byte[] source, int offset, int length) throws IOException {
		if (length==0) return;
		ensureMapped(position,length);
		Mapping current=currentMapping(null);
		while (true) {
			try {
				MemorySegment.copy(source,offset,current.segment,ValueLayout.JAVA_BYTE,
						position,length);
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	private void ensureMapped(long position, long length) throws IOException {
		long requiredEnd=Math.addExact(position,length);
		long preferredEnd=Math.addExact(requiredEnd,EtchConstants.REGION_MARGIN);
		Mapping current=mapping;
		if ((current!=null)&&(current.segment.byteSize()>=preferredEnd)) return;

		synchronized (this) {
			if (closed) throw new IOException("Etch mapping is closed");
			current=mapping;
			if ((current!=null)&&(current.segment.byteSize()>=preferredEnd)) return;

			long target=INITIAL_MAPPING_SIZE;
			while (target<preferredEnd) target=Math.multiplyExact(target,2L);

			Arena arena=Arena.ofShared();
			MemorySegment segment;
			try {
				segment=channel.map(MapMode.READ_WRITE,0L,target,arena);
			} catch (IOException | RuntimeException e) {
				arena.close();
				throw e;
			}

			Mapping replacement=new Mapping(segment,arena);
			Mapping previous=mapping;
			mapping=replacement;
			if (previous!=null) previous.arena.close();
		}
	}

	private Mapping currentMapping(Mapping previous) {
		Mapping current=mapping;
		if (current==null) throw new IllegalStateException("Etch mapping is closed");
		if ((previous!=null)&&(previous==current)) throw new IllegalStateException("Etch mapping is not accessible");
		return current;
	}

	@Override
	public void force() {
		Mapping current=currentMapping(null);
		while (true) {
			try {
				current.segment.force();
				return;
			} catch (IllegalStateException e) {
				current=currentMapping(current);
			}
		}
	}

	@Override
	public String implementationName() {
		return "MemorySegment";
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed=true;
		Mapping previous=mapping;
		mapping=null;
		if (previous!=null) previous.arena.close();
	}

	private record Mapping(MemorySegment segment, Arena arena) {
	}
}
