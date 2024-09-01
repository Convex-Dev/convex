package convex.peer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.ResultContext;
import convex.core.SourceCodes;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.LoadMonitor;
import convex.net.Message;
import convex.net.MessageType;

public class QueryHandler extends AThreadedComponent {

	private static final Logger log = LoggerFactory.getLogger(QueryHandler.class.getName());

	/**
	 * Queue for received messages to be processed by this Peer Server
	 */
	private ArrayBlockingQueue<Message> queryQueue = new ArrayBlockingQueue<Message>(Config.QUERY_QUEUE_SIZE);

	public QueryHandler(Server server) {
		super(server);	
		queryQueue= new ArrayBlockingQueue<>(Config.TRANSACTION_QUEUE_SIZE);
	}
	
	/**
	 * Offer a transaction for handling
	 * @param m Message offered
	 * @return True if queued for handling, false otherwise
	 */
	public boolean offerQuery(Message m) {
		return queryQueue.offer(m);
	}
	

	@Override
	protected void loop() throws InterruptedException {
		LoadMonitor.down();
		Message m = queryQueue.poll(10000, TimeUnit.MILLISECONDS);
		LoadMonitor.up();
		if (m==null) return;
		
		MessageType type=m.getType();
		switch (type) {
		case QUERY:
			handleQuery(m);
			break;
		case REQUEST_DATA:
			server.handleDataRequest(m);
			break;
		default:
			log.warn("Unexpected Message type on query queue: "+type);
		}
	}
	
	private void handleQuery(Message m) {
		try {
			// query is a vector [id , form, address?]
			AVector<ACell> v= m.getPayload();
			CVMLong id = (CVMLong) v.get(0);
			ACell form = v.get(1);
	
			// extract the Address, might be null
			Address address = RT.ensureAddress(v.get(2));
	
			log.debug( "Processing query: {} with address: {}" , form, address);
			// log.log(LEVEL_MESSAGE, "Processing query: " + form + " with address: " +
			// address);
			
			// Return result
			ResultContext resultContext = server.getPeer().executeQuery(form, address);
			Result result=Result.fromContext(id, resultContext).withSource(SourceCodes.PEER);
			
			// Report result back to message sender
			boolean resultReturned= m.returnResult(result);
	
			if (!resultReturned) {
				log.warn("Failed to send query result back to client with ID: {}", id);
			}
		} catch (Exception e) {
			log.debug("Terminated client: "+e.getMessage());
			m.closeConnection();
		}

	}

	@Override
	protected String getThreadName() {
		return "Query handler on port: " + server.getPort();
	}

}
