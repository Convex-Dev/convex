package convex.etch;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;

import convex.core.util.Utils;

/**
 * Java 21-compatible Etch mapping backend.
 */
final class MBBFileMapper extends AFileMapper {
	private final FileChannel channel;
	private final boolean readOnly;
	private final ArrayList<MappedByteBuffer> regionMap=new ArrayList<>();

	MBBFileMapper(FileChannel channel) {
		this(channel,false);
	}

	MBBFileMapper(FileChannel channel, boolean readOnly) {
		this.channel=channel;
		this.readOnly=readOnly;
	}

	@Override
	public void get(long position, byte[] destination, int offset, int length) throws IOException {
		int remaining=length;
		long current=position;
		int destinationOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=getBuffer(current,chunk,false);
			int index=bufferIndex(current);
			mapped.get(index,destination,destinationOffset,chunk);
			current+=chunk;
			destinationOffset+=chunk;
			remaining-=chunk;
		}
	}

	@Override
	public boolean matches(long position, byte[] expected, int offset, int length) throws IOException {
		int remaining=length;
		long current=position;
		int expectedOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=getBuffer(current,chunk,false);
			int index=bufferIndex(current);
			int end=index+chunk;
			while (index+Long.BYTES<=end) {
				if (mapped.getLong(index)!=Utils.readLong(expected,expectedOffset,Long.BYTES)) return false;
				index+=Long.BYTES;
				expectedOffset+=Long.BYTES;
			}
			while (index<end) {
				if (mapped.get(index++)!=expected[expectedOffset++]) return false;
			}
			current+=chunk;
			remaining-=chunk;
		}
		return true;
	}

	@Override
	public void getTransformed(long position, byte[] destination, int offset, int length,
			EtchFileCipher cipher) throws IOException {
		int remaining=length;
		long current=position;
		int destinationOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=getBuffer(current,chunk,false);
			ByteBuffer input=mappedSlice(mapped,bufferIndex(current),chunk);
			cipher.decrypt(input,destination,destinationOffset);
			current+=chunk;
			destinationOffset+=chunk;
			remaining-=chunk;
		}
	}

	@Override
	public void ensureWriteCapacity(long position, long length) throws IOException {
		if ((position<0L)||(length<0L)) throw new IllegalArgumentException("Negative Etch file range");
		long end=Math.addExact(position,length);
		long current=position;
		while (current<end) {
			int chunk=Math.toIntExact(Math.min(end-current,
					EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			getBuffer(current,chunk,true);
			current+=chunk;
		}
	}

	@Override
	public void put(long position, byte[] source, int offset, int length) {
		int remaining=length;
		long current=position;
		int sourceOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=regionMap.get(Math.toIntExact(current/EtchConstants.MAX_REGION_SIZE));
			int index=bufferIndex(current);
			mapped.put(index,source,sourceOffset,chunk);
			current+=chunk;
			sourceOffset+=chunk;
			remaining-=chunk;
		}
	}

	@Override
	public void putTransformed(long position, byte[] source, int offset, int length,
			EtchFileCipher cipher) throws IOException {
		int remaining=length;
		long current=position;
		int sourceOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=regionMap.get(Math.toIntExact(current/EtchConstants.MAX_REGION_SIZE));
			ByteBuffer output=mappedSlice(mapped,bufferIndex(current),chunk);
			cipher.encrypt(source,sourceOffset,output);
			current+=chunk;
			sourceOffset+=chunk;
			remaining-=chunk;
		}
	}

	private static ByteBuffer mappedSlice(MappedByteBuffer mapped, int offset, int length) {
		return mapped.duplicate().position(offset).limit(offset+length);
	}

	@Override
	public long readIndexSlotAcquire(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES,false);
		long value=mapped.getLong(bufferIndex(position));
		VarHandle.acquireFence();
		return value;
	}

	@Override
	public void writeIndexSlotRelease(long position, long value) throws IOException {
		VarHandle.releaseFence();
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES,true);
		mapped.putLong(bufferIndex(position),value);
	}

	private MappedByteBuffer getBuffer(long position, int length, boolean writable) throws IOException {
		int regionIndex=Math.toIntExact(position/EtchConstants.MAX_REGION_SIZE);
		return getInternalBuffer(regionIndex,Math.addExact(position,length),writable);
	}

	private int bufferIndex(long position) {
		return Math.toIntExact(position%EtchConstants.MAX_REGION_SIZE);
	}

	private MappedByteBuffer getInternalBuffer(int regionIndex, long requiredEnd, boolean writable) throws IOException {
		int mapSize=regionMap.size();
		MappedByteBuffer mapped=(regionIndex<mapSize)?regionMap.get(regionIndex):null;
		long regionEnd=Math.multiplyExact((long)regionIndex+1L,EtchConstants.MAX_REGION_SIZE);
		long preferredEnd=writable
				?Math.min(regionEnd,Math.addExact(requiredEnd,EtchConstants.REGION_MARGIN))
				:requiredEnd;
		if ((mapped==null)||((mapped.capacity()+regionIndex*EtchConstants.MAX_REGION_SIZE)<preferredEnd)) {
			mapped=createBuffer(regionIndex,requiredEnd,writable);
		}
		return mapped;
	}

	private synchronized MappedByteBuffer createBuffer(int regionIndex, long requiredEnd, boolean writable) throws IOException {
		while (regionMap.size()<=regionIndex) regionMap.add(null);

		long position=((long)regionIndex)*EtchConstants.MAX_REGION_SIZE;
		int length;
		if (!writable) {
			long fileSize=channel.size();
			if (requiredEnd>fileSize) {
				throw new IOException("Read beyond physical Etch file: end="+requiredEnd+" size="+fileSize);
			}
			length=Math.toIntExact(Math.min(EtchConstants.MAX_REGION_SIZE,fileSize-position));
		} else if (regionIndex==0) {
			length=1<<16;
			while ((length<EtchConstants.MAX_REGION_SIZE)&&(length<requiredEnd)) {
				length*=2;
			}
			length=Math.toIntExact(Math.min(EtchConstants.MAX_REGION_SIZE,
					(long)length+EtchConstants.REGION_MARGIN));
		} else {
			length=(int)EtchConstants.MAX_REGION_SIZE;
		}

		MappedByteBuffer mapped=channel.map(readOnly?MapMode.READ_ONLY:MapMode.READ_WRITE,position,length);
		regionMap.set(regionIndex,mapped);
		return mapped;
	}

	@Override
	public synchronized void force() throws IOException {
		if (readOnly) return;
		for (MappedByteBuffer mapped: regionMap) {
			if (mapped!=null) mapped.force();
		}
		channel.force(false);
	}

	@Override
	public synchronized void forceRange(long position, long length) throws IOException {
		if (readOnly) return;
		if ((position<0L)||(length<0L)) throw new IllegalArgumentException("Negative Etch file range");
		long end=Math.addExact(position,length);
		long current=position;
		while (current<end) {
			int regionIndex=Math.toIntExact(current/EtchConstants.MAX_REGION_SIZE);
			MappedByteBuffer mapped=(regionIndex<regionMap.size())?regionMap.get(regionIndex):null;
			if (mapped==null) throw new IOException("Etch force range is not mapped: "+current);
			int index=bufferIndex(current);
			int count=Math.toIntExact(Math.min(end-current,(long)mapped.capacity()-index));
			if (count<=0) throw new IOException("Etch force range exceeds mapping: "+current);
			mapped.force(index,count);
			current+=count;
		}
	}

	@Override
	public String implementationName() {
		return "MappedByteBuffer";
	}

	@Override
	public synchronized void close() {
		// Java 21 has no supported deterministic unmap operation.
		regionMap.clear();
	}
}
