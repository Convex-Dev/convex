package convex.peer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.store.Stores;
import convex.core.util.*; 

/**
 * Base class for a threaded execution component that runs within the context of a Peer Server
 */
public abstract class AThreadedComponent {

	static final Logger log = LoggerFactory.getLogger(AThreadedComponent.class.getName());

	protected final Server server;
	
	protected final Thread thread; 

	private class ComponentTask implements Runnable {
		@Override
		public void run() {
			// Set Thread-local store for the current Server
			Stores.setCurrent(server.getStore());
			
			// Run main component loop
			while (server.isLive()) {
				try {
					loop();		
				} catch (InterruptedException e) {
					log.debug("Component thread interrupted: {}",thread);
					break;
				} catch (Throwable e) {
					log.warn("Unexpected exception in "+this.getClass().getSimpleName(),e);
				} 
			}
			
			// Finally close the component properly
			close();
		}
	}
	
	public double getLoad() {  
		return LoadMonitor.getLoad(thread);
	}

	protected AThreadedComponent(Server server) {
		this.server=server;
		this.thread=new Thread(new ComponentTask());
	}
	
	protected abstract void loop() throws InterruptedException;
	
	protected abstract String getThreadName();

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
	
	@Override
	public void finalize() {
		close();
	}
}
