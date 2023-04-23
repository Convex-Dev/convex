package convex.peer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.store.Stores;

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
			Stores.setCurrent(server.getStore());
			while (server.isLive()) {
				try {
					loop();		
				} catch (InterruptedException e) {
					log.debug("Component thread interrupted: {}",thread);
				} catch (Throwable e) {
					log.warn("Unexpected exception in Server component: ",e);
				} 
			}
		}
	}

	protected AThreadedComponent(Server server) {
		this.server=server;
		this.thread=new Thread(new ComponentTask());
	}
	
	protected abstract void loop() throws InterruptedException;

	public void start() {
		thread.start();
	}
	
	public void close() {
		thread.interrupt();
	}
	
	@Override
	public void finalize() {
		close();
	}
}
