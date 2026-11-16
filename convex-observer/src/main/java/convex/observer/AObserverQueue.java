package convex.observer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.ThreadUtils;

public abstract class AObserverQueue<T> {

	static final Logger log = LoggerFactory.getLogger(AObserverQueue.class.getName());

	private volatile boolean running;
	/** The queue thread while started. Guarded by this. */
	private Thread thread;

	protected final ArrayBlockingQueue<Supplier<T>> queue;

	public AObserverQueue() {
		this.queue=new ArrayBlockingQueue<>(getQueueSize());
	}
	
	/**
	 * Get the size of the queue on initialisation. Can be overridden.
	 * @return Size of queue requested
	 */
	protected int getQueueSize() {
		return 5000;
	}

	public synchronized void start() {
		if (running) return;
		running=true;
		String name=getThreadName();
		this.thread=new Thread(new QueueTask());
		thread.setName(name);
		log.debug("Thread started: {}",name);
		thread.setDaemon(true);
		thread.start();
	}
	
	protected String getThreadName() {
		return "Observability Task Queue";
	}

	private class QueueTask implements Runnable {
		@Override
		public void run() {
			// Run main component loop
			while (running) {
				try {
					loop();		
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // maintain interrupt status
					log.debug("Component thread interrupted: {}",Thread.currentThread());
					break;
				} catch (Exception e) {
					log.warn("Unexpected exception in "+this.getClass().getSimpleName(),e);
					// Stop observer ??
					// break;
				} 
			}
			
			// Finally close the component properly
			close();
		}
	}
	
	/**
	 * Close this threaded component, interrupting its thread and waiting for it to end.
	 * Closing one never started does nothing. Subclasses may override, but should call
	 * `super.close()` to close the main thread
	 */
	public void close() {
		Thread t;
		synchronized (this) {
			running=false;
			t=thread;
			thread=null;
		}
		if (t==null) return;
		t.interrupt();
		// Outside the lock: the queue thread closes this too as it ends
		ThreadUtils.joinUninterruptibly(t);
	}


	public abstract void loop() throws InterruptedException;

}
