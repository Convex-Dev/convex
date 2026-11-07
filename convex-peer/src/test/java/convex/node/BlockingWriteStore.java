package convex.node;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.store.MemoryStore;

/**
 * Memory store that holds the first top-level write made by one named thread
 * until released, and records whether that thread was interrupted meanwhile.
 * It models store I/O that must never be interrupted: an interrupt during Etch
 * channel I/O closes the file channel for every user of the store (#776).
 */
public final class BlockingWriteStore extends MemoryStore {
	/** Counted down once the named thread is held inside its write. */
	public final CountDownLatch entered=new CountDownLatch(1);
	/** Counted down once the held write has been released. */
	public final CountDownLatch finished=new CountDownLatch(1);
	private final CountDownLatch release=new CountDownLatch(1);
	private final AtomicBoolean claimed=new AtomicBoolean();
	private final String threadName;
	private volatile Thread held;
	private volatile boolean interrupted;

	public BlockingWriteStore(String threadName) {
		this.threadName=threadName;
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref,int status,Consumer<Ref<ACell>> noveltyHandler) {
		if (threadName.equals(Thread.currentThread().getName()) && claimed.compareAndSet(false,true)) {
			held=Thread.currentThread();
			entered.countDown();
			boolean wasInterrupted=false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException e) {
					// Model a store call that can only stop at its own safe boundary.
					wasInterrupted=true;
				}
			}
			if (wasInterrupted || Thread.currentThread().isInterrupted()) {
				interrupted=true;
				Thread.currentThread().interrupt();
			}
			finished.countDown();
		}
		return super.storeTopRef(ref,status,noveltyHandler);
	}

	/** Lets the held write continue. */
	public void release() {
		release.countDown();
	}

	/** The thread held by this store, once {@link #entered} is counted down. */
	public Thread heldThread() {
		return held;
	}

	/** Whether the held thread was interrupted while inside its write. */
	public boolean wasInterrupted() {
		return interrupted;
	}
}
