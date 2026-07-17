package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

public class DLFSNodeTest {

	@Test
	public void testArbitraryMetadata() {
		AVector<ACell> original=DLFSNode.createEmptyFile(CVMLong.create(1));
		AString metadata=Strings.create("application-defined");

		AVector<ACell> updated=DLFSNode.withMetadata(original, metadata, CVMLong.create(2));

		assertNull(DLFSNode.getMetadata(original));
		assertSame(metadata, DLFSNode.getMetadata(updated));
		assertEquals(CVMLong.create(2), DLFSNode.getUTime(updated));
		assertNull(DLFSNode.getMetadata(null));
	}

	@Test
	public void testMetadataCanBeCleared() {
		AVector<ACell> node=DLFSNode.withMetadata(
			DLFSNode.createDirectory(CVMLong.create(1)), Strings.create("value"), CVMLong.create(2));

		AVector<ACell> cleared=DLFSNode.withMetadata(node, null, CVMLong.create(3));

		assertNull(DLFSNode.getMetadata(cleared));
		assertEquals(CVMLong.create(3), DLFSNode.getUTime(cleared));
		assertThrows(IllegalArgumentException.class,
			()->DLFSNode.withMetadata(node, null, null));
	}

	@Test
	public void testDirectoryMergePreservesNewerMetadataWithoutWalkingIt() {
		AString ownMetadata=Strings.create("own");
		AString newerMetadata=Strings.create("newer");

		AVector<ACell> own=DLFSNode.withMetadata(
			DLFSNode.createDirectory(CVMLong.create(1)), ownMetadata, CVMLong.create(10));
		own=own.assoc(DLFSNode.POS_DIR,
			Index.of(Strings.create("a"), DLFSNode.createEmptyFile(CVMLong.create(10))));

		AVector<ACell> other=DLFSNode.withMetadata(
			DLFSNode.createDirectory(CVMLong.create(1)), newerMetadata, CVMLong.create(20));
		other=other.assoc(DLFSNode.POS_DIR,
			Index.of(Strings.create("b"), DLFSNode.createEmptyFile(CVMLong.create(20))));

		AVector<ACell> merged=DLFSNode.merge(own, other);

		assertSame(newerMetadata, DLFSNode.getMetadata(merged));
		assertEquals(2L, DLFSNode.getDirectoryEntries(merged).count());
	}

	@Test
	public void testEqualTimestampMetadataPrefersOwnValue() {
		CVMLong timestamp=CVMLong.create(10);
		AString ownMetadata=Strings.create("own");
		AString otherMetadata=Strings.create("other");
		AVector<ACell> own=DLFSNode.withMetadata(DLFSNode.createDirectory(timestamp), ownMetadata, timestamp);
		AVector<ACell> other=DLFSNode.withMetadata(DLFSNode.createDirectory(timestamp), otherMetadata, timestamp);

		assertSame(own, DLFSNode.merge(own, other));
		assertSame(ownMetadata, DLFSNode.getMetadata(DLFSNode.merge(own, other)));
	}
}
