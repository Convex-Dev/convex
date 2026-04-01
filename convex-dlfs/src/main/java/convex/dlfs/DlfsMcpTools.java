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

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.AVector;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpServer;
import convex.restapi.mcp.McpTool;

/**
 * MCP tools for the Data Lattice File System (DLFS).
 *
 * <p>Registers DLFS file operations as tools on a {@link McpServer}, enabling
 * AI agents to manage drives, read/write files, and list directories via MCP.</p>
 */
public class DlfsMcpTools {

	private static final String TOOLS_PATH = "convex/dlfs/mcp/tools/";

	private static final AString FIELD_DRIVE = Strings.intern("drive");
	private static final AString FIELD_PATH = Strings.intern("path");
	private static final AString FIELD_NAME = Strings.intern("name");
	private static final AString FIELD_CONTENT = Strings.intern("content");

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
	 * Gets the identity for drive resolution. Currently null (anonymous).
	 * Can be extended to extract identity from the request context.
	 */
	private String getIdentity() {
		return null;
	}

	/**
	 * Resolves a drive filesystem, or null if not found.
	 */
	private FileSystem getDrive(String driveName) {
		return driveManager.getDrive(getIdentity(), driveName);
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

			FileSystem fs = getDrive(driveCell.toString());
			if (fs == null) return McpProtocol.toolError("Drive not found: " + driveCell);

			AString pathCell = RT.ensureString(arguments.get(FIELD_PATH));
			Path dir = resolvePath(fs, pathCell != null ? pathCell.toString() : null);

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

			FileSystem fs = getDrive(driveCell.toString());
			if (fs == null) return McpProtocol.toolError("Drive not found: " + driveCell);

			Path path = resolvePath(fs, pathCell.toString());
			try {
				byte[] bytes = Files.readAllBytes(path);

				// Try to return as text; fall back to base64
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

			FileSystem fs = getDrive(driveCell.toString());
			if (fs == null) return McpProtocol.toolError("Drive not found: " + driveCell);

			Path path = resolvePath(fs, pathCell.toString());
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

			FileSystem fs = getDrive(driveCell.toString());
			if (fs == null) return McpProtocol.toolError("Drive not found: " + driveCell);

			Path path = resolvePath(fs, pathCell.toString());
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

			FileSystem fs = getDrive(driveCell.toString());
			if (fs == null) return McpProtocol.toolError("Drive not found: " + driveCell);

			Path path = resolvePath(fs, pathCell.toString());
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
