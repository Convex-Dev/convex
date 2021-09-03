package convex.core.data;

public abstract class AObject {
	/**
	 * We cache the Blob for the binary encoding of this Cell
	 */
	protected Blob encoding;

	/**
	 * Prints this Object to a readable String Representation
	 * 
	 * @param sb StringBuilder to append to
	 */
	public abstract void print(StringBuilder sb);
	
	/**
	 * Renders this object as a String value
	 * @return String representation
	 */
	public final String print() {
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
	 * @return Blob Encoding of Object
	 */
	protected abstract Blob createEncoding();
	
	/**
	 * Attach the given encoding Blob to this object, if no encoding is currently cached
	 * 
	 * Warning: Blob must be the correct canonical representation of this Cell,
	 * otherwise bad things may happen (incorrect hashcode, etc.)
	 * 
	 * @param data Encoding of Value. Must be a correct canonical encoding.
	 */
	public final void attachEncoding(Blob data) {
		this.encoding=data;
	}

}
