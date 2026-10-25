package convex.lattice.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.fs.impl.DLFSLocal;

/** Mutation time belongs to the filesystem; replication preserves the stored times. */
class DLFSClockTest {
	@Test void forksAndClonesRetainADynamicClockWithoutSharingState() throws Exception {
		var time=new AtomicReference<>(CVMLong.ONE);
		try (var live=DLFS.create()) {
			live.setClock(time::get);
			try (var fork=live.fork(); var nested=fork.fork(); var clone=live.clone()) {
				time.set(CVMLong.TWO);
				Files.createDirectory(nested.getPath("/draft"));
				Files.createDirectory(clone.getPath("/snapshot"));
				assertEquals(CVMLong.TWO,DLFSNode.getUTime(nested.getCursor().get()));
				assertEquals(CVMLong.TWO,DLFSNode.getUTime(clone.getCursor().get()));
				assertFalse(Files.exists(live.getPath("/draft")));
				// Replacing the live clock does not reconfigure existing copies.
				live.setClock(() -> CVMLong.create(3));
				Files.createDirectory(live.getPath("/live"));
				time.set(CVMLong.create(4));
				Files.createDirectory(nested.getPath("/later"));
				assertEquals(time.get(),DLFSNode.getUTime(nested.getCursor().get()));
				Supplier<CVMLong> failClock=() -> { throw new AssertionError("Merge must not read a clock"); };
				live.setClock(failClock);
				fork.setClock(failClock);
				nested.setClock(failClock);
				nested.sync();
				assertFalse(Files.exists(live.getPath("/draft")),"nested sync stops at its immediate parent");
				fork.sync();
				assertTrue(Files.exists(live.getPath("/live")));
				assertTrue(Files.exists(live.getPath("/draft")));
				assertEquals(CVMLong.create(4),DLFSNode.getUTime(live.getCursor().get()));
				clone.sync();
				assertFalse(Files.exists(live.getPath("/snapshot")),"a clone is disconnected");
				live.merge(clone.getCursor().get());
				assertEquals(CVMLong.TWO,DLFSNode.getUTime(live.getNode(live.getPath("/snapshot"))));
			}
		}
	}

	@Test void aRetriedMutationReadsTheClockOnce() throws Exception {
		var attempts=new AtomicInteger();
		var metadata=Strings.create("concurrent edit");
		var root=new TransactionalRootCursor<AVector<ACell>>(DLFSLattice.INSTANCE.zero()) {
			@Override public boolean compareAndSet(AVector<ACell> expected,AVector<ACell> value) {
				if (attempts.incrementAndGet()==1) set(expected.assoc(DLFSNode.POS_METADATA,metadata));
				return super.compareAndSet(expected,value);
			}
		};
		var ticks=new AtomicInteger();
		try (var fs=new DLFSLocal(DLFS.provider(),null,root)) {
			fs.setClock(() -> CVMLong.create(ticks.incrementAndGet()));
			Files.createDirectory(fs.getPath("/directory"));
			assertEquals(2,attempts.get());
			assertEquals(1,ticks.get());
			assertEquals(metadata,root.get().get(DLFSNode.POS_METADATA));
			assertEquals(CVMLong.ONE,DLFSNode.getUTime(root.get()));
			assertEquals(CVMLong.ONE,DLFSNode.getUTime(fs.getNode(fs.getPath("/directory"))));
		}
	}
}
