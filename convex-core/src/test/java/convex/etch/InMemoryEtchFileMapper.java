package convex.etch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import convex.core.util.Utils;

/** Small deterministic mapper for byte-exact Etch format tests. */
final class InMemoryEtchFileMapper implements EtchFileMapper {
	private byte[] bytes=new byte[0];
	private int fullForceCount;
	private int rangeForceCount;
	private long forcedPosition=-1L;
	private long forcedLength=-1L;
	private boolean failNextFullForce;

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
	public void get(long position, byte[] destination, int offset, int length) {
		System.arraycopy(bytes,Math.toIntExact(position),destination,offset,length);
	}

	@Override
	public boolean matches(long position, byte[] expected, int offset, int length) {
		int start=Math.toIntExact(position);
		return Arrays.equals(bytes,start,start+length,expected,offset,offset+length);
	}

	@Override
	public void getTransformed(long position, byte[] destination, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		cursor.transform(ByteBuffer.wrap(bytes,Math.toIntExact(position),length),
				ByteBuffer.wrap(destination,offset,length));
	}

	@Override
	public void ensureWriteCapacity(long position, long length) {
		int required=Math.toIntExact(Math.addExact(position,length));
		if (required>bytes.length) bytes=Arrays.copyOf(bytes,required);
	}

	@Override
	public void put(long position, byte[] source, int offset, int length) {
		System.arraycopy(source,offset,bytes,Math.toIntExact(position),length);
	}

	@Override
	public void putTransformed(long position, byte[] source, int offset, int length,
			EtchCipherCursor cursor) throws IOException {
		cursor.transform(ByteBuffer.wrap(source,offset,length),
				ByteBuffer.wrap(bytes,Math.toIntExact(position),length));
	}

	@Override
	public long readIndexSlotAcquire(long position) {
		return Utils.readLong(bytes,Math.toIntExact(position),Long.BYTES);
	}

	@Override
	public void writeIndexSlotRelease(long position, long value) {
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
	public void forceRange(long position, long length) {
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
