package convex.db.psql.msg;

import convex.db.psql.PgMessage;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * ParameterStatus message - reports a runtime parameter value.
 */
public class ParameterStatus extends PgMessage {

	private final String name;
	private final String value;

	public ParameterStatus(String name, String value) {
		this.name = name;
		this.value = value;
	}

	@Override
	public byte getType() {
		return PARAMETER_STATUS;
	}

	@Override
	public void write(ByteBuf buf) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
		int length = 4 + nameBytes.length + 1 + valueBytes.length + 1;

		buf.writeByte(PARAMETER_STATUS);
		buf.writeInt(length);
		buf.writeBytes(nameBytes);
		buf.writeByte(0);
		buf.writeBytes(valueBytes);
		buf.writeByte(0);
	}

	// Common parameter status messages
	public static ParameterStatus serverVersion(String version) {
		return new ParameterStatus("server_version", version);
	}

	public static ParameterStatus clientEncoding(String encoding) {
		return new ParameterStatus("client_encoding", encoding);
	}

	public static ParameterStatus serverEncoding(String encoding) {
		return new ParameterStatus("server_encoding", encoding);
	}

	public static ParameterStatus dateStyle(String style) {
		return new ParameterStatus("DateStyle", style);
	}

	public static ParameterStatus timeZone(String tz) {
		return new ParameterStatus("TimeZone", tz);
	}

	public static ParameterStatus integerDatetimes(boolean enabled) {
		return new ParameterStatus("integer_datetimes", enabled ? "on" : "off");
	}
}
