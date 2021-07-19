package convex.core.data;

public abstract class AObject {
	/**
	 * We cache the Blob for the binary encoding of this Cell
	 */
	protected Blob encoding;

	public abstract void print(StringBuilder sb);
	
	public String print() {
		StringBuilder sb = new StringBuilder();
		print(sb);
		return sb.toString();
	}
	
	/**
	 * Gets the encoded byte representation of this cell.
	 * 
	 * @return A blob representing this cell in encoded form
	 */
	public Blob getEncoding() {
		if (encoding==null) encoding=createEncoding();
		return encoding;
	}
	
	/**
	 * Creates a Blob object representing this object. Should be called only after
	 * the cached encoding has been checked.
	 * 
	 * @return
	 */
	protected abstract Blob createEncoding();
	
	/**
	 * Attach the given encoding Blob to this object, if no encoding is currently cached
	 * 
	 * Warning: Blob must be the correct canonical representation of this Cell,
	 * otherwise bad things may happen (incorrect hashcode, etc.)
	 * 
	 * @param data
	 */
	public final void attachEncoding(Blob data) {
		this.encoding=data;
	}

}
