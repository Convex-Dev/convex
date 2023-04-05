package convex.peer;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.store.Stores;
import convex.net.message.Message;

/**
 * Server component for handling client transactions
 */
public class TransactionHandler {
	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());

	protected final Server server;
	
	protected final Thread transactionThread; 
	
	protected final ArrayBlockingQueue<Message> transactionQueue= new ArrayBlockingQueue<>(Constants.TRANSACTION_QUEUE_SIZE);
	
	public TransactionHandler(Server server) {
		this.server=server;
		transactionThread=new Thread(transactionHandlerLoop,"Transaction handler on port: "+server.getPort());
	}
	
	/**
	 * Offer a transaction for handling
	 * @param m Message offered
	 * @return True if queued for handling, false otherwise
	 */
	public boolean offer(Message m) {
		return transactionQueue.offer(m);
	}
	
	protected final Runnable transactionHandlerLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(server.getStore());
			ArrayList<Message> messages=new ArrayList<>();
			while (server.isLive()) {
				try {
					Message m = transactionQueue.poll(1000, TimeUnit.MILLISECONDS);
					messages.add(m);
					
					transactionQueue.drainTo(messages);
					
					Thread.sleep(10);
				} catch (InterruptedException e) {
					log.debug("Transaction handler thread interrupted");
				} catch (Throwable e) {
					log.warn("Unexpected exception in Transaction handler: ",e);
				}
				messages.clear();
			}
		}
	};
	
	protected void processMessage(Message m) {
		
	}

	public void close() {
		transactionThread.interrupt();
	}

	public void start() {
		transactionThread.start();
	}
}
