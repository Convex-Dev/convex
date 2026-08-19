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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import convex.auth.did.DID;
import convex.auth.did.DIDURL;
import convex.auth.did.DIDVerifier;
import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
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
 * a vector of signed UCAN JWT tokens. When a caller doesn't own the requested drive,
 * the tokens are verified at the trust boundary and the request is authorised via
 * {@link UCANValidator#isAuthorised}: capability coverage, per-hop delegation
 * attenuation, and a self-sovereign root check (the chain's root issuer must be the
 * drive owner named in the resource). Delegation chains are supported — a delegatee
 * may re-delegate (attenuated) access.</p>
 *
 * <ul>
 *   <li>Resource: DID URL {@code <owner-did>/dlfs/<drive>[/<path>]}</li>
 *   <li>Abilities: {@code crud/read} (list, read), {@code crud/write} (write, mkdir,
 *       delete) — a broader grant ({@code crud}, {@code *}) covers these</li>
 * </ul>
 *
 * <p>A drive may be addressed by bare name (the caller's own drive, or a delegated
 * drive whose owner is inferred from the grants) or explicitly as a DID URL
 * {@code did:key:zOwner.../drive} — the same cross-user addressing pattern used by
 * Covia's DLFS adapter, and the only way to reach a delegated drive shadowed by an
 * own drive of the same name.</p>
 */
public class DlfsMcpTools {
	/** MCP responses should remain small enough for interactive tool use. */
	static final long MAX_MCP_FILE_SIZE = 8L * 1024 * 1024;
	static final int MAX_MCP_DIRECTORY_ENTRIES = 10_000;

	private static final String TOOLS_PATH = "convex/dlfs/mcp/tools/";

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_CONTENT = Strings.intern("content");
	private static final AString FIELD_UCANS = Strings.intern("ucans");

	/** Resource path prefix appended to the owner's DID to form the DID URL */
	private static final String DLFS_PATH_PREFIX = "/dlfs/";

	private final DLFSDriveManager driveManager;
	private boolean requireAuthForWrites;

	public DlfsMcpTools(DLFSDriveManager driveManager) {
		this.driveManager = driveManager;
	}

	DlfsMcpTools setRequireAuthForWrites(boolean require) {
		this.requireAuthForWrites = require;
		return this;
	}

	private AMap<AString, ACell> rejectUnauthenticatedWrite() {
		if (requireAuthForWrites && getIdentity() == null) {
			return McpProtocol.toolError("Authentication required");
		}
		return null;
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
		String canonical = DLFSPathValidator.canonicalRelativePath(filePath);
		if (canonical.isEmpty()) {
			return fs.getRootDirectories().iterator().next();
		}
		return fs.getPath("/" + canonical);
	}

	private static void sync(FileSystem fs) {
		if (fs instanceof convex.lattice.fs.DLFileSystem dlfs) dlfs.sync();
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
	 * Resolves a drive for the caller.
	 *
	 * <p>A DID-URL drive reference ({@code did:key:zOwner.../drive}) names the owner
	 * explicitly. A bare name resolves to the caller's own drive first, then to a
	 * delegated drive whose owner is inferred from the presented grants.</p>
	 *
	 * @param driveName Drive name, or a DID-URL drive reference
	 * @param filePath  File path within drive (for resource matching), may be null
	 * @param requiredAbility The ability required (crud/read or crud/write)
	 * @param arguments Tool arguments (may contain ucans)
	 * @return DriveAccess with the filesystem or an error message
	 */
	private DriveAccess resolveDrive(String driveName, String filePath,
			AString requiredAbility, AMap<AString, ACell> arguments) {
		try {
			filePath = DLFSPathValidator.canonicalRelativePath(filePath);
		} catch (IllegalArgumentException e) {
			return DriveAccess.denied("Invalid path: " + e.getMessage());
		}
		String callerIdentity = getIdentity();

		// Explicit owner: DID-URL drive reference
		if (driveName != null && driveName.startsWith("did:")) {
			return resolveDIDURLDrive(driveName, filePath, requiredAbility, arguments, callerIdentity);
		}
		if (!DLFSPathValidator.isValidDriveName(driveName)) {
			return DriveAccess.denied("Invalid drive name");
		}

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

		// Trust boundary: verify signatures, chains and temporal bounds once
		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(ucans, DIDVerifier.CONVEX);
		if (proofs == null) return DriveAccess.denied("Drive not found: " + driveName);

		// The request names only the drive, so candidate owners come from the grants:
		// the self-sovereign owner of each granted resource that has such a drive
		for (String owner : grantOwners(proofs)) {
			FileSystem ownerFs = driveManager.getDrive(owner, driveName);
			if (ownerFs == null) continue;
			if (authorised(proofs, callerIdentity, owner, driveName, filePath, requiredAbility)) {
				return DriveAccess.ok(ownerFs);
			}
		}

		return DriveAccess.denied("Drive not found: " + driveName);
	}

	/**
	 * Resolves a DID-URL drive reference ({@code did:key:zOwner.../drive}): the DID
	 * names the owner and the path component names the drive. The caller's own drive
	 * is opened directly; another owner's drive requires UCAN authorisation.
	 */
	private DriveAccess resolveDIDURLDrive(String driveRef, String filePath,
			AString requiredAbility, AMap<AString, ACell> arguments, String callerIdentity) {
		String owner;
		String drive;
		try {
			DIDURL didURL = DIDURL.create(driveRef);
			owner = didURL.getDID().toString();
			drive = didURL.getPath();
		} catch (Exception e) {
			return DriveAccess.denied("Invalid drive reference: " + driveRef);
		}
		if (drive != null && drive.startsWith("/")) drive = drive.substring(1);
		if (drive == null || drive.isEmpty()) {
			return DriveAccess.denied("DID-URL drive reference must name a drive, e.g. did:key:.../<drive>");
		}
		if (!DLFSPathValidator.isValidDriveName(drive)) {
			return DriveAccess.denied("Invalid drive name in drive reference");
		}

		if (owner.equals(callerIdentity)) {
			FileSystem fs = driveManager.getDrive(callerIdentity, drive);
			return (fs != null) ? DriveAccess.ok(fs) : DriveAccess.denied("Drive not found: " + driveRef);
		}

		if (callerIdentity == null) {
			return DriveAccess.denied("Authentication required to present UCAN proofs");
		}
		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(
			RT.ensureVector(arguments.get(FIELD_UCANS)), DIDVerifier.CONVEX);
		if (proofs != null && authorised(proofs, callerIdentity, owner, drive, filePath, requiredAbility)) {
			FileSystem fs = driveManager.getDrive(owner, drive);
			if (fs != null) return DriveAccess.ok(fs);
		}
		return DriveAccess.denied("Drive not found: " + driveRef);
	}

	/**
	 * The single authorisation gate for delegated drive access: capability coverage,
	 * per-hop delegation attenuation, and the self-sovereign root check (root issuer
	 * must be the drive owner), all via {@link UCANValidator#isAuthorised}.
	 */
	private static boolean authorised(AVector<ACell> proofs, String caller, String owner,
			String driveName, String filePath, AString requiredAbility) {
		String resource = owner + DLFS_PATH_PREFIX + driveName;
		if (filePath != null && !filePath.isEmpty()) {
			resource += "/" + filePath;
		}
		return UCANValidator.isAuthorised(proofs, Strings.create(caller),
			Strings.create(resource), requiredAbility,
			RootAuthorityPolicy.SELF_SOVEREIGN, System.currentTimeMillis() / 1000);
	}

	/**
	 * Candidate drive owners for a bare-name delegated request: the distinct
	 * self-sovereign owners of the resources granted by the verified proofs.
	 */
	private static Set<String> grantOwners(AVector<ACell> proofs) {
		Set<String> owners = new LinkedHashSet<>();
		for (long i = 0; i < proofs.count(); i++) {
			UCAN token = UCAN.parse(RT.castMap(proofs.get(i)));
			if (token == null) continue;
			AVector<ACell> att = token.getCapabilities();
			for (long j = 0; j < att.count(); j++) {
				AMap<AString, ACell> cap = RT.castMap(att.get(j));
				if (cap == null) continue;
				DID owner = RootAuthorityPolicy.ownerDID(RT.ensureString(cap.get(Capability.WITH)));
				if (owner != null) owners.add(owner.toString());
			}
		}
		return owners;
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
			AMap<AString, ACell> rejected = rejectUnauthenticatedWrite();
			if (rejected != null) return rejected;
			AString nameCell = RT.ensureString(arguments.get(FIELD_NAME));
			if (nameCell == null) return McpProtocol.toolError("'name' is required");

			if (!DLFSPathValidator.isValidDriveName(nameCell.toString())) {
				return McpProtocol.toolError("Invalid drive name");
			}
			String identity=getIdentity();
			boolean created = driveManager.createDrive(identity, nameCell.toString());
			if (!created) return McpProtocol.toolError("Drive already exists: " + nameCell);
			driveManager.sync(identity);

			return McpProtocol.toolSuccess(Maps.of("created", CVMBool.TRUE, FIELD_NAME, nameCell));
		}
	}

	private class DeleteDriveTool extends McpTool {
		DeleteDriveTool() {
			super(McpTool.loadMetadata(TOOLS_PATH + "deleteDrive.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AMap<AString, ACell> rejected = rejectUnauthenticatedWrite();
			if (rejected != null) return rejected;
			AString nameCell = RT.ensureString(arguments.get(FIELD_NAME));
			if (nameCell == null) return McpProtocol.toolError("'name' is required");

			// Drive deletion only for own drives — no UCAN delegation
			String identity=getIdentity();
			boolean deleted = driveManager.deleteDrive(identity, nameCell.toString());
			if (!deleted) return McpProtocol.toolError("Drive not found: " + nameCell);
			driveManager.sync(identity);

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
						if (entries.count() >= MAX_MCP_DIRECTORY_ENTRIES) {
							return McpProtocol.toolError("Directory listing limit exceeded");
						}
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
				long size = Files.size(path);
				if (size > MAX_MCP_FILE_SIZE) {
					return McpProtocol.toolError("File is too large for MCP read");
				}
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
			AMap<AString, ACell> rejected = rejectUnauthenticatedWrite();
			if (rejected != null) return rejected;
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
				if (bytes.length > MAX_MCP_FILE_SIZE) {
					return McpProtocol.toolError("Content is too large for MCP write");
				}
				boolean isNew = !Files.exists(path);
				if (access.fs() instanceof convex.lattice.fs.DLFileSystem dlfs) {
					dlfs.writeAllBytes((convex.lattice.fs.DLPath) path, bytes);
				} else {
					Files.write(path, bytes, StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				}
				sync(access.fs());
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
			AMap<AString, ACell> rejected = rejectUnauthenticatedWrite();
			if (rejected != null) return rejected;
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_WRITE, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				Files.createDirectory(path);
				sync(access.fs());
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
			AMap<AString, ACell> rejected = rejectUnauthenticatedWrite();
			if (rejected != null) return rejected;
			AString driveCell = RT.ensureString(arguments.get(FIELD_DRIVE));
			if (driveCell == null) return McpProtocol.toolError("'drive' is required");

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			if (pathCell == null) return McpProtocol.toolError("'path' is required");

			DriveAccess access = resolveDrive(driveCell.toString(), pathCell.toString(), Capability.CRUD_DELETE, arguments);
			if (access.error() != null) return McpProtocol.toolError(access.error());

			Path path = resolvePath(access.fs(), pathCell.toString());
			try {
				Files.delete(path);
				sync(access.fs());
				return McpProtocol.toolSuccess(Maps.of("deleted", CVMBool.TRUE));
			} catch (NoSuchFileException e) {
				return McpProtocol.toolError("File not found: " + pathCell);
			} catch (IOException e) {
				return McpProtocol.toolError("Error deleting: " + e.getMessage());
			}
		}
	}
}
