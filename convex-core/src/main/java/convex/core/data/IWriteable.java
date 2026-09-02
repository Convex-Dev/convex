package convex.core.data;

public interface IWriteable {
	/**
	 * Writes this object to a byte array including an appropriate message tag
	 * 
	 * @param bs byte array to write this object to
	 * @param pos position at which to write the value
	 * @return The updated position
	 */
	public int encode(byte[] bs, int pos);
}
