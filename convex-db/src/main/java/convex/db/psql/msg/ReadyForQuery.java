package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

/**
 * ReadyForQuery message - indicates the backend is ready for a new query.
 */
public class ReadyForQuery extends PgMessage {

	/** Idle (not in a transaction) */
	public static final byte IDLE = 'I';

	/** In a transaction block */
	public static final byte IN_TRANSACTION = 'T';

	/** In a failed transaction block */
	public static final byte FAILED_TRANSACTION = 'E';

	public static final ReadyForQuery IDLE_INSTANCE = new ReadyForQuery(IDLE);

	private final byte status;

	public ReadyForQuery(byte status) {
		this.status = status;
	}

	@Override
	public byte getType() {
		return READY_FOR_QUERY;
	}

	@Override
	public void write(ByteBuf buf) {
		buf.writeByte(READY_FOR_QUERY);
		buf.writeInt(5); // length: 4 (length) + 1 (status)
		buf.writeByte(status);
	}
}
