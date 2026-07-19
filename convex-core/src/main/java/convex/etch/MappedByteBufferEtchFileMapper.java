package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
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
	public EtchCursor cursor(long position, long dataLength) throws IOException {
		int regionIndex=Math.toIntExact(position/Etch.MAX_REGION_SIZE);
		MappedByteBuffer mapped=getInternalBuffer(regionIndex,position,dataLength);
		MappedByteBuffer duplicate=(MappedByteBuffer)((ByteBuffer)mapped).duplicate();
		duplicate.position(Math.toIntExact(position%Etch.MAX_REGION_SIZE));
		return new ByteBufferCursor(duplicate);
	}

	private MappedByteBuffer getInternalBuffer(int regionIndex, long position, long dataLength) throws IOException {
		int mapSize=regionMap.size();
		MappedByteBuffer mapped=(regionIndex<mapSize)?regionMap.get(regionIndex):null;
		if ((mapped==null)||((mapped.capacity()+regionIndex*Etch.MAX_REGION_SIZE)<position+Etch.REGION_MARGIN)) {
			mapped=createBuffer(regionIndex,dataLength);
		}
		return mapped;
	}

	private synchronized MappedByteBuffer createBuffer(int regionIndex, long dataLength) throws IOException {
		while (regionMap.size()<=regionIndex) regionMap.add(null);

		long position=((long)regionIndex)*Etch.MAX_REGION_SIZE;
		int length;
		if (regionIndex==0) {
			length=1<<16;
			while ((length<Etch.MAX_REGION_SIZE)&&((position+length)<(dataLength+Etch.REGION_MARGIN))) {
				length*=2;
			}
		} else {
			length=(int)Etch.MAX_REGION_SIZE;
		}

		length+=Etch.REGION_MARGIN;
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

	private static final class ByteBufferCursor implements EtchCursor {
		private final ByteBuffer buffer;

		private ByteBufferCursor(ByteBuffer buffer) {
			this.buffer=buffer;
		}

		@Override
		public byte get() {
			return buffer.get();
		}

		@Override
		public short getShort() {
			return buffer.getShort();
		}

		@Override
		public long getLong() {
			return buffer.getLong();
		}

		@Override
		public void get(byte[] destination) {
			buffer.get(destination);
		}

		@Override
		public void get(byte[] destination, int offset, int length) {
			buffer.get(destination,offset,length);
		}

		@Override
		public void put(byte value) {
			buffer.put(value);
		}

		@Override
		public void putShort(short value) {
			buffer.putShort(value);
		}

		@Override
		public void putLong(long value) {
			buffer.putLong(value);
		}

		@Override
		public void put(byte[] source) {
			buffer.put(source);
		}

		@Override
		public void put(byte[] source, int offset, int length) {
			buffer.put(source,offset,length);
		}
	}
}
