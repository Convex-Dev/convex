package convex.core.data;

import convex.core.util.Utils;

public abstract class ACountedBlob extends ABlob {
	
	protected final long count;

	public ACountedBlob(long count) {
		this.count=count;
	}
	
	/**
	 * Returns true if this is a fully packed set of chunks
	 * @return True if fully packed, false otherwise
	 */
	@Override
	public boolean isChunkPacked() {
		return (count&(Blob.CHUNK_LENGTH-1))==0;
	}
	
	@Override
	public final int size() {
		return Utils.checkedInt(count);
	}

	@Override
	public final long count() {
		return count;
	}
}
