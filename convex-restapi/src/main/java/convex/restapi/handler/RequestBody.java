package convex.restapi.handler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.server.Request;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.ContentTooLargeResponse;
import io.javalin.http.Context;

/**
 * Reads structured request bodies whole, within a limit the caller gives. A chunked
 * body declares no length, so it is bounded as it is read, the same as one that does.
 * Exceeding the limit is the client's error, answered 413 like any other.
 *
 * <p>For structured messages, which are materialised in memory anyway. A large-file
 * upload endpoint should instead stream to its destination through its own byte and
 * time limits.</p>
 *
 * <p>Also discards what a handler left unread, within a limit, so that its response can
 * be delivered.</p>
 */
public final class RequestBody {

	private static final int CHUNK_SIZE=8192;

	private RequestBody() {
	}

	/**
	 * Reads a request body whole. A body an earlier handler has already read through the
	 * context, as {@link Context#body()} does, is taken from the context's copy of it.
	 *
	 * @param ctx Request context
	 * @param maxBytes Most bytes the body may have
	 * @return The body
	 * @throws ContentTooLargeResponse if the body has more than maxBytes bytes
	 * @throws BadRequestResponse if the body cannot be read
	 */
	public static byte[] readBytes(Context ctx, long maxBytes) {
		// Javalin's body() and bodyAsBytes() spend the stream and keep a copy, which only
		// bodyAsBytes() returns from then on
		if (contentBytesRead(ctx)>0) {
			byte[] read=ctx.bodyAsBytes();
			if (read.length>maxBytes) throw tooLarge(maxBytes);
			return read;
		}
		InputStream in=ctx.bodyInputStream();
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		byte[] chunk=new byte[CHUNK_SIZE];
		long total=0;
		try {
			int n;
			while ((n=in.read(chunk))>=0) {
				total+=n;
				if (total>maxBytes) throw tooLarge(maxBytes);
				out.write(chunk,0,n);
			}
		} catch (IOException e) {
			throw new BadRequestResponse("Unable to read request body");
		}
		return out.toByteArray();
	}

	private static ContentTooLargeResponse tooLarge(long maxBytes) {
		return new ContentTooLargeResponse("Request body exceeds maximum size of "+maxBytes+" bytes");
	}

	/** Bytes of the request body read from the connection so far, by any reader. */
	private static long contentBytesRead(Context ctx) {
		return Request.getContentBytesRead(ServletContextRequest.getServletContextRequest(ctx.req()));
	}

	/**
	 * Reads a request body whole and gives a stream over it.
	 *
	 * @param ctx Request context
	 * @param maxBytes Most bytes the body may have
	 * @return Stream over the body
	 * @throws ContentTooLargeResponse if the body has more than maxBytes bytes
	 * @throws BadRequestResponse if the body cannot be read
	 */
	public static InputStream boundedInputStream(Context ctx, long maxBytes) {
		return new ByteArrayInputStream(readBytes(ctx,maxBytes));
	}

	/**
	 * Reads and discards the rest of a request body, up to a limit on the whole body.
	 * Reads until the body ends or until maxBytes bytes of it, counting those already read,
	 * have been read. Does nothing for a request that offered {@code Expect: 100-continue}.
	 * An I/O failure ends the read and is not thrown.
	 *
	 * @param ctx Request context
	 * @param maxBytes Most bytes of the body to have read in all
	 */
	public static void discardRest(Context ctx, long maxBytes) {
		// Such a client sends its body only when invited, and reading would invite it
		String expect=ctx.header("Expect");
		if ((expect!=null)&&expect.toLowerCase(Locale.ROOT).contains("100-continue")) return;

		long remaining=maxBytes-Math.max(0,contentBytesRead(ctx));
		try {
			InputStream in=ctx.req().getInputStream();
			byte[] chunk=new byte[CHUNK_SIZE];
			while (remaining>0) {
				int n=in.read(chunk,0,(int)Math.min(chunk.length,remaining));
				if (n<0) return;
				remaining-=n;
			}
		} catch (IOException e) {
			// The client has gone, so there is no response left to deliver
		}
	}
}
