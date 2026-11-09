package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

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
import convex.lattice.cursor.Transactions;

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

		@Override public Bank rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return new Bank(cursor,clock); }
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
		@Override public Ledger rebind(AForkableCursor<AHashMap<Keyword,ACell>> cursor) { return new Ledger(cursor,clock); }

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
	 * applies the next queued write.
	 */
	private static final class ScriptedRoot extends Root<AHashMap<Keyword,ACell>> {
		private final Deque<BinaryOperator<AHashMap<Keyword,ACell>>> competing=new ArrayDeque<>();

		ScriptedRoot() {
			super(Maps.empty());
		}

		/** Queues another writer's commit, computed from the current value and the value the attempt proposes. */
		void competeWith(BinaryOperator<AHashMap<Keyword,ACell>> write) { competing.add(write); }

		/** Whether the calling thread holds this root's claim, the one case a second claim is refused. */
		boolean claimedByCaller() {
			try {
				Transactions.executeExclusively(this,draft -> {});
				return false;
			} catch (IllegalStateException e) {
				return true;
			}
		}

		@Override public boolean compareAndSet(AHashMap<Keyword,ACell> expected,AHashMap<Keyword,ACell> value) {
			var write=competing.poll();
			if (write!=null) super.set(write.apply(get(),value));
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
	private static BinaryOperator<AHashMap<Keyword,ACell>> deposit(Keyword branch,String name,long amount) {
		return (current,proposed) -> {
			var other=new Root<>(current);
			new Bank(other,() -> CVMLong.ZERO).branch(branch).deposit(name,amount);
			return other.get();
		};
	}

	/** Another writer's replacement of the bank with an equal value held in different cells. */
	private static AHashMap<Keyword,ACell> equalCopy(AHashMap<Keyword,ACell> current,AHashMap<Keyword,ACell> proposed) {
		AHashMap<Keyword,ACell> copy=Maps.empty();
		for (var entry:current.entrySet()) copy=copy.assoc(entry.getKey(),entry.getValue());
		assertEquals(current,copy);
		assertNotSame(current,copy);
		return copy;
	}

	// ===== Atomicity and isolation =====

	@Test void editsCommitTogetherAndStayPrivateUntilCommit() {
		Ledger north=bank(new ScriptedRoot()).branch(NORTH);
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
		Bank bank=bank(new ScriptedRoot());
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
		Ledger north=bank(new ScriptedRoot()).branch(NORTH);
		long first=north.transact(draft -> draft.transfer("alice","bob",10));
		long second=north.transact(draft -> draft.transfer("alice","bob",10));
		north.execute(draft -> draft.transfer("alice","bob",10));
		long fourth=north.transact(draft -> draft.transfer("alice","bob",10));
		assertEquals(List.of(1L,2L,4L),List.of(first,second,fourth));
		assertEquals(60,north.balance("alice"));
	}

	// ===== Conflicts =====

	@Test void competingCommitRerunsTheOperationsAndLosesNeitherChange() {
		var root=new ScriptedRoot();
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
		var root=new ScriptedRoot();
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

	@Test void competingCommitOfTheSameValueStillRerunsTheOperations() {
		var root=new ScriptedRoot();
		Ledger north=bank(root).branch(NORTH);
		root.competeWith((current,proposed) -> proposed); // another writer commits exactly this attempt's value
		var runs=new AtomicInteger();
		long receipt=north.transact(draft -> {
			runs.incrementAndGet();
			return draft.transfer("alice","bob",30);
		});
		assertEquals(2,runs.get(),"Commit checks the input the run saw, not the value it would write");
		assertEquals(2L,receipt);
		assertEquals(40,north.balance("alice"),"Both transfers happened");
		assertEquals(60,north.balance("bob"));
	}

	@Test void competingWriteOutsideTheComponentDoesNotRerunIt() {
		var root=new ScriptedRoot();
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

	@Test void repeatedConflictsFinishUnderTheClaim() {
		var root=new ScriptedRoot();
		Ledger north=bank(root).branch(NORTH);
		for (int i=0;i<2;i++) root.competeWith(deposit(NORTH,"alice",1));
		List<Boolean> claimed=new ArrayList<>();
		north.execute(draft -> {
			claimed.add(root.claimedByCaller());
			draft.transfer("alice","bob",30);
		});
		assertEquals(List.of(false,false,true),claimed,"The third run holds the claim, so it cannot conflict");
		assertEquals(72,north.balance("alice"));
		assertFalse(root.claimedByCaller(),"The claim was released");
	}

	@Test void changesElsewhereNeverLeadToTheClaim() {
		var root=new ScriptedRoot();
		Bank bank=bank(root);
		for (int i=0;i<5;i++) root.competeWith(deposit(SOUTH,"carol",1));
		List<Boolean> claimed=new ArrayList<>();
		bank.branch(NORTH).execute(draft -> {
			claimed.add(root.claimedByCaller());
			draft.transfer("alice","bob",30);
		});
		assertEquals(List.of(false),claimed,"One run, finished without the claim");
		assertEquals(5,bank.branch(SOUTH).balance("carol"));
	}

	// ===== Scope and composition =====

	@Test void operationsAcrossBranchesNeedTheirCommonAncestor() {
		Bank bank=bank(new ScriptedRoot());
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

	@Test void transactionWithinABranchTouchesNothingAboveIt() {
		Bank bank=bank(new ScriptedRoot());
		var south=bank.value().get(SOUTH);
		bank.branch(NORTH).execute(draft -> draft.transfer("alice","bob",30));
		assertSame(south,bank.value().get(SOUTH));
		assertNull(bank.value().get(Timestamped.TIMESTAMP),"The stamp stays on the branch that changed");
	}

	@Test void nestedTransactionIsASavepoint() {
		Bank bank=bank(new ScriptedRoot());
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
		Bank bank=bank(new ScriptedRoot());
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
		Ledger east=bank(new ScriptedRoot()).branch(EAST);
		assertEquals(0L,east.transact(Ledger::total));
		assertFalse(east.storage().exists(),"Reading creates nothing");
		east.execute(draft -> draft.open("dave",10));
		assertEquals(10,east.balance("dave"));
	}

	// ===== Policies and lifetimes =====

	@Test void editingPoliciesApplyInTheDraftAndNotAgainAtCommit() {
		Ledger north=bank(new ScriptedRoot()).branch(NORTH);
		long before=ticks.get();
		north.execute(draft -> draft.transfer("alice","bob",30));
		assertEquals(before+3,ticks.get(),"Debit, credit and receipt are each stamped once");
		assertEquals(CVMLong.create(before+3),north.storage().get().get(Timestamped.TIMESTAMP));
	}

	@Test void retainedDraftAndItsForksCannotChangeCommittedState() {
		Bank bank=bank(new ScriptedRoot());
		var retained=new AtomicReference<Bank>();
		var fork=new AtomicReference<Bank>();
		bank.execute(draft -> {
			retained.set(draft);
			draft.branch(NORTH).transfer("alice","bob",30);
			fork.set(draft.fork());
		});
		fork.get().branch(NORTH).deposit("bob",5);
		fork.get().sync(); // publishes into the retained draft, which is all it was forked from
		assertEquals(35,retained.get().branch(NORTH).balance("bob"));
		retained.get().branch(NORTH).transfer("alice","bob",30);
		assertEquals(40,retained.get().branch(NORTH).balance("alice"));
		assertEquals(70,bank.branch(NORTH).balance("alice"));
		assertEquals(30,bank.branch(NORTH).balance("bob"));
	}

	@Test void stagedTransactionCommitsToTheForkUntilSync() {
		Bank live=bank(new ScriptedRoot());
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

	@Test void exclusiveTransactionRunsOnceUnderTheClaim() {
		var root=new ScriptedRoot();
		Bank bank=bank(root);
		var runs=new AtomicInteger();
		long receipt=bank.exclusive().transact(draft -> {
			runs.incrementAndGet();
			assertTrue(root.claimedByCaller());
			return draft.branch(NORTH).transfer("alice","bob",30);
		});
		assertEquals(1L,receipt);
		assertEquals(1,runs.get());
		assertEquals(70,bank.branch(NORTH).balance("alice"));

		var before=bank.value();
		var abort=new IllegalArgumentException();
		assertSame(abort,assertThrows(IllegalArgumentException.class,() -> bank.exclusive().execute(draft -> {
			draft.branch(NORTH).transfer("alice","bob",30);
			throw abort;
		})));
		assertSame(before,bank.value(),"A failed exclusive transaction commits nothing");
		assertFalse(root.claimedByCaller(),"and releases the claim");
	}

	@Test void exclusiveTransactionsWorkOnAnyStorage() {
		Bank bank=bank(new ScriptedRoot());
		bank.branch(NORTH).exclusive().execute(draft -> draft.transfer("alice","bob",10));
		assertEquals(90,bank.branch(NORTH).balance("alice"),"A branch of an ordinary root");
		Bank staged=bank.fork();
		staged.exclusive().execute(draft -> draft.branch(NORTH).transfer("alice","bob",30));
		assertEquals(60,staged.branch(NORTH).balance("alice"),"A fork");
		assertEquals(90,bank.branch(NORTH).balance("alice"));
		bank.execute(draft -> draft.exclusive().execute(inner -> inner.branch(NORTH).transfer("alice","bob",10)));
		assertEquals(80,bank.branch(NORTH).balance("alice"),"A draft");
	}

	@Test void exclusiveComponentChangesStateOnlyThroughItsTransactions() {
		Bank bank=bank(new ScriptedRoot());
		var exclusive=bank.exclusive();
		bank.branch(NORTH).deposit("alice",5);
		assertEquals(bank.value(),exclusive.value(),"Reads follow the source");
		assertTrue(exclusive.exists());
		var before=bank.value();
		assertThrows(ReadOnlyCursorException.class,() -> exclusive.cursor().set(Maps.empty()));
		assertThrows(ReadOnlyCursorException.class,exclusive::sync);
		assertThrows(UnsupportedOperationException.class,exclusive::fork);
		assertThrows(UnsupportedOperationException.class,exclusive::persist);
		assertThrows(UnsupportedOperationException.class,() -> exclusive.merge(Maps.empty()));
		assertSame(before,bank.value());
	}

	// ===== Asynchronous transactions =====

	@Test void asyncTransactionsCompleteAfterCommit() {
		Bank bank=bank(new ScriptedRoot());
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
