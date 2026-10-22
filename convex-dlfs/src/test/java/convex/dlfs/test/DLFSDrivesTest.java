package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSDrives;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Root;
import convex.lattice.fs.DLFSNode;

public class DLFSDrivesTest {

	@Test
	public void testExistingDrivePreservedAndReopenedAtCapacity() throws Exception {
		var time=new java.util.concurrent.atomic.AtomicReference<>(CVMLong.create(100));
		var drives=DLFSDrives.create(time::get);
		var drive=drives.getOrCreateDrive("home",1);
		assertNotNull(drive);
		Files.writeString(drive.fileSystem().getPath("/hello.txt"),"preserved");
		var before=drives.cursor().get();
		time.set(CVMLong.create(200));
		assertSame(drive,drives.getOrCreateDrive("home",0));
		assertNull(drives.createDrive("home"));
		assertNull(drives.getOrCreateDrive("another",1));
		drive.close();
		var reopened=drives.getOrCreateDrive("home",1);
		assertNotSame(drive,reopened);
		assertEquals("preserved",Files.readString(reopened.fileSystem().getPath("/hello.txt")));
		assertSame(before,drives.cursor().get(),"opening must not change any lattice state");
	}

	@Test
	public void testInvalidAndMalformedEntriesPreserved() {
		var drives=DLFSDrives.create();
		var malformed=Vectors.<ACell>of(CVMLong.ONE);
		drives.cursor().set(Maps.of(Strings.create("broken"),malformed));
		var before=drives.cursor().get();
		assertNull(drives.getOrCreateDrive(null));
		assertNull(drives.getOrCreateDrive("../invalid"));
		assertNull(drives.getOrCreateDrive("broken"));
		assertNull(drives.getOrCreateDrive("absent",0));
		assertNull(drives.getOrCreateDrive("absent",-1));
		assertSame(before,drives.cursor().get());
	}

	@Test
	public void testRecreateUsesExactClockAndPreservesLocalTie() throws Exception {
		var time=CVMLong.create(100);
		var drives=DLFSDrives.create(() -> time);
		var old=drives.getOrCreateDrive("home",1);
		Files.writeString(old.fileSystem().getPath("/old.txt"),"deleted");
		assertTrue(drives.deleteDrive("home"));
		assertFalse(old.isOpen());
		var tombstone=drives.cursor().get();
		var recreated=drives.getOrCreateDrive("home",1);
		assertNotNull(recreated);
		assertNotSame(old,recreated);
		assertFalse(Files.exists(recreated.fileSystem().getPath("/old.txt")));
		assertEquals(time,DLFSNode.getUTime(recreated.cursor().get()));
		drives.cursor().updateAndGet(own->DLFSDrives.LATTICE.merge(own,tombstone));
		assertEquals(DLFSDrives.DriveStatus.LIVE,drives.driveStatus("home"));
	}

	@Test
	public void testForkCreationPublishesOnlyOnSync() {
		var drives=DLFSDrives.create();
		var working=drives.fork();
		assertNotNull(working.getOrCreateDrive("draft"));
		assertNull(drives.drive("draft"));
		working.sync();
		assertNotNull(drives.drive("draft"));
	}

	@Test
	public void testHostedDrivesUseSuppliedClock() {
		var host=LatticeHost.create(DLFSDrives.LATTICE.zero(),new MemoryStore());
		var time=CVMLong.create(123);
		var drives=DLFSDrives.connect(host.cursor(),host.store(),() -> time);
		var drive=drives.getOrCreateDrive("home");
		assertEquals(time,DLFSNode.getUTime(drive.cursor().get()));
	}

	@Test
	public void testConcurrentCreatorsBothOpenWinningDrive() throws Exception {
		for (boolean separateViews:List.of(false,true)) {
			var cursor=new RacingRoot();
			var first=DLFSDrives.wrap(cursor);
			var second=separateViews ? DLFSDrives.wrap(cursor) : first;
			try (var executor=Executors.newFixedThreadPool(2)) {
				var a=executor.submit(()->first.getOrCreateDrive("same",1));
				var b=executor.submit(()->second.getOrCreateDrive("same",1));
				var left=a.get(10,TimeUnit.SECONDS);
				var right=b.get(10,TimeUnit.SECONDS);
				assertNotNull(left);
				assertNotNull(right);
				Files.writeString(left.fileSystem().getPath("/left"),"left");
				Files.writeString(right.fileSystem().getPath("/right"),"right");
				assertEquals("left",Files.readString(right.fileSystem().getPath("/left")));
				assertEquals("right",Files.readString(left.fileSystem().getPath("/right")));
			}
			assertEquals(List.of("same"),first.driveNames());
		}
	}

	@Test
	public void testConcurrentNamesRespectCapacity() throws Exception {
		var cursor=new RacingRoot();
		var first=DLFSDrives.wrap(cursor);
		var second=DLFSDrives.wrap(cursor);
		try (var executor=Executors.newFixedThreadPool(2)) {
			var a=executor.submit(()->first.getOrCreateDrive("first",1));
			var b=executor.submit(()->second.getOrCreateDrive("second",1));
			assertTrue((a.get(10,TimeUnit.SECONDS)!=null) ^ (b.get(10,TimeUnit.SECONDS)!=null));
		}
		assertEquals(1,first.driveNames().size());
	}

	@Test
	public void testDeletionBeforeReturningDoesNotRetryCreation() {
		var name=Strings.create("home");
		var time=CVMLong.create(100);
		var cursor=new Root<AHashMap<AString,AVector<ACell>>>(Maps.empty()) {
			@Override
			public AHashMap<AString,AVector<ACell>> getAndUpdate(
					UnaryOperator<AHashMap<AString,AVector<ACell>>> update) {
				var previous=super.getAndUpdate(update);
				// A deletion lands after creation commits, before the view is returned.
				super.updateAndGet(registry->registry.assoc(name,DLFSNode.createEmptyFile(time)));
				return previous;
			}
		};
		var drives=DLFSDrives.wrap(cursor,() -> time);
		assertNull(drives.getOrCreateDrive("home"));
		assertEquals(DLFSDrives.DriveStatus.DELETED,drives.driveStatus("home"));
	}

	/** Forces two updates to prepare from the same input, exercising the CAS retry. */
	private static final class RacingRoot extends Root<AHashMap<AString,AVector<ACell>>> {
		private final CountDownLatch prepared=new CountDownLatch(2);

		RacingRoot() { super(Maps.empty()); }

		@Override
		public AHashMap<AString,AVector<ACell>> getAndUpdate(
				UnaryOperator<AHashMap<AString,AVector<ACell>>> update) {
			var firstAttempt=new AtomicBoolean(true);
			return super.getAndUpdate(value->{
				var result=update.apply(value);
				if (firstAttempt.getAndSet(false)) {
					prepared.countDown();
					try {
						assertTrue(prepared.await(5,TimeUnit.SECONDS),"both creators must prepare an update");
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError(e);
					}
				}
				return result;
			});
		}
	}
}
