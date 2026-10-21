package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.SingleRegion;
import convex.lattice.Timestamped;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.IndexLattice;

/**
 * A task board with stable IDs and independently ordered task records:
 * <pre>{@code
 * {:board {task-id {:timestamp t :title "Review" :done false}}}
 * }</pre>
 * Titles may repeat or change without changing identity. Completion and reopening
 * update a task's record; its newer timestamp wins over an older status on merge.
 * Different task IDs merge independently. Concurrent edits to the same task select
 * one whole record, with the established record winning timestamp ties.
 *
 * <p>{@link Board} owns one staged copy of the task index. Transactions commit
 * changes to several tasks together into that copy; exceptions discard the attempt.
 * Application sync subsequently publishes the board to the host. The transaction
 * callback may be retried, so callers supply stable IDs chosen before entering it.
 * IDs must be unique across the writers of a board; the test IDs are fixtures.</p>
 *
 * <p>The clock is an explicit capability shared by rebound transaction views.
 * {@link Timestamped} handles local stamping and supplies the payload ordering to
 * the board's index lattice. This example has no signing policy.</p>
 */
class TaskBoardApplicationTest {
	private static final Keyword BOARD=Keyword.intern("board");
	private static final Keyword TITLE=Keyword.intern("title");
	private static final Keyword DONE=Keyword.intern("done");
	private static final IndexLattice<AString,AHashMap<Keyword,ACell>> BOARD_LATTICE=
		IndexLattice.create(Timestamped.lattice());

	/** Constructs the domain component directly at its host path. */
	static final class TaskBoardApp extends SingleRegion<Board> {
		TaskBoardApp(LatticeHost<Index<Keyword,ACell>> host,Supplier<CVMLong> getTimestamp) {
			super(host,BOARD,new Board(host.cursor().path(BOARD),getTimestamp));
		}
		Board board() { return component(); }
	}

	/** One publication and transaction boundary spanning all task records. */
	static final class Board extends ALatticeComponent<Index<AString,AHashMap<Keyword,ACell>>>
			implements TransactionalComponent<Index<AString,AHashMap<Keyword,ACell>>,Board> {
		private final Supplier<CVMLong> getTimestamp;

		Board(ACursor<Index<AString,AHashMap<Keyword,ACell>>> destination,Supplier<CVMLong> getTimestamp) {
			super(destination);
			this.getTimestamp=getTimestamp;
		}

		// Rebinding shares the supplied draft directly and retains the clock, not the source board.
		private Board(Board source,AForkableCursor<Index<AString,AHashMap<Keyword,ACell>>> cursor) {
			super(cursor);
			getTimestamp=source.getTimestamp;
		}

		@Override public Board withCursor(AForkableCursor<Index<AString,AHashMap<Keyword,ACell>>> cursor) {
			return new Board(this,cursor);
		}

		@Override public Index<AString,AHashMap<Keyword,ACell>> merge(Index<AString,AHashMap<Keyword,ACell>> incoming) {
			return cursor.updateAndGet(own -> BOARD_LATTICE.merge(own,incoming));
		}

		@Override protected Index<AString,AHashMap<Keyword,ACell>> mergePublication(
				Index<AString,AHashMap<Keyword,ACell>> own,Index<AString,AHashMap<Keyword,ACell>> other) {
			return BOARD_LATTICE.merge(own,other);
		}

		/** A delivery replaces a complete ordered record, never an individual task field. */
		@SuppressWarnings("unchecked")
		@Override protected Index<AString,AHashMap<Keyword,ACell>> mergeAtPath(ACell value,ACell... path) {
			if (path.length!=1 || !(path[0] instanceof AString id) || !(value instanceof AHashMap)) return cursor.get();
			return merge(Index.of(id,(AHashMap<Keyword,ACell>)value));
		}

		Task task(String id) {
			return new Task(cursor.withDefault(Index.none()).path(Strings.create(id)),getTimestamp);
		}

		void plan(String id,String title) { task(id).create(title); }

		/** Computes pending IDs from one immutable snapshot, independent of task titles. */
		ASet<AString> pending() {
			var tasks=cursor.get();
			ASet<AString> pending=Sets.empty();
			if (tasks!=null) {
				for (var entry:tasks.entrySet()) {
					if (!CVMBool.TRUE.equals(entry.getValue().get(DONE))) pending=pending.include(entry.getKey());
				}
			}
			return pending;
		}
	}

	/** Local operations on one timestamped record; no extra fork or publication step. */
	static final class Task extends ALatticeComponent<AHashMap<Keyword,ACell>> {
		Task(AForkableCursor<AHashMap<Keyword,ACell>> storage,Supplier<CVMLong> getTimestamp) {
			super(Timestamped.create(storage.withDefault(Maps.empty()),getTimestamp).cursor());
		}

		boolean exists() { return cursor.get().containsKey(TITLE); }
		AString title() { return (AString)cursor.get().get(TITLE); }
		boolean isDone() { return CVMBool.TRUE.equals(cursor.get().get(DONE)); }

		void create(String title) {
			cursor.update(record -> {
				if (record.containsKey(TITLE)) throw new IllegalStateException("Task ID already exists");
				return record.assoc(TITLE,Strings.create(title)).assoc(DONE,CVMBool.FALSE);
			});
		}

		void rename(String title) { cursor.update(record -> requireTask(record).assoc(TITLE,Strings.create(title))); }
		void complete() { setDone(true); }
		void reopen() { setDone(false); }
		private void setDone(boolean done) { cursor.update(record -> requireTask(record).assoc(DONE,CVMBool.create(done))); }
		private static AHashMap<Keyword,ACell> requireTask(AHashMap<Keyword,ACell> record) {
			if (!record.containsKey(TITLE)) throw new IllegalStateException("Task does not exist");
			return record;
		}
	}

	/** Readers see the old board until commit; the async continuation publishes only after commit. */
	@Test void completeAndPlanAtomicallyThenPublish() throws Exception {
		var host=LatticeHost.create();
		var app=new TaskBoardApp(host,() -> CVMLong.ONE);
		host.configureApplications(List.of(app));
		var board=app.board();
		board.plan("write","Write the example");
		board.transact(draft -> {
			draft.plan("review","Review the example");
			draft.task("write").complete();
			assertEquals(Sets.of("write"),board.pending());
			assertFalse(board.task("write").isDone());
			assertFalse(board.task("review").exists());
		});
		assertEquals(Sets.of("review"),board.pending());
		assertTrue(board.task("write").isDone());
		assertTrue(host.cursor().get().isEmpty());

		// The future signals commit and publication; the timeout only bounds test failure.
		board.transactAsync(draft -> draft.task("review").complete())
			.thenRun(app::sync).get(10,TimeUnit.SECONDS);
		assertEquals(board.cursor().get(),host.cursor().get().get(BOARD));
		assertTrue(board.pending().isEmpty());
	}

	/** Failure after changing two task records must leak neither change into the live board. */
	@Test void abandonedPlanLeavesTheBoardUnchanged() {
		var board=new TaskBoardApp(LatticeHost.create(),() -> CVMLong.ONE).board();
		board.plan("review","Review");
		var before=board.cursor().get();
		assertThrows(IllegalStateException.class,() -> board.transact(draft -> {
			draft.plan("publish","Publish");
			draft.task("review").complete();
			throw new IllegalStateException("Review not approved");
		}));
		assertSame(before,board.cursor().get());
		assertEquals(Sets.of("review"),board.pending());
		assertFalse(board.task("publish").exists());
	}

	/** Labels are mutable display data; duplicate labels and reopening never change identity. */
	@Test void duplicateTitlesRenameAndReopenPreserveTaskIdentity() {
		var clock=new AtomicReference<>(CVMLong.ONE);
		var board=new TaskBoardApp(LatticeHost.create(),clock::get).board();
		board.plan("first","Review");
		board.plan("second","Review");
		var first=board.task("first");
		clock.set(CVMLong.TWO);
		first.complete();
		assertEquals(Sets.of("second"),board.pending());
		clock.set(CVMLong.create(3));
		first.rename("Review revised draft");
		first.reopen();
		assertEquals(Sets.of("first","second"),board.pending());
		assertEquals(Strings.create("Review revised draft"),board.task("first").title());
		assertEquals(Strings.create("Review"),board.task("second").title());
		var before=board.cursor().get();
		assertThrows(IllegalStateException.class,() -> board.plan("first","Replacement"));
		clock.set(CVMLong.create(4));
		first.reopen(); // An unchanged edit must not stamp, even after the clock advances.
		assertSame(before,board.cursor().get());
	}

	/** Stale completion cannot undo a later reopen; different IDs survive replica merges. */
	@Test void replayCannotUndoReopeningOrDiscardAnotherTask() {
		var sourceHost=LatticeHost.create();
		var clock=new AtomicReference<>(CVMLong.ONE);
		var source=new TaskBoardApp(sourceHost,clock::get);
		source.board().plan("review","Review");
		clock.set(CVMLong.TWO);
		source.board().task("review").complete();
		source.sync();
		var completed=sourceHost.cursor().get();
		clock.set(CVMLong.create(3));
		source.board().task("review").reopen();
		source.sync();
		var reopened=sourceHost.cursor().get();
		for (var deliveries:List.of(List.of(completed,reopened),List.of(reopened,completed))) {
			var host=LatticeHost.create();
			var replica=new TaskBoardApp(host,() -> CVMLong.create(1000));
			host.configureApplications(List.of(replica));
			replica.board().plan("other","Independent task");
			for (var incoming:deliveries) host.merge(incoming);
			host.merge(completed);
			assertEquals(Sets.of("review","other"),replica.board().pending());
			assertEquals(CVMLong.create(3),replica.board().task("review").cursor().get().get(Timestamped.TIMESTAMP));
			assertTrue(host.cursor().get().isEmpty());
			replica.sync();
			assertEquals(replica.board().cursor().get(),host.cursor().get().get(BOARD));
		}
	}

	/** Whole-board and task-path delivery obey the same ordering and preserve other tasks. */
	@Test void taskDeliveryStopsAtTheOrderedRecord() {
		var clock=new AtomicReference<>(CVMLong.ONE);
		var source=new TaskBoardApp(LatticeHost.create(),clock::get).board();
		source.plan("review","Review");
		var planned=source.cursor().get();
		clock.set(CVMLong.TWO);
		source.task("review").complete();
		var completed=source.cursor().get();
		var whole=new TaskBoardApp(LatticeHost.create(),clock::get).board();
		var partial=new TaskBoardApp(LatticeHost.create(),clock::get).board();
		whole.plan("other","Independent task");
		partial.plan("other","Independent task");
		var id=Strings.create("review");
		for (var delivery:List.of(completed,planned,completed)) {
			whole.merge(delivery);
			partial.mergeAt(delivery.get(id),id);
			assertEquals(whole.cursor().get(),partial.cursor().get());
		}
		assertTrue(partial.task("review").isDone());
		assertEquals(Sets.of("other"),partial.pending());
		var before=partial.cursor().get();
		partial.mergeAt(CVMBool.FALSE,id,DONE);
		assertSame(before,partial.cursor().get());
	}

	/** Read-only handles and failed edits of absent tasks do not create records. */
	@Test void missingTasksAndUnchangedEditsAreInert() {
		var host=LatticeHost.create();
		var app=new TaskBoardApp(host,() -> { throw new AssertionError("No changed edit needs a timestamp"); });
		var missing=app.board().task("missing");
		assertFalse(missing.exists());
		assertNull(missing.title());
		assertThrows(IllegalStateException.class,missing::complete);
		assertThrows(IllegalStateException.class,missing::reopen);
		assertThrows(IllegalStateException.class,() -> missing.rename("No task"));
		missing.sync();
		app.sync();
		assertTrue(host.cursor().get().isEmpty());
	}
}
