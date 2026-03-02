package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * CloseComplete message - sent after successful Close.
 */
public class CloseComplete extends PgMessage {

	public static final CloseComplete INSTANCE = new CloseComplete();

	private CloseComplete() {}

	@Override
	public byte getType() {
		return CLOSE_COMPLETE;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(CLOSE_COMPLETE);
		buf.writeInt(4); // length (just the length field itself)
	}
}
