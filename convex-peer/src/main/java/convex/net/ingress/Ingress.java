package convex.net.ingress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.util.Utils;
import convex.net.RequestFailures;
import convex.net.ingress.IngressChannel.Entry;
import convex.net.ingress.IngressChannel.State;

/**
 * Decodes and routes the messages a set of connections receive, on a few workers of
 * its own instead of the threads that read the connections. Each connection is
 * served in order and in fair turns; connections in the priority lane have workers
 * kept for them. A message whose destination is full waits at the head of its
 * connection, which stops being read once enough is waiting, until the destination
 * has room or the deadline passes.
 *
 * <p>A connection's route is called once per message, on a worker. It returns null
 * once the message has left, a {@link Destination} that refused it, or any other
 * predicate, which is run on a virtual thread of its own as a blocking retry. A
 * message refused at the deadline, or by a retry, is answered with
 * {@link Result#SERVER_LOADED} if it is a request, and otherwise dropped.</p>
 */
public final class Ingress implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(Ingress.class.getName());

	/** Runs deadline tasks. */
	interface Timer {
		/**
		 * Runs a task after a delay.
		 *
		 * @return Action that cancels the task
		 */
		Runnable schedule(Runnable task, long delayMillis);

		/** Stops running tasks. */
		void close();
	}

	private enum Outcome { LEFT, BLOCKED }

	private final Object lock = new Object();
	private final String name;
	private final IngressLimits limits;
	private final Predicate<AConnection> priority;
	private final Consumer<Throwable> jvmFailure;
	private final FairScheduler scheduler;
	private final Timer timer;
	private final RequestFailures failures = new RequestFailures(log);
	private final List<Worker> workers = new ArrayList<>();

	/** Told of each channel whose turn or retry has ended. For tests. */
	private volatile Consumer<IngressChannel> turnObserver;

	// Guarded by lock
	private final Set<IngressChannel> channels = new HashSet<>();
	private final Set<IngressChannel> paused = new LinkedHashSet<>();
	private long totalBytes;
	private int waitingWorkers;
	private boolean closed;

	/**
	 * Creates and starts an ingress.
	 *
	 * @param name Name for its threads
	 * @param limits Limits
	 * @param priority Which connections are served in the priority lane
	 * @param jvmFailure Told of a failure of the JVM itself, after the connection it
	 *        happened on is closed
	 */
	public Ingress(String name, IngressLimits limits, Predicate<AConnection> priority, Consumer<Throwable> jvmFailure) {
		this(name, limits, priority, jvmFailure, defaultTimer(name));
	}

	Ingress(String name, IngressLimits limits, Predicate<AConnection> priority, Consumer<Throwable> jvmFailure,
			Timer timer) {
		this.name = name;
		this.limits = limits;
		this.priority = priority;
		this.jvmFailure = jvmFailure;
		this.timer = timer;
		this.scheduler = new FairScheduler(limits.workers() - limits.reservedWorkers(),
			limits.priorityQuantum(), limits.ordinaryQuantum());
		for (int i = 0; i < limits.workers(); i++) workers.add(new Worker(i));
		for (Worker w : workers) w.start();
	}

	/**
	 * Tests whether the current thread is an ingress worker. A worker must not wait
	 * for anything another worker or connection would have to provide.
	 *
	 * @return true on an ingress worker
	 */
	public static boolean isWorkerThread() {
		return Thread.currentThread() instanceof Worker;
	}

	/**
	 * Registers a connection.
	 *
	 * @param connection Connection whose messages are admitted
	 * @param route Delivery of each message, called on a worker
	 * @param resumeReading Action that makes the transport read again after
	 *        {@link IngressChannel#admit(Message)} returned false; must not block
	 * @return Channel to admit the connection's messages to
	 */
	public IngressChannel register(AConnection connection, Function<Message, Predicate<Message>> route,
			Runnable resumeReading) {
		IngressChannel c = new IngressChannel(this, connection, route, resumeReading);
		synchronized (lock) {
			if (closed) {
				c.closed = true;
			} else {
				channels.add(c);
			}
		}
		return c;
	}

	boolean admit(IngressChannel c, Message m) {
		int bytes = (int) m.getMessageData().count();
		boolean inPriority = priority.test(c.connection);
		synchronized (lock) {
			if (c.closed) return false;
			c.waiting.addLast(new Entry(m, bytes));
			c.bytes += bytes;
			totalBytes += bytes;
			if (c.state == State.IDLE) {
				c.state = State.QUEUED;
				scheduler.enqueue(c, inPriority);
				wake();
			}
			if (c.waiting.size() < limits.connectionMessages() && c.bytes < limits.connectionBytes()
					&& totalBytes < limits.totalBytes()) {
				return true;
			}
			c.paused = true;
			paused.add(c);
			return false;
		}
	}

	void disconnect(IngressChannel c) {
		List<Runnable> after = new ArrayList<>(1);
		synchronized (lock) {
			if (c.closed) return;
			c.closed = true;
			channels.remove(c);
			// A running channel is discarded by its worker at the end of its turn
			if (c.state != State.RUNNING) discard(c, after);
		}
		runAll(after);
	}

	/**
	 * Stops this ingress: nothing more is admitted, waiting messages are discarded,
	 * and the workers stop once their current turn ends. Idempotent.
	 */
	@Override
	public void close() {
		List<Runnable> after = new ArrayList<>();
		synchronized (lock) {
			if (closed) return;
			closed = true;
			for (IngressChannel c : channels) {
				c.closed = true;
				if (c.state != State.RUNNING) discard(c, after);
			}
			channels.clear();
			lock.notifyAll();
		}
		runAll(after);
		timer.close();
		boolean interrupted = false;
		for (Worker w : workers) {
			if (w == Thread.currentThread()) continue;
			while (true) {
				try {
					w.join();
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	// ========== Workers ==========

	private final class Worker extends Thread {
		Worker(int i) {
			super(name + " ingress " + i);
			setDaemon(true);
		}

		@Override
		public void run() {
			while (true) {
				IngressChannel c = take();
				if (c == null) return;
				try {
					serve(c);
				} catch (Throwable e) {
					// Containment for a defect here: the channel's turn has ended in serve
					if (Utils.isJvmFailure(e)) jvmFailure.accept(e);
					log.error("Ingress worker failed serving {}", c.connection, e);
				}
			}
		}
	}

	/** Waits for a connection to serve, or returns null once closed. */
	private IngressChannel take() {
		synchronized (lock) {
			while (true) {
				if (closed) return null;
				IngressChannel c = scheduler.next();
				if (c != null) {
					c.state = State.RUNNING;
					// Another worker may serve what remains
					wake();
					return c;
				}
				waitingWorkers++;
				try {
					lock.wait();
				} catch (InterruptedException e) {
					// Only close stops a worker
				} finally {
					waitingWorkers--;
				}
			}
		}
	}

	/** Wakes one waiting worker if there is work it could take. Under the lock. */
	private void wake() {
		if (waitingWorkers > 0 && scheduler.hasEligible()) lock.notify();
	}

	/** Serves a channel's turn: its messages in order, while its credit lasts. */
	private void serve(IngressChannel c) {
		List<Runnable> after = new ArrayList<>(2);
		try {
			while (true) {
				Entry e;
				Predicate<Message> blockedOn;
				boolean expired;
				synchronized (lock) {
					if (c.closed || closed) break;
					e = c.waiting.peekFirst();
					if (e == null) break;
					blockedOn = c.blockedOn;
					expired = c.expired;
					c.signalled = false;
				}
				// A message is charged once, when routed, not for each retry
				long cost = (blockedOn == null) ? Math.max(e.bytes(), limits.minimumCost()) : 0;
				Outcome o = (blockedOn == null) ? route(c, e) : retry(c, e, (Destination) blockedOn, expired);
				synchronized (lock) {
					c.credit -= cost;
					if (o != Outcome.LEFT) break;
					leave(c, after);
					if (c.credit <= 0 || c.closed || closed || c.waiting.isEmpty()) break;
				}
			}
		} finally {
			endTurn(c, after);
			runAll(after);
			observeTurn(c);
		}
	}

	private void observeTurn(IngressChannel c) {
		Consumer<IngressChannel> observer = turnObserver;
		if (observer != null) observer.accept(c);
	}

	private Outcome route(IngressChannel c, Entry e) {
		Message m = e.message();
		Predicate<Message> next;
		try {
			next = c.route.apply(m);
		} catch (Throwable t) {
			fail(c, m, t);
			return Outcome.LEFT;
		}
		if (next == null) return Outcome.LEFT;
		if (next instanceof Destination d) return block(c, e, d);
		return startRetry(c, m, next);
	}

	private Outcome retry(IngressChannel c, Entry e, Destination d, boolean expired) {
		if (d.tryOffer(e.message())) return Outcome.LEFT;
		if (expired) {
			refuse(e.message());
			return Outcome.LEFT;
		}
		return block(c, e, d);
	}

	/**
	 * Leaves the first message waiting for a destination that refused it, unless the
	 * destination takes it once the capacity action is registered.
	 */
	private Outcome block(IngressChannel c, Entry e, Destination d) {
		long episode;
		boolean first;
		synchronized (lock) {
			first = (c.blockedOn == null);
			if (first) c.episode++;
			episode = c.episode;
			c.blockedOn = d;
		}
		d.whenCapacity(() -> signal(c, episode));
		// After registering: room made since the refusal ran no action for this message
		if (d.tryOffer(e.message())) return Outcome.LEFT;
		if (first) {
			Runnable cancel = timer.schedule(() -> expire(c, episode), limits.deadlineMillis());
			synchronized (lock) {
				c.cancelDeadline = cancel;
			}
		}
		return Outcome.BLOCKED;
	}

	private Outcome startRetry(IngressChannel c, Message m, Predicate<Message> retry) {
		long episode;
		synchronized (lock) {
			episode = ++c.episode;
			c.blockedOn = retry;
		}
		try {
			Thread.ofVirtual().name(name + " ingress retry").start(() -> runRetry(c, m, retry, episode));
		} catch (Throwable t) {
			Utils.checkRequestFailure(t);
			failures.log(t, "Could not start a retry of {} message", m.getType());
			refuse(m);
			return Outcome.LEFT;
		}
		return Outcome.BLOCKED;
	}

	private void runRetry(IngressChannel c, Message m, Predicate<Message> retry, long episode) {
		boolean taken = false;
		try {
			taken = retry.test(m);
		} catch (Throwable t) {
			contain(c, m, t);
		}
		if (!taken) refuse(m);
		List<Runnable> after = new ArrayList<>(1);
		synchronized (lock) {
			if (c.closed || c.episode != episode || c.blockedOn != retry) return;
			if (c.state == State.RUNNING) {
				// Its worker has not yet ended the turn that started this retry
				c.headDone = true;
			} else {
				leave(c, after);
				requeue(c, c.inPriority);
			}
		}
		runAll(after);
		observeTurn(c);
	}

	/** Ends a channel's turn and sets where it goes next. */
	private void endTurn(IngressChannel c, List<Runnable> after) {
		boolean inPriority = priority.test(c.connection);
		synchronized (lock) {
			scheduler.finish(c);
			if (c.headDone) {
				c.headDone = false;
				leave(c, after);
			}
			if (c.closed || closed) {
				c.closed = true;
				discard(c, after);
				return;
			}
			if ((c.blockedOn != null) && !c.signalled) {
				c.state = State.BLOCKED;
			} else {
				requeue(c, inPriority);
			}
			c.signalled = false;
		}
	}

	/** Puts a channel that is not queued back in a lane, or makes it idle. Under the lock. */
	private void requeue(IngressChannel c, boolean inPriority) {
		if (c.waiting.isEmpty()) {
			c.state = State.IDLE;
			// Keep a debt, not a surplus, while idle
			c.credit = Math.min(c.credit, 0);
		} else {
			c.state = State.QUEUED;
			scheduler.enqueue(c, inPriority);
			wake();
		}
	}

	private void signal(IngressChannel c, long episode) {
		synchronized (lock) {
			if (c.closed || c.episode != episode || !(c.blockedOn instanceof Destination)) return;
			wakeBlocked(c);
		}
	}

	private void expire(IngressChannel c, long episode) {
		synchronized (lock) {
			if (c.closed || c.episode != episode || !(c.blockedOn instanceof Destination)) return;
			c.expired = true;
			c.cancelDeadline = null;
			wakeBlocked(c);
		}
	}

	/** Makes a blocked channel try its first message again. Under the lock. */
	private void wakeBlocked(IngressChannel c) {
		if (c.state == State.BLOCKED) {
			c.state = State.QUEUED;
			scheduler.enqueue(c, c.inPriority);
			wake();
		} else if (c.state == State.RUNNING) {
			c.signalled = true;
		}
	}

	// ========== Credit ==========

	/** Removes the first message, which has left. Under the lock. */
	private void leave(IngressChannel c, List<Runnable> after) {
		Entry e = c.waiting.pollFirst();
		c.blockedOn = null;
		c.expired = false;
		if (c.cancelDeadline != null) {
			after.add(c.cancelDeadline);
			c.cancelDeadline = null;
		}
		if (e != null) release(c, e.bytes(), after);
	}

	/** Discards every waiting message of a closed channel. Under the lock. */
	private void discard(IngressChannel c, List<Runnable> after) {
		long bytes = c.bytes;
		c.waiting.clear();
		c.blockedOn = null;
		if (c.cancelDeadline != null) {
			after.add(c.cancelDeadline);
			c.cancelDeadline = null;
		}
		if (c.state == State.QUEUED) scheduler.remove(c);
		c.state = State.IDLE;
		c.paused = false;
		paused.remove(c);
		release(c, bytes, after);
	}

	/** Releases a channel's bytes, resuming what may read again. Under the lock. */
	private void release(IngressChannel c, long bytes, List<Runnable> after) {
		long before = totalBytes;
		totalBytes -= bytes;
		c.bytes -= bytes;
		long low = limits.totalBytes() / 2;
		if ((before > low) && (totalBytes <= low)) {
			for (IngressChannel p : new ArrayList<>(paused)) {
				if (canResume(p)) resume(p, after);
			}
		} else if (c.paused && canResume(c)) {
			resume(c, after);
		}
	}

	private boolean canResume(IngressChannel c) {
		return !c.closed && (c.waiting.size() <= limits.connectionMessages() / 2)
			&& (c.bytes <= limits.connectionBytes() / 2) && (totalBytes <= limits.totalBytes() / 2);
	}

	private void resume(IngressChannel c, List<Runnable> after) {
		c.paused = false;
		paused.remove(c);
		after.add(c.resumeReading);
	}

	// ========== Failures ==========

	private void refuse(Message m) {
		try {
			m.returnResult(Result.SERVER_LOADED);
		} catch (Throwable t) {
			Utils.checkRequestFailure(t);
			log.debug("Could not refuse {} message", m.getType(), t);
		}
	}

	/** A route that throws: the failure is contained to its message. */
	private void fail(IngressChannel c, Message m, Throwable t) {
		if (!contain(c, m, t)) return;
		try {
			m.returnResult(RequestFailures.reply(t));
		} catch (Throwable e) {
			Utils.checkRequestFailure(e);
			log.debug("Could not answer failed {} message", m.getType(), e);
		}
	}

	/**
	 * Contains a failure of one message: logs it, unless the JVM itself has failed,
	 * when the connection is closed and the failure passed on.
	 *
	 * @return true if contained to the message
	 */
	private boolean contain(IngressChannel c, Message m, Throwable t) {
		if (Utils.isJvmFailure(t)) {
			try {
				c.connection.close();
			} finally {
				jvmFailure.accept(t);
			}
			return false;
		}
		if (t instanceof InterruptedException) Thread.currentThread().interrupt();
		failures.log(t, "Failed to route {} message", m.getType());
		return true;
	}

	private static void runAll(List<Runnable> actions) {
		for (Runnable r : actions) {
			try {
				r.run();
			} catch (Throwable t) {
				Utils.checkRequestFailure(t);
				log.warn("Ingress action failed", t);
			}
		}
	}

	private static Timer defaultTimer(String name) {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, name + " ingress deadlines");
			t.setDaemon(true);
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		return new Timer() {
			@Override
			public Runnable schedule(Runnable task, long delayMillis) {
				ScheduledFuture<?> f = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
				return () -> f.cancel(false);
			}

			@Override
			public void close() {
				executor.shutdownNow();
			}
		};
	}

	// ========== For tests ==========

	/** Sets the observer told of each channel whose turn or retry has ended. */
	void setTurnObserver(Consumer<IngressChannel> observer) {
		turnObserver = observer;
	}

	/** Encoded bytes waiting in all channels. */
	long getTotalBytes() {
		synchronized (lock) {
			return totalBytes;
		}
	}

	/** Workers waiting for work. */
	int getWaitingWorkers() {
		synchronized (lock) {
			return waitingWorkers;
		}
	}

	/** Whether a worker waits while there is a channel it could serve. */
	boolean isIdleWhileEligible() {
		synchronized (lock) {
			return waitingWorkers > 0 && scheduler.hasEligible();
		}
	}

	/** Whether every worker has stopped. */
	boolean isStopped() {
		for (Worker w : workers) {
			if (w.isAlive()) return false;
		}
		return true;
	}

	/** Turns the ordinary lane has now. */
	int getOrdinaryServing() {
		synchronized (lock) {
			return scheduler.ordinaryServing();
		}
	}

	/** A channel's state. */
	State getState(IngressChannel c) {
		synchronized (lock) {
			return c.state;
		}
	}

	/** Messages waiting in a channel. */
	int getWaiting(IngressChannel c) {
		synchronized (lock) {
			return c.waiting.size();
		}
	}
}
