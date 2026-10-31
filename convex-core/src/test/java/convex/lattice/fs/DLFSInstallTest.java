package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.RefSoft;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobOutputStream;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.impl.DLFSLocal;

public class DLFSInstallTest {

	private static ABlob content(int n) {
		return Blobs.createRandom(new Random(n*13L+1),n);
	}

	private static DLFSLocal drive(AtomicLong clock) {
		DLFSLocal fs=DLFS.createLocal();
		fs.setClock(() -> CVMLong.create(clock.get()));
		return fs;
	}

	private static long mtime(DLFileSystem fs, DLPath path) throws IOException {
		return DLFSNode.getMTime(fs.getNode(path)).longValue();
	}

	@Test public void testCreateAndReplace() throws IOException {
		AtomicLong clock=new AtomicLong(100);
		DLFSLocal fs=drive(clock);
		DLPath dir=fs.getPath("/dir");
		DLPath file=fs.getPath("/dir/file.bin");
		Files.createDirectory(dir);

		clock.set(200);
		ABlob first=content(5000);
		assertNull(fs.install(file,first,DLFSCondition.ANY));
		AVector<ACell> created=fs.getNode(file);
		assertEquals(first,DLFSNode.getData(created));
		assertEquals(200,DLFSNode.getUTime(created).longValue());
		assertEquals(200,mtime(fs,file));
		assertEquals(200,mtime(fs,dir),"Creating a file updates its parent");

		// Metadata and an unknown trailing field, which a replacement must keep
		clock.set(300);
		AVector<ACell> annotated=DLFSNode.withMetadata(created,Strings.create("meta"),CVMLong.create(300)).conj(CVMLong.create(42));
		fs.updateNode(file,annotated);
		AVector<ACell> before=fs.getNode(file);

		clock.set(400);
		ABlob second=content(100);
		assertEquals(before,fs.install(file,second,DLFSCondition.ANY));
		AVector<ACell> replaced=fs.getNode(file);
		assertEquals(second,DLFSNode.getData(replaced));
		assertEquals(Strings.create("meta"),DLFSNode.getMetadata(replaced));
		assertEquals(CVMLong.create(42),replaced.get(replaced.count()-1));
		assertEquals(before.count(),replaced.count());
		assertEquals(400,DLFSNode.getUTime(replaced).longValue());
		assertEquals(400,mtime(fs,file));
		assertEquals(200,mtime(fs,dir),"Replacing content leaves the parent alone");
	}

	@Test public void testConditions() throws IOException {
		AtomicLong clock=new AtomicLong(100);
		DLFSLocal fs=drive(clock);
		DLPath file=fs.getPath("/file.bin");

		assertNull(fs.install(file,content(10),DLFSCondition.ABSENT));
		AVector<ACell> root=fs.getCursor().get();
		assertThrows(ConditionFailedException.class,() -> fs.install(file,content(20),DLFSCondition.ABSENT));
		assertSame(root,fs.getCursor().get());

		Hash current=Cells.getHash(fs.getNode(file));
		clock.set(200);
		assertNotNull(fs.install(file,content(30),DLFSCondition.matching(current)));
		assertThrows(ConditionFailedException.class,() -> fs.install(file,content(40),DLFSCondition.matching(current)));
		assertEquals(content(30),DLFSNode.getData(fs.getNode(file)));

		// A deleted name counts as absent, and installing over it clears the tombstone
		clock.set(300);
		Files.delete(file);
		assertTrue(DLFSNode.getTombstones(fs.getNode(fs.getPath("/"))).containsKey(Strings.create("file.bin")));
		clock.set(400);
		assertNull(fs.install(file,content(50),DLFSCondition.ABSENT));
		assertFalse(DLFSNode.getTombstones(fs.getNode(fs.getPath("/"))).containsKey(Strings.create("file.bin")));
		assertEquals(content(50),DLFSNode.getData(fs.getNode(file)));

		// Any test of the entry may serve as a condition; it sees null for a missing file
		assertThrows(ConditionFailedException.class,() -> fs.install(fs.getPath("/other"),content(1),entry -> entry!=null));
		assertNull(fs.getNode(fs.getPath("/other")));
	}

	@Test public void testConditionAcrossViews() throws IOException {
		var cursor=Cursors.root(DLFSLattice.INSTANCE.zero());
		DLFSLocal a=new DLFSLocal(DLFS.provider(),"a",cursor);
		DLFSLocal b=new DLFSLocal(DLFS.provider(),"b",cursor);
		DLPath pathA=a.getPath("/shared.bin");
		DLPath pathB=b.getPath("/shared.bin");

		a.install(pathA,content(10),DLFSCondition.ANY);
		Hash read=Cells.getHash(a.getNode(pathA));
		b.install(pathB,content(20),DLFSCondition.ANY);

		assertThrows(ConditionFailedException.class,() -> a.install(pathA,content(30),DLFSCondition.matching(read)));
		assertEquals(content(20),DLFSNode.getData(a.getNode(pathA)));
	}

	@Test public void testInstallFailures() throws IOException {
		DLFSLocal fs=drive(new AtomicLong(100));
		Files.createDirectory(fs.getPath("/dir"));
		fs.install(fs.getPath("/file"),content(10),DLFSCondition.ANY);

		assertThrows(NoSuchFileException.class,() -> fs.install(fs.getPath("/missing/file"),content(10),DLFSCondition.ANY));
		assertThrows(NotDirectoryException.class,() -> fs.install(fs.getPath("/file/child"),content(10),DLFSCondition.ANY));
		FileSystemException overDirectory=assertThrows(FileSystemException.class,
			() -> fs.install(fs.getPath("/dir"),content(10),DLFSCondition.ANY));
		assertFalse(overDirectory instanceof ConditionFailedException);
		assertTrue(DLFSNode.isDirectory(fs.getNode(fs.getPath("/dir"))));
		assertThrows(FileSystemException.class,() -> fs.install(fs.getPath("/"),content(10),DLFSCondition.ANY));

		DLFSLocal other=DLFS.createLocal();
		assertThrows(IllegalArgumentException.class,() -> fs.install(other.getPath("/file"),content(10),DLFSCondition.ANY));
		assertThrows(IllegalArgumentException.class,() -> fs.newWriter(other.getPath("/file"),DLFSCondition.ANY));
	}

	@Test public void testWriterPublishesOnlyOnCommit() throws IOException {
		DLFSLocal fs=drive(new AtomicLong(100));
		DLPath file=fs.getPath("/file.bin");
		byte[] bytes=content(3*Blob.CHUNK_LENGTH+5).getBytes();

		DLFileWriter writer=fs.newWriter(file,DLFSCondition.ABSENT);
		writer.write(bytes,0,5000);
		assertNull(fs.getNode(file),"Nothing is visible before commit");
		writer.write(bytes,5000,bytes.length-5000);
		assertNull(writer.commit());
		assertArrayEquals(bytes,Files.readAllBytes(file));
		assertThrows(IOException.class,() -> writer.write(1));
		assertThrows(IOException.class,writer::commit);

		// Closing without commit discards the content
		AVector<ACell> root=fs.getCursor().get();
		try (DLFileWriter discarded=fs.newWriter(file,DLFSCondition.ANY)) {
			discarded.write(new byte[] {1,2,3});
		}
		assertSame(root,fs.getCursor().get());
		assertArrayEquals(bytes,Files.readAllBytes(file));

		// A failed condition finishes the writer and changes nothing
		DLFileWriter refused=fs.newWriter(file,DLFSCondition.ABSENT);
		refused.write(7);
		assertThrows(ConditionFailedException.class,refused::commit);
		assertThrows(IOException.class,() -> refused.write(8));
		assertSame(root,fs.getCursor().get());
	}

	@Test public void testWriteFromFailingStream() throws IOException {
		DLFSLocal fs=drive(new AtomicLong(100));
		DLPath file=fs.getPath("/file.bin");
		InputStream failing=new InputStream() {
			int sent=0;
			@Override public int read() throws IOException {
				if (sent>=10000) throw new IOException("source failed");
				return (sent++)&0xff;
			}
		};
		IOException e=assertThrows(IOException.class,() -> fs.write(file,failing,DLFSCondition.ANY));
		assertEquals("source failed",e.getMessage());
		assertNull(fs.getNode(file),"A failed source publishes nothing");

		assertNull(fs.write(file,new ByteArrayInputStream(new byte[] {1,2}),DLFSCondition.ANY));
		assertArrayEquals(new byte[] {1,2},Files.readAllBytes(file));
	}

	@Test public void testWriterSavesToDriveStore() throws IOException {
		MemoryStore store=new MemoryStore();
		DLFSLocal fs=DLFSLocal.create(DLFS.provider(),"stored",Cursors.root(DLFSLattice.INSTANCE.zero()),store);
		DLPath file=fs.getPath("/large.bin");
		byte[] bytes=content((int)BlobOutputStream.DEFAULT_PERSIST_INTERVAL+Blob.CHUNK_LENGTH).getBytes();
		Hash firstChunk=Blob.wrap(bytes,0,Blob.CHUNK_LENGTH).getHash();

		DLFileWriter writer=fs.newWriter(file,DLFSCondition.ANY);
		writer.write(bytes);
		assertNotNull(store.refForHash(firstChunk),"Content is saved before commit");
		assertNull(fs.getNode(file));
		writer.commit();

		ABlob data=DLFSNode.getData(fs.getNode(file));
		assertArrayEquals(bytes,data.getBytes());
		assertNotNull(store.refForHash(data.getHash()));
	}

	@Test public void testWriterLeavesSoftBranchesInEtch() throws IOException {
		try (EtchStore store=EtchStore.createTemp("dlfs-writer")) {
			DLFSLocal fs=DLFSLocal.create(DLFS.provider(),"etch",Cursors.root(DLFSLattice.INSTANCE.zero()),store);
			DLPath file=fs.getPath("/file.bin");
			byte[] bytes=content(5*Blob.CHUNK_LENGTH).getBytes();
			fs.write(file,new ByteArrayInputStream(bytes),DLFSCondition.ANY);

			ABlob data=DLFSNode.getData(fs.getNode(file));
			assertArrayEquals(bytes,data.getBytes());
			RefSoft<?> ref=assertInstanceOf(RefSoft.class,data.getRef(0));
			assertSame(store,ref.getStore());
		}
	}

	@Test public void testWriteAllBytes() throws IOException {
		DLFSLocal fs=drive(new AtomicLong(100));
		DLPath file=fs.getPath("/file.bin");
		byte[] bytes={1,2,3};
		assertTrue(fs.writeAllBytes(file,bytes));
		bytes[0]=9;
		assertArrayEquals(new byte[] {1,2,3},Files.readAllBytes(file),"Content is copied");
		assertFalse(fs.writeAllBytes(file,new byte[] {4}));
		assertArrayEquals(new byte[] {4},Files.readAllBytes(file));
	}
}
