package convex.etch;

import java.io.IOException;
import java.util.Objects;

import convex.core.data.AArrayBlob;
import convex.core.util.Utils;

/**
 * Complete mapped I/O boundary for one Etch file.
 *
 * <p>The mapper owns the logical file length. Reads and ordinary writes are
 * bounded by that length; only append operations may grow it. Implementations
 * own physical mapping and growth, while callers select whether each operation
 * uses the file cipher.</p>
 */
abstract class AFileMapper implements AutoCloseable {
	private static final int ZERO_BUFFER_SIZE=16_384;
	private static final byte[] ZERO_BUFFER=new byte[ZERO_BUFFER_SIZE];

	private final String fileName;
	private final boolean readOnly;
	private volatile long length;

	AFileMapper(String fileName, long length, long physicalLength, boolean readOnly) {
		this.fileName=Objects.requireNonNull(fileName,"fileName");
		this.readOnly=readOnly;
		this.length=length;
		if ((length<0L)||(length>physicalLength)) {
			throw corruption("Invalid stored data length",length,physicalLength);
		}
	}

	final long length() {
		return length;
	}

	/** Adopts the validated stored length after opening an existing file. */
	final void adoptLength(long storedLength) {
		long physicalLength=length;
		if ((storedLength<0L)||(storedLength>physicalLength)) {
			throw corruption("Invalid stored data length",storedLength,physicalLength);
		}
		length=storedLength;
	}

	final void read(long position, byte[] destination, int offset, int count,
			EtchFileCipher cipher) throws IOException {
		Objects.requireNonNull(destination,"destination");
		Objects.checkFromIndexSize(offset,count,destination.length);
		position=checkedRange(position,count);
		if (cipher==null) {
			readMapped(position,destination,offset,count);
		} else {
			cipher.initialise(position);
			readTransformedMapped(position,destination,offset,count,cipher);
		}
	}

	final boolean matches(long position, byte[] expected, int offset, int count)
			throws IOException {
		Objects.requireNonNull(expected,"expected");
		Objects.checkFromIndexSize(offset,count,expected.length);
		return matchesMapped(checkedRange(position,count),expected,offset,count);
	}

	final void write(long position, byte[] source, int offset, int count,
			EtchFileCipher cipher) throws IOException {
		checkWritable();
		Objects.requireNonNull(source,"source");
		Objects.checkFromIndexSize(offset,count,source.length);
		position=checkedRange(position,count);
		ensureMapped(position,count);
		writePrepared(position,source,offset,count,cipher);
	}

	final synchronized long append(byte[] source, int offset, int count, int alignment,
			EtchFileCipher cipher) throws IOException {
		checkWritable();
		Objects.requireNonNull(source,"source");
		Objects.checkFromIndexSize(offset,count,source.length);
		long position=appendPosition(count,alignment);
		ensureMapped(position,count);
		writePrepared(position,source,offset,count,cipher);
		length=Math.addExact(position,count);
		return position;
	}

	/** Appends an aligned zero-filled region without allocating a temporary region. */
	final synchronized long appendZeros(int count, int alignment, EtchFileCipher cipher)
			throws IOException {
		checkWritable();
		long position=appendPosition(count,alignment);
		ensureMapped(position,count);
		for (int offset=0;offset<count;offset+=ZERO_BUFFER_SIZE) {
			int chunk=Math.min(count-offset,ZERO_BUFFER_SIZE);
			writePrepared(position+offset,ZERO_BUFFER,0,chunk,cipher);
		}
		length=Math.addExact(position,count);
		return position;
	}

	/**
	 * Appends the three existing record buffers as one logical operation. The
	 * logical end is published only after every segment has been written.
	 */
	final synchronized long append(AArrayBlob first, byte[] middle, int middleLength,
			AArrayBlob last, EtchFileCipher cipher) throws IOException {
		checkWritable();
		Objects.requireNonNull(first,"first");
		Objects.requireNonNull(middle,"middle");
		Objects.requireNonNull(last,"last");
		Objects.checkFromIndexSize(0,middleLength,middle.length);
		int firstLength=Math.toIntExact(first.count());
		int lastLength=Math.toIntExact(last.count());
		long recordLength=Math.addExact(Math.addExact(firstLength,middleLength),lastLength);
		long position=appendPosition(recordLength,1);
		ensureMapped(position,recordLength);
		long writePosition=position;
		writePrepared(writePosition,first.getInternalArray(),first.getInternalOffset(),
				firstLength,cipher);
		writePosition+=firstLength;
		writePrepared(writePosition,middle,0,middleLength,cipher);
		writePosition+=middleLength;
		writePrepared(writePosition,last.getInternalArray(),last.getInternalOffset(),
				lastLength,cipher);
		length=Math.addExact(position,recordLength);
		return position;
	}

	final long readLongAcquire(long position, EtchFileCipher cipher) throws IOException {
		position=checkedRange(position,Long.BYTES);
		long value=readLongAcquireMapped(position);
		return (cipher==null)?value:cipher.transformLong(position,value);
	}

	final void writeLongRelease(long position, long value, EtchFileCipher cipher)
			throws IOException {
		checkWritable();
		position=checkedRange(position,Long.BYTES);
		writeLongReleaseMapped(position,
				(cipher==null)?value:cipher.transformLong(position,value));
	}

	final void forceRange(long position, long count) throws IOException {
		forceRangeMapped(checkedRange(position,count),count);
	}

	private void writePrepared(long position, byte[] source, int offset, int count,
			EtchFileCipher cipher) throws IOException {
		if (cipher==null) {
			writeMapped(position,source,offset,count);
		} else {
			cipher.initialise(position);
			writeTransformedMapped(position,source,offset,count,cipher);
		}
	}

	private long appendPosition(long count, int alignment) {
		if ((count<0L)||(alignment<=0)||((alignment&(alignment-1))!=0)) {
			throw new IllegalArgumentException("Invalid Etch append length or alignment");
		}
		long position=Utils.roundUpToAlignment(length,alignment);
		rangeEnd(position,count);
		return position;
	}

	private long checkedRange(long position, long count) {
		long end=rangeEnd(position,count);
		long currentLength=length;
		if (end>currentLength) {
			throw corruption("Access outside logical Etch data",position,end);
		}
		return position;
	}

	private long rangeEnd(long position, long count) {
		if ((position<0L)||(count<0L)) {
			throw corruption("Negative Etch file range",position,count);
		}
		try {
			return Math.addExact(position,count);
		} catch (ArithmeticException e) {
			throw corruption("Overflowing Etch file range",position,count);
		}
	}

	private void checkWritable() throws IOException {
		if (readOnly) throw new IOException("Etch mapping is read-only: "+fileName);
	}

	private EtchCorruptionError corruption(String message, long first, long second) {
		return new EtchCorruptionError(message+": first="+Utils.toHexString(first)
				+" second="+Utils.toHexString(second)+" dataLength="
				+Utils.toHexString(length)+" file="+fileName);
	}

	abstract void readMapped(long position, byte[] destination, int offset, int length)
			throws IOException;

	abstract boolean matchesMapped(long position, byte[] expected, int offset, int length)
			throws IOException;

	abstract void readTransformedMapped(long position, byte[] destination, int offset,
			int length, EtchFileCipher cipher) throws IOException;

	abstract void ensureMapped(long position, long length) throws IOException;

	abstract void writeMapped(long position, byte[] source, int offset, int length)
			throws IOException;

	abstract void writeTransformedMapped(long position, byte[] source, int offset,
			int length, EtchFileCipher cipher) throws IOException;

	abstract long readLongAcquireMapped(long position) throws IOException;

	abstract void writeLongReleaseMapped(long position, long value) throws IOException;

	abstract void force() throws IOException;

	abstract void forceRangeMapped(long position, long length) throws IOException;

	abstract String implementationName();

	@Override
	public abstract void close() throws IOException;
}
