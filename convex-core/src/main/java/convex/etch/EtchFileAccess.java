package convex.etch;

import java.io.IOException;

import convex.core.util.Utils;

/**
 * Logical file access layered over an {@link EtchFileMapper}.
 *
 * <p>The mapper owns physical addressability. This class owns the logical end
 * of valid Etch data, checks complete access ranges, and publishes append
 * progress only after every byte of an append has been written.</p>
 */
final class EtchFileAccess implements AutoCloseable {
	private static final int ZERO_BUFFER_SIZE=16_384;
	private static final byte[] ZERO_BUFFER=new byte[ZERO_BUFFER_SIZE];
	private static final long NO_APPEND=-1L;

	private final EtchFileMapper mapper;
	private final String fileName;
	private volatile long dataLength;

	// Append state is accessed only by Etch's single writer.
	private long appendBase=NO_APPEND;
	private long appendEnd=NO_APPEND;

	EtchFileAccess(EtchFileMapper mapper, String fileName, long dataLength, long physicalLength) {
		this.mapper=mapper;
		this.fileName=fileName;
		this.dataLength=dataLength;
		if ((dataLength<0L)||(dataLength>physicalLength)) {
			throw corruption("Invalid stored data length",dataLength,physicalLength);
		}
	}

	byte getByte(long position) throws IOException {
		return mapper.getByte(checkedRange(position,Byte.BYTES));
	}

	short getShort(long position) throws IOException {
		return mapper.getShort(checkedRange(position,Short.BYTES));
	}

	long getLongAcquire(long position) throws IOException {
		return mapper.getLongAcquire(checkedRange(position,Long.BYTES));
	}

	void get(long position, byte[] destination, int offset, int length) throws IOException {
		mapper.get(checkedRange(position,length),destination,offset,length);
	}

	void putByte(long position, byte value) throws IOException {
		mapper.putByte(checkedRange(position,Byte.BYTES),value);
	}

	void putLong(long position, long value) throws IOException {
		mapper.putLong(checkedRange(position,Long.BYTES),value);
	}

	void putLongRelease(long position, long value) throws IOException {
		mapper.putLongRelease(checkedRange(position,Long.BYTES),value);
	}

	void put(long position, byte[] source, int offset, int length) throws IOException {
		mapper.put(checkedRange(position,length),source,offset,length);
	}

	long append(byte[] source, int offset, int length, int alignment) throws IOException {
		long position=beginAppend(length,alignment);
		try {
			putAppend(position,source,offset,length);
			commitAppend();
			return position;
		} catch (IOException | RuntimeException | Error e) {
			abortAppend();
			throw e;
		}
	}

	long appendZeroes(int length, int alignment) throws IOException {
		long position=beginAppend(length,alignment);
		try {
			for (int offset=0;offset<length;offset+=ZERO_BUFFER_SIZE) {
				putAppend(position+offset,ZERO_BUFFER,0,Math.min(length-offset,ZERO_BUFFER_SIZE));
			}
			commitAppend();
			return position;
		} catch (IOException | RuntimeException | Error e) {
			abortAppend();
			throw e;
		}
	}

	long beginAppend(long length, int alignment) {
		if (appendEnd!=NO_APPEND) throw new IllegalStateException("Etch append already in progress");
		if ((length<0L)||(alignment<=0)||((alignment&(alignment-1))!=0)) {
			throw new IllegalArgumentException("Invalid Etch append length or alignment");
		}
		long base=dataLength;
		long position=Math.addExact(base,alignment-1L)&-alignment;
		appendBase=base;
		appendEnd=Math.addExact(position,length);
		return position;
	}

	void putAppend(long position, byte[] source, int offset, int length) throws IOException {
		if (appendEnd==NO_APPEND) throw new IllegalStateException("No Etch append in progress");
		long end=rangeEnd(position,length);
		if ((position<appendBase)||(end>appendEnd)) {
			throw new IllegalArgumentException("Write outside reserved Etch append range");
		}
		mapper.put(position,source,offset,length);
	}

	void commitAppend() {
		if (appendEnd==NO_APPEND) throw new IllegalStateException("No Etch append in progress");
		if (dataLength!=appendBase) throw new IllegalStateException("Etch data length changed during append");
		dataLength=appendEnd;
		appendBase=NO_APPEND;
		appendEnd=NO_APPEND;
	}

	void abortAppend() {
		appendBase=NO_APPEND;
		appendEnd=NO_APPEND;
	}

	long getDataLength() {
		return dataLength;
	}

	void force() throws IOException {
		mapper.force();
	}

	String implementationName() {
		return mapper.implementationName();
	}

	private long checkedRange(long position, long length) {
		long end=rangeEnd(position,length);
		long currentLength=dataLength;
		if ((position<0L)||(end>currentLength)) {
			throw corruption("Access outside logical Etch data",position,end);
		}
		return position;
	}

	private long rangeEnd(long position, long length) {
		if ((position<0L)||(length<0L)) throw corruption("Negative Etch file range",position,length);
		try {
			return Math.addExact(position,length);
		} catch (ArithmeticException e) {
			throw corruption("Overflowing Etch file range",position,length);
		}
	}

	private EtchCorruptionError corruption(String message, long first, long second) {
		return new EtchCorruptionError(message+": first="+Utils.toHexString(first)
				+" second="+Utils.toHexString(second)+" dataLength="
				+Utils.toHexString(dataLength)+" file="+fileName);
	}

	@Override
	public void close() throws IOException {
		mapper.close();
	}
}
