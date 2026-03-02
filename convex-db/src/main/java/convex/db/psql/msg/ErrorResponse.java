package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ErrorResponse message - reports an error to the client.
 */
public class ErrorResponse extends PgMessage {

	// Error field types
	public static final byte SEVERITY = 'S';
	public static final byte SEVERITY_NON_LOCALIZED = 'V';
	public static final byte CODE = 'C';
	public static final byte MESSAGE = 'M';
	public static final byte DETAIL = 'D';
	public static final byte HINT = 'H';
	public static final byte POSITION = 'P';
	public static final byte INTERNAL_POSITION = 'p';
	public static final byte INTERNAL_QUERY = 'q';
	public static final byte WHERE = 'W';
	public static final byte SCHEMA_NAME = 's';
	public static final byte TABLE_NAME = 't';
	public static final byte COLUMN_NAME = 'c';
	public static final byte DATA_TYPE_NAME = 'd';
	public static final byte CONSTRAINT_NAME = 'n';
	public static final byte FILE = 'F';
	public static final byte LINE = 'L';
	public static final byte ROUTINE = 'R';

	private final Map<Byte, String> fields;

	private ErrorResponse(Map<Byte, String> fields) {
		this.fields = fields;
	}

	@Override
	public byte getType() {
		return ERROR_RESPONSE;
	}

	@Override
	public void write(ByteBuf buf) {
		// Calculate length
		int length = 4 + 1; // length field + terminator
		for (Map.Entry<Byte, String> entry : fields.entrySet()) {
			length += 1 + entry.getValue().getBytes(StandardCharsets.UTF_8).length + 1;
		}

		buf.writeByte(ERROR_RESPONSE);
		buf.writeInt(length);

		for (Map.Entry<Byte, String> entry : fields.entrySet()) {
			buf.writeByte(entry.getKey());
			writeCString(buf, entry.getValue());
		}

		buf.writeByte(0); // terminator
	}

	/**
	 * Builder for ErrorResponse messages.
	 */
	public static class Builder {
		private final Map<Byte, String> fields = new LinkedHashMap<>();

		public Builder severity(String severity) {
			fields.put(SEVERITY, severity);
			fields.put(SEVERITY_NON_LOCALIZED, severity);
			return this;
		}

		public Builder code(String code) {
			fields.put(CODE, code);
			return this;
		}

		public Builder message(String message) {
			fields.put(MESSAGE, message);
			return this;
		}

		public Builder detail(String detail) {
			if (detail != null) {
				fields.put(DETAIL, detail);
			}
			return this;
		}

		public Builder hint(String hint) {
			if (hint != null) {
				fields.put(HINT, hint);
			}
			return this;
		}

		public Builder position(int position) {
			if (position > 0) {
				fields.put(POSITION, String.valueOf(position));
			}
			return this;
		}

		public ErrorResponse build() {
			return new ErrorResponse(fields);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates an error response from an exception.
	 */
	public static ErrorResponse fromException(Throwable t) {
		// Find the root cause for better error messages
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		// Build the primary message - prefer root cause if it has more detail
		String message = t.getMessage();
		String rootMessage = root.getMessage();
		if (message == null || message.isBlank()) {
			message = t.getClass().getSimpleName();
		}

		Builder builder = builder()
			.severity("ERROR")
			.message(message);

		// Add root cause as detail if different from main message
		if (root != t && rootMessage != null && !rootMessage.equals(message)) {
			builder.detail("Caused by: " + root.getClass().getSimpleName() + ": " + rootMessage);
		}

		// Set SQL state code
		if (t instanceof SQLException sqlEx) {
			String sqlState = sqlEx.getSQLState();
			builder.code(sqlState != null ? sqlState : "42000");
		} else {
			builder.code("XX000"); // Internal error
		}

		return builder.build();
	}

	/**
	 * Creates a syntax error response.
	 */
	public static ErrorResponse syntaxError(String message, int position) {
		return builder()
			.severity("ERROR")
			.code("42601") // syntax_error
			.message(message)
			.position(position)
			.build();
	}

	/**
	 * Creates an undefined table error.
	 */
	public static ErrorResponse undefinedTable(String tableName) {
		return builder()
			.severity("ERROR")
			.code("42P01") // undefined_table
			.message("relation \"" + tableName + "\" does not exist")
			.build();
	}

	/**
	 * Creates an authentication failure error.
	 */
	public static ErrorResponse authenticationFailed(String user) {
		return builder()
			.severity("FATAL")
			.code("28P01") // invalid_password
			.message("password authentication failed for user \"" + user + "\"")
			.build();
	}
}
