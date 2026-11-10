package convex.lattice.fs.impl;

import static convex.test.HeldCells.assertHeldSoftly;
import static convex.test.HeldCells.assertOnlyStrongAround;
import static convex.test.HeldCells.assertStrongOnlyOnTheWayTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.etch.EtchStore;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.Transactions;
import convex.lattice.fs.DLFS;
import convex.lattice.fs.DLFSCondition;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLPath;

/**
 * What a drive keeps in memory as files are written. A drive's cursor may be a root,
 * a fork or a path into a larger value, with file data on the heap or checkpointed
 * into a store, and the drive behaves the same over each. Saved data stays
 * reclaimable through a write, including when the save lands in the middle of it,
 * and content checkpointed while it is being written stays reclaimable when the
 * write is published.
 */
public class DLFSLocalHeldCellsTest {

	private static final AString DRIVE=Strings.create("drive");
	private static final int CHUNK=Blob.CHUNK_LENGTH;
	private static final int FILES=4;
	private static final CVMLong TIME=CVMLong.create(1000);
	private static final Runnable NOTHING=() -> {};

	private EtchStore store;

	@BeforeEach void openStore() throws IOException {
		store=EtchStore.createTemp("dlfs-held-cells");
	}

	@AfterEach void closeStore() {
		store.close();
	}

	// ===== Content =====

	/** Several chunks, so the blob is a tree with a branch cell for each chunk. */
	private static ABlob content(long seed) {
		return Blobs.createRandom(new Random(seed),5L*CHUNK);
	}

	/** One whole chunk, which replaces a branch cell of a file exactly. */
	private static Blob chunk(long seed) {
		return Blob.wrap(Blobs.createRandom(new Random(seed),CHUNK).getBytes());
	}

	// ===== Where a drive keeps its root, and its file data =====

	/**
	 * A drive, the cursor that holds its value and so is the one to persist, its
	 * clock, and an action run whenever it checkpoints content.
	 */
	private record Drive(DLFSLocal fs,ACursor<? extends ACell> holder,boolean stored,
			AtomicLong clock,AtomicReference<Runnable> atCheckpoint) {}

	private record Storage(String name,boolean stored,Function<AStore,Drive> create) {
		@Override public String toString() { return name; }
	}

	private static Drive drive(AForkableCursor<AVector<ACell>> cursor,ACursor<? extends ACell> holder,AStore store) {
		var atCheckpoint=new AtomicReference<Runnable>(NOTHING);
		var clock=new AtomicLong(TIME.longValue());
		DLFSLocal fs=new DLFSLocal(DLFS.provider(),null,cursor,store) {
			@Override protected ABlob checkpointBlob(ABlob data) throws IOException {
				atCheckpoint.get().run();
				return super.checkpointBlob(data);
			}
		};
		fs.setClock(() -> CVMLong.create(clock.get()));
		return new Drive(fs,holder,store!=null,clock,atCheckpoint);
	}

	private static Stream<Storage> cursors(boolean stored) {
		String data=stored?", checkpointing to a store":", file data on the heap";
		return Stream.of(
			new Storage("root"+data,stored,store -> {
				var root=Cursors.root(DLFSLattice.INSTANCE.zero());
				return drive(root,root,store);
			}),
			new Storage("fork"+data,stored,store -> {
				var fork=Cursors.root(DLFSLattice.INSTANCE.zero()).fork();
				return drive(fork,fork,store);
			}),
			new Storage("path into a larger value"+data,stored,store -> {
				Root<AHashMap<AString,ACell>> parent=Cursors.root(Maps.of(DRIVE,DLFSLattice.INSTANCE.zero()));
				return drive(parent.path(DRIVE),parent,store);
			}));
	}

	static Stream<Storage> storage() {
		return Stream.concat(cursors(false),cursors(true));
	}

	static Stream<Storage> checkpointing() {
		return cursors(true);
	}

	private Drive create(Storage storage) {
		return storage.create.apply(storage.stored?store:null);
	}

	private static DLPath file(DLFSLocal fs,int i) {
		return fs.getPath("/dir/file"+i+".bin");
	}

	/** A directory of multi-chunk files, with nothing of the drive itself saved. */
	private static void populate(DLFSLocal fs) throws IOException {
		Files.createDirectory(fs.getPath("/dir"));
		for (int i=0;i<FILES;i++) fs.install(file(fs,i),content(i),DLFSCondition.ANY);
	}

	private <V extends ACell> void persist(ACursor<V> holder) {
		try { holder.persist(value -> Cells.persist(value,store)); }
		catch (IOException e) { throw new UncheckedIOException(e); }
	}

	/** Runs the action the first time only, since the callbacks it is injected into may repeat. */
	private static Runnable once(Runnable action) {
		var done=new AtomicBoolean();
		return () -> { if (done.compareAndSet(false,true)) action.run(); };
	}

	private static AVector<ACell> root(DLFSLocal fs) {
		return fs.getCursor().get();
	}

	private static ABlob data(DLFSLocal fs,DLPath path) throws IOException {
		return DLFSNode.getData(fs.getNode(path));
	}

	/**
	 * Content that went through a checkpoint on a drive with a store is reclaimable at
	 * once, and only the entries leading to its file are strong. Otherwise the content
	 * is strong, with the entries leading to it.
	 */
	private void assertOnlyTheWriteIsStrong(Drive drive,DLPath path,ABlob content,boolean checkpointed) throws IOException {
		if (checkpointed && drive.stored) {
			assertHeldSoftly(store,data(drive.fs,path));
			assertStrongOnlyOnTheWayTo(root(drive.fs),drive.fs.getNode(path));
		} else {
			assertOnlyStrongAround(root(drive.fs),content);
		}
	}

	// ===== Writes to saved data =====

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void aFileUpdateAfterASaveLeavesOnlyTheNewContentStrong(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		persist(drive.holder);
		DLPath path=file(drive.fs,1);
		Blob written=chunk(100);

		drive.fs.updateFileData(path,data -> data.replaceSlice(2L*CHUNK,written),TIME,false);

		assertEquals(content(1).replaceSlice(2L*CHUNK,written),data(drive.fs,path));
		assertOnlyTheWriteIsStrong(drive,path,written,false);
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void anInstallAfterASaveLeavesOnlyTheNewContentStrong(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		persist(drive.holder);
		DLPath path=file(drive.fs,1);
		ABlob installed=content(100);

		drive.fs.install(path,installed,DLFSCondition.ANY);

		assertEquals(installed,data(drive.fs,path));
		assertOnlyTheWriteIsStrong(drive,path,installed,true);
	}

	// ===== A save landing during a write =====

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void aSaveLandingDuringAFileUpdateLeavesOnlyTheNewContentStrong(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		DLPath path=file(drive.fs,1);
		Blob written=chunk(100);
		Runnable save=once(() -> persist(drive.holder));

		drive.fs.updateFileData(path,data -> {
			save.run();
			return data.replaceSlice(2L*CHUNK,written);
		},TIME,false);

		assertEquals(content(1).replaceSlice(2L*CHUNK,written),data(drive.fs,path));
		assertOnlyTheWriteIsStrong(drive,path,written,false);
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void aSaveLandingDuringAnInstallLeavesOnlyTheNewContentStrong(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		DLPath path=file(drive.fs,1);
		ABlob installed=content(100);
		Runnable save=once(() -> persist(drive.holder));

		drive.fs.install(path,installed,existing -> {
			save.run();
			return true;
		});

		assertEquals(installed,data(drive.fs,path));
		assertOnlyTheWriteIsStrong(drive,path,installed,true);
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("checkpointing")
	void aSaveLandingDuringACheckpointLeavesTheFileReclaimable(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		DLPath path=file(drive.fs,1);
		Blob written=chunk(100);
		drive.atCheckpoint.set(once(() -> persist(drive.holder)));

		drive.fs.updateFileData(path,data -> data.replaceSlice(2L*CHUNK,written),TIME,true);

		assertEquals(content(1).replaceSlice(2L*CHUNK,written),data(drive.fs,path));
		assertOnlyTheWriteIsStrong(drive,path,written,true);
	}

	// ===== The same on every kind of cursor =====

	@Test void aDriveEndsWithTheSameValueOnEveryKindOfCursor() throws IOException {
		AVector<ACell> expected=null;
		for (Storage storage:storage().toList()) {
			Drive drive=create(storage);
			populate(drive.fs);
			drive.fs.updateFileData(file(drive.fs,1),data -> data.replaceSlice(CHUNK,chunk(100)),TIME,false);
			drive.fs.updateFileData(file(drive.fs,2),data -> data.slice(0,CHUNK),TIME,true);
			drive.fs.install(file(drive.fs,3),content(200),DLFSCondition.ANY);
			if (expected==null) expected=root(drive.fs);
			assertEquals(expected,root(drive.fs),storage.name);
		}
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void anUpdateThatLeavesTheContentEqualChangesNothing(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		var before=root(drive.fs);

		drive.fs.updateFileData(file(drive.fs,1),data -> content(1),CVMLong.create(2000),true);

		assertSame(before,root(drive.fs),"The same content in another object is no change, so nothing is written or stamped");
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("storage")
	void writingTheSameBytesAgainThroughAChannelChangesNothing(Storage storage) throws IOException {
		Drive drive=create(storage);
		populate(drive.fs);
		DLPath small=drive.fs.getPath("/dir/small.bin");
		byte[] few={1,2,3,4};
		Files.write(small,few);
		var before=root(drive.fs);
		drive.clock.set(2000);

		try (var channel=Files.newByteChannel(file(drive.fs,1),StandardOpenOption.WRITE)) {
			channel.write(ByteBuffer.wrap(content(1).getBytes()));
		}
		try (var channel=Files.newByteChannel(small,StandardOpenOption.WRITE)) {
			channel.write(ByteBuffer.wrap(few));
		}

		assertSame(before,root(drive.fs),"Neither file's content or time changed");
	}

	// ===== Content checkpointed before it is published =====

	@Test void contentCheckpointedInAForkIsHeldSoftlyBeforeAndAfterItIsPublished() throws IOException {
		Root<AVector<ACell>> destination=Cursors.root(DLFSLattice.INSTANCE.zero());
		DLFSLocal published=DLFSLocal.create(DLFS.provider(),null,destination,store);
		DLFSLocal working=published.fork();
		DLPath path=working.getPath("/large.bin");
		working.createFile(path);

		working.updateFileData(path,data -> content(1),TIME,true);

		assertHeldSoftly(store,data(working,path));
		assertNull(published.getNode(published.getPath("/large.bin")),"Nothing is published yet");

		working.sync();

		ABlob data=data(published,published.getPath("/large.bin"));
		assertArrayEquals(content(1).getBytes(),data.getBytes());
		assertHeldSoftly(store,data);
	}

	@Test void contentCheckpointedInATransactionIsHeldSoftlyOnceCommitted() throws IOException {
		Root<AVector<ACell>> cursor=Cursors.root(DLFSLattice.INSTANCE.zero());
		DLFSLocal live=DLFSLocal.create(DLFS.provider(),null,cursor,store);
		DLPath path=live.getPath("/large.bin");
		live.createFile(path);

		Transactions.execute(cursor,draft -> {
			DLFSLocal drafted=DLFSLocal.create(DLFS.provider(),null,draft,store);
			try {
				drafted.updateFileData(drafted.getPath("/large.bin"),data -> content(1),TIME,true);
				assertHeldSoftly(store,data(drafted,drafted.getPath("/large.bin")));
				assertEquals(0,data(live,path).count(),"Nothing is committed yet");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});

		ABlob data=data(live,path);
		assertArrayEquals(content(1).getBytes(),data.getBytes());
		assertHeldSoftly(store,data);
	}

	@Test void aSaveOfTheDriveKeepsCheckpointedContentAndLeavesNothingStrong() throws IOException {
		Root<AVector<ACell>> cursor=Cursors.root(DLFSLattice.INSTANCE.zero());
		DLFSLocal fs=DLFSLocal.create(DLFS.provider(),null,cursor,store);
		DLPath path=fs.getPath("/large.bin");
		fs.createFile(path);
		fs.updateFileData(path,data -> content(1),TIME,true);
		ABlob checkpointed=data(fs,path);

		persist(cursor);

		assertHeldSoftly(store,cursor.get());
		assertEquals(checkpointed,data(fs,path));
	}
}
