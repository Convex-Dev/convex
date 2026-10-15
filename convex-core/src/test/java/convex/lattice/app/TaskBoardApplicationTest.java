package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

/** A transaction records completion and plans the next task; publication remains a separate choice. */
class TaskBoardApplicationTest {
	private static final Keyword BOARD=Keyword.intern("board");
	private static final Keyword PLANNED=Keyword.intern("planned");
	private static final Keyword DONE=Keyword.intern("done");
	private static final KeyedLattice BOARD_LATTICE=KeyedLattice.create(
		PLANNED,SetLattice.create(),DONE,SetLattice.create());
	private static final KeyedLattice LATTICE=KeyedLattice.create(BOARD,BOARD_LATTICE);

	static final class TaskBoardApp extends ALatticeApplication<Index<Keyword,ACell>> {
		TaskBoardApp(LatticeHost<Index<Keyword,ACell>> host) { super(LATTICE,host); }
		Board board() { return new Board(resolve(BOARD)); }
	}

	static final class Board extends ALatticeComponent<Index<Keyword,ACell>>
			implements TransactionalComponent<Index<Keyword,ACell>,Board> {
		Board(AForkableCursor<Index<Keyword,ACell>> cursor) { super(cursor); }
		@Override public Board withCursor(AForkableCursor<Index<Keyword,ACell>> cursor) { return new Board(cursor); }
		Tasks planned() { return new Tasks(resolve(cursor,BOARD_LATTICE,PLANNED)); }
		Tasks completed() { return new Tasks(resolve(cursor,BOARD_LATTICE,DONE)); }
		ASet<AString> pending() { return planned().all().excludeAll(completed().all()); }
	}

	static final class Tasks extends ALatticeComponent<ASet<AString>> {
		Tasks(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		void add(String task) { cursor.updateAndGet(tasks -> emptyIfNull(tasks).include(Strings.create(task))); }
		ASet<AString> all() { return emptyIfNull(cursor.get()); }
		private static ASet<AString> emptyIfNull(ASet<AString> tasks) { return tasks==null?Sets.empty():tasks; }
	}

	@Test void completeAndPlanAtomicallyThenPublish() throws Exception {
		var host=LatticeHost.create();
		var app=new TaskBoardApp(host);
		host.configureApplications(List.of(app));
		var board=app.board();
		board.planned().add("Write the example");
		board.transact(draft -> {
			draft.planned().add("Review the example");
			draft.completed().add("Write the example");
			assertEquals(Sets.of("Write the example"),board.planned().all());
			assertTrue(board.completed().all().isEmpty());
		});
		assertEquals(Sets.of("Review the example"),board.pending());
		assertEquals(Sets.of("Write the example"),board.completed().all());
		assertTrue(host.cursor().get().isEmpty());

		board.transactAsync(draft -> draft.completed().add("Review the example"))
			.thenRun(app::sync).get(10,TimeUnit.SECONDS);
		assertEquals(app.cursor().get(),host.cursor().get());
		assertTrue(board.pending().isEmpty());
	}

	@Test void abandonedPlanLeavesTheBoardUnchanged() {
		var host=LatticeHost.create();
		var app=new TaskBoardApp(host);
		var board=app.board();
		board.planned().add("Review");
		assertThrows(IllegalStateException.class,() -> board.transact(draft -> {
			draft.planned().add("Publish");
			draft.completed().add("Review");
			throw new IllegalStateException("Review not approved");
		}));
		assertEquals(Sets.of("Review"),board.pending());
		assertTrue(board.completed().all().isEmpty());
	}
}
