package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.fs.impl.DLFSLocal;

public class DLFSCorruptionTest {

	private static final CVMLong TS=CVMLong.create(100);

	@Test
	public void testCorruptionIsDistinctFromAbsence() throws Exception {
		AVector<ACell> corrupt=Vectors.of(Strings.create("broken"));
		AVector<ACell> source=file(1);
		Index<AString,AVector<ACell>> entries=Index.of(Strings.create("bad"),corrupt);
		entries=entries.assoc(Strings.create("source"),source);
		DLFSLocal fs=drive(entries);

		DLPath missing=fs.getPath("/missing");
		DLPath bad=fs.getPath("/bad");
		assertNull(fs.getNode(missing));
		assertThrows(DLFSCorruptionException.class,()->fs.getNode(bad));
		assertThrows(DLFSCorruptionException.class,
			()->Files.readAttributes(bad,BasicFileAttributes.class));
		assertThrows(DLFSCorruptionException.class,()->Files.createDirectory(bad));
		assertThrows(DLFSCorruptionException.class,()->Files.copy(fs.getPath("/source"),bad));
		Files.delete(bad);
		assertNull(fs.getNode(bad));
		Files.createDirectory(bad);
		assertTrue(Files.isDirectory(bad));
	}

	@Test
	public void testReadableSiblingsAndDirectoryListingSurviveCorruptEntry() throws Exception {
		AVector<ACell> corrupt=Vectors.of(Strings.create("broken"));
		Index<AString,AVector<ACell>> entries=Index.of(Strings.create("bad"),corrupt);
		entries=entries.assoc(Strings.create("good"),file(7));
		DLFSLocal fs=drive(entries);

		assertArrayEquals(new byte[] {7},Files.readAllBytes(fs.getPath("/good")));
		Set<String> names=new HashSet<>();
		try (DirectoryStream<Path> stream=Files.newDirectoryStream(fs.getRoot())) {
			for (Path path:stream) names.add(path.getFileName().toString());
		}
		assertTrue(names.contains("bad"));
		assertTrue(names.contains("good"));
	}

	@Test
	public void testReadableChildThroughDamagedDirectory() throws Exception {
		Index<AString,AVector<ACell>> children=Index.of(Strings.create("child"),file(3));
		AVector<ACell> damaged=Vectors.of(children,null,null,Strings.create("bad timestamp"));
		DLFSLocal fs=drive(Index.of(Strings.create("damaged"),damaged));
		DLPath directory=fs.getPath("/damaged");
		DLPath child=fs.getPath("/damaged/child");

		assertArrayEquals(new byte[] {3},Files.readAllBytes(child));
		try (DirectoryStream<Path> stream=Files.newDirectoryStream(directory)) {
			assertTrue(stream.iterator().hasNext());
		}
		assertThrows(DLFSCorruptionException.class,()->fs.getNode(directory));
		assertThrows(DLFSCorruptionException.class,()->Files.delete(child),
			"mutating through a corrupt parent must fail closed");

		assertThrows(java.nio.file.DirectoryNotEmptyException.class,()->Files.delete(directory),
			"readable children must not be discarded while repairing a corrupt directory");
		fs.updateNode(directory,DLFSNode.createDirectory(TS));
		assertTrue(Files.isDirectory(directory));
	}

	@Test
	public void testExplicitReplacementCanRepairCorruptEntry() throws Exception {
		AVector<ACell> corrupt=Vectors.of(Strings.create("broken"));
		DLFSLocal fs=drive(Index.of(Strings.create("bad"),corrupt));
		DLPath bad=fs.getPath("/bad");
		AVector<ACell> replacement=file(9);

		assertSame(replacement,fs.updateNode(bad,replacement));
		assertArrayEquals(new byte[] {9},Files.readAllBytes(bad));
	}

	@Test
	public void testDamagedRootCanBeMountedAndReplaced() throws Exception {
		Index<AString,AVector<ACell>> entries=Index.of(Strings.create("readable"),file(5));
		AVector<ACell> damagedRoot=Vectors.of(entries,null,null,Strings.create("bad timestamp"));
		DLFSLocal fs=new DLFSLocal(new DLFSProvider(),null,damagedRoot);

		assertArrayEquals(new byte[] {5},Files.readAllBytes(fs.getPath("/readable")));
		try (DirectoryStream<Path> stream=Files.newDirectoryStream(fs.getRoot())) {
			assertTrue(stream.iterator().hasNext());
		}
		assertThrows(DLFSCorruptionException.class,()->fs.getNode(fs.getRoot()));

		AVector<ACell> replacement=DLFSNode.createDirectory(TS);
		assertSame(replacement,fs.updateNode(fs.getRoot(),replacement));
		assertSame(replacement,fs.getNode(fs.getRoot()));
	}

	@Test
	public void testForeignMalformedDescendantIsRejected() {
		AVector<ACell> corrupt=Vectors.of(Strings.create("broken"));
		AVector<ACell> branch=DLFSNode.createDirectory(TS).assoc(DLFSNode.POS_DIR,
			Index.of(Strings.create("deep"),corrupt));
		AVector<ACell> foreign=DLFSNode.createDirectory(TS).assoc(DLFSNode.POS_DIR,
			Index.of(Strings.create("branch"),branch));
		AVector<ACell> own=DLFSNode.createDirectory(TS).assoc(DLFSNode.POS_DIR,
			Index.of(Strings.create("local"),file(1)));

		assertFalse(DLFSLattice.INSTANCE.checkForeign(foreign));
		assertSame(own,DLFSLattice.INSTANCE.merge(own,foreign));
		assertTrue(DLFSNode.isEmpty(DLFSLattice.INSTANCE.merge(null,foreign)));
	}

	private static AVector<ACell> file(int value) {
		return DLFSNode.createEmptyFile(TS).assoc(DLFSNode.POS_DATA,Blob.wrap(new byte[] {(byte)value}));
	}

	private static DLFSLocal drive(Index<AString,AVector<ACell>> entries) {
		AVector<ACell> root=DLFSNode.createDirectory(TS).assoc(DLFSNode.POS_DIR,entries);
		return new DLFSLocal(new DLFSProvider(),null,root);
	}
}
