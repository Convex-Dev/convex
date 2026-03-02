package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * BackendKeyData message - provides the process ID and secret key
 * that can be used to cancel queries.
 */
public class BackendKeyData extends PgMessage {

	private final int processId;
	private final int secretKey;

	public BackendKeyData(int processId, int secretKey) {
		this.processId = processId;
		this.secretKey = secretKey;
	}

	@Override
	public byte getType() {
		return BACKEND_KEY_DATA;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(BACKEND_KEY_DATA);
		buf.writeInt(12); // length: 4 (length) + 4 (pid) + 4 (key)
		buf.writeInt(processId);
		buf.writeInt(secretKey);
	}

	public int getProcessId() {
		return processId;
	}

	public int getSecretKey() {
		return secretKey;
	}
}
