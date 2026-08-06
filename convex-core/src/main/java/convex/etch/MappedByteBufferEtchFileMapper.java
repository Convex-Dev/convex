package convex.etch;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;

/**
 * Java 21-compatible Etch mapping backend.
 */
final class MappedByteBufferEtchFileMapper implements EtchFileMapper {
	private final FileChannel channel;
	private final ArrayList<MappedByteBuffer> regionMap=new ArrayList<>();

	MappedByteBufferEtchFileMapper(FileChannel channel) {
		this.channel=channel;
	}

	@Override
	public byte getByte(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Byte.BYTES);
		return mapped.get(bufferIndex(position));
	}

	@Override
	public short getShort(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Short.BYTES);
		return mapped.getShort(bufferIndex(position));
	}

	@Override
	public long getLong(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES);
		return mapped.getLong(bufferIndex(position));
	}

	@Override
	public void get(long position, byte[] destination, int offset, int length) throws IOException {
		int remaining=length;
		long current=position;
		int destinationOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=getBuffer(current,chunk);
			int index=bufferIndex(current);
			mapped.get(index,destination,destinationOffset,chunk);
			current+=chunk;
			destinationOffset+=chunk;
			remaining-=chunk;
		}
	}

	@Override
	public void putByte(long position, byte value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Byte.BYTES);
		mapped.put(bufferIndex(position),value);
	}

	@Override
	public void putShort(long position, short value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Short.BYTES);
		mapped.putShort(bufferIndex(position),value);
	}

	@Override
	public void putLong(long position, long value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES);
		mapped.putLong(bufferIndex(position),value);
	}

	@Override
	public void put(long position, byte[] source, int offset, int length) throws IOException {
		int remaining=length;
		long current=position;
		int sourceOffset=offset;
		while (remaining>0) {
			int chunk=Math.min(remaining,
					Math.toIntExact(EtchConstants.MAX_REGION_SIZE-(current%EtchConstants.MAX_REGION_SIZE)));
			MappedByteBuffer mapped=getBuffer(current,chunk);
			int index=bufferIndex(current);
			mapped.put(index,source,sourceOffset,chunk);
			current+=chunk;
			sourceOffset+=chunk;
			remaining-=chunk;
		}
	}

	private MappedByteBuffer getBuffer(long position, int length) throws IOException {
		int regionIndex=Math.toIntExact(position/EtchConstants.MAX_REGION_SIZE);
		return getInternalBuffer(regionIndex,Math.addExact(position,length));
	}

	private int bufferIndex(long position) {
		return Math.toIntExact(position%EtchConstants.MAX_REGION_SIZE);
	}

	private MappedByteBuffer getInternalBuffer(int regionIndex, long requiredEnd) throws IOException {
		int mapSize=regionMap.size();
		MappedByteBuffer mapped=(regionIndex<mapSize)?regionMap.get(regionIndex):null;
		long preferredEnd=Math.addExact(requiredEnd,EtchConstants.REGION_MARGIN);
		if ((mapped==null)||((mapped.capacity()+regionIndex*EtchConstants.MAX_REGION_SIZE)<preferredEnd)) {
			mapped=createBuffer(regionIndex,requiredEnd);
		}
		return mapped;
	}

	private synchronized MappedByteBuffer createBuffer(int regionIndex, long requiredEnd) throws IOException {
		while (regionMap.size()<=regionIndex) regionMap.add(null);

		long position=((long)regionIndex)*EtchConstants.MAX_REGION_SIZE;
		int length;
		if (regionIndex==0) {
			length=1<<16;
			while ((length<EtchConstants.MAX_REGION_SIZE)&&((position+length)<requiredEnd)) {
				length*=2;
			}
		} else {
			length=(int)EtchConstants.MAX_REGION_SIZE;
		}

		length+=EtchConstants.REGION_MARGIN;
		MappedByteBuffer mapped=channel.map(MapMode.READ_WRITE,position,length);
		regionMap.set(regionIndex,mapped);
		return mapped;
	}

	@Override
	public synchronized void force() {
		for (MappedByteBuffer mapped: regionMap) {
			if (mapped!=null) mapped.force();
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
