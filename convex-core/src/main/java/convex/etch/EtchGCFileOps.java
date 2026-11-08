package convex.etch;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Filesystem boundary for GC lifecycle operations; absent from cell read/write paths. */
class EtchGCFileOps {
	static final EtchGCFileOps SYSTEM=new EtchGCFileOps();
	String readMetadata(Path path) throws IOException { return Files.readString(path); }
	void checkpoint(Etch etch) throws IOException { etch.flush(); }
	void link(Path name,Path existing) throws IOException { Files.createLink(name,existing); }
	boolean delete(File file) throws IOException { return file.delete(); }
	void cleanup(Path path) throws IOException { Files.deleteIfExists(path); }
	void adopt(Path from,Path to) throws IOException { Files.move(from,to); }
	Path temporary(Path path) throws IOException {
		return Files.createTempFile(path.toAbsolutePath().getParent(),path.getFileName()+".",".tmp");
	}
	void writeAndForce(Path path,String contents) throws IOException {
		try (FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)) {
			ByteBuffer bytes=StandardCharsets.UTF_8.encode(contents);
			while (bytes.hasRemaining()) channel.write(bytes);
			channel.force(true);
		}
	}
	void replace(Path from,Path to) throws IOException {
		Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
	}
	void metadata(Path path,String contents) throws IOException {
		Path temporary=temporary(path);
		Throwable failure=null;
		try { writeAndForce(temporary,contents); replace(temporary,path); }
		catch (IOException | RuntimeException | Error e) { failure=e; throw e; }
		finally {
			try { cleanup(temporary); }
			catch (IOException cleanup) {
				if (failure==null) throw cleanup;
				failure.addSuppressed(cleanup);
			}
		}
	}
}
