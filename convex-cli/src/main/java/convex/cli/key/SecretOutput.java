package convex.cli.key;

import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.util.FileUtils;

/** Secure destination for CLI output containing private key material. */
final class SecretOutput implements AutoCloseable {
	private final AKeyCommand command;
	private final Writer writer;
	private final boolean closeWriter;
	private final Path path;
	private final boolean labelled;
	private final String description;

	private SecretOutput(AKeyCommand command, Writer writer, boolean closeWriter,
			Path path, boolean labelled, String description) {
		this.command=command;
		this.writer=writer;
		this.closeWriter=closeWriter;
		this.path=path;
		this.labelled=labelled;
		this.description=description;
	}

	static SecretOutput open(AKeyCommand command, String filename, String optionName,
			String description) {
		if (filename==null) {
			if (!command.isInteractive()) {
				throw new CLIError(ExitCodes.USAGE,description+" output in non-interactive mode requires "
						+optionName+" <path> (or '-' for stdout)");
			}
			Console console=System.console();
			if (console==null) {
				throw new CLIError(ExitCodes.USAGE,"Unable to display "+description
						+" because no console is attached. Specify "+optionName+" <path> (or '-' for stdout)");
			}
			return new SecretOutput(command,console.writer(),false,null,true,description);
		}

		if ("-".equals(filename.trim())) {
			return new SecretOutput(command,command.commandLine().getOut(),false,null,false,description);
		}

		Path path=FileUtils.getFile(filename).toPath();
		try {
			Path parent=path.getParent();
			if (parent!=null) Files.createDirectories(parent);
			SeekableByteChannel channel=Files.newByteChannel(path,
					EnumSet.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE));
			try {
				setOwnerOnlyPermissions(path);
			} catch (IOException|RuntimeException e) {
				channel.close();
				Files.deleteIfExists(path);
				throw e;
			}
			Writer writer=Channels.newWriter(channel,StandardCharsets.UTF_8);
			return new SecretOutput(command,writer,true,path,false,description);
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"Unable to create secure "+description+" file: "+path,e);
		}
	}

	void write(String value, String label) {
		try {
			if (labelled && label!=null) {
				writer.write(label);
				writer.write(System.lineSeparator());
			}
			writer.write(value);
			writer.write(System.lineSeparator());
			writer.flush();
			if ((writer instanceof PrintWriter pw)&&pw.checkError()) {
				throw new IOException("Output stream reported an error");
			}
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"Unable to write "+description,e);
		}
	}

	private static void setOwnerOnlyPermissions(Path path) throws IOException {
		PosixFileAttributeView posix=Files.getFileAttributeView(path,PosixFileAttributeView.class);
		if (posix!=null) {
			posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
			return;
		}

		AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
		if (acl!=null) {
			UserPrincipal owner=Files.getOwner(path);
			AclEntry ownerAccess=AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build();
			acl.setAcl(List.of(ownerAccess));
			return;
		}

		throw new IOException("File system does not support owner-only file permissions");
	}

	@Override
	public void close() {
		if (!closeWriter) return;
		try {
			writer.close();
			command.inform(description+" saved to "+path);
		} catch (IOException e) {
			throw new CLIError(ExitCodes.IOERR,"Unable to close "+description+" file: "+path,e);
		}
	}
}
