package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * Authentication request for cleartext password.
 */
public class AuthenticationCleartextPassword extends PgMessage {

	public static final AuthenticationCleartextPassword INSTANCE = new AuthenticationCleartextPassword();

	private AuthenticationCleartextPassword() {}

	@Override
	public byte getType() {
		return AUTH;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(AUTH);
		buf.writeInt(8); // length: 4 (length) + 4 (auth type)
		buf.writeInt(3); // 3 = AuthenticationCleartextPassword
	}
}
