package convex.core.data.impl;

import org.bouncycastle.util.Arrays;

import convex.core.data.ABlob;
import convex.core.data.ADerivedBlob;
import convex.core.data.Cells;

/**
 * Blob filled with all zeros. Useful to avoid allocating lots of empty arrays, potentially.
 */
public class ZeroBlob extends ADerivedBlob {

	public static final ZeroBlob EMPTY = Cells.intern(new ZeroBlob(0));

	protected ZeroBlob(long count) {
		super(count);
	}
	
	@Override
	public ABlob append(ABlob other) {
		if (other instanceof ZeroBlob) {
			// fast path for appending zeros :-)
			return create(count+((ZeroBlob)other).count);
		}
		return super.append(other);
	}

	@Override
	public ZeroBlob sliceImpl(long start, long end) {
		return new ZeroBlob(end-start);
	}

	public static ZeroBlob create(long count) {
		if (count<0) throw new IllegalArgumentException("Negative Count");
		if (count==0) return EMPTY;
		return new ZeroBlob(count);
	}

	@Override
	public int getBytes(byte[] dest, int destOffset) {
		int end=destOffset+size();
		Arrays.fill(dest, destOffset,end,(byte)0);
		return end;
	}
	
	@Override
	public byte byteAtUnchecked(long i) {
		return 0;
	}
}
