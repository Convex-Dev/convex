package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * Authentication OK message - sent when authentication succeeds.
 */
public class AuthenticationOk extends PgMessage {

	public static final AuthenticationOk INSTANCE = new AuthenticationOk();

	private AuthenticationOk() {}

	@Override
	public byte getType() {
		return AUTH;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(AUTH);
		buf.writeInt(8); // length: 4 (length) + 4 (auth type)
		buf.writeInt(0); // 0 = AuthenticationOk
	}
}
