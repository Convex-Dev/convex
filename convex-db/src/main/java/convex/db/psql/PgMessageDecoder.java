package convex.db.psql;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes PostgreSQL wire protocol messages from the client.
 */
public class PgMessageDecoder extends ByteToMessageDecoder {

	private boolean startupComplete = false;

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		if (!startupComplete) {
			decodeStartup(in, out);
		} else {
			decodeRegular(in, out);
		}
	}

	/**
	 * Decodes the startup message (no message type byte).
	 */
	private void decodeStartup(ByteBuf in, List<Object> out) {
		if (in.readableBytes() < 4) {
			return; // Wait for length
		}

		in.markReaderIndex();
		int length = in.readInt();

		if (in.readableBytes() < length - 4) {
			in.resetReaderIndex();
			return; // Wait for full message
		}

		int version = in.readInt();

		if (version == 80877103) {
			// SSLRequest - we don't support SSL, respond with 'N'
			out.add(new SSLRequest());
			return;
		}

		if (version == 80877102) {
			// CancelRequest
			int pid = in.readInt();
			int key = in.readInt();
			out.add(new CancelRequest(pid, key));
			return;
		}

		// Regular startup message
		int majorVersion = version >> 16;
		int minorVersion = version & 0xFFFF;

		Map<String, String> params = new HashMap<>();
		while (in.isReadable()) {
			String key = readCString(in);
			if (key.isEmpty()) {
				break;
			}
			String value = readCString(in);
			params.put(key, value);
		}

		startupComplete = true;
		out.add(new StartupMessage(majorVersion, minorVersion, params));
	}

	/**
	 * Decodes regular messages (with type byte).
	 */
	private void decodeRegular(ByteBuf in, List<Object> out) {
		if (in.readableBytes() < 5) {
			return; // Wait for type + length
		}

		in.markReaderIndex();
		byte type = in.readByte();
		int length = in.readInt();

		if (in.readableBytes() < length - 4) {
			in.resetReaderIndex();
			return; // Wait for full message
		}

		switch (type) {
			case PgMessage.QUERY -> {
				String query = readCString(in);
				out.add(new Query(query));
			}
			case PgMessage.PARSE -> {
				String name = readCString(in);
				String query = readCString(in);
				short paramCount = in.readShort();
				int[] paramTypes = new int[paramCount];
				for (int i = 0; i < paramCount; i++) {
					paramTypes[i] = in.readInt();
				}
				out.add(new Parse(name, query, paramTypes));
			}
			case PgMessage.BIND -> {
				String portal = readCString(in);
				String statement = readCString(in);

				// Parameter format codes
				short numParamFormats = in.readShort();
				short[] paramFormats = new short[numParamFormats];
				for (int i = 0; i < numParamFormats; i++) {
					paramFormats[i] = in.readShort();
				}

				// Parameter values
				short numParams = in.readShort();
				byte[][] paramValues = new byte[numParams][];
				for (int i = 0; i < numParams; i++) {
					int paramLen = in.readInt();
					if (paramLen == -1) {
						paramValues[i] = null; // NULL
					} else {
						paramValues[i] = new byte[paramLen];
						in.readBytes(paramValues[i]);
					}
				}

				// Result format codes
				short numResultFormats = in.readShort();
				short[] resultFormats = new short[numResultFormats];
				for (int i = 0; i < numResultFormats; i++) {
					resultFormats[i] = in.readShort();
				}

				out.add(new Bind(portal, statement, paramFormats, paramValues, resultFormats));
			}
			case PgMessage.DESCRIBE -> {
				byte descType = in.readByte();
				String name = readCString(in);
				out.add(new Describe(descType, name));
			}
			case PgMessage.EXECUTE -> {
				String portal = readCString(in);
				int maxRows = in.readInt();
				out.add(new Execute(portal, maxRows));
			}
			case PgMessage.SYNC -> out.add(Sync.INSTANCE);
			case PgMessage.FLUSH -> out.add(Flush.INSTANCE);
			case PgMessage.TERMINATE -> out.add(Terminate.INSTANCE);
			case PgMessage.CLOSE -> {
				byte closeType = in.readByte();
				String name = readCString(in);
				out.add(new Close(closeType, name));
			}
			case PgMessage.PASSWORD -> {
				String password = readCString(in);
				out.add(new PasswordMessage(password));
			}
			default -> {
				// Unknown message type - skip it
				in.skipBytes(length - 4);
			}
		}
	}

	private String readCString(ByteBuf buf) {
		int start = buf.readerIndex();
		int end = start;
		while (buf.getByte(end) != 0) {
			end++;
		}
		byte[] bytes = new byte[end - start];
		buf.readBytes(bytes);
		buf.readByte(); // consume null terminator
		return new String(bytes, StandardCharsets.UTF_8);
	}

	// ========== Frontend Message Classes ==========

	public record StartupMessage(int majorVersion, int minorVersion, Map<String, String> params) {}
	public record SSLRequest() {}
	public record CancelRequest(int processId, int secretKey) {}
	public record Query(String sql) {}
	public record Parse(String name, String query, int[] paramTypes) {}
	public record Bind(String portal, String statement, short[] paramFormats, byte[][] paramValues, short[] resultFormats) {}
	public record Describe(byte type, String name) {}
	public record Execute(String portal, int maxRows) {}
	public enum Sync { INSTANCE }
	public enum Flush { INSTANCE }
	public enum Terminate { INSTANCE }
	public record Close(byte type, String name) {}
	public record PasswordMessage(String password) {}
}
