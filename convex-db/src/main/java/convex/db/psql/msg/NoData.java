package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * NoData message - sent when Describe finds no data to return.
 */
public class NoData extends PgMessage {

	public static final NoData INSTANCE = new NoData();

	private NoData() {}

	@Override
	public byte getType() {
		return NO_DATA;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(NO_DATA);
		buf.writeInt(4); // length (just the length field itself)
	}
}
