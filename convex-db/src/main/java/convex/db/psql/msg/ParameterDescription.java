package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * ParameterDescription message - describes the parameters of a prepared statement.
 */
public class ParameterDescription extends PgMessage {

	public static final ParameterDescription EMPTY = new ParameterDescription(new int[0]);

	private final int[] parameterTypes;

	public ParameterDescription(int[] parameterTypes) {
		this.parameterTypes = parameterTypes;
	}

	@Override
	public byte getType() {
		return PARAMETER_DESCRIPTION;
	}

	@Override
	public void write(ByteBuf buf) {
		// Length: 4 (length) + 2 (count) + 4 * count (OIDs)
		int length = 4 + 2 + 4 * parameterTypes.length;

		buf.writeByte(PARAMETER_DESCRIPTION);
		buf.writeInt(length);
		buf.writeShort(parameterTypes.length);

		for (int oid : parameterTypes) {
			buf.writeInt(oid);
		}
	}
}
