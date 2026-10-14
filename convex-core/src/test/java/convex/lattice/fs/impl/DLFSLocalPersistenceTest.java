package convex.lattice.fs.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.RefSoft;
import convex.core.data.Blob;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSLattice;

public class DLFSLocalPersistenceTest {

	@Test
	public void testCheckpointReusedAfterUnrelatedRootChange() throws Exception {
		var cursor=Cursors.createLattice(DLFSLattice.INSTANCE);
		var other=new DLFSLocal(DLFS.provider(),"other",cursor);
		AtomicInteger checkpoints=new AtomicInteger();
		var fileSystem=new DLFSLocal(DLFS.provider(),"writer",cursor) {
			@Override protected ABlob checkpointBlob(ABlob data) throws IOException {
				checkpoints.incrementAndGet();
				Files.writeString(other.getPath("/sibling.txt"),"concurrent sibling");
				return data;
			}
		};
		var file=fileSystem.getPath("/file.txt");
		Files.writeString(file,"original");
		fileSystem.updateFileData(file,data -> Blob.fromHex("ab"),CVMLong.ONE,true);
		assertEquals(1,checkpoints.get(),"A sibling update must not repeat blob persistence");
		assertEquals(Blob.fromHex("ab"),convex.lattice.fs.DLFSNode.getData(fileSystem.getNode(file)));
		assertEquals("concurrent sibling",Files.readString(other.getPath("/sibling.txt")));
	}

	@Test
	public void testCheckpointRecomputedAfterSameFileChange() throws Exception {
		var cursor=Cursors.createLattice(DLFSLattice.INSTANCE);
		var other=new DLFSLocal(DLFS.provider(),"other",cursor);
		AtomicInteger checkpoints=new AtomicInteger();
		var fileSystem=new DLFSLocal(DLFS.provider(),"writer",cursor) {
			@Override protected ABlob checkpointBlob(ABlob data) throws IOException {
				if (checkpoints.incrementAndGet()==1) {
					try (var channel=Files.newByteChannel(other.getPath("/file.txt"),StandardOpenOption.WRITE)) {
						channel.position(3);
						channel.write(ByteBuffer.wrap(new byte[]{'B'}));
					}
				}
				return data;
			}
		};
		var file=fileSystem.getPath("/file.txt");
		Files.writeString(file,"0000");
		fileSystem.updateFileData(file,data -> data.replaceSlice(0,Blob.fromHex("41")),CVMLong.ONE,true);
		assertEquals(2,checkpoints.get());
		assertEquals("A00B",Files.readString(file));
	}

	@Test
	public void testCheckpointFailureDoesNotInstallFileUpdate() throws Exception {
		var cursor=Cursors.createLattice(DLFSLattice.INSTANCE);
		var fileSystem=new DLFSLocal(DLFS.provider(),"writer",cursor) {
			@Override protected ABlob checkpointBlob(ABlob data) throws IOException {
				throw new IOException("checkpoint failed");
			}
		};
		var file=fileSystem.getPath("/file.txt");
		Files.writeString(file,"original");
		var before=cursor.get();
		assertThrows(IOException.class,() -> fileSystem.updateFileData(file,
			data -> Blob.fromHex("ab"),CVMLong.ONE,true));
		assertSame(before,cursor.get());
		assertEquals("original",Files.readString(file));
	}

	private static ABlob testBlob() {
		return Blobs.createRandom(new Random(702),64*1024);
	}

	@Test
	public void testStorelessCheckpointIsIdentity() throws Exception {
		DLFSLocal fileSystem=DLFSLocal.create(DLFS.provider());
		ABlob data=testBlob();
		Hash rootHash=fileSystem.getRootHash();

		assertSame(data,fileSystem.checkpointBlob(data));
		assertEquals(rootHash,fileSystem.getRootHash(),
			"checkpointing must not mutate the filesystem cursor");
	}

	@Test
	public void testStoreCheckpointPreservesLogicalValue() throws Exception {
		ABlob data=testBlob();
		try (EtchStore store=EtchStore.createTemp("dlfs-checkpoint-contract")) {
			var cursor=Cursors.createLattice(DLFSLattice.INSTANCE);
			DLFSLocal fileSystem=DLFSLocal.create(DLFS.provider(),"contract",cursor,store);
			Hash rootHash=fileSystem.getRootHash();

			ABlob checkpointed=fileSystem.checkpointBlob(data);

			assertEquals(data,checkpointed);
			assertEquals(data.getHash(),checkpointed.getHash());
			assertEquals(rootHash,fileSystem.getRootHash(),
				"checkpointing must not mutate the filesystem cursor");
			RefSoft<?> storedRef=assertInstanceOf(RefSoft.class,checkpointed.getRef(0));
			assertSame(store,storedRef.getStore());
			assertEquals(data.byteAt(0),checkpointed.byteAt(0));
			assertEquals(data.byteAt(data.count()-1),checkpointed.byteAt(checkpointed.count()-1));
		}
	}
}
