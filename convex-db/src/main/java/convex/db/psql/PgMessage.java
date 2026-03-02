package convex.db.psql;

import io.netty.buffer.ByteBuf;

/**
 * Base class for PostgreSQL wire protocol messages.
 *
 * <p>PostgreSQL messages consist of:
 * <ul>
 *   <li>1 byte message type identifier (except for startup)</li>
 *   <li>4 byte length (including the length itself)</li>
 *   <li>Message-specific payload</li>
 * </ul>
 */
public abstract class PgMessage {

	/**
	 * Writes this message to the buffer.
	 */
	public abstract void write(ByteBuf buf);

	/**
	 * Returns the message type byte.
	 */
	public abstract byte getType();

	// ========== Frontend (Client -> Server) Message Types ==========

	/** Simple query */
	public static final byte QUERY = 'Q';

	/** Parse (extended query) */
	public static final byte PARSE = 'P';

	/** Bind (extended query) */
	public static final byte BIND = 'B';

	/** Describe (extended query) */
	public static final byte DESCRIBE = 'D';

	/** Execute (extended query) */
	public static final byte EXECUTE = 'E';

	/** Sync (extended query) */
	public static final byte SYNC = 'S';

	/** Flush */
	public static final byte FLUSH = 'H';

	/** Close */
	public static final byte CLOSE = 'C';

	/** Terminate */
	public static final byte TERMINATE = 'X';

	/** Password message */
	public static final byte PASSWORD = 'p';

	// ========== Backend (Server -> Client) Message Types ==========

	/** Authentication request */
	public static final byte AUTH = 'R';

	/** Parameter status */
	public static final byte PARAMETER_STATUS = 'S';

	/** Backend key data */
	public static final byte BACKEND_KEY_DATA = 'K';

	/** Ready for query */
	public static final byte READY_FOR_QUERY = 'Z';

	/** Row description */
	public static final byte ROW_DESCRIPTION = 'T';

	/** Data row */
	public static final byte DATA_ROW = 'D';

	/** Command complete */
	public static final byte COMMAND_COMPLETE = 'C';

	/** Empty query response */
	public static final byte EMPTY_QUERY = 'I';

	/** Error response */
	public static final byte ERROR_RESPONSE = 'E';

	/** Notice response */
	public static final byte NOTICE_RESPONSE = 'N';

	/** Parse complete */
	public static final byte PARSE_COMPLETE = '1';

	/** Bind complete */
	public static final byte BIND_COMPLETE = '2';

	/** Close complete */
	public static final byte CLOSE_COMPLETE = '3';

	/** No data */
	public static final byte NO_DATA = 'n';

	/** Parameter description */
	public static final byte PARAMETER_DESCRIPTION = 't';

	// ========== Helper Methods ==========

	/**
	 * Writes a null-terminated string to the buffer.
	 */
	protected static void writeCString(ByteBuf buf, String s) {
		if (s != null) {
			buf.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		buf.writeByte(0);
	}

	/**
	 * Reads a null-terminated string from the buffer.
	 */
	protected static String readCString(ByteBuf buf) {
		int start = buf.readerIndex();
		int end = start;
		while (buf.getByte(end) != 0) {
			end++;
		}
		byte[] bytes = new byte[end - start];
		buf.readBytes(bytes);
		buf.readByte(); // consume null terminator
		return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
	}
}
