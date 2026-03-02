package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import convex.db.psql.PgType;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * RowDescription message - describes the columns of a query result.
 */
public class RowDescription extends PgMessage {

	private final Column[] columns;

	public RowDescription(Column[] columns) {
		this.columns = columns;
	}

	/**
	 * Creates a RowDescription from JDBC ResultSetMetaData.
	 */
	public static RowDescription fromMetaData(ResultSetMetaData meta) throws SQLException {
		int count = meta.getColumnCount();
		Column[] columns = new Column[count];
		for (int i = 0; i < count; i++) {
			int colIndex = i + 1;
			String name = meta.getColumnLabel(colIndex);
			if (name == null || name.isEmpty()) {
				name = meta.getColumnName(colIndex);
			}
			String typeName = meta.getColumnTypeName(colIndex);
			int typeOid = PgType.fromSqlType(typeName);
			int typeLen = PgType.typeLength(typeOid);
			int precision = meta.getPrecision(colIndex);

			columns[i] = new Column(name, 0, 0, typeOid, (short) typeLen, precision, 0);
		}
		return new RowDescription(columns);
	}

	@Override
	public byte getType() {
		return ROW_DESCRIPTION;
	}

	@Override
	public void write(ByteBuf buf) {
		// Calculate total length
		int length = 4 + 2; // length field + column count
		for (Column col : columns) {
			length += col.encodedLength();
		}

		buf.writeByte(ROW_DESCRIPTION);
		buf.writeInt(length);
		buf.writeShort(columns.length);

		for (Column col : columns) {
			col.write(buf);
		}
	}

	public int getColumnCount() {
		return columns.length;
	}

	public Column getColumn(int index) {
		return columns[index];
	}

	/**
	 * Describes a single column in a row.
	 */
	public static class Column {
		public final String name;
		public final int tableOid;
		public final short columnAttr;
		public final int typeOid;
		public final short typeLen;
		public final int typeMod;
		public final short formatCode; // 0 = text, 1 = binary

		public Column(String name, int tableOid, int columnAttr, int typeOid,
				short typeLen, int typeMod, int formatCode) {
			this.name = name;
			this.tableOid = tableOid;
			this.columnAttr = (short) columnAttr;
			this.typeOid = typeOid;
			this.typeLen = typeLen;
			this.typeMod = typeMod;
			this.formatCode = (short) formatCode;
		}

		int encodedLength() {
			return name.getBytes(StandardCharsets.UTF_8).length + 1 + 18;
		}

		void write(ByteBuf buf) {
			writeCString(buf, name);
			buf.writeInt(tableOid);
			buf.writeShort(columnAttr);
			buf.writeInt(typeOid);
			buf.writeShort(typeLen);
			buf.writeInt(typeMod);
			buf.writeShort(formatCode);
		}
	}
}
