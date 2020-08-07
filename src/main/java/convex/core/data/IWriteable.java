package convex.core.data;

import java.nio.ByteBuffer;

public interface IWriteable {
	/**
	 * Writes this object to a ByteBuffer including an appropriate message tag
	 * 
	 * @param bb A ByteBuffer to write this object to
	 * @return The updated ByteBuffer
	 */
	public ByteBuffer write(ByteBuffer bb);


}
