package convex.etch;

import java.io.IOException;

import convex.core.data.AArrayBlob;
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

	private final EtchFileMapper mapper;
	private final String fileName;
	private volatile long dataLength;

	EtchFileAccess(EtchFileMapper mapper, String fileName, long dataLength, long physicalLength) {
		this.mapper=mapper;
		this.fileName=fileName;
		this.dataLength=dataLength;
		if ((dataLength<0L)||(dataLength>physicalLength)) {
			throw corruption("Invalid stored data length",dataLength,physicalLength);
		}
	}

	void readHeader(long position, byte[] destination, int offset, int length) throws IOException {
		mapper.get(checkedRange(position,length),destination,offset,length);
	}

	void writeHeader(long position, byte[] source, int offset, int length) throws IOException {
		position=checkedRange(position,length);
		mapper.ensureWriteCapacity(position,length);
		mapper.put(position,source,offset,length);
	}

	void readData(long position, byte[] destination, int offset, int length) throws IOException {
		mapper.get(checkedRange(position,length),destination,offset,length);
	}

	void writeData(long position, byte[] source, int offset, int length) throws IOException {
		position=checkedRange(position,length);
		mapper.ensureWriteCapacity(position,length);
		mapper.put(position,source,offset,length);
	}

	long readIndexSlotAcquire(long position) throws IOException {
		return mapper.readIndexSlotAcquire(checkedRange(position,Long.BYTES));
	}

	void writeIndexSlotRelease(long position, long value) throws IOException {
		mapper.writeIndexSlotRelease(checkedRange(position,Long.BYTES),value);
	}

	long appendHeader(byte[] source, int offset, int length) throws IOException {
		return append(source,offset,length,1);
	}

	long appendIndex(byte[] source, int offset, int length, int alignment) throws IOException {
		return append(source,offset,length,alignment);
	}

	private long append(byte[] source, int offset, int length, int alignment) throws IOException {
		long position=prepareAppend(length,alignment);
		mapper.put(position,source,offset,length);
		dataLength=Math.addExact(position,length);
		return position;
	}

	long appendZeroIndex(int length, int alignment) throws IOException {
		long position=prepareAppend(length,alignment);
		for (int offset=0;offset<length;offset+=ZERO_BUFFER_SIZE) {
			mapper.put(position+offset,ZERO_BUFFER,0,Math.min(length-offset,ZERO_BUFFER_SIZE));
		}
		dataLength=Math.addExact(position,length);
		return position;
	}

	long appendDataRecord(AArrayBlob key, byte[] recordHeader, int headerLength,
			AArrayBlob encoding) throws IOException {
		int keyLength=Math.toIntExact(key.count());
		int encodingLength=Math.toIntExact(encoding.count());
		long recordLength=Math.addExact(Math.addExact(keyLength,headerLength),encodingLength);
		long position=prepareAppend(recordLength,1);
		long writePosition=position;
		mapper.put(writePosition,key.getInternalArray(),key.getInternalOffset(),keyLength);
		writePosition+=keyLength;
		mapper.put(writePosition,recordHeader,0,headerLength);
		writePosition+=headerLength;
		mapper.put(writePosition,encoding.getInternalArray(),encoding.getInternalOffset(),encodingLength);
		dataLength=Math.addExact(position,recordLength);
		return position;
	}

	private long prepareAppend(long length, int alignment) throws IOException {
		if ((length<0L)||(alignment<=0)||((alignment&(alignment-1))!=0)) {
			throw new IllegalArgumentException("Invalid Etch append length or alignment");
		}
		long position=Utils.roundUpToAlignment(dataLength,alignment);
		mapper.ensureWriteCapacity(position,length);
		return position;
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
