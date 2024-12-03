package convex.core.data;

import convex.core.Constants;
import convex.core.data.util.BlobBuilder;

public abstract class AObject {
	/**
	 * We cache the Blob for the binary encoding of this Cell
	 */
	protected Blob encoding;

	/**
	 * Prints this Object to a readable String Representation. 
	 * 
	 * SECURITY: Must halt and return false in O(1) time when limit of printing is exceeded otherwise
	 * DoS attacks may be possible.
	 * 
	 * @param sb BlobBuilder to append to. May be partially written if print limit exceeded
	 * @param limit Limit of printing in string bytes
	 * @return True if fully printed within limit, false otherwise
	 */
	public abstract boolean print(BlobBuilder sb, long limit);
	
	/**
	 * Prints this Object as a CVM String value, for human consumption. 
	 * 
	 * May include readable message indicating failure if print limit exceeded.
	 * 
	 * @return String representation
	 */
	public final AString print() {
		return print(Constants.PRINT_LIMIT);
	}
	
	/**
	 * Prints this Object as a CVM String value, for human consumption. 
	 * 
	 * May include readable message indicating failure if print limit exceeded.
	 * 
	 * @param limit Limit of bytes to print
	 * @return String representation
	 */
	public final AString print(long limit) {
		BlobBuilder bb = new BlobBuilder();
		boolean printed = print(bb,limit);
		if (!printed) {
			bb.append(Constants.PRINT_EXCEEDED_MESSAGE);
		}
		AString s=bb.getCVMString();
		return s;
	}
	
	/**
	 * Gets the encoded byte representation of this value.
	 * 
	 * @return A Blob representing this value in encoded form
	 */
	public Blob getEncoding() {
		Blob result=encoding;
		if (result==null) {
			result=createEncoding();
			encoding=result;
		}
		return result;
	}
	
	/**
	 * Creates a Blob object representing this object. Should be called only after
	 * the cached encoding has been checked.
	 * 
	 * @return Blob Encoding of Object
	 */
	protected abstract Blob createEncoding();
	
	/**
	 * Attach the given encoded Blob to this object
	 * 
	 * Warning: Blob must be the correct canonical representation of this Cell,
	 * otherwise bad things may happen (incorrect hashcode, etc.)
	 * 
	 * @param data Encoding of Value. Must be a correct canonical encoding, or null
	 * to clear the cached encoding
	 */
	public final void attachEncoding(Blob data) {
		this.encoding=data;
	}

}
