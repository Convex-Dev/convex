package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * ParseComplete message - sent after successful Parse.
 */
public class ParseComplete extends PgMessage {

	public static final ParseComplete INSTANCE = new ParseComplete();

	private ParseComplete() {}

	@Override
	public byte getType() {
		return PARSE_COMPLETE;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(PARSE_COMPLETE);
		buf.writeInt(4); // length (just the length field itself)
	}
}
