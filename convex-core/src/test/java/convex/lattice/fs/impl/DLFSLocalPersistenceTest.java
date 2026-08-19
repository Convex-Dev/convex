package convex.lattice.fs.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.RefSoft;
import convex.etch.EtchStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSLattice;

public class DLFSLocalPersistenceTest {

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
