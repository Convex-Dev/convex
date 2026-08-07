package convex.etch;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Arrays;

import convex.core.util.Utils;

/**
 * FFM Etch mapping backend, loaded on JDK 22+ from the multi-release JAR.
 *
 * <p>The file is divided into fixed-size address regions. Completed regions
 * remain mapped for the life of the mapper; growth replaces only the final
 * partial region. This bounds both remapping work and unused file allocation,
 * while direct region indexing keeps the normal access path small.</p>
 *
 * <p>Growth tuning is deliberately local to this implementation. These values
 * affect only allocation and mapping frequency, not the Etch file format.</p>
 */
final class FFMEtchFileMapper implements EtchFileMapper {
	/** Shift used for direct address-to-region conversion. */
	private static final int REGION_SHIFT=30;

	/**
	 * Maximum size of one mapping. A 1 GiB region bounds the cost of replacing
	 * the active mapping while requiring only 1,024 entries for a 1 TiB file.
	 */
	private static final long REGION_SIZE=1L<<REGION_SHIFT;

	/** Mask used to obtain an address within a region. */
	private static final long REGION_MASK=REGION_SIZE-1L;

	/**
	 * Capacity rounding for new mappings. 64 KiB matches the conservative
	 * Windows allocation granularity and is also a multiple of common page sizes.
	 */
	private static final long MAPPING_ALIGNMENT=1L<<16;

	/** Initial allocation for a new, small Etch file. */
	private static final long INITIAL_MAPPING_SIZE=1L<<20;

	/**
	 * Minimum extension once the initial mapping is exhausted. Remapping is
	 * substantially more expensive than ordinary mapped access, so small files
	 * grow by at least 64 MiB rather than repeatedly remapping in tiny steps.
	 */
	private static final long MIN_MAPPING_GROWTH=1L<<26;

	/** Maximum speculative extension; never larger than one address region. */
	private static final long MAX_MAPPING_GROWTH=REGION_SIZE;

	/** Use 1/32 (3.125%) of current file extent as proportional headroom. */
	private static final int PROPORTIONAL_GROWTH_SHIFT=5;

	private static final Mapping[] EMPTY_MAPPINGS=new Mapping[0];

	private static final VarHandle ALIGNED_LONG=
			ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).varHandle();

	private final FileChannel channel;
	private final boolean readOnly;
	private volatile Mapping[] mappings=EMPTY_MAPPINGS;
	private volatile boolean closed;

	FFMEtchFileMapper(FileChannel channel) {
		this(channel,false);
	}

	FFMEtchFileMapper(FileChannel channel, boolean readOnly) {
		this.channel=channel;
		this.readOnly=readOnly;
	}

	@Override
	public long readIndexSlotAcquire(long position) throws IOException {
		checkAtomicLong(position);
		ensureMapped(position,Long.BYTES,false);
		Mapping current=mappingFor(position,null);
		while (true) {
			try {
				return (long)ALIGNED_LONG.getAcquire(current.segment,current.offset(position));
			} catch (IllegalStateException e) {
				current=mappingFor(position,current);
			}
		}
	}

	@Override
	public void get(long position, byte[] destination, int offset, int length) throws IOException {
		checkRange(position,length);
		if (length==0) return;
		ensureMapped(position,length,false);

		long currentPosition=position;
		int currentOffset=offset;
		int remaining=length;
		Mapping current=mappingFor(currentPosition,null);
		while (remaining>0) {
			int count=(int)Math.min(remaining,current.end()-currentPosition);
			try {
				MemorySegment.copy(current.segment,ValueLayout.JAVA_BYTE,current.offset(currentPosition),
						destination,currentOffset,count);
			} catch (IllegalStateException e) {
				current=mappingFor(currentPosition,current);
				continue;
			}
			currentPosition+=count;
			currentOffset+=count;
			remaining-=count;
			if (remaining>0) current=mappingFor(currentPosition,null);
		}
	}

	@Override
	public void getTransformed(long position, byte[] destination, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		checkRange(position,length);
		if (length==0) return;
		ensureMapped(position,length,false);

		long currentPosition=position;
		int currentOffset=offset;
		int remaining=length;
		Mapping current=mappingFor(currentPosition,null);
		while (remaining>0) {
			int count=(int)Math.min(remaining,current.end()-currentPosition);
			ByteBuffer input;
			try {
				input=current.segment.asSlice(current.offset(currentPosition),count).asByteBuffer();
			} catch (IllegalStateException e) {
				current=mappingFor(currentPosition,current);
				continue;
			}
			try {
				cursor.transform(input,ByteBuffer.wrap(destination,currentOffset,count));
			} catch (IllegalStateException e) {
				// Retrying could reuse the wrong part of the cipher stream.
				throw new IOException("Etch mapping changed during encrypted read",e);
			}
			currentPosition+=count;
			currentOffset+=count;
			remaining-=count;
			if (remaining>0) current=mappingFor(currentPosition,null);
		}
	}

	@Override
	public void writeIndexSlotRelease(long position, long value) throws IOException {
		checkAtomicLong(position);
		ensureMapped(position,Long.BYTES,true);
		Mapping current=mappingFor(position,null);
		while (true) {
			try {
				ALIGNED_LONG.setRelease(current.segment,current.offset(position),value);
				return;
			} catch (IllegalStateException e) {
				current=mappingFor(position,current);
			}
		}
	}

	@Override
	public void ensureWriteCapacity(long position, long length) throws IOException {
		ensureMapped(position,length,true);
	}

	@Override
	public void put(long position, byte[] source, int offset, int length) {
		if (length==0) return;

		long currentPosition=position;
		int currentOffset=offset;
		int remaining=length;
		Mapping current=mappingFor(currentPosition,null);
		while (remaining>0) {
			int count=(int)Math.min(remaining,current.end()-currentPosition);
			try {
				MemorySegment.copy(source,currentOffset,current.segment,ValueLayout.JAVA_BYTE,
						current.offset(currentPosition),count);
			} catch (IllegalStateException e) {
				current=mappingFor(currentPosition,current);
				continue;
			}
			currentPosition+=count;
			currentOffset+=count;
			remaining-=count;
			if (remaining>0) current=mappingFor(currentPosition,null);
		}
	}

	@Override
	public void putTransformed(long position, byte[] source, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		if (length==0) return;

		long currentPosition=position;
		int currentOffset=offset;
		int remaining=length;
		Mapping current=mappingFor(currentPosition,null);
		while (remaining>0) {
			int count=(int)Math.min(remaining,current.end()-currentPosition);
			ByteBuffer output;
			try {
				output=current.segment.asSlice(current.offset(currentPosition),count).asByteBuffer();
			} catch (IllegalStateException e) {
				current=mappingFor(currentPosition,current);
				continue;
			}
			try {
				cursor.transform(ByteBuffer.wrap(source,currentOffset,count),output);
			} catch (IllegalStateException e) {
				// Retrying could reuse the wrong part of the cipher stream.
				throw new IOException("Etch mapping changed during encrypted write",e);
			}
			currentPosition+=count;
			currentOffset+=count;
			remaining-=count;
			if (remaining>0) current=mappingFor(currentPosition,null);
		}
	}

	private void ensureMapped(long position, long length, boolean writable) throws IOException {
		checkRange(position,length);
		if (length==0) return;
		long requiredEnd=position+length;
		Mapping[] current=mappings;
		if (covers(current,position,requiredEnd)) return;

		synchronized (this) {
			if (closed) throw new IOException("Etch mapping is closed");
			current=mappings;
			if (covers(current,position,requiredEnd)) return;

			long fileSize=channel.size();
			if (!writable&&(requiredEnd>fileSize)) {
				throw new IOException("Read beyond physical Etch file: end="+requiredEnd+" size="+fileSize);
			}
			int firstRegion=regionIndex(position);
			int lastRegion=regionIndex(requiredEnd-1L);
			for (int i=firstRegion;i<=lastRegion;i++) {
				long regionStart=regionStart(i);
				long requiredLength=Math.min(REGION_SIZE,requiredEnd-regionStart);
				if (requiredLength<=0L) continue;
				fileSize=ensureRegionMapped(i,requiredLength,fileSize,writable);
			}
		}
	}

	private long ensureRegionMapped(int regionIndex, long requiredLength, long fileSize,
			boolean writable) throws IOException {
		Mapping[] current=mappings;
		Mapping previous=(regionIndex<current.length)?current[regionIndex]:null;
		if ((previous!=null)&&(previous.segment.byteSize()>=requiredLength)) return fileSize;

		long start=regionStart(regionIndex);
		long physicalLength=Math.min(REGION_SIZE,Math.max(0L,fileSize-start));
		long existingLength=(previous==null)?0L:previous.segment.byteSize();
		long target=Math.max(requiredLength,Math.max(physicalLength,existingLength));

		boolean initialMapping=writable&&(regionIndex==0)&&(previous==null)
				&&(fileSize==0L)&&(target<=INITIAL_MAPPING_SIZE);
		if (initialMapping) {
			target=Utils.roundUpToAlignment(INITIAL_MAPPING_SIZE,MAPPING_ALIGNMENT);
		} else if (writable&&(requiredLength>physicalLength)) {
			long extent=Math.max(fileSize,Math.addExact(start,target));
			target=Math.min(REGION_SIZE,Math.addExact(target,mappingGrowth(extent)));
			target=Math.min(REGION_SIZE,Utils.roundUpToAlignment(target,MAPPING_ALIGNMENT));
		}

		long targetEnd=Math.addExact(start,target);
		if (targetEnd>fileSize) {
			extendFile(targetEnd);
			fileSize=targetEnd;
		}

		Arena arena=Arena.ofShared();
		MemorySegment segment;
		try {
			segment=channel.map(readOnly?MapMode.READ_ONLY:MapMode.READ_WRITE,start,target,arena);
		} catch (IOException | RuntimeException e) {
			arena.close();
			throw e;
		}

		Mapping replacement=new Mapping(start,segment,arena);
		try {
			current=mappings;
			Mapping[] updated=(regionIndex<current.length)
					?current.clone():Arrays.copyOf(current,regionIndex+1);
			previous=(regionIndex<current.length)?current[regionIndex]:null;
			updated[regionIndex]=replacement;
			mappings=updated;
		} catch (RuntimeException | Error e) {
			arena.close();
			throw e;
		}
		if (previous!=null) previous.arena.close();
		return fileSize;
	}

	private void extendFile(long targetEnd) throws IOException {
		if (channel.size()>=targetEnd) return;
		ByteBuffer marker=ByteBuffer.allocate(1);
		while (marker.hasRemaining()) {
			channel.write(marker,targetEnd-1L);
		}
	}

	private static long mappingGrowth(long extent) {
		long proportional=extent>>PROPORTIONAL_GROWTH_SHIFT;
		return Math.max(MIN_MAPPING_GROWTH,Math.min(MAX_MAPPING_GROWTH,proportional));
	}

	private static boolean covers(Mapping[] current, long position, long end) {
		int first=regionIndex(position);
		int last=regionIndex(end-1L);
		for (int i=first;i<=last;i++) {
			if (i>=current.length) return false;
			Mapping mapping=current[i];
			if (mapping==null) return false;
			long requiredEnd=Math.min(end,regionStart(i)+REGION_SIZE);
			if (mapping.end()<requiredEnd) return false;
		}
		return true;
	}

	private Mapping mappingFor(long position, Mapping previous) {
		Mapping[] current=mappings;
		int index=regionIndex(position);
		Mapping mapping=(index<current.length)?current[index]:null;
		if (mapping==null) throw new IllegalStateException("Etch mapping is closed");
		if (mapping==previous) throw new IllegalStateException("Etch mapping is not accessible");
		return mapping;
	}

	private static int regionIndex(long position) {
		return Math.toIntExact(position>>REGION_SHIFT);
	}

	private static long regionStart(int regionIndex) {
		return ((long)regionIndex)<<REGION_SHIFT;
	}

	private static boolean crossesRegion(long position, long length) {
		checkRange(position,length);
		return (position&REGION_MASK)>REGION_SIZE-length;
	}

	private static void checkRange(long position, long length) {
		if ((position<0L)||(length<0L)) throw new IllegalArgumentException("Negative Etch file range");
		Math.addExact(position,length);
	}

	private static void checkAtomicLong(long position) {
		checkRange(position,Long.BYTES);
		if ((position&(Long.BYTES-1L))!=0L) {
			throw new IllegalArgumentException("Atomic Etch index position is not 8-byte aligned: "+position);
		}
		if (crossesRegion(position,Long.BYTES)) {
			throw new IllegalArgumentException("Atomic Etch index position crosses an FFM region: "+position);
		}
	}

	@Override
	public synchronized void force() throws IOException {
		if (closed) throw new IllegalStateException("Etch mapping is closed");
		if (readOnly) return;
		for (Mapping mapping:mappings) {
			if (mapping!=null) mapping.segment.force();
		}
		channel.force(false);
	}

	@Override
	public synchronized void forceRange(long position, long length) throws IOException {
		checkRange(position,length);
		if (closed) throw new IllegalStateException("Etch mapping is closed");
		if (readOnly) return;
		long end=position+length;
		long current=position;
		while (current<end) {
			int index=regionIndex(current);
			Mapping mapping=(index<mappings.length)?mappings[index]:null;
			if (mapping==null) throw new IOException("Etch force range is not mapped: "+current);
			long count=Math.min(end-current,mapping.end()-current);
			if (count<=0L) throw new IOException("Etch force range exceeds mapping: "+current);
			mapping.segment.asSlice(mapping.offset(current),count).force();
			current+=count;
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
		Mapping[] previous=mappings;
		mappings=EMPTY_MAPPINGS;
		for (Mapping mapping:previous) {
			if (mapping!=null) mapping.arena.close();
		}
	}

	private record Mapping(long start, MemorySegment segment, Arena arena) {
		long end() {
			return start+segment.byteSize();
		}

		long offset(long position) {
			return position-start;
		}
	}
}
