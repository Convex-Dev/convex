package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * BindComplete message - sent after successful Bind.
 */
public class BindComplete extends PgMessage {

	public static final BindComplete INSTANCE = new BindComplete();

	private BindComplete() {}

	@Override
	public byte getType() {
		return BIND_COMPLETE;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(BIND_COMPLETE);
		buf.writeInt(4); // length (just the length field itself)
	}
}
