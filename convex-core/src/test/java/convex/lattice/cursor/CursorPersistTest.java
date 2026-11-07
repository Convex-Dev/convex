package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.store.MemoryStore;

/** Persisting through a cursor installs the persisted value wherever the cursor holds one. */
public class CursorPersistTest {

	private final MemoryStore store=new MemoryStore();
	private final List<ACell> inputs=new ArrayList<>();
	private final List<ACell> outputs=new ArrayList<>();

	/** Persists into the test store, recording what was persisted and what came back. */
	private <V extends ACell> V record(V value) throws IOException {
		inputs.add(value);
		V persisted=Cells.persist(value,store);
		outputs.add(persisted);
		return persisted;
	}

	/** Large enough to be stored under its own hash rather than embedded. */
	private static ABlob blob(int seed) {
		return Blobs.createRandom(new Random(seed),1000);
	}

	@Test public void rootInstallsThePersistedValue() throws IOException {
		ABlob blob=blob(1);
		Root<ASet<ACell>> root=Root.create(Sets.of(blob));
		var persisted=root.persist(this::record);
		assertSame(persisted,root.get());
		assertNotNull(store.refForHash(blob.getHash()));
	}

	@Test public void viewStoresCellsButLeavesItsSource() throws IOException {
		Keyword key=Keyword.intern("k");
		ABlob blob=blob(2);
		Root<Index<Keyword,ACell>> root=Root.create(Index.of(key,Sets.of(blob)));
		var before=root.get();
		root.<ASet<ACell>>path(key).persist(this::record);
		assertNotNull(store.refForHash(blob.getHash()));
		assertSame(before,root.get());
	}

	@Test public void forkPersistsItsWorkingValueThenItsBaseline() throws IOException {
		Root<ASet<ACell>> root=Root.create(Sets.empty());
		var fork=root.fork();
		ABlob a=blob(3);
		ABlob b=blob(4);
		fork.updateAndGet(s -> s.include(a));
		fork.sync((own,other) -> own.includeAll(other));
		fork.updateAndGet(s -> s.include(b));

		fork.persist(this::record);
		assertEquals(List.of(Sets.of(a,b),Sets.of(a)),inputs,"the working value, then the baseline");
		assertNotSame(inputs.get(1),outputs.get(1),"persisting rebuilds the baseline with stored references");
		fork.persist(this::record);
		assertSame(outputs.get(0),inputs.get(2),"the working value was replaced");
		assertSame(outputs.get(1),inputs.get(3),"the baseline was replaced");
	}

	@Test public void persistNeverRewindsABaselineMovedBySync() throws IOException {
		Root<ASet<ACell>> root=Root.create(Sets.empty());
		var fork=root.fork();
		BinaryOperator<ASet<ACell>> noMerge=(own,other) -> {
			throw new AssertionError("Only this fork writes its destination, so no merge is needed");
		};
		ABlob a=blob(5);
		ABlob b=blob(6);
		ABlob c=blob(7);
		fork.updateAndGet(s -> s.include(a));
		fork.sync(noMerge);

		var calls=new AtomicInteger();
		fork.persist(value -> {
			// The second call persists the baseline: a sync lands first and moves it.
			if (calls.incrementAndGet()==2) {
				fork.updateAndGet(s -> s.include(b));
				fork.sync(noMerge);
			}
			return Cells.persist(value,store);
		});
		fork.updateAndGet(s -> s.include(c));
		assertEquals(Sets.of(a,b,c),fork.sync(noMerge));
	}
}
