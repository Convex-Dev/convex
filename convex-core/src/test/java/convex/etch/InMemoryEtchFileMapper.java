package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import convex.core.util.Utils;

/** Small deterministic mapper for byte-exact Etch format tests. */
final class InMemoryEtchFileMapper extends AFileMapper {
	private byte[] bytes;
	private int fullForceCount;
	private int rangeForceCount;
	private long forcedPosition=-1L;
	private long forcedLength=-1L;
	private boolean failNextFullForce;

	InMemoryEtchFileMapper() {
		this(0L);
	}

	InMemoryEtchFileMapper(long length) {
		super("in-memory",length,length,false);
		bytes=new byte[Math.toIntExact(length)];
	}

	byte[] copyOf(long length) {
		return Arrays.copyOf(bytes,Math.toIntExact(length));
	}

	byte[] copyRange(long start, long end) {
		return Arrays.copyOfRange(bytes,Math.toIntExact(start),Math.toIntExact(end));
	}

	int fullForceCount() {
		return fullForceCount;
	}

	int rangeForceCount() {
		return rangeForceCount;
	}

	long forcedPosition() {
		return forcedPosition;
	}

	long forcedLength() {
		return forcedLength;
	}

	void failNextFullForce() {
		failNextFullForce=true;
	}

	@Override
	void readMapped(long position, byte[] destination, int offset, int length) {
		System.arraycopy(bytes,Math.toIntExact(position),destination,offset,length);
	}

	@Override
	boolean matchesMapped(long position, byte[] expected, int offset, int length) {
		int start=Math.toIntExact(position);
		return Arrays.equals(bytes,start,start+length,expected,offset,offset+length);
	}

	@Override
	void readTransformedMapped(long position, byte[] destination, int offset, int length,
			EtchFileCipher cipher) throws IOException {
		cipher.decrypt(ByteBuffer.wrap(bytes,Math.toIntExact(position),length),destination,offset);
	}

	@Override
	void ensureMapped(long position, long length) {
		int required=Math.toIntExact(Math.addExact(position,length));
		if (required>bytes.length) bytes=Arrays.copyOf(bytes,required);
	}

	@Override
	void writeMapped(long position, byte[] source, int offset, int length) {
		System.arraycopy(source,offset,bytes,Math.toIntExact(position),length);
	}

	@Override
	void writeTransformedMapped(long position, byte[] source, int offset, int length,
			EtchFileCipher cipher) throws IOException {
		cipher.encrypt(source,offset,ByteBuffer.wrap(bytes,Math.toIntExact(position),length));
	}

	@Override
	long readLongAcquireMapped(long position) {
		return Utils.readLong(bytes,Math.toIntExact(position),Long.BYTES);
	}

	@Override
	void writeLongReleaseMapped(long position, long value) {
		Utils.writeLong(bytes,Math.toIntExact(position),value);
	}

	@Override
	public void force() throws IOException {
		fullForceCount++;
		if (failNextFullForce) {
			failNextFullForce=false;
			throw new IOException("Injected full-force failure");
		}
	}

	@Override
	void forceRangeMapped(long position, long length) {
		rangeForceCount++;
		forcedPosition=position;
		forcedLength=length;
	}

	@Override
	public String implementationName() {
		return "in-memory";
	}

	@Override
	public void close() {
	}
}
