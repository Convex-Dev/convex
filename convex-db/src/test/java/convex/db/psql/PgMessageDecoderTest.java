package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;

/**
 * Unit tests for {@link PgMessageDecoder}, exercising malformed / hostile frames
 * against the raw decoder via a Netty {@link EmbeddedChannel}.
 *
 * <p>These cover pre-authentication denial-of-service and crash inputs: the decoder
 * runs on untrusted bytes before any authentication step, so it must reject
 * malformed length/count fields with a {@link CorruptedFrameException} rather than
 * buffering unbounded memory or throwing {@code NegativeArraySizeException} /
 * {@code IndexOutOfBoundsException}.
 */
public class PgMessageDecoderTest {

	private static final int PROTOCOL_3_0 = (3 << 16); // major 3, minor 0

	/** Writes a NUL-terminated C-string. */
	private static void putCString(ByteBuf buf, String s) {
		buf.writeBytes(s.getBytes(StandardCharsets.UTF_8));
		buf.writeByte(0);
	}

	/** Builds a well-formed startup message frame (length prefix + version + params). */
	private static ByteBuf startupFrame() {
		ByteBuf body = Unpooled.buffer();
		body.writeInt(PROTOCOL_3_0);
		putCString(body, "user");
		putCString(body, "test");
		body.writeByte(0); // empty key terminates parameter list

		ByteBuf frame = Unpooled.buffer();
		frame.writeInt(body.readableBytes() + 4); // length includes the length field
		frame.writeBytes(body);
		return frame;
	}

	/** Feeds a valid startup so the decoder flips into regular-message mode. */
	private static EmbeddedChannel authenticatedChannel() {
		EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
		assertTrue(ch.writeInbound(startupFrame()));
		Object msg = ch.readInbound();
		assertInstanceOf(PgMessageDecoder.StartupMessage.class, msg);
		return ch;
	}

	private static void assertRejected(EmbeddedChannel ch, ByteBuf frame) {
		Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(frame));
		// CorruptedFrameException is a DecoderException; ByteToMessageDecoder rethrows it as-is.
		boolean found = false;
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof CorruptedFrameException) { found = true; break; }
		}
		assertTrue(found, "Expected CorruptedFrameException, got: " + t);
	}

	@Test
	public void testOversizedStartupLength() {
		EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
		ByteBuf frame = Unpooled.buffer();
		frame.writeInt(Integer.MAX_VALUE); // absurd length, must be rejected before buffering
		frame.writeInt(PROTOCOL_3_0);
		assertRejected(ch, frame);
	}

	@Test
	public void testTooSmallStartupLength() {
		EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
		ByteBuf frame = Unpooled.buffer();
		frame.writeInt(7); // below the 8-byte minimum
		frame.writeInt(PROTOCOL_3_0);
		assertRejected(ch, frame);
	}

	@Test
	public void testOversizedRegularLength() {
		EmbeddedChannel ch = authenticatedChannel();
		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.QUERY);
		frame.writeInt(Integer.MAX_VALUE);
		assertRejected(ch, frame);
	}

	@Test
	public void testTooSmallRegularLength() {
		EmbeddedChannel ch = authenticatedChannel();
		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.QUERY);
		frame.writeInt(3); // below the 4-byte minimum
		frame.writeByte('x');
		assertRejected(ch, frame);
	}

	@Test
	public void testBindNegativeNumParams() {
		EmbeddedChannel ch = authenticatedChannel();
		ByteBuf body = Unpooled.buffer();
		putCString(body, "");      // portal
		putCString(body, "");      // statement
		body.writeShort(0);        // numParamFormats
		body.writeShort(-1);       // numParams  <-- hostile

		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.BIND);
		frame.writeInt(body.readableBytes() + 4);
		frame.writeBytes(body);
		assertRejected(ch, frame);
	}

	@Test
	public void testBindNegativeParamLen() {
		EmbeddedChannel ch = authenticatedChannel();
		ByteBuf body = Unpooled.buffer();
		putCString(body, "");      // portal
		putCString(body, "");      // statement
		body.writeShort(0);        // numParamFormats
		body.writeShort(1);        // numParams
		body.writeInt(-2);         // paramLen (-1 is NULL sentinel; -2 is hostile)

		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.BIND);
		frame.writeInt(body.readableBytes() + 4);
		frame.writeBytes(body);
		assertRejected(ch, frame);
	}

	@Test
	public void testParseNegativeParamCount() {
		EmbeddedChannel ch = authenticatedChannel();
		ByteBuf body = Unpooled.buffer();
		putCString(body, "");      // statement name
		putCString(body, "");      // query
		body.writeShort(-1);       // paramCount <-- hostile

		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.PARSE);
		frame.writeInt(body.readableBytes() + 4);
		frame.writeBytes(body);
		assertRejected(ch, frame);
	}

	@Test
	public void testUnterminatedCString() {
		EmbeddedChannel ch = authenticatedChannel();
		byte[] noNul = "SELECT 1".getBytes(StandardCharsets.UTF_8);
		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.QUERY);
		frame.writeInt(noNul.length + 4); // valid length, but no NUL terminator in body
		frame.writeBytes(noNul);
		assertRejected(ch, frame);
	}

	@Test
	public void testWellFormedStartupAndQueryStillDecode() {
		EmbeddedChannel ch = authenticatedChannel(); // asserts StartupMessage decodes

		byte[] sql = "SELECT 1".getBytes(StandardCharsets.UTF_8);
		ByteBuf frame = Unpooled.buffer();
		frame.writeByte(PgMessage.QUERY);
		frame.writeInt(sql.length + 1 + 4); // body = sql + NUL, plus length field
		frame.writeBytes(sql);
		frame.writeByte(0);

		assertTrue(ch.writeInbound(frame));
		Object msg = ch.readInbound();
		PgMessageDecoder.Query q = assertInstanceOf(PgMessageDecoder.Query.class, msg);
		assertEquals("SELECT 1", q.sql());
	}
}
