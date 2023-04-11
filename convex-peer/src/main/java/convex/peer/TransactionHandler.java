package convex.peer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Peer;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
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
					if (m==null) continue;
					
					// We have at least one transaction to handle, drain queue to get the rest
					messages.add(m);
					transactionQueue.drainTo(messages);
					
					// Process transaction messages
					for (Message msg: messages) {
						processMessage(msg);
					}
				} catch (InterruptedException e) {
					log.debug("Transaction handler thread interrupted");
				} catch (Throwable e) {
					log.warn("Unexpected exception in Transaction handler: ",e);
				} finally {
					messages.clear();
				}
			}
		}
	};
	
	/**
	 * Register of client interests in receiving transaction responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	/**
	 * Register interest in receiving a result for a transaction
	 * @param signedTransactionHash
	 * @param m
	 */
	private void registerInterest(Hash signedTransactionHash, Message m) {
		interests.put(signedTransactionHash, m);
	}
	
	protected void processMessage(Message m) {
		try {
			// Transaction is a vector [id , signed-object]
			AVector<ACell> v = m.getPayload();
			@SuppressWarnings("unchecked")
			SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(1);
	
			// System.out.println("transact: "+v);
			if (!(sd.getValue() instanceof ATransaction)) {
				Result r=Result.create(m.getID(), Strings.BAD_FORMAT, ErrorCodes.FORMAT);
				m.reportResult(r);
				return;
			}
			
			if (!sd.checkSignature()) {
				// terminate the connection, dishonest client?
				try {
					// TODO: throttle?
					Result r=Result.create(m.getID(), Strings.BAD_SIGNATURE, ErrorCodes.SIGNATURE);
					m.reportResult(r);
				} catch (Exception e) {
					// Ignore?? Connection probably gone anyway
				}
				log.debug("Bad signature from Client! {}" , sd);
				return;
			}
			
			// Persist the signed transaction. Might throw MissingDataException?
			// If we already have the transaction persisted, will get signature status
			sd=ACell.createPersisted(sd).getValue();
	
			boolean queued= server.transactionQueue.offer(sd);
			if (!queued) {
				Result r=Result.create(m.getID(), Strings.SERVER_LOADED, ErrorCodes.LOAD);
				m.reportResult(r);
			} 
			registerInterest(sd.getHash(), m);		
		} catch (Throwable e) {
			log.warn("Unandled exception in transaction handler",e);
		}
	}
	
	
	long reportedConsensusPoint;

	public void maybeReportTransactions(Peer peer) {
		// Report transaction results
		long newConsensusPoint = peer.getConsensusPoint();
		if (newConsensusPoint > reportedConsensusPoint) {
			log.debug("Consensus point update from {} to {}" ,reportedConsensusPoint , newConsensusPoint);
			for (long i = reportedConsensusPoint; i < newConsensusPoint; i++) {
				SignedData<Block> block = peer.getPeerOrder().getBlock(i);
				// only report our own transactions!
				if (block.getAccountKey().equals(peer.getPeerKey())) {
					BlockResult br = peer.getBlockResult(i);
					reportTransactions(block.getValue(), br);
				}
			}
			reportedConsensusPoint=newConsensusPoint;
		}
	}
	
	private void reportTransactions(Block block, BlockResult br) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		for (long j = 0; j < nTrans; j++) {
			try {
				SignedData<ATransaction> t = block.getTransactions().get(j);
				Hash h = t.getHash();
				Message m = interests.get(h);
				if (m != null) {
					ACell id = m.getID();
					log.trace("Returning tranaction result ID {} to {}", id,m.getOriginString());
					Result res = br.getResults().get(j);

					boolean reported = m.reportResult(res);
					if (!reported) {
						// ignore?
					}
					interests.remove(h);
				}
			} catch (Throwable e) {
				log.warn("Exception while reporting transaction Result: ",e);
				// ignore
			}
		}
	}

	public void close() {
		transactionThread.interrupt();
	}

	public void start() {
		this.reportedConsensusPoint=server.getPeer().getConsensusPoint();
		transactionThread.start();
	}

	public boolean isAwaitingResults() {
		return interests.size()>0;
	}

	public int countInterests() {
		return interests.size();
	}
}
