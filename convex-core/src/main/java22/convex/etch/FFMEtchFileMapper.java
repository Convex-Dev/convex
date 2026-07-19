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
	public EtchCursor cursor(long position, long dataLength) throws IOException {
		ensureMapped(position,dataLength);
		return new SegmentCursor(this,position);
	}

	private void ensureMapped(long position, long dataLength) throws IOException {
		long required=Math.addExact(position,Etch.REGION_MARGIN);
		Mapping current=mapping;
		if ((current!=null)&&(current.segment.byteSize()>=required)) return;

		synchronized (this) {
			if (closed) throw new IOException("Etch mapping is closed");
			current=mapping;
			if ((current!=null)&&(current.segment.byteSize()>=required)) return;

			long target=INITIAL_MAPPING_SIZE;
			long logicalRequired=Math.max(required,Math.addExact(dataLength,Etch.REGION_MARGIN));
			while (target<logicalRequired) target=Math.multiplyExact(target,2L);

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

	private static final class SegmentCursor implements EtchCursor {
		private final FFMEtchFileMapper owner;
		private long position;

		private SegmentCursor(FFMEtchFileMapper owner, long position) {
			this.owner=owner;
			this.position=position;
		}

		@Override
		public byte get() {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					byte value=current.segment.get(ValueLayout.JAVA_BYTE,position);
					position++;
					return value;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public short getShort() {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					short value=current.segment.get(SHORT,position);
					position+=Short.BYTES;
					return value;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public long getLong() {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					long value=current.segment.get(LONG,position);
					position+=Long.BYTES;
					return value;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public long getLongAcquire() {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					long value=(long)ALIGNED_LONG.getAcquire(current.segment,position);
					position+=Long.BYTES;
					return value;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void get(byte[] destination) {
			get(destination,0,destination.length);
		}

		@Override
		public void get(byte[] destination, int offset, int length) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					current.segment.asSlice(position,length).asByteBuffer().get(destination,offset,length);
					position+=length;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void put(byte value) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					current.segment.set(ValueLayout.JAVA_BYTE,position,value);
					position++;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void putShort(short value) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					current.segment.set(SHORT,position,value);
					position+=Short.BYTES;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void putLong(long value) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					current.segment.set(LONG,position,value);
					position+=Long.BYTES;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void putLongRelease(long value) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					ALIGNED_LONG.setRelease(current.segment,position,value);
					position+=Long.BYTES;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}

		@Override
		public void put(byte[] source) {
			put(source,0,source.length);
		}

		@Override
		public void put(byte[] source, int offset, int length) {
			Mapping current=owner.currentMapping(null);
			while (true) {
				try {
					current.segment.asSlice(position,length).asByteBuffer().put(source,offset,length);
					position+=length;
					return;
				} catch (IllegalStateException e) {
					current=owner.currentMapping(current);
				}
			}
		}
	}
}
