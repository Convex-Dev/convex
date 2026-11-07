package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ReadOnlyCursorException;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;

/**
 * Transactions on lattice components, one behaviour per test. A bank holds branch
 * ledgers:
 * <pre>{@code
 * {:north {:balances {"alice" 100 "bob" 0} :timestamp 2}
 *  :south {:balances {"carol" 0} :timestamp 3}}
 * }</pre>
 * Each ledger operation is a separate edit, so only a transaction makes a transfer
 * atomic. A ledger's editing view stamps every changed edit from a clock that counts
 * its calls. Competing writers are scripted rather than threaded: the shared root
 * applies the next queued write just before a commit attempt, as another writer
 * committing first would, so every conflict happens at a known point.
 */
class TransactionBehaviourTest {

	private static final Keyword NORTH=Keyword.intern("north");
	private static final Keyword SOUTH=Keyword.intern("south");
	private static final Keyword EAST=Keyword.intern("east");
	private static final Keyword BALANCES=Keyword.intern("balances");
	private static final Keyword SEQ=Keyword.intern("seq");

	/** Branch ledgers under one root; a bank transaction can span branches. */
	private static final class Bank extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Bank> {
		private final Supplier<CVMLong> clock;

		Bank(AForkableCursor<AHashMap<Keyword,ACell>> cursor,Supplier<CVMLong> clock) {
			super(cursor);
			this.clock=clock;
		}

		Ledger branch(Keyword name) { return new Ledger(cursor().path(name),clock); }

		@Override public Bank withCursor(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return rebind(cursor); }
		@Override protected Bank rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return new Bank(cursor,clock); }
		@Override public Bank fork() { return rebind(storage.fork()); }
	}

	/** One branch's balances and receipt counter, stamping each changed edit. */
	private static final class Ledger extends ALatticeComponent<AHashMap<Keyword,ACell>>
			implements TransactionalComponent<AHashMap<Keyword,ACell>,Ledger> {
		private final Supplier<CVMLong> clock;
		private final Timestamped<AHashMap<Keyword,ACell>> stamped;

		Ledger(AForkableCursor<AHashMap<Keyword,ACell>> cursor,Supplier<CVMLong> clock) {
			super(cursor);
			this.clock=clock;
			stamped=Timestamped.create(cursor.withDefault(Maps.empty()),clock);
		}

		@Override public AForkableCursor<AHashMap<Keyword,ACell>> cursor() { return stamped.cursor(); }
		@Override public Ledger withCursor(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return rebind(cursor); }
		@Override protected Ledger rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return new Ledger(cursor,clock); }

		void open(String name,long amount) {
			AString key=Strings.create(name);
			cursor().update(record -> {
				if (balances(record).containsKey(key)) throw new IllegalStateException("Account exists");
				return record.assoc(BALANCES,balances(record).assoc(key,CVMLong.create(amount)));
			});
		}

		void deposit(String name,long amount) { adjust(name,amount); }

		void withdraw(String name,long amount) {
			if (balance(name)<amount) throw new IllegalStateException("Insufficient funds");
			adjust(name,-amount);
		}

		/** Debits, credits, then numbers the receipt: three edits, atomic only in a transaction. */
		long transfer(String from,String to,long amount) {
			withdraw(from,amount);
			deposit(to,amount);
			return seq(cursor().updateAndGet(record -> record.assoc(SEQ,CVMLong.create(seq(record)+1))));
		}

		long balance(String name) {
			ACell amount=balances(cursor().get()).get(Strings.create(name));
			if (amount==null) throw new IllegalStateException("No account");
			return ((CVMLong)amount).longValue();
		}

		long total() {
			long total=0;
			for (var entry:balances(cursor().get()).entrySet()) total+=((CVMLong)entry.getValue()).longValue();
			return total;
		}

		private void adjust(String name,long delta) {
			AString key=Strings.create(name);
			cursor().update(record -> {
				ACell amount=balances(record).get(key);
				if (amount==null) throw new IllegalStateException("No account");
				return record.assoc(BALANCES,balances(record).assoc(key,CVMLong.create(((CVMLong)amount).longValue()+delta)));
			});
		}

		@SuppressWarnings("unchecked")
		private static AHashMap<AString,ACell> balances(AHashMap<Keyword,ACell> record) {
			AHashMap<AString,ACell> balances=(AHashMap<AString,ACell>)record.get(BALANCES);
			return balances==null?Maps.empty():balances;
		}

		private static long seq(AHashMap<Keyword,ACell> record) {
			ACell seq=record.get(SEQ);
			return seq==null?0:((CVMLong)seq).longValue();
		}
	}

	/**
	 * Shared storage whose competing writers are scripted: each commit attempt first
	 * applies the next queued write. Without a lock it presents as an ordinary
	 * optimistic root, since the lock is the only capability a root exposes.
	 */
	private static final class ScriptedRoot extends TransactionalRootCursor<AHashMap<Keyword,ACell>> {
		private final boolean lockable;
		private final Deque<UnaryOperator<AHashMap<Keyword,ACell>>> competing=new ArrayDeque<>();

		ScriptedRoot(boolean lockable) {
			super(Maps.empty());
			this.lockable=lockable;
		}

		void competeWith(UnaryOperator<AHashMap<Keyword,ACell>> write) { competing.add(write); }

		ReentrantLock writeLock() { return (ReentrantLock)super.exclusiveLock(); }

		@Override protected Lock exclusiveLock() { return lockable?super.exclusiveLock():null; }

		@Override public boolean compareAndSet(AHashMap<Keyword,ACell> expected,AHashMap<Keyword,ACell> value) {
			var write=competing.poll();
			if (write!=null) super.set(write.apply(get()));
			return super.compareAndSet(expected,value);
		}
	}

	private final AtomicLong ticks=new AtomicLong();
	private final Supplier<CVMLong> clock=() -> CVMLong.create(ticks.incrementAndGet());

	/** Opens alice with 100 and bob with nothing at north, and carol with nothing at south. */
	private Bank bank(ScriptedRoot root) {
		Bank bank=new Bank(root,clock);
		bank.branch(NORTH).open("alice",100);
		bank.branch(NORTH).open("bob",0);
		bank.branch(SOUTH).open("carol",0);
		return bank;
	}

	/** Another writer's deposit, as that writer would commit it to the whole bank. */
	private static UnaryOperator<AHashMap<Keyword,ACell>> deposit(Keyword branch,String name,long amount) {
		return value -> {
			var other=new Root<>(value);
			new Bank(other,() -> CVMLong.ZERO).branch(branch).deposit(name,amount);
			return other.get();
		};
	}

	/** Another writer's replacement of the bank with an equal value held in different cells. */
	private static AHashMap<Keyword,ACell> equalCopy(AHashMap<Keyword,ACell> value) {
		AHashMap<Keyword,ACell> copy=Maps.empty();
		for (var entry:value.entrySet()) copy=copy.assoc(entry.getKey(),entry.getValue());
		assertEquals(value,copy);
		assertNotSame(value,copy);
		return copy;
	}

	// ===== Atomicity and isolation =====

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void editsCommitTogetherAndStayPrivateUntilCommit(boolean lockable) {
		Ledger north=bank(new ScriptedRoot(lockable)).branch(NORTH);
		north.execute(draft -> {
			draft.transfer("alice","bob",30);
			assertEquals(70,draft.balance("alice"));
			assertEquals(100,north.balance("alice"),"Live state is unchanged until commit");
			assertEquals(0,north.balance("bob"));
		});
		assertEquals(70,north.balance("alice"));
		assertEquals(30,north.balance("bob"));
	}

	@Test void failureDiscardsEveryEditOfTheAttempt() {
		Bank bank=bank(new ScriptedRoot(false));
		Ledger north=bank.branch(NORTH);
		assertThrows(IllegalStateException.class,() -> north.transfer("alice","nobody",30));
		assertEquals(70,north.balance("alice"),"Outside a transaction the debit was a separate edit");

		var before=bank.value();
		assertThrows(IllegalStateException.class,() -> north.execute(draft -> draft.transfer("alice","nobody",30)));
		assertSame(before,bank.value(),"The debit was discarded with the failed credit");
		var abort=new IllegalArgumentException();
		assertSame(abort,assertThrows(IllegalArgumentException.class,() -> north.execute(draft -> {
			draft.transfer("alice","bob",30);
			throw abort;
		})),"The callback's own exception propagates");
		assertSame(before,bank.value());
	}

	// ===== Results =====

	@Test void transactReturnsTheResultAndExecuteReturnsNothing() {
		Ledger north=bank(new ScriptedRoot(false)).branch(NORTH);
		long first=north.transact(draft -> draft.transfer("alice","bob",10));
		long second=north.transact(draft -> draft.transfer("alice","bob",10));
		north.execute(draft -> draft.transfer("alice","bob",10));
		long fourth=north.transact(draft -> draft.transfer("alice","bob",10));
		assertEquals(List.of(1L,2L,4L),List.of(first,second,fourth));
		assertEquals(60,north.balance("alice"));
	}

	// ===== Conflicts =====

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void competingCommitRerunsTheOperationsAndLosesNeitherChange(boolean lockable) {
		var root=new ScriptedRoot(lockable);
		Ledger north=bank(root).branch(NORTH);
		root.competeWith(deposit(NORTH,"alice",50));
		var runs=new AtomicInteger();
		long ticksBefore=ticks.get();
		long alice=north.transact(draft -> {
			runs.incrementAndGet();
			draft.transfer("alice","bob",30);
			return draft.balance("alice");
		});
		assertEquals(2,runs.get());
		assertEquals(120L,alice,"The result comes from the run that committed, which saw the deposit");
		assertEquals(120,north.balance("alice"));
		assertEquals(30,north.balance("bob"));
		assertEquals(6,ticks.get()-ticksBefore,"Each run stamps its own edits: callbacks must be safe to repeat");
	}

	@Test void equalReplacementReusesTheRun() {
		var root=new ScriptedRoot(false);
		Bank bank=bank(root);
		root.competeWith(TransactionBehaviourTest::equalCopy);
		var runs=new AtomicInteger();
		long receipt=bank.transact(draft -> {
			runs.incrementAndGet();
			return draft.branch(NORTH).transfer("alice","bob",30);
		});
		assertEquals(1,runs.get(),"Inputs compare by value, not by reference");
		assertEquals(1L,receipt);
		assertEquals(70,bank.branch(NORTH).balance("alice"));
	}

	@Test void competingWriteOutsideTheComponentDoesNotRerunIt() {
		var root=new ScriptedRoot(false);
		Bank bank=bank(root);
		root.competeWith(deposit(SOUTH,"carol",5));
		var runs=new AtomicInteger();
		bank.branch(NORTH).execute(draft -> {
			runs.incrementAndGet();
			draft.transfer("alice","bob",30);
		});
		assertEquals(1,runs.get());
		assertEquals(70,bank.branch(NORTH).balance("alice"));
		assertEquals(5,bank.branch(SOUTH).balance("carol"));
	}

	@ParameterizedTest
	@ValueSource(booleans={false,true})
	void repeatedConflictsEndUnderTheLockOnlyWhereThereIsOne(boolean lockable) {
		var root=new ScriptedRoot(lockable);
		Ledger north=bank(root).branch(NORTH);
		int conflicts=lockable?3:5;
		for (int i=0;i<conflicts;i++) root.competeWith(deposit(NORTH,"alice",1));
		List<Boolean> underLock=new ArrayList<>();
		north.execute(draft -> {
			underLock.add(root.writeLock().isHeldByCurrentThread());
			draft.transfer("alice","bob",30);
		});
		List<Boolean> expected=new ArrayList<>(Collections.nCopies(conflicts,false));
		if (lockable) expected.add(true); // the fourth run holds the lock, so it cannot conflict
		else expected.add(false); // an ordinary root retries until a run commits
		assertEquals(expected,underLock);
		assertEquals(70+conflicts,north.balance("alice"));
	}

	// ===== Scope and composition =====

	@Test void operationsAcrossBranchesNeedTheirCommonAncestor() {
		Bank bank=bank(new ScriptedRoot(false));
		bank.branch(NORTH).execute(draft -> draft.withdraw("alice",30));
		assertThrows(IllegalStateException.class,() -> bank.branch(SOUTH).execute(draft -> draft.deposit("nobody",30)));
		assertEquals(70,bank.branch(NORTH).balance("alice"),"Separate transactions commit separately");

		var before=bank.value();
		assertThrows(IllegalStateException.class,() -> bank.execute(draft -> {
			draft.branch(NORTH).withdraw("alice",30);
			draft.branch(SOUTH).deposit("nobody",30);
		}));
		assertSame(before,bank.value(),"Neither branch changed");
		bank.execute(draft -> {
			draft.branch(NORTH).withdraw("alice",30);
			draft.branch(SOUTH).deposit("carol",30);
		});
		assertEquals(40,bank.branch(NORTH).balance("alice"));
		assertEquals(30,bank.branch(SOUTH).balance("carol"));
	}

	@Test void nestedTransactionIsASavepoint() {
		Bank bank=bank(new ScriptedRoot(false));
		long receipt=bank.transact(draft -> {
			Ledger north=draft.branch(NORTH);
			long first=north.transact(nested -> nested.transfer("alice","bob",10));
			assertEquals(90,north.balance("alice"),"A nested commit lands in the enclosing draft");
			assertEquals(100,bank.branch(NORTH).balance("alice"),"and not in live state");
			assertThrows(IllegalStateException.class,() -> north.execute(nested -> nested.transfer("alice","nobody",10)));
			assertEquals(90,north.balance("alice"),"A failed nested transaction discards only its own edits");
			return first;
		});
		assertEquals(1L,receipt);
		assertEquals(90,bank.branch(NORTH).balance("alice"));
		assertEquals(10,bank.branch(NORTH).balance("bob"));
	}

	// ===== Unchanged and missing state =====

	@Test void transactionThatChangesNothingWritesNothing() {
		Bank bank=bank(new ScriptedRoot(false));
		bank.branch(SOUTH).deposit("carol",5);
		var before=bank.value();
		long ticksBefore=ticks.get();
		long total=bank.transact(draft -> draft.branch(NORTH).total()+draft.branch(SOUTH).total());
		assertEquals(105L,total,"A reading transaction sees one consistent state of the whole bank");
		bank.execute(draft -> draft.branch(NORTH).deposit("bob",0));
		assertSame(before,bank.value());
		assertEquals(ticksBefore,ticks.get(),"An unchanged edit is not stamped");
	}

	@Test void transactionCanCreateMissingState() {
		Ledger east=bank(new ScriptedRoot(false)).branch(EAST);
		assertEquals(0L,east.transact(Ledger::total));
		assertFalse(east.storage().exists(),"Reading creates nothing");
		east.execute(draft -> draft.open("dave",10));
		assertEquals(10,east.balance("dave"));
	}

	// ===== Policies and lifetimes =====

	@Test void editingPoliciesApplyInTheDraftAndNotAgainAtCommit() {
		Ledger north=bank(new ScriptedRoot(false)).branch(NORTH);
		long before=ticks.get();
		north.execute(draft -> draft.transfer("alice","bob",30));
		assertEquals(before+3,ticks.get(),"Debit, credit and receipt are each stamped once");
		assertEquals(CVMLong.create(before+3),north.storage().get().get(Timestamped.TIMESTAMP));
	}

	@Test void retainedDraftCannotChangeCommittedState() {
		Bank bank=bank(new ScriptedRoot(false));
		var retained=new AtomicReference<Bank>();
		bank.execute(draft -> {
			retained.set(draft);
			draft.branch(NORTH).transfer("alice","bob",30);
		});
		retained.get().branch(NORTH).transfer("alice","bob",30);
		assertEquals(40,retained.get().branch(NORTH).balance("alice"));
		assertEquals(70,bank.branch(NORTH).balance("alice"));
	}

	@Test void stagedTransactionCommitsToTheForkUntilSync() {
		Bank live=bank(new ScriptedRoot(false));
		Bank staged=live.fork();
		staged.execute(draft -> {
			draft.branch(NORTH).transfer("alice","bob",30);
			draft.sync(); // a draft's storage is a private root, so this publishes nothing
		});
		assertEquals(70,staged.branch(NORTH).balance("alice"));
		assertEquals(100,live.branch(NORTH).balance("alice"),"Publishing is a separate step");
		staged.sync();
		assertEquals(70,live.branch(NORTH).balance("alice"));
	}

	// ===== Exclusive transactions =====

	@Test void exclusiveTransactionRunsOnceHoldingTheLock() {
		var root=new ScriptedRoot(true);
		Bank bank=bank(root);
		var runs=new AtomicInteger();
		try (var other=Executors.newSingleThreadExecutor()) {
			long receipt=bank.exclusive().transact(draft -> {
				runs.incrementAndGet();
				assertTrue(root.writeLock().isHeldByCurrentThread());
				boolean taken=CompletableFuture.supplyAsync(() -> {
					boolean locked=root.writeLock().tryLock();
					if (locked) root.writeLock().unlock();
					return locked;
				},other).orTimeout(10,TimeUnit.SECONDS).join();
				assertFalse(taken,"No other writer can take the lock meanwhile");
				return draft.branch(NORTH).transfer("alice","bob",30);
			});
			assertEquals(1L,receipt);
		}
		assertEquals(1,runs.get());
		assertEquals(70,bank.branch(NORTH).balance("alice"));

		var before=bank.value();
		var abort=new IllegalArgumentException();
		assertSame(abort,assertThrows(IllegalArgumentException.class,() -> bank.exclusive().execute(draft -> {
			draft.branch(NORTH).transfer("alice","bob",30);
			throw abort;
		})));
		assertSame(before,bank.value(),"A failed exclusive transaction commits nothing");
		assertFalse(root.writeLock().isLocked());
	}

	@Test void exclusiveTransactionsNeedARootWithALock() {
		Bank bank=bank(new ScriptedRoot(false));
		assertThrows(UnsupportedOperationException.class,bank::exclusive);
		Bank staged=bank.fork();
		staged.exclusive().execute(draft -> draft.branch(NORTH).transfer("alice","bob",30));
		assertEquals(70,staged.branch(NORTH).balance("alice"),"A fork's working storage always has a lock");
		assertEquals(100,bank.branch(NORTH).balance("alice"));
		bank.execute(draft -> draft.exclusive().execute(inner -> inner.branch(NORTH).transfer("alice","bob",10)));
		assertEquals(90,bank.branch(NORTH).balance("alice"),"So does a draft, which an exclusive transaction locks alone");
	}

	@Test void exclusiveComponentChangesStateOnlyThroughItsTransactions() {
		Bank bank=bank(new ScriptedRoot(true));
		var exclusive=bank.exclusive();
		bank.branch(NORTH).deposit("alice",5);
		assertEquals(bank.value(),exclusive.value(),"Reads follow the source");
		assertThrows(ReadOnlyCursorException.class,() -> exclusive.cursor().set(Maps.empty()));
		assertThrows(ReadOnlyCursorException.class,exclusive::sync);
		assertThrows(UnsupportedOperationException.class,exclusive::fork);
	}

	// ===== Asynchronous transactions =====

	@Test void asyncTransactionsCompleteAfterCommit() {
		Bank bank=bank(new ScriptedRoot(false));
		Ledger north=bank.branch(NORTH);
		long alice=north.transactAsync(draft -> draft.transfer("alice","bob",30),Runnable::run)
			.thenApply(receipt -> north.balance("alice")).join();
		assertEquals(70L,alice,"A continuation sees the committed state");
		north.executeAsync(draft -> draft.deposit("bob",1),Runnable::run).join();
		assertEquals(31,north.balance("bob"));

		var before=bank.value();
		var failed=north.executeAsync(draft -> draft.transfer("alice","nobody",10),Runnable::run);
		assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,failed::join).getCause());
		assertSame(before,bank.value());
	}
}
