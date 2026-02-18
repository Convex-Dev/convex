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
 * WebDAV-compatible HTTP handler with multi-drive support.
 *
 * <p>URL structure:
 * <pre>
 * /dlfs/                              - list drives
 * /dlfs/{drive}/                      - drive root
 * /dlfs/{drive}/path/to/file.txt      - file within drive
 * </pre>
 *
 * <p>Each authenticated user has their own set of named drives, managed by
 * a {@link DLFSDriveManager}. The handler uses only the standard
 * {@code java.nio.file} API, so it works with any compliant
 * {@link FileSystem} implementation.
 */
public class DLFSWebDAV {

	private static final String ROUTE = "/dlfs/";
	private static final String ROUTE_BARE = "/dlfs";
	private static final String ROUTE_PATH = ROUTE + "<path>";

	private final DLFSDriveManager driveManager;
	private boolean requireAuthForWrites = false;

	public DLFSWebDAV(DLFSDriveManager driveManager) {
		this.driveManager = driveManager;
	}

	public DLFSDriveManager getDriveManager() {
		return driveManager;
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

	// ==================== Path Resolution ====================

	/**
	 * Parsed drive path: drive name + path within the drive's filesystem.
	 * If driveName is null, this is the drive listing root.
	 */
	record DrivePath(String driveName, String filePath) {}

	/**
	 * Extracts the drive name and file path from the request URI.
	 *
	 * <pre>
	 * /dlfs/                    → (null, null)        drive listing
	 * /dlfs/personal            → ("personal", "")    drive root
	 * /dlfs/personal/           → ("personal", "")    drive root
	 * /dlfs/personal/docs/f.txt → ("personal", "docs/f.txt")
	 * </pre>
	 */
	DrivePath parseDrivePath(Context ctx) {
		String pathParam = null;

		// Try Javalin path param first (standard routes, already URL-decoded)
		try {
			pathParam = ctx.pathParam("path");
		} catch (Exception e) {
			// not available (custom methods in before handler)
		}

		// Fall back to URI extraction
		if (pathParam == null || pathParam.isEmpty()) {
			String uri = ctx.req().getRequestURI();
			if (uri.startsWith(ROUTE) && uri.length() > ROUTE.length()) {
				pathParam = uri.substring(ROUTE.length());
				pathParam = java.net.URLDecoder.decode(pathParam, java.nio.charset.StandardCharsets.UTF_8);
			} else {
				return new DrivePath(null, null); // drive listing
			}
		}

		if (pathParam == null || pathParam.isEmpty()) {
			return new DrivePath(null, null); // drive listing
		}

		// Strip trailing slash
		if (pathParam.endsWith("/")) {
			pathParam = pathParam.substring(0, pathParam.length() - 1);
		}

		// Split into drive name + remainder
		int slash = pathParam.indexOf('/');
		if (slash < 0) {
			return new DrivePath(pathParam, ""); // drive root
		}
		String driveName = pathParam.substring(0, slash);
		String filePath = pathParam.substring(slash + 1);
		return new DrivePath(driveName, filePath);
	}

	/**
	 * Gets the identity string for the current request (DID from JWT, or null).
	 */
	private String getIdentity(Context ctx) {
		var id = AuthMiddleware.getIdentity(ctx);
		return id != null ? id.toString() : null;
	}

	/**
	 * Resolves a DrivePath to a filesystem Path. Returns null if the drive doesn't exist.
	 */
	private Path resolveFilePath(Context ctx, DrivePath dp) {
		if (dp.driveName() == null) return null;
		FileSystem fs = driveManager.getDrive(getIdentity(ctx), dp.driveName());
		if (fs == null) return null;
		Path root = fs.getRootDirectories().iterator().next();
		if (dp.filePath() == null || dp.filePath().isEmpty()) return root;
		return fs.getPath("/" + dp.filePath());
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
		DrivePath dp = parseDrivePath(ctx);

		// Drive listing
		if (dp.driveName() == null) {
			List<String> drives = driveManager.listDrives(getIdentity(ctx));
			ctx.status(200);
			ctx.contentType("text/plain; charset=utf-8");
			ctx.result("Drives: " + String.join(", ", drives));
			return;
		}

		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(404).result("Not Found: drive '" + dp.driveName() + "' does not exist");
			return;
		}

		BasicFileAttributes attrs = readAttributesSafe(path);
		if (attrs == null) {
			ctx.status(404).result("Not Found");
			return;
		}

		if (attrs.isDirectory()) {
			ctx.status(200);
			ctx.contentType("text/plain; charset=utf-8");
			ctx.result("Directory: " + dp.driveName() + path);
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
		DrivePath dp = parseDrivePath(ctx);

		if (dp.driveName() == null) {
			ctx.status(403).result("Forbidden: cannot create files outside a drive. Create a drive first with MKCOL.");
			return;
		}

		// Reject PUT to drive root (files must have a path within the drive)
		if (dp.filePath() == null || dp.filePath().isEmpty()) {
			ctx.status(403).result("Forbidden: cannot PUT to a drive root. Use MKCOL to create drives, or PUT files inside a drive.");
			return;
		}

		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(404).result("Not Found: drive '" + dp.driveName() + "' does not exist. Create it first with MKCOL /dlfs/" + dp.driveName() + "/");
			return;
		}

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
		DrivePath dp = parseDrivePath(ctx);

		if (dp.driveName() == null) {
			ctx.status(405).result("Method Not Allowed");
			return;
		}

		// Drive-level delete (empty file path)
		if (dp.filePath() == null || dp.filePath().isEmpty()) {
			boolean deleted = driveManager.deleteDrive(getIdentity(ctx), dp.driveName());
			if (deleted) {
				ctx.status(204);
			} else {
				ctx.status(404).result("Not Found");
			}
			return;
		}

		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(404).result("Not Found");
			return;
		}

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
		DrivePath dp = parseDrivePath(ctx);

		if (dp.driveName() == null) {
			ctx.status(200);
			return;
		}

		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(404);
			return;
		}

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
		DrivePath dp = parseDrivePath(ctx);

		// Drive listing
		if (dp.driveName() == null) {
			String depthHeader = ctx.header("Depth");
			int depth = "0".equals(depthHeader) ? 0 : 1;
			List<String> drives = driveManager.listDrives(getIdentity(ctx));
			String xml = PropfindResponse.buildDriveList(drives, depth);
			ctx.status(207);
			ctx.contentType("application/xml; charset=utf-8");
			ctx.result(xml);
			return;
		}

		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(404).result("Not Found: drive '" + dp.driveName() + "' does not exist");
			return;
		}

		BasicFileAttributes attrs = readAttributesSafe(path);
		if (attrs == null) {
			ctx.status(404).result("Not Found");
			return;
		}

		String depthHeader = ctx.header("Depth");
		int depth = "0".equals(depthHeader) ? 0 : 1;

		List<Path> children = new ArrayList<>();
		if (depth >= 1 && attrs.isDirectory()) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
				for (Path child : stream) {
					children.add(child);
				}
			}
		}

		String xml = PropfindResponse.build(dp.driveName(), path, attrs, children);
		ctx.status(207);
		ctx.contentType("application/xml; charset=utf-8");
		ctx.result(xml);
	}

	void handleMkcol(Context ctx) {
		if (rejectUnauthenticatedWrite(ctx)) return;
		DrivePath dp = parseDrivePath(ctx);

		if (dp.driveName() == null) {
			ctx.status(405).result("Method Not Allowed");
			return;
		}

		// Drive-level creation (empty file path)
		if (dp.filePath() == null || dp.filePath().isEmpty()) {
			boolean created = driveManager.createDrive(getIdentity(ctx), dp.driveName());
			if (created) {
				ctx.header("Location", ROUTE + encodePathComponent(dp.driveName()) + "/");
				ctx.status(201);
			} else {
				ctx.status(405).result("Method Not Allowed: drive already exists");
			}
			return;
		}

		// Directory creation within a drive
		Path path = resolveFilePath(ctx, dp);
		if (path == null) {
			ctx.status(409).result("Conflict: drive '" + dp.driveName() + "' does not exist");
			return;
		}

		try {
			Files.createDirectory(path);
			ctx.header("Location", ROUTE + encodePathComponent(dp.driveName()) + "/" + encodePath(dp.filePath()) + "/");
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
		DrivePath dp = parseDrivePath(ctx);

		// Drive-level rename (e.g. Windows Explorer renaming "New folder" to user's name)
		if (dp.driveName() != null && (dp.filePath() == null || dp.filePath().isEmpty())) {
			DrivePath destDp = parseDestinationDrivePath(ctx);
			if (destDp == null || destDp.driveName() == null) {
				ctx.status(400).result("Bad Request: missing or invalid Destination header");
				return;
			}
			boolean renamed = driveManager.renameDrive(getIdentity(ctx), dp.driveName(), destDp.driveName());
			if (renamed) {
				ctx.header("Location", ROUTE + encodePathComponent(destDp.driveName()) + "/");
				ctx.status(201);
			} else {
				ctx.status(409).result("Conflict: source drive not found or target already exists");
			}
			return;
		}

		Path source = resolveFilePath(ctx, dp);
		if (source == null) {
			ctx.status(404).result("Not Found");
			return;
		}

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
		Files.delete(source);

		ctx.status(destExists ? 204 : 201);
	}

	void handleCopy(Context ctx) throws IOException {
		if (rejectUnauthenticatedWrite(ctx)) return;
		DrivePath dp = parseDrivePath(ctx);
		Path source = resolveFilePath(ctx, dp);
		if (source == null) {
			ctx.status(404).result("Not Found");
			return;
		}

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
		DrivePath dp = parseDrivePath(ctx);
		Path path = resolveFilePath(ctx, dp);
		if (path == null || !Files.exists(path)) {
			ctx.status(404).result("Not Found");
			return;
		}

		String href = ROUTE + dp.driveName() + "/" + path.toString().replaceFirst("^/", "");
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
	 * Parses the Destination header into a DrivePath (for drive-level MOVE/COPY).
	 */
	private DrivePath parseDestinationDrivePath(Context ctx) {
		String destHeader = ctx.header("Destination");
		if (destHeader == null) return null;
		try {
			java.net.URI destURI = java.net.URI.create(destHeader);
			String destPath = destURI.getPath();
			if (destPath == null) return null;

			// Strip the /dlfs/ prefix
			String remainder;
			if (destPath.startsWith(ROUTE)) {
				remainder = destPath.substring(ROUTE.length());
			} else if (destPath.startsWith(ROUTE_BARE)) {
				remainder = destPath.substring(ROUTE_BARE.length());
				if (remainder.startsWith("/")) remainder = remainder.substring(1);
			} else {
				return null;
			}

			if (remainder.endsWith("/")) remainder = remainder.substring(0, remainder.length() - 1);
			if (remainder.isEmpty()) return new DrivePath(null, null);

			int slash = remainder.indexOf('/');
			if (slash < 0) {
				return new DrivePath(remainder, "");
			}
			return new DrivePath(remainder.substring(0, slash), remainder.substring(slash + 1));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolves the Destination header to a filesystem path.
	 * Destination must be within the same drive.
	 */
	private Path resolveDestination(Context ctx) {
		String destHeader = ctx.header("Destination");
		if (destHeader == null) return null;
		try {
			java.net.URI destURI = java.net.URI.create(destHeader);
			String destPath = destURI.getPath();
			if (destPath == null) return null;

			// Strip the /dlfs/ prefix
			if (destPath.startsWith(ROUTE)) {
				destPath = destPath.substring(ROUTE.length());
			} else if (destPath.startsWith(ROUTE_BARE)) {
				destPath = destPath.substring(ROUTE_BARE.length());
				if (destPath.startsWith("/")) destPath = destPath.substring(1);
			} else {
				return null;
			}

			if (destPath.endsWith("/")) destPath = destPath.substring(0, destPath.length() - 1);
			if (destPath.isEmpty()) return null;

			// Parse drive name from destination
			int slash = destPath.indexOf('/');
			String destDrive;
			String destFile;
			if (slash < 0) {
				destDrive = destPath;
				destFile = "";
			} else {
				destDrive = destPath.substring(0, slash);
				destFile = destPath.substring(slash + 1);
			}

			FileSystem fs = driveManager.getDrive(getIdentity(ctx), destDrive);
			if (fs == null) return null;

			Path root = fs.getRootDirectories().iterator().next();
			if (destFile.isEmpty()) return root;
			return fs.getPath("/" + destFile);
		} catch (Exception e) {
			return null;
		}
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

	/**
	 * URL-encodes a single path component (e.g. a drive name or file name).
	 * Spaces become %20 (not +), slashes are preserved.
	 */
	static String encodePathComponent(String component) {
		try {
			return java.net.URLEncoder.encode(component, "UTF-8").replace("+", "%20");
		} catch (java.io.UnsupportedEncodingException e) {
			return component; // UTF-8 is always supported
		}
	}

	/**
	 * URL-encodes a full path (encoding each component separately, preserving slashes).
	 */
	static String encodePath(String path) {
		if (path == null || path.isEmpty()) return path;
		String[] parts = path.split("/", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) sb.append("/");
			if (!parts[i].isEmpty()) {
				sb.append(encodePathComponent(parts[i]));
			}
		}
		return sb.toString();
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
