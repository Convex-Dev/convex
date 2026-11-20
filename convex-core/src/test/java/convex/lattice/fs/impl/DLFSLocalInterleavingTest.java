package convex.lattice.fs.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSCondition;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLPath;

/**
 * Each change to a drive is one atomic update of its root, so an edit made through
 * another view, or another channel, after the change has checked the drive and before
 * it lands is seen: the change runs again on top of it, or fails as it then must.
 */
public class DLFSLocalInterleavingTest {

	private static final CVMLong TIME=CVMLong.create(1000);

	/** An edit of the drive, run through another view. */
	@FunctionalInterface
	private interface Edit {
		void run() throws IOException;
	}

	/** A drive root that runs one edit just before the next write lands on it. */
	private static final class InterleavingRoot extends Root<AVector<ACell>> {
		private final AtomicReference<Edit> next=new AtomicReference<>();
		private volatile boolean ran;

		InterleavingRoot() {
			super(DLFSLattice.INSTANCE.zero());
		}

		void beforeNextWrite(Edit edit) {
			next.set(edit);
		}

		boolean ran() {
			return ran;
		}

		@Override protected boolean install(AVector<ACell> held,AVector<ACell> value) {
			Edit edit=next.getAndSet(null);
			if (edit!=null) {
				try {
					edit.run();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				ran=true;
			}
			return super.install(held,value);
		}
	}

	private static DLFSLocal view(Root<AVector<ACell>> root) {
		DLFSLocal fs=new DLFSLocal(DLFS.provider(),null,root);
		fs.setClock(() -> TIME);
		return fs;
	}

	private static byte[] bytes(int n,int value) {
		byte[] b=new byte[n];
		Arrays.fill(b,(byte)value);
		return b;
	}

	@Test void creatingAFileThatAnotherViewCreatesMeanwhileFails() throws IOException {
		var root=new InterleavingRoot();
		try (var fs=view(root); var other=view(root)) {
			DLPath path=fs.getPath("/file");
			Blob theirs=Blob.wrap(bytes(4,7));
			root.beforeNextWrite(() -> other.install(other.getPath("/file"),theirs,DLFSCondition.ANY));

			assertThrows(FileAlreadyExistsException.class,() -> fs.createFile(path));
			assertTrue(root.ran());
			assertEquals(theirs,DLFSNode.getData(fs.getNode(path)),"the other view's file is kept");
		}
	}

	@Test void creatingADirectoryWhoseParentAnotherViewDeletesMeanwhileFails() throws IOException {
		var root=new InterleavingRoot();
		try (var fs=view(root); var other=view(root)) {
			Files.createDirectory(fs.getPath("/parent"));
			root.beforeNextWrite(() -> other.delete(other.getPath("/parent")));

			assertThrows(NoSuchFileException.class,() -> Files.createDirectory(fs.getPath("/parent/child")));
			assertTrue(root.ran());
			assertNull(fs.getNode(fs.getPath("/parent")));
		}
	}

	@Test void deletingADirectoryThatAnotherViewFillsMeanwhileFails() throws IOException {
		var root=new InterleavingRoot();
		try (var fs=view(root); var other=view(root)) {
			Files.createDirectory(fs.getPath("/dir"));
			root.beforeNextWrite(() -> other.createFile(other.getPath("/dir/file")));

			assertThrows(DirectoryNotEmptyException.class,() -> fs.delete(fs.getPath("/dir")));
			assertTrue(root.ran());
			assertNotNull(fs.getNode(fs.getPath("/dir/file")),"the directory and the other view's file are kept");
		}
	}

	@Test void replacingANodeWhoseParentAnotherViewDeletesMeanwhileFails() throws IOException {
		var root=new InterleavingRoot();
		try (var fs=view(root); var other=view(root)) {
			Files.createDirectory(fs.getPath("/dir"));
			root.beforeNextWrite(() -> other.delete(other.getPath("/dir")));

			assertThrows(NoSuchFileException.class,
				() -> fs.updateNode(fs.getPath("/dir/file"),DLFSNode.createEmptyFile(TIME)));
			assertTrue(root.ran());
			assertNull(fs.getNode(fs.getPath("/dir")));
		}
	}

	@Test void aWriteThroughAnotherChannelMeanwhileIsKept() throws IOException {
		var root=new InterleavingRoot();
		try (var fs=view(root)) {
			DLPath path=fs.getPath("/file");
			int n=16;
			Files.write(path,new byte[2*n]);
			try (SeekableByteChannel mine=Files.newByteChannel(path,StandardOpenOption.WRITE);
					SeekableByteChannel theirs=Files.newByteChannel(path,StandardOpenOption.WRITE)) {
				// The other channel writes the second half after this channel has prepared the first
				root.beforeNextWrite(() -> theirs.position(n).write(ByteBuffer.wrap(bytes(n,2))));

				assertEquals(n,mine.write(ByteBuffer.wrap(bytes(n,1))));
				assertTrue(root.ran());
				assertEquals(n,mine.position());
				assertEquals(2L*n,theirs.position());
			}
			byte[] expected=new byte[2*n];
			Arrays.fill(expected,0,n,(byte)1);
			Arrays.fill(expected,n,2*n,(byte)2);
			assertArrayEquals(expected,Files.readAllBytes(path));
		}
	}
}
