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
		MappedByteBuffer mapped=getBuffer(position,Byte.BYTES,false);
		return mapped.get(bufferIndex(position));
	}

	@Override
	public short getShort(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Short.BYTES,false);
		return mapped.getShort(bufferIndex(position));
	}

	@Override
	public long getLong(long position) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES,false);
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
			MappedByteBuffer mapped=getBuffer(current,chunk,false);
			int index=bufferIndex(current);
			mapped.get(index,destination,destinationOffset,chunk);
			current+=chunk;
			destinationOffset+=chunk;
			remaining-=chunk;
		}
	}

	@Override
	public void putByte(long position, byte value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Byte.BYTES,true);
		mapped.put(bufferIndex(position),value);
	}

	@Override
	public void putShort(long position, short value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Short.BYTES,true);
		mapped.putShort(bufferIndex(position),value);
	}

	@Override
	public void putLong(long position, long value) throws IOException {
		MappedByteBuffer mapped=getBuffer(position,Long.BYTES,true);
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
			MappedByteBuffer mapped=getBuffer(current,chunk,true);
			int index=bufferIndex(current);
			mapped.put(index,source,sourceOffset,chunk);
			current+=chunk;
			sourceOffset+=chunk;
			remaining-=chunk;
		}
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
