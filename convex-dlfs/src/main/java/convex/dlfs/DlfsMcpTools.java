package convex.dlfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.restapi.auth.AuthMiddleware;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpServer;
import convex.restapi.mcp.McpTool;
import io.javalin.http.Context;

/**
 * MCP tools for the Data Lattice File System (DLFS).
 *
 * <p>Registers DLFS file operations as tools on a {@link McpServer}, enabling
 * AI agents to manage drives, read/write files, and list directories via MCP.</p>
 *
 * <h3>UCAN Delegated Access</h3>
 *
 * <p>Tools that access drives support an optional {@code ucans} argument containing
 * a vector of signed UCAN tokens. When a caller doesn't own the requested drive,
 * the tokens are validated and checked for a capability covering the operation:</p>
 *
 * <ul>
 *   <li>Resource: DID URL {@code <owner-did>/dlfs/<drive>[/<path>]}</li>
 *   <li>Abilities: {@code dlfs/read} (list, read), {@code dlfs/write} (write, mkdir, delete),
 *       {@code dlfs/*} (all)</li>
 * </ul>
 *
 * <p>The UCAN's issuer must be the drive owner, and the audience must match the
 * caller's DID. Signature, expiry, and chain integrity are verified.</p>
 */
public class DlfsMcpTools {

	private static final String TOOLS_PATH = "convex/dlfs/mcp/tools/";

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_CONTENT = Strings.intern("content");
	private static final AString FIELD_UCANS = Strings.intern("ucans");

	/** Resource path prefix appended to the owner's DID to form the DID URL */
	private static final String DLFS_PATH_PREFIX = "/dlfs/";

	private final DLFSDriveManager driveManager;

	public DlfsMcpTools(DLFSDriveManager driveManager) {
		this.driveManager = driveManager;
	}

	/**
	 * Registers all DLFS tools on the given McpServer.
	 */
	public void registerAll(McpServer mcpServer) {
		mcpServer.registerTool(new ListDrivesTool());
		mcpServer.registerTool(new CreateDriveTool());
		mcpServer.registerTool(new DeleteDriveTool());
		mcpServer.registerTool(new ListTool());
		mcpServer.registerTool(new ReadTool());
		mcpServer.registerTool(new WriteTool());
		mcpServer.registerTool(new MkdirTool());
		mcpServer.registerTool(new DeleteTool());
	}

	// ==================== Identity ====================

	/**
	 * Gets the caller's identity from the current MCP request context.
	 * Returns the DID string from the JWT bearer token, or null for anonymous.
	 */
	private String getIdentity() {
		Context ctx = McpServer.getCurrentContext();
		if (ctx == null) return null;
		var id = AuthMiddleware.getIdentity(ctx);
		return id != null ? id.toString() : null;
	}

	/**
	 * Resolves a path within a drive.
	 */
	private Path resolvePath(FileSystem fs, String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return fs.getRootDirectories().iterator().next();
		}
		return fs.getPath("/" + filePath);
	}

	// ==================== UCAN Drive Resolution ====================

	/**
	 * Result of resolving a drive, possibly via UCAN delegation.
	 */
	private record DriveAccess(FileSystem fs, String error) {
		static DriveAccess ok(FileSystem fs) { return new DriveAccess(fs, null); }
		static DriveAccess denied(String error) { return new DriveAccess(null, error); }
	}

	/**
	 * Resolves a drive for the caller. First checks the caller's own drives;
	 * if not found, checks UCAN proofs for delegated access.
	 *
	 * @param driveName Drive name
	 * @param filePath  File path within drive (for resource matching), may be null
	 * @param requiredAbility The ability required (dlfs/read or dlfs/write)
	 * @param arguments Tool arguments (may contain ucans)
	 * @return DriveAccess with the filesystem or an error message
	 */
	private DriveAccess resolveDrive(String driveName, String filePath,
			AString requiredAbility, AMap<AString, ACell> arguments) {
		String callerIdentity = getIdentity();

		// Try caller's own drive first
		FileSystem fs = driveManager.getDrive(callerIdentity, driveName);
		if (fs != null) return DriveAccess.ok(fs);

		// No own drive — check for UCAN delegation
		AVector<ACell> ucans = RT.ensureVector(arguments.get(FIELD_UCANS));
		if (ucans == null || ucans.isEmpty()) {
			return DriveAccess.denied("Drive not found: " + driveName);
		}

		// Caller must be authenticated to present UCANs
		if (callerIdentity == null) {
			return DriveAccess.denied("Authentication required to present UCAN proofs");
		}

		long now = System.currentTimeMillis() / 1000;

		// Build the required resource as a DID URL
		// Format: <owner-did>/dlfs/<drive>[/<path>]
		String resourceSuffix = DLFS_PATH_PREFIX + driveName;
		if (filePath != null && !filePath.isEmpty()) {
			resourceSuffix += "/" + filePath;
		}

		// Check each UCAN token (JWT strings)
		for (long i = 0; i < ucans.count(); i++) {
			AString jwtString = RT.ensureString(ucans.get(i));
			if (jwtString == null) continue;

			// Validate JWT signature, expiry, chain
			UCAN ucan = UCANValidator.validateJWT(jwtString, now);
			if (ucan == null) continue;

			// Audience must match the caller
			AString audience = ucan.getAudience();
			if (audience == null || !audience.toString().equals(callerIdentity)) continue;

			// Issuer must own the drive
			String issuerDID = ucan.getIssuer().toString();
			FileSystem ownerFs = driveManager.getDrive(issuerDID, driveName);
			if (ownerFs == null) continue;

			// Check capabilities cover the request
			String requiredResource = issuerDID + resourceSuffix;
			AString requiredWith = Strings.create(requiredResource);

			AVector<ACell> capabilities = ucan.getCapabilities();
			for (long j = 0; j < capabilities.count(); j++) {
				AMap<AString, ACell> cap = RT.ensureMap(capabilities.get(j));
				if (cap == null) continue;
				if (Capability.covers(cap, requiredWith, requiredAbility)) {
					return DriveAccess.ok(ownerFs);
				}
			}
		}

		return DriveAccess.denied("Drive not found: " + driveName);
	}

	// ==================== Tools ====================

	private class ListDrivesTool extends McpTool {
		ListDrivesTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "listDrives.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			List<String> drives = driveManager.listDrives(getIdentity());
			AVector<ACell> driveNames = Vectors.empty();
			for (String name : drives) {
				driveNames = driveNames.conj(Strings.create(name));
			}
			return McpProtocol.toolSuccess(Maps.of("drives", driveNames));
		}
	}

	private class CreateDriveTool extends McpTool {
		CreateDriveTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "createDrive.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString nameCell = RT.ensureString(arguments.get(FIELD_NAME));
			if (nameCell == null) return McpProtocol.toolError("'name' is required");

			boolean created = driveManager.createDrive(getIdentity(), nameCell.toString());
			if (!created) return McpProtocol.toolError("Drive already exists: " + nameCell);

			return McpProtocol.toolSuccess(Maps.of("created", CVMBool.TRUE, FIELD_NAME, nameCell));
		}
	}

	private class DeleteDriveTool extends McpTool {
		DeleteDriveTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "deleteDrive.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString nameCell = RT.ensureString(arguments.get(FIELD_NAME));
			if (nameCell == null) return McpProtocol.toolError("'name' is required");

			// Drive deletion only for own drives — no UCAN delegation
			boolean deleted = driveManager.deleteDrive(getIdentity(), nameCell.toString());
			if (!deleted) return McpProtocol.toolError("Drive not found: " + nameCell);

			return McpProtocol.toolSuccess(Maps.of("deleted", CVMBool.TRUE));
		}
	}

	private class ListTool extends McpTool {
		ListTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "list.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			String filePath = pathCell != null ? pathCell.toString() : null;

			DriveAccess access = resolveDrive(driveCell.toString(), filePath, Capability.CRUD_READ, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path dir = resolvePath(access.fs(), filePath);
			try {
				BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
				if (!attrs.isDirectory()) {
					return McpProtocol.toolError("Not a directory: " + pathCell);
				}

				AVector<AMap<AString, ACell>> entries = Vectors.empty();
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
					for (Path child : stream) {
						BasicFileAttributes childAttrs = Files.readAttributes(child, BasicFileAttributes.class);
						Path fileName = child.getFileName();
						String name = (fileName != null) ? fileName.toString() : child.toString();
						AMap<AString, ACell> entry = Maps.of(
							"name", name,
							"type", childAttrs.isDirectory() ? "directory" : "file"
						);
						if (childAttrs.isRegularFile()) {
							entry = entry.assoc(Strings.create("size"), CVMLong.create(childAttrs.size()));
						}
						entries = entries.conj(entry);
					}
				}
				return McpProtocol.toolSuccess(Maps.of("entries", entries));
			} catch (NoSuchFileException e) {
				return McpProtocol.toolError("Path not found: " + pathCell);
			} catch (IOException e) {
				return McpProtocol.toolError("Error listing directory: " + e.getMessage());
			}
		}
	}

	private class ReadTool extends McpTool {
		ReadTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "read.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_READ, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				byte[] bytes = Files.readAllBytes(path);

				if (isLikelyText(bytes)) {
					String text = new String(bytes, StandardCharsets.UTF_8);
					return McpProtocol.toolSuccess(Maps.of(
						"content", text,
						"encoding", "utf-8",
						"size", CVMLong.create(bytes.length)
					));
				} else {
					String b64 = Base64.getEncoder().encodeToString(bytes);
					return McpProtocol.toolSuccess(Maps.of(
						"content", b64,
						"encoding", "base64",
						"size", CVMLong.create(bytes.length)
					));
				}
			} catch (NoSuchFileException e) {
				return McpProtocol.toolError("File not found: " + pathCell);
			} catch (IOException e) {
				return McpProtocol.toolError("Error reading file: " + e.getMessage());
			}
		}

		private boolean isLikelyText(byte[] bytes) {
			for (byte b : bytes) {
				if (b == 0) return false;
			}
			return true;
		}
	}

	private class WriteTool extends McpTool {
		WriteTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "write.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			AString contentCell = RT.ensureString(arguments.get(FIELD_CONTENT));
			if (contentCell == null) return McpProtocol.toolError("'content' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_WRITE, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				byte[] bytes = contentCell.toString().getBytes(StandardCharsets.UTF_8);
				boolean isNew = !Files.exists(path);
				Files.write(path, bytes,
						StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
				return McpProtocol.toolSuccess(Maps.of(
					"written", CVMLong.create(bytes.length),
					"created", isNew ? CVMBool.TRUE : CVMBool.FALSE
				));
			} catch (IOException e) {
				return McpProtocol.toolError("Error writing file: " + e.getMessage());
			}
		}
	}

	private class MkdirTool extends McpTool {
		MkdirTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "mkdir.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_WRITE, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				Files.createDirectory(path);
				return McpProtocol.toolSuccess(Maps.of("created", CVMBool.TRUE));
			} catch (IOException e) {
				return McpProtocol.toolError("Error creating directory: " + e.getMessage());
			}
		}
	}

	private class DeleteTool extends McpTool {
		DeleteTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "delete.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_DELETE, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				Files.delete(path);
				return McpProtocol.toolSuccess(Maps.of("deleted", CVMBool.TRUE));
			} catch (NoSuchFileException e) {
				return McpProtocol.toolError("File not found: " + pathCell);
			} catch (IOException e) {
				return McpProtocol.toolError("Error deleting: " + e.getMessage());
			}
		}
	}
}
