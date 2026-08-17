package convex.dlfs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import convex.lattice.fs.DLFileSystem;
import convex.restapi.auth.AuthMiddleware;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;

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
	/** Maximum children returned by one PROPFIND response. */
	public static final int MAX_DIRECTORY_ENTRIES = 10_000;

	/** Canonical WebDAV mount path, including its trailing slash. */
	public static final String MOUNT_PATH = "/dlfs/";
	private static final String ROUTE_BARE = MOUNT_PATH.substring(0, MOUNT_PATH.length() - 1);
	private static final String ROUTE_PATH = MOUNT_PATH + "<path>";

	private final DLFSDriveManager driveManager;
	private boolean requireAuthForWrites = false;
	private long maxFileSize = DLFSServer.DEFAULT_MAX_REQUEST_SIZE;

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

	/** Sets the maximum file body accepted or buffered by WebDAV operations. */
	public DLFSWebDAV setMaxFileSize(long bytes) {
		if (bytes <= 0) throw new IllegalArgumentException("Maximum file size must be positive");
		this.maxFileSize = bytes;
		return this;
	}

	// Custom WebDAV HTTP methods, supported natively by Javalin 7+
	private static final HandlerType PROPFIND = HandlerType.findOrCreate("PROPFIND");
	private static final HandlerType PROPPATCH = HandlerType.findOrCreate("PROPPATCH");
	private static final HandlerType MKCOL = HandlerType.findOrCreate("MKCOL");
	private static final HandlerType MOVE = HandlerType.findOrCreate("MOVE");
	private static final HandlerType COPY = HandlerType.findOrCreate("COPY");
	private static final HandlerType LOCK = HandlerType.findOrCreate("LOCK");
	private static final HandlerType UNLOCK = HandlerType.findOrCreate("UNLOCK");

	/**
	 * Registers WebDAV routes on the given routes configuration.
	 */
	public void addRoutes(RoutesConfig routes) {
		routes.get(ROUTE_PATH, this::handleGet);
		routes.get(MOUNT_PATH, this::handleGet);
		routes.put(ROUTE_PATH, this::handlePut);
		routes.delete(ROUTE_PATH, this::handleDelete);
		routes.head(ROUTE_PATH, this::handleHead);
		routes.head(MOUNT_PATH, this::handleHead);
		routes.head(ROUTE_BARE, this::handleHead);
		routes.options(ROUTE_PATH, this::handleOptions);
		routes.options(MOUNT_PATH, this::handleOptions);
		routes.options(ROUTE_BARE, this::handleOptions);

		// Root-level DAV discovery (Windows WebClient sends OPTIONS / then PROPFIND /)
		routes.options("/", this::handleOptions);
		routes.addHttpHandler(PROPFIND, "/", this::handleRootPropfind);

		// Custom WebDAV methods on the DLFS paths
		addDLFSMethod(routes, PROPFIND, this::handlePropfind);
		addDLFSMethod(routes, MKCOL, this::handleMkcol);
		addDLFSMethod(routes, MOVE, this::handleMove);
		addDLFSMethod(routes, COPY, this::handleCopy);
		// These methods are deliberately explicit failures. Advertising or returning
		// successful locks/properties without enforcing them is unsafe for clients.
		addDLFSMethod(routes, PROPPATCH, this::handleUnsupported);
		addDLFSMethod(routes, LOCK, this::handleUnsupported);
		addDLFSMethod(routes, UNLOCK, this::handleUnsupported);
	}

	/**
	 * Registers a handler for a WebDAV method on all DLFS path forms.
	 */
	private static void addDLFSMethod(RoutesConfig routes, HandlerType method, Handler handler) {
		routes.addHttpHandler(method, ROUTE_BARE, handler);
		routes.addHttpHandler(method, MOUNT_PATH, handler);
		routes.addHttpHandler(method, ROUTE_PATH, handler);
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

		// Try Javalin path param first (routes registered on ROUTE_PATH, already URL-decoded)
		try {
			pathParam = ctx.pathParam("path");
		} catch (Exception e) {
			// not available (bare /dlfs routes have no path param)
		}

		// Fall back to URI extraction
		if (pathParam == null || pathParam.isEmpty()) {
			String uri = ctx.req().getRequestURI();
			if (uri.startsWith(MOUNT_PATH) && uri.length() > MOUNT_PATH.length()) {
				pathParam = uri.substring(MOUNT_PATH.length());
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
		if (slash < 0) return validatedDrivePath(pathParam, "");
		String driveName = pathParam.substring(0, slash);
		String filePath = pathParam.substring(slash + 1);
		return validatedDrivePath(driveName, filePath);
	}

	private static DrivePath validatedDrivePath(String driveName, String filePath) {
		if (!DLFSPathValidator.isValidDriveName(driveName)) {
			throw new IllegalArgumentException("Invalid drive name");
		}
		return new DrivePath(driveName, DLFSPathValidator.canonicalRelativePath(filePath));
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

	/**
	 * Syncs the DLFS drive after a mutating operation to ensure lattice persistence.
	 */
	private void syncDrive(Context ctx, DrivePath dp) {
		if (dp.driveName() == null) return;
		FileSystem fs = driveManager.getDrive(getIdentity(ctx), dp.driveName());
		if (fs instanceof DLFileSystem dlfs) {
			dlfs.sync();
		}
	}

	private static void prepareMutation(Path path) {
		FileSystem fs = path.getFileSystem();
		if (fs instanceof DLFileSystem dlfs) dlfs.updateTimestamp();
	}

	private boolean writeCompleteFile(Path path, byte[] data) throws IOException {
		if (data.length > maxFileSize) return false;
		prepareMutation(path);
		if (path.getFileSystem() instanceof DLFileSystem dlfs) {
			dlfs.writeAllBytes((convex.lattice.fs.DLPath) path, data);
		} else {
			Files.write(path, data, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		}
		return true;
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
			String etag = calculateETag(path);
			if (headerMatches(ctx.header("If-None-Match"), etag)) {
				ctx.header("ETag", etag);
				ctx.status(304);
				return;
			}
			ctx.contentType(guessContentType(path.toString()));
			ctx.header("Content-Length", String.valueOf(attrs.size()));
			setLastModified(ctx, attrs);
			setETag(ctx, path);
			ctx.result(Files.newInputStream(path));
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

		long declaredLength = ctx.contentLength();
		if (declaredLength > maxFileSize) {
			ctx.status(413).result("Content Too Large");
			return;
		}
		byte[] body = ctx.bodyAsBytes();
		if (body.length > maxFileSize) {
			ctx.status(413).result("Content Too Large");
			return;
		}

		// Ensure parent directory exists
		Path parent = path.getParent();
		if (parent != null && !Files.isDirectory(parent)) {
			ctx.status(409).result("Conflict: parent directory does not exist");
			return;
		}

		boolean isNew = !Files.exists(path);
		if (!checkWritePreconditions(ctx, path, !isNew)) return;
		writeCompleteFile(path, body);

		syncDrive(ctx, dp);
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
			String identity=getIdentity(ctx);
			boolean deleted = driveManager.deleteDrive(identity, dp.driveName());
			if (deleted) {
				driveManager.sync(identity);
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
			prepareMutation(path);
			Files.delete(path);
			syncDrive(ctx, dp);
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
			setETag(ctx, path);
		}

		ctx.status(200);
	}

	/**
	 * Handles PROPFIND on root (/), listing /dlfs/ as a child collection.
	 * Required for Windows WebDAV client discovery.
	 */
	void handleRootPropfind(Context ctx) {
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<D:multistatus xmlns:D=\"DAV:\">"
			+ "<D:response><D:href>/</D:href><D:propstat><D:prop>"
			+ "<D:displayname>/</D:displayname>"
			+ "<D:resourcetype><D:collection/></D:resourcetype>"
			+ "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
			+ "<D:response><D:href>/dlfs/</D:href><D:propstat><D:prop>"
			+ "<D:displayname>dlfs</D:displayname>"
			+ "<D:resourcetype><D:collection/></D:resourcetype>"
			+ "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
			+ "</D:multistatus>";
		ctx.contentType("application/xml; charset=utf-8");
		ctx.status(207);
		ctx.result(xml);
	}

	void handleOptions(Context ctx) {
		ctx.header("DAV", "1");
		ctx.header("MS-Author-Via", "DAV");
		ctx.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY");
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
					if (children.size() >= MAX_DIRECTORY_ENTRIES) {
						ctx.status(507).result("Directory listing limit exceeded");
						return;
					}
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
			String identity=getIdentity(ctx);
			boolean created = driveManager.createDrive(identity, dp.driveName());
			if (created) {
				driveManager.sync(identity);
				ctx.header("Location", MOUNT_PATH + encodePathComponent(dp.driveName()) + "/");
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
			prepareMutation(path);
			Files.createDirectory(path);
			syncDrive(ctx, dp);
			ctx.header("Location", MOUNT_PATH + encodePathComponent(dp.driveName()) + "/" + encodePath(dp.filePath()) + "/");
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
			String identity=getIdentity(ctx);
			boolean renamed = driveManager.renameDrive(identity, dp.driveName(), destDp.driveName());
			if (renamed) {
				driveManager.sync(identity);
				ctx.header("Location", MOUNT_PATH + encodePathComponent(destDp.driveName()) + "/");
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

		DrivePath destDp = parseDestinationDrivePath(ctx);
		if (destDp == null || !dp.driveName().equals(destDp.driveName())) {
			ctx.status(409).result("Conflict: destination must be within the same drive");
			return;
		}
		Path dest = resolveFilePath(ctx, destDp);
		if (dest == null || destDp.filePath().isEmpty()) {
			ctx.status(400).result("Bad Request: missing or invalid Destination header");
			return;
		}

		if (!Files.exists(source)) {
			ctx.status(404).result("Not Found");
			return;
		}
		if (!Files.isRegularFile(source)) {
			ctx.status(501).result("Directory MOVE is not implemented");
			return;
		}
		if (source.equals(dest)) {
			ctx.status(204);
			return;
		}
		if (!Files.isDirectory(dest.getParent())) {
			ctx.status(409).result("Conflict: destination parent does not exist");
			return;
		}

		boolean overwrite = !"F".equals(ctx.header("Overwrite"));
		boolean destExists = Files.exists(dest);
		if (destExists && !overwrite) {
			ctx.status(412).result("Precondition Failed: destination exists");
			return;
		}

		long sourceSize = Files.size(source);
		if (sourceSize > maxFileSize) {
			ctx.status(413).result("Source file is too large to move");
			return;
		}
		FileSystem fs = source.getFileSystem();
		synchronized (fs) {
			byte[] data = Files.readAllBytes(source);
			writeCompleteFile(dest, data);
			prepareMutation(source);
			Files.delete(source);
		}

		syncDrive(ctx, dp);
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

		DrivePath destDp = parseDestinationDrivePath(ctx);
		if (destDp == null || !dp.driveName().equals(destDp.driveName())) {
			ctx.status(409).result("Conflict: destination must be within the same drive");
			return;
		}
		Path dest = resolveFilePath(ctx, destDp);
		if (dest == null || destDp.filePath().isEmpty()) {
			ctx.status(400).result("Bad Request: missing or invalid Destination header");
			return;
		}

		if (!Files.exists(source)) {
			ctx.status(404).result("Not Found");
			return;
		}
		if (!Files.isRegularFile(source)) {
			ctx.status(501).result("Directory COPY is not implemented");
			return;
		}
		if (source.equals(dest)) {
			ctx.status(204);
			return;
		}
		if (!Files.isDirectory(dest.getParent())) {
			ctx.status(409).result("Conflict: destination parent does not exist");
			return;
		}

		boolean overwrite = !"F".equals(ctx.header("Overwrite"));
		boolean destExists = Files.exists(dest);
		if (destExists && !overwrite) {
			ctx.status(412).result("Precondition Failed: destination exists");
			return;
		}

		prepareMutation(dest);
		if (destExists) {
			Files.copy(source,dest,StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.copy(source,dest);
		}

		syncDrive(ctx, dp);
		ctx.status(destExists ? 204 : 201);
	}

	void handleUnsupported(Context ctx) {
		ctx.status(501).result("WebDAV method is not implemented");
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
			if (destPath.startsWith(MOUNT_PATH)) {
				remainder = destPath.substring(MOUNT_PATH.length());
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
				return validatedDrivePath(remainder, "");
			}
			return validatedDrivePath(remainder.substring(0, slash), remainder.substring(slash + 1));
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

	private static void setETag(Context ctx, Path path) {
		ctx.header("ETag", calculateETag(path));
	}

	private static String calculateETag(Path path) {
		try {
			if (path.getFileSystem() instanceof DLFileSystem dlfs && path instanceof convex.lattice.fs.DLPath dlp) {
				convex.core.data.Hash hash = dlfs.getNodeHash(dlp);
				if (hash != null) return "\"" + hash.toHexString() + "\"";
			}
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return "W/\"" + attrs.size() + "-" + attrs.lastModifiedTime().toMillis() + "\"";
		} catch (IOException e) {
			return "\"missing\"";
		}
	}

	private static boolean headerMatches(String header, String etag) {
		if (header == null) return false;
		for (String token : header.split(",")) {
			String candidate = token.strip();
			if (candidate.equals("*") || candidate.equals(etag)) return true;
		}
		return false;
	}

	private static boolean checkWritePreconditions(Context ctx, Path path, boolean exists) {
		String ifMatch = ctx.header("If-Match");
		if (ifMatch != null) {
			boolean matched = exists && (ifMatch.strip().equals("*")
				|| headerMatches(ifMatch, calculateETag(path)));
			if (!matched) {
				ctx.status(412).result("Precondition Failed");
				return false;
			}
		}
		String ifNoneMatch = ctx.header("If-None-Match");
		if (exists && ifNoneMatch != null && headerMatches(ifNoneMatch, calculateETag(path))) {
			ctx.status(412).result("Precondition Failed");
			return false;
		}
		return true;
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
