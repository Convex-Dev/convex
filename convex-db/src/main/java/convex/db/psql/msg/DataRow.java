package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DataRow message - contains the values of a single row.
 */
public class DataRow extends PgMessage {

	private final byte[][] values;

	public DataRow(byte[][] values) {
		this.values = values;
	}

	/**
	 * Creates a DataRow from a JDBC ResultSet (current row).
	 */
	public static DataRow fromResultSet(ResultSet rs, int columnCount) throws SQLException {
		byte[][] values = new byte[columnCount][];
		for (int i = 0; i < columnCount; i++) {
			Object obj = rs.getObject(i + 1);
			if (obj == null) {
				values[i] = null;
			} else {
				values[i] = formatValue(obj);
			}
		}
		return new DataRow(values);
	}

	/**
	 * Formats a value as text for the PostgreSQL protocol.
	 */
	private static byte[] formatValue(Object obj) {
		if (obj == null) {
			return null;
		}

		String text;
		if (obj instanceof Boolean b) {
			text = b ? "t" : "f";
		} else if (obj instanceof byte[] bytes) {
			// Format as hex with \x prefix
			StringBuilder sb = new StringBuilder("\\x");
			for (byte b : bytes) {
				sb.append(String.format("%02x", b & 0xff));
			}
			text = sb.toString();
		} else {
			text = obj.toString();
		}

		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public byte getType() {
		return DATA_ROW;
	}

	@Override
	public void write(ByteBuf buf) {
		// Calculate length
		int length = 4 + 2; // length field + column count
		for (byte[] value : values) {
			length += 4; // length of value (or -1 for null)
			if (value != null) {
				length += value.length;
			}
		}

		buf.writeByte(DATA_ROW);
		buf.writeInt(length);
		buf.writeShort(values.length);

		for (byte[] value : values) {
			if (value == null) {
				buf.writeInt(-1); // NULL
			} else {
				buf.writeInt(value.length);
				buf.writeBytes(value);
			}
		}
	}
}
