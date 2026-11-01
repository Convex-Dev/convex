package convex.restapi.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Sends stored content as the response to a GET or HEAD, with an entity tag and
 * single byte ranges (RFC 9110 sections 13 and 14).
 *
 * <p>The body streams from a {@link SeekableByteChannel}, such as a file channel
 * or a {@code BlobChannel}, so content of any size is sent in bounded memory.
 * The response is never compressed, so its length and ranges refer to the
 * stored bytes.</p>
 */
public final class ContentResponse {

	private ContentResponse() {
	}

	/**
	 * Sends content from a channel. Representation headers other than the
	 * content type, such as Last-Modified, are the caller's to set first.
	 *
	 * <ul>
	 * <li>If-None-Match naming the entity tag answers 304.</li>
	 * <li>On GET, a single byte range answers 206, and a range wholly beyond the
	 * content answers 416. A range under If-Range applies only when If-Range
	 * equals a strong entity tag. Anything else the server may ignore, such as
	 * several ranges, gets the whole content.</li>
	 * <li>HEAD gets the headers for the whole content, without a body.</li>
	 * </ul>
	 *
	 * <p>Takes ownership of the channel: it is closed once the body is written,
	 * or before returning when there is no body.</p>
	 *
	 * @param ctx Request context
	 * @param etag Entity tag of the content, with its quotes
	 * @param contentType Content type, or null to leave it unset
	 * @param content Channel over the whole content
	 * @throws IOException If the channel cannot be read
	 */
	public static void send(Context ctx, String etag, String contentType, SeekableByteChannel content) throws IOException {
		boolean streaming = false;
		try {
			Objects.requireNonNull(etag);
			ctx.header("ETag", etag);
			if (anyMatches(ctx.header("If-None-Match"), etag)) {
				ctx.status(304);
				return;
			}

			long size = content.size();
			if (contentType != null) ctx.contentType(contentType);
			ctx.header("Accept-Ranges", "bytes");
			ctx.disableCompression();

			ByteRange range = HandlerType.GET.equals(ctx.method()) ? requestedRange(ctx, etag, size) : null;
			if (range == ByteRange.UNSATISFIABLE) {
				ctx.header("Content-Range", "bytes */" + size);
				ctx.status(416);
				return;
			}
			long start = 0;
			long count = size;
			if (range != null) {
				start = range.first();
				count = range.last() - start + 1;
				ctx.header("Content-Range", range.contentRange(size));
			}
			ctx.header("Content-Length", String.valueOf(count));
			ctx.status((range == null) ? 200 : 206);
			if (HandlerType.HEAD.equals(ctx.method())) return;

			content.position(start);
			ctx.result(new RangeStream(content, count));
			streaming = true;
		} finally {
			if (!streaming) content.close();
		}
	}

	/**
	 * Gets the byte range a GET asks for. If-Range must equal the entity tag by
	 * strong comparison; a weak, stale or date If-Range gets the whole content.
	 * @return Range to serve, ByteRange.UNSATISFIABLE, or null for the whole content
	 */
	private static ByteRange requestedRange(Context ctx, String etag, long size) {
		String ifRange = ctx.header("If-Range");
		if ((ifRange != null) && (isWeak(etag) || !ifRange.strip().equals(etag))) return null;
		return ByteRange.parse(ctx.header("Range"), size);
	}

	/**
	 * Tests an If-None-Match list by weak comparison: an entry matches when it
	 * is "*" or names the same tag, weak or not.
	 */
	private static boolean anyMatches(String header, String etag) {
		if (header == null) return false;
		String tag = opaqueTag(etag);
		for (String token : header.split(",")) {
			String candidate = token.strip();
			if (candidate.equals("*") || opaqueTag(candidate).equals(tag)) return true;
		}
		return false;
	}

	private static boolean isWeak(String etag) {
		return etag.startsWith("W/");
	}

	private static String opaqueTag(String etag) {
		return isWeak(etag) ? etag.substring(2) : etag;
	}

	/**
	 * Stream over a count of bytes from a channel's position. Closing it closes
	 * the channel.
	 */
	private static final class RangeStream extends InputStream {
		private final SeekableByteChannel channel;
		private long remaining;

		private RangeStream(SeekableByteChannel channel, long count) {
			this.channel = channel;
			this.remaining = count;
		}

		@Override
		public int read() throws IOException {
			byte[] b = new byte[1];
			return (read(b, 0, 1) <= 0) ? -1 : (b[0] & 0xFF);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			Objects.checkFromIndexSize(off, len, b.length);
			if (len == 0) return 0;
			if (remaining <= 0) return -1;
			int n = channel.read(ByteBuffer.wrap(b, off, (int) Math.min(len, remaining)));
			if (n > 0) remaining -= n;
			return n;
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	}
}
