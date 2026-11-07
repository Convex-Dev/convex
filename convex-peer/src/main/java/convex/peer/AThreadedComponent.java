package convex.peer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.LoadMonitor;

/**
 * Base class for a threaded execution component that runs within the context of a Peer Server
 */
public abstract class AThreadedComponent {

	private static final Logger log = LoggerFactory.getLogger(AThreadedComponent.class.getName());

	protected final Server server;

	protected final Thread thread;

	private class ComponentTask implements Runnable {
		@Override
		public void run() {
			// Run main component loop
			while (server.isRunning()&&!Thread.currentThread().isInterrupted()) {
				try {
					loop();
				} catch (InterruptedException e) {
					// Interrupted, so we are exiting
					log.trace("Component thread interrupted: {}",thread);
					Thread.currentThread().interrupt();
					break;
				} catch (Exception e) {
					log.error("Unexpected error in component loop: {}",thread,e);
				}
			}
			
			// Finally close the component properly
			log.trace("Component thread stopping: {}",thread);
			close();
		}
	}
	
	public double getLoad() {  
		return LoadMonitor.getLoad(thread);
	}

	protected AThreadedComponent(Server server) {
		this.server=server;
		// Virtual thread: ideal for IO-bound queue-polling loops, lower memory footprint
		this.thread=Thread.ofVirtual().unstarted(new ComponentTask());
	}
	
	protected abstract void loop() throws InterruptedException;
	
	protected abstract String getThreadName();
	
	protected AStore getStore() {
		return server.getStore();
	}

	/**
	 * Start the threaded component
	 */
	public void start() {
		String name=getThreadName();
		thread.setName(name);
		log.trace("Thread started: {}",name);
		thread.setDaemon(true);
		thread.start();
	}
	
	/**
	 * Close this threaded component, including interrupting any running thread(s). 
	 * Subclasses may override, but should call `super.close()` to close the main thread
	 */
	public void close() {
		Thread t=thread;
		t.interrupt();
	}

	/**
	 * Offers a message to a queue this component's thread drains, waiting up to the
	 * internal timeout for space. On that thread it does not wait: nothing else makes
	 * room, so the wait could only time out.
	 *
	 * @return true if queued, false if the queue stayed full or the caller was interrupted
	 */
	protected boolean offerBlocking(BlockingQueue<Message> queue, Message m) {
		if (Thread.currentThread()==thread) return queue.offer(m);
		try {
			return queue.offer(m, Config.DEFAULT_INTERNAL_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Waits until this component can no longer access Peer resources.
	 *
	 * @return {@code false} when called by the component thread itself
	 * @throws InterruptedException if the waiting thread is interrupted
	 */
	boolean awaitStopped() throws InterruptedException {
		Thread t=thread;
		if (Thread.currentThread()==t) return false;
		t.join();
		return true;
	}
}
