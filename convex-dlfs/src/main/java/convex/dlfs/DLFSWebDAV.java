package convex.dlfs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import convex.restapi.auth.AuthMiddleware;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * WebDAV-compatible HTTP handler backed by a Java NIO {@link FileSystem}.
 *
 * <p>Registers routes on a Javalin app for standard WebDAV methods: GET, PUT,
 * DELETE, HEAD, OPTIONS, PROPFIND, and MKCOL. The handler uses only the
 * standard {@code java.nio.file} API, so it works with any compliant
 * {@link FileSystem} implementation (including DLFS).
 */
public class DLFSWebDAV {

	private static final String ROUTE = "/dlfs/";
	private static final String ROUTE_BARE = "/dlfs";
	private static final String ROUTE_PATH = ROUTE + "<path>";

	private final FileSystem fs;
	private final Path root;
	private boolean requireAuthForWrites = false;

	public DLFSWebDAV(FileSystem fs) {
		this.fs = fs;
		this.root = fs.getRootDirectories().iterator().next();
	}

	public FileSystem getFileSystem() {
		return fs;
	}

	/**
	 * Sets whether mutating operations (PUT, DELETE, MKCOL) require authentication.
	 * When enabled, these operations return 401 if no valid bearer token is present.
	 *
	 * @param require true to require auth for writes
	 * @return this (for chaining)
	 */
	public DLFSWebDAV setRequireAuthForWrites(boolean require) {
		this.requireAuthForWrites = require;
		return this;
	}

	/**
	 * Registers WebDAV routes on the given Javalin app.
	 */
	public void addRoutes(Javalin app) {
		app.get(ROUTE_PATH, this::handleGet);
		app.get(ROUTE, this::handleGet);
		app.put(ROUTE_PATH, this::handlePut);
		app.delete(ROUTE_PATH, this::handleDelete);
		app.head(ROUTE_PATH, this::handleHead);
		app.head(ROUTE, this::handleHead);
		app.head(ROUTE_BARE, this::handleHead);
		app.options(ROUTE_PATH, this::handleOptions);
		app.options(ROUTE, this::handleOptions);
		app.options(ROUTE_BARE, this::handleOptions);

		// Custom WebDAV methods — Javalin has no built-in handler type for these
		app.before(ctx -> {
			String method = ctx.req().getMethod();
			String uri = ctx.req().getRequestURI();
			if (!uri.startsWith(ROUTE_BARE)) return;

			if ("PROPFIND".equals(method)) {
				handlePropfind(ctx);
				ctx.skipRemainingHandlers();
			} else if ("MKCOL".equals(method)) {
				handleMkcol(ctx);
				ctx.skipRemainingHandlers();
			} else if ("MOVE".equals(method)) {
				handleMove(ctx);
				ctx.skipRemainingHandlers();
			} else if ("COPY".equals(method)) {
				handleCopy(ctx);
				ctx.skipRemainingHandlers();
			} else if ("PROPPATCH".equals(method)) {
				handleProppatch(ctx);
				ctx.skipRemainingHandlers();
			} else if ("LOCK".equals(method)) {
				handleLock(ctx);
				ctx.skipRemainingHandlers();
			} else if ("UNLOCK".equals(method)) {
				ctx.status(204);
				ctx.skipRemainingHandlers();
			}
		});
	}

	// ==================== Handlers ====================

	/**
	 * Checks write authentication. Returns true if the request should be rejected.
	 */
	private boolean rejectUnauthenticatedWrite(Context ctx) {
		if (!requireAuthForWrites) return false;
		if (AuthMiddleware.getIdentity(ctx) != null) return false;
		ctx.status(401).result("Authentication required");
		return true;
	}

	void handleGet(Context ctx) throws IOException {
		Path path = resolvePath(ctx);

		BasicFileAttributes attrs = readAttributesSafe(path);
		if (attrs == null) {
			ctx.status(404).result("Not Found");
			return;
		}

		if (attrs.isDirectory()) {
			ctx.status(200);
			ctx.contentType("text/plain; charset=utf-8");
			ctx.result("Directory: " + path);
			return;
		}

		if (attrs.isRegularFile()) {
			byte[] bytes = Files.readAllBytes(path);
			ctx.contentType(guessContentType(path.toString()));
			ctx.header("Content-Length", String.valueOf(bytes.length));
			setLastModified(ctx, attrs);
			ctx.result(bytes);
			return;
		}

		ctx.status(404).result("Not Found");
	}

	void handlePut(Context ctx) throws IOException {
		if (rejectUnauthenticatedWrite(ctx)) return;
		Path path = resolvePath(ctx);
		byte[] body = ctx.bodyAsBytes();

		// Ensure parent directory exists
		Path parent = path.getParent();
		if (parent != null && !Files.isDirectory(parent)) {
			ctx.status(409).result("Conflict: parent directory does not exist");
			return;
		}

		boolean isNew = !Files.exists(path);

		Files.write(path, body,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);

		ctx.status(isNew ? 201 : 204);
	}

	void handleDelete(Context ctx) {
		if (rejectUnauthenticatedWrite(ctx)) return;
		Path path = resolvePath(ctx);
		try {
			Files.delete(path);
			ctx.status(204);
		} catch (NoSuchFileException e) {
			ctx.status(404).result("Not Found");
		} catch (IOException e) {
			ctx.status(409).result("Conflict: " + e.getMessage());
		}
	}

	void handleHead(Context ctx) throws IOException {
		Path path = resolvePath(ctx);
		BasicFileAttributes attrs = readAttributesSafe(path);
		if (attrs == null) {
			ctx.status(404);
			return;
		}

		if (attrs.isRegularFile()) {
			ctx.contentType(guessContentType(path.toString()));
			ctx.header("Content-Length", String.valueOf(attrs.size()));
			setLastModified(ctx, attrs);
		}

		ctx.status(200);
	}

	void handleOptions(Context ctx) {
		ctx.header("DAV", "1, 2");
		ctx.header("MS-Author-Via", "DAV");
		ctx.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY, LOCK, UNLOCK");
		ctx.status(200);
	}

	void handlePropfind(Context ctx) throws IOException {
		Path path = resolvePath(ctx);
		BasicFileAttributes attrs = readAttributesSafe(path);
		if (attrs == null) {
			ctx.status(404).result("Not Found");
			return;
		}

		String depthHeader = ctx.header("Depth");
		int depth = "0".equals(depthHeader) ? 0 : 1;

		// Collect children if depth >= 1 and this is a directory
		List<Path> children = new ArrayList<>();
		if (depth >= 1 && attrs.isDirectory()) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
				for (Path child : stream) {
					children.add(child);
				}
			}
		}

		String xml = PropfindResponse.build(path, attrs, children);
		ctx.status(207);
		ctx.contentType("application/xml; charset=utf-8");
		ctx.result(xml);
	}

	void handleMkcol(Context ctx) {
		if (rejectUnauthenticatedWrite(ctx)) return;
		Path path = resolvePath(ctx);
		try {
			Files.createDirectory(path);
			ctx.status(201);
		} catch (FileAlreadyExistsException e) {
			ctx.status(405).result("Method Not Allowed: resource already exists");
		} catch (NoSuchFileException e) {
			ctx.status(409).result("Conflict: parent directory does not exist");
		} catch (IOException e) {
			ctx.status(409).result("Conflict: " + e.getMessage());
		}
	}

	void handleMove(Context ctx) throws IOException {
		if (rejectUnauthenticatedWrite(ctx)) return;
		Path source = resolvePath(ctx);
		Path dest = resolveDestination(ctx);
		if (dest == null) {
			ctx.status(400).result("Bad Request: missing or invalid Destination header");
			return;
		}

		if (!Files.exists(source)) {
			ctx.status(404).result("Not Found");
			return;
		}

		boolean overwrite = !"F".equals(ctx.header("Overwrite"));
		boolean destExists = Files.exists(dest);
		if (destExists && !overwrite) {
			ctx.status(412).result("Precondition Failed: destination exists");
			return;
		}

		// Copy content then delete source
		byte[] data = Files.readAllBytes(source);
		Files.write(dest, data,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
		Files.delete(source);

		ctx.status(destExists ? 204 : 201);
	}

	void handleCopy(Context ctx) throws IOException {
		if (rejectUnauthenticatedWrite(ctx)) return;
		Path source = resolvePath(ctx);
		Path dest = resolveDestination(ctx);
		if (dest == null) {
			ctx.status(400).result("Bad Request: missing or invalid Destination header");
			return;
		}

		if (!Files.exists(source)) {
			ctx.status(404).result("Not Found");
			return;
		}

		boolean overwrite = !"F".equals(ctx.header("Overwrite"));
		boolean destExists = Files.exists(dest);
		if (destExists && !overwrite) {
			ctx.status(412).result("Precondition Failed: destination exists");
			return;
		}

		byte[] data = Files.readAllBytes(source);
		Files.write(dest, data,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);

		ctx.status(destExists ? 204 : 201);
	}

	void handleProppatch(Context ctx) throws IOException {
		Path path = resolvePath(ctx);
		if (!Files.exists(path)) {
			ctx.status(404).result("Not Found");
			return;
		}

		// Accept all property changes — return 200 OK for each property
		String href = "/dlfs/" + path.toString().replaceFirst("^/", "");
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<D:multistatus xmlns:D=\"DAV:\">"
				+ "<D:response>"
				+ "<D:href>" + href + "</D:href>"
				+ "<D:propstat>"
				+ "<D:prop/>"
				+ "<D:status>HTTP/1.1 200 OK</D:status>"
				+ "</D:propstat>"
				+ "</D:response>"
				+ "</D:multistatus>";
		ctx.status(207);
		ctx.contentType("application/xml; charset=utf-8");
		ctx.result(xml);
	}

	void handleLock(Context ctx) {
		// Minimal LOCK response — returns a lock token so clients can proceed
		String token = "opaquelocktoken:" + java.util.UUID.randomUUID();
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<D:prop xmlns:D=\"DAV:\">"
				+ "<D:lockdiscovery><D:activelock>"
				+ "<D:locktype><D:write/></D:locktype>"
				+ "<D:lockscope><D:exclusive/></D:lockscope>"
				+ "<D:depth>0</D:depth>"
				+ "<D:timeout>Second-3600</D:timeout>"
				+ "<D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
				+ "</D:activelock></D:lockdiscovery>"
				+ "</D:prop>";
		ctx.status(200);
		ctx.contentType("application/xml; charset=utf-8");
		ctx.header("Lock-Token", "<" + token + ">");
		ctx.result(xml);
	}

	// ==================== Utilities ====================

	/**
	 * Resolves the Destination header to a filesystem path.
	 */
	private Path resolveDestination(Context ctx) {
		String destHeader = ctx.header("Destination");
		if (destHeader == null) return null;
		try {
			// Destination is a full URL — extract the path portion
			java.net.URI destURI = java.net.URI.create(destHeader);
			String destPath = destURI.getPath();
			if (destPath == null) return null;

			// Strip the /dlfs/ prefix
			if (destPath.startsWith(ROUTE)) {
				destPath = destPath.substring(ROUTE.length());
			} else if (destPath.startsWith(ROUTE_BARE)) {
				destPath = destPath.substring(ROUTE_BARE.length());
			} else {
				return null;
			}

			if (destPath.endsWith("/")) destPath = destPath.substring(0, destPath.length() - 1);
			if (destPath.isEmpty()) return root;
			return fs.getPath("/" + destPath);
		} catch (Exception e) {
			return null;
		}
	}


	Path resolvePath(Context ctx) {
		// Try Javalin path param first (works for standard routes — already URL-decoded)
		String pathParam;
		try {
			pathParam = ctx.pathParam("path");
		} catch (Exception e) {
			pathParam = null;
		}

		// Fall back to extracting from URI (needed for custom methods in before handler)
		if (pathParam == null || pathParam.isEmpty()) {
			String uri = ctx.req().getRequestURI();
			if (uri.startsWith(ROUTE) && uri.length() > ROUTE.length()) {
				pathParam = uri.substring(ROUTE.length());
			} else if (uri.equals(ROUTE_BARE)) {
				return root;
			}
			if (pathParam != null) {
				// URL-decode (getRequestURI returns raw/encoded form)
				pathParam = java.net.URLDecoder.decode(pathParam, java.nio.charset.StandardCharsets.UTF_8);
				if (pathParam.endsWith("/")) {
					pathParam = pathParam.substring(0, pathParam.length() - 1);
				}
			}
		}

		if (pathParam == null || pathParam.isEmpty()) {
			return root;
		}
		return fs.getPath("/" + pathParam);
	}

	/**
	 * Reads basic file attributes, returning null if the path does not exist
	 * or is not a regular file/directory.
	 */
	private static BasicFileAttributes readAttributesSafe(Path path) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
			if (!attrs.isDirectory() && !attrs.isRegularFile()) return null;
			return attrs;
		} catch (IOException e) {
			return null;
		}
	}

	private static void setLastModified(Context ctx, BasicFileAttributes attrs) {
		FileTime ft = attrs.lastModifiedTime();
		if (ft != null && ft.toMillis() > 0) {
			ctx.header("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME
					.format(ft.toInstant().atZone(ZoneOffset.UTC)));
		}
	}

	static String guessContentType(String path) {
		if (path == null) return "application/octet-stream";
		String lower = path.toLowerCase();
		if (lower.endsWith(".txt")) return "text/plain";
		if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
		if (lower.endsWith(".json")) return "application/json";
		if (lower.endsWith(".xml")) return "application/xml";
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".gif")) return "image/gif";
		if (lower.endsWith(".css")) return "text/css";
		if (lower.endsWith(".js")) return "application/javascript";
		if (lower.endsWith(".pdf")) return "application/pdf";
		return "application/octet-stream";
	}
}
