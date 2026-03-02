package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * CommandComplete message - indicates successful completion of a command.
 */
public class CommandComplete extends PgMessage {

	private final String tag;

	public CommandComplete(String tag) {
		this.tag = tag;
	}

	@Override
	public byte getType() {
		return COMMAND_COMPLETE;
	}

	@Override
	public void write(ByteBuf buf) {
		byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
		int length = 4 + tagBytes.length + 1;

		buf.writeByte(COMMAND_COMPLETE);
		buf.writeInt(length);
		buf.writeBytes(tagBytes);
		buf.writeByte(0);
	}

	// Factory methods for common commands

	public static CommandComplete select(long rowCount) {
		return new CommandComplete("SELECT " + rowCount);
	}

	public static CommandComplete insert(long rowCount) {
		return new CommandComplete("INSERT 0 " + rowCount);
	}

	public static CommandComplete update(long rowCount) {
		return new CommandComplete("UPDATE " + rowCount);
	}

	public static CommandComplete delete(long rowCount) {
		return new CommandComplete("DELETE " + rowCount);
	}

	public static CommandComplete createTable() {
		return new CommandComplete("CREATE TABLE");
	}

	public static CommandComplete dropTable() {
		return new CommandComplete("DROP TABLE");
	}

	public static CommandComplete begin() {
		return new CommandComplete("BEGIN");
	}

	public static CommandComplete commit() {
		return new CommandComplete("COMMIT");
	}

	public static CommandComplete rollback() {
		return new CommandComplete("ROLLBACK");
	}

	public static CommandComplete set() {
		return new CommandComplete("SET");
	}
}
