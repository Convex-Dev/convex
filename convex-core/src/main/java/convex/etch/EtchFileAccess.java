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
	private final EtchFileCipher cipher;
	private final boolean encryptedIndex;
	private volatile long dataLength;

	EtchFileAccess(EtchFileMapper mapper, String fileName, long dataLength, long physicalLength) {
		this(mapper,fileName,dataLength,physicalLength,null,false);
	}

	EtchFileAccess(EtchFileMapper mapper, String fileName, long dataLength, long physicalLength,
			EtchFileCipher cipher, boolean encryptedIndex) {
		this.mapper=mapper;
		this.fileName=fileName;
		this.cipher=cipher;
		this.encryptedIndex=encryptedIndex;
		this.dataLength=dataLength;
		if (encryptedIndex&&(cipher==null)) {
			throw new IllegalArgumentException("Index encryption requires a file cipher");
		}
		if ((dataLength<0L)||(dataLength>physicalLength)) {
			throw corruption("Invalid stored data length",dataLength,physicalLength);
		}
	}

	void readHeader(long position, byte[] destination, int offset, int length) throws IOException {
		mapper.get(checkedRange(position,length),destination,offset,length);
	}

	void readRaw(long position, byte[] destination, int offset, int length) throws IOException {
		mapper.get(checkedRange(position,length),destination,offset,length);
	}

	void writeHeader(long position, byte[] source, int offset, int length) throws IOException {
		position=checkedRange(position,length);
		mapper.ensureWriteCapacity(position,length);
		mapper.put(position,source,offset,length);
	}

	void readData(long position, byte[] destination, int offset, int length) throws IOException {
		position=checkedRange(position,length);
		if (cipher==null) {
			mapper.get(position,destination,offset,length);
		} else {
			mapper.getTransformed(position,destination,offset,length,cipher.start(position));
		}
	}

	void writeData(long position, byte[] source, int offset, int length) throws IOException {
		position=checkedRange(position,length);
		mapper.ensureWriteCapacity(position,length);
		if (cipher==null) {
			mapper.put(position,source,offset,length);
		} else {
			mapper.putTransformed(position,source,offset,length,cipher.start(position));
		}
	}

	long readIndexSlotAcquire(long position) throws IOException {
		position=checkedRange(position,Long.BYTES);
		long value=mapper.readIndexSlotAcquire(position);
		return encryptedIndex?cipher.start(position).transformLong(value):value;
	}

	/** Bulk index read for exclusive maintenance scans. */
	void readIndex(long position, byte[] destination, int offset, int length)
			throws IOException {
		position=checkedRange(position,length);
		if (encryptedIndex) {
			mapper.getTransformed(position,destination,offset,length,cipher.start(position));
		} else {
			mapper.get(position,destination,offset,length);
		}
	}

	void writeIndexSlotRelease(long position, long value) throws IOException {
		position=checkedRange(position,Long.BYTES);
		if (encryptedIndex) value=cipher.start(position).transformLong(value);
		mapper.writeIndexSlotRelease(position,value);
	}

	long appendHeader(byte[] source, int offset, int length) throws IOException {
		return append(source,offset,length,1,null);
	}

	long appendIndex(byte[] source, int offset, int length, int alignment) throws IOException {
		return append(source,offset,length,alignment,encryptedIndex?cipher:null);
	}

	private long append(byte[] source, int offset, int length, int alignment,
			EtchFileCipher writeCipher) throws IOException {
		long position=prepareAppend(length,alignment);
		if (writeCipher==null) {
			mapper.put(position,source,offset,length);
		} else {
			mapper.putTransformed(position,source,offset,length,writeCipher.start(position));
		}
		dataLength=Math.addExact(position,length);
		return position;
	}

	long appendZeroIndex(int length, int alignment) throws IOException {
		long position=prepareAppend(length,alignment);
		EtchCipherCursor cursor=encryptedIndex?cipher.start(position):null;
		for (int offset=0;offset<length;offset+=ZERO_BUFFER_SIZE) {
			int count=Math.min(length-offset,ZERO_BUFFER_SIZE);
			if (cursor==null) {
				mapper.put(position+offset,ZERO_BUFFER,0,count);
			} else {
				mapper.putTransformed(position+offset,ZERO_BUFFER,0,count,cursor);
			}
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
		EtchCipherCursor cursor=(cipher==null)?null:cipher.start(position);
		putData(writePosition,key.getInternalArray(),key.getInternalOffset(),keyLength,cursor);
		writePosition+=keyLength;
		putData(writePosition,recordHeader,0,headerLength,cursor);
		writePosition+=headerLength;
		putData(writePosition,encoding.getInternalArray(),encoding.getInternalOffset(),encodingLength,cursor);
		dataLength=Math.addExact(position,recordLength);
		return position;
	}

	private void putData(long position, byte[] source, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		if (cursor==null) {
			mapper.put(position,source,offset,length);
		} else {
			mapper.putTransformed(position,source,offset,length,cursor);
		}
	}

	DataRecord readDataRecord(long recordPosition, boolean includeKey) throws IOException {
		return readDataRecord(recordPosition,includeKey,null);
	}

	DataRecord readDataRecord(long recordPosition, AArrayBlob expectedKey) throws IOException {
		return readDataRecord(recordPosition,true,expectedKey);
	}

	private DataRecord readDataRecord(long recordPosition, boolean includeKey,
			AArrayBlob expectedKey) throws IOException {
		long readPosition=includeKey?recordPosition:Math.addExact(recordPosition,EtchConstants.KEY_SIZE);
		int headerOffset=includeKey?EtchConstants.KEY_SIZE:0;
		int headerLength=headerOffset+EtchConstants.LABEL_SIZE+EtchConstants.ENCODING_LENGTH_SIZE;
		byte[] header=new byte[headerLength];
		EtchCipherCursor cursor=(cipher==null)?null:cipher.start(readPosition);
		readData(readPosition,header,0,headerLength,cursor);
		if ((expectedKey!=null)&&!expectedKey.equalsBytes(header,0)) return null;

		int encodingLength=Utils.readShort(header,headerOffset+EtchConstants.LABEL_SIZE);
		if (encodingLength<=0) throw corruption("Invalid Etch encoding length",readPosition,encodingLength);
		long encodingPosition=Math.addExact(readPosition,headerLength);
		byte[] encoding=new byte[encodingLength];
		readData(encodingPosition,encoding,0,encodingLength,cursor);
		return new DataRecord(header,headerOffset,encoding);
	}

	private void readData(long position, byte[] destination, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		position=checkedRange(position,length);
		if (cursor==null) {
			mapper.get(position,destination,offset,length);
		} else {
			mapper.getTransformed(position,destination,offset,length,cursor);
		}
	}

	record DataRecord(byte[] header, int headerOffset, byte[] encoding) {
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

	boolean isEncrypted() {
		return cipher!=null;
	}

	boolean isIndexEncrypted() {
		return encryptedIndex;
	}

	void force() throws IOException {
		mapper.force();
	}

	void forceHeader(long position, int length) throws IOException {
		mapper.forceRange(checkedRange(position,length),length);
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
