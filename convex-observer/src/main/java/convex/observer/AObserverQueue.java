package convex.observer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.store.AStore;
import convex.core.store.Stores;

public abstract class AObserverQueue<T> {
	
	static final Logger log = LoggerFactory.getLogger(AObserverQueue.class.getName());

	private boolean running;
	private AStore store;
	private Thread thread; 
	
	protected final ArrayBlockingQueue<Supplier<T>> queue;

	public AObserverQueue(AStore store) {
		this.store=store;
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
			// Set Thread-local store for the current Server
			Stores.setCurrent(store);
			// Run main component loop
			while (running) {
				try {
					loop();		
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // maintain interrupt status
					log.debug("Component thread interrupted: {}",thread);
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
	 * Close this threaded component, including interrupting any running thread(s). 
	 * Subclasses may override, but should call `super.close()` to close the main thread
	 */
	public synchronized void close() {
		Thread t=thread;
		running=false;
		t.interrupt();
	}


	public abstract void loop() throws InterruptedException;

}
