package convex.core.data;

import java.util.Arrays;

import convex.core.exceptions.TODOException;

/**
 * Base class for Blobs which represent an integral numeric value
 */
public abstract class ANumericBlob extends AArrayBlob {

	protected ANumericBlob(byte[] bytes, int offset, int length) {
		super(bytes, offset, length);
	}

	@Override
	public int estimatedEncodingSize() {
		// Tag+reasonable length+raw bytes
		return 10+length;
	}

	@Override
	public boolean equals(ABlob a) {
		if (a instanceof ANumericBlob) {
			return equals((ANumericBlob)a);
		}
		return false;
	}
	
	public boolean equals(ANumericBlob a) {
		// TODO: should be overridden to handle specific types
		return Arrays.equals(store, offset, offset+length, a.store, a.offset, a.offset+a.length);
	}

	@Override
	public Blob getChunk(long i) {
		return toBlob().getChunk(i);
	}

	@Override
	public boolean isRegularBlob() {
		return false;
	}

	
	// TODO: these should be abstract
	@Override
	public int encode(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCanonical() {
		return false;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public byte getTag() {
		throw new TODOException();
	}
}
