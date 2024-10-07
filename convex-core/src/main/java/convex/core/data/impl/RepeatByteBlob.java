package convex.core.data.impl;

import java.util.Arrays;

public class RepeatByteBlob extends ADerivedBlob {
	private final byte b;
	
	protected RepeatByteBlob(long count, byte b) {
		super(count);
		this.b=b;
	}
	
	@Override
	public RepeatByteBlob sliceImpl(long start, long end) {
		return new RepeatByteBlob(end-start,b);
	}

	@Override
	public int getBytes(byte[] dest, int destOffset) {
		int end=destOffset+size();
		Arrays.fill(dest, destOffset,end,(byte)0);
		return end;
	}
	
	@Override
	public byte byteAtUnchecked(long i) {
		return b;
	}

}
