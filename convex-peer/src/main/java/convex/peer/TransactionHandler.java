package convex.peer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keywords;
import convex.core.data.PeerStatus;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.lang.Reader;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Server component for handling client transactions
 */
public class TransactionHandler {
	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());
	
	/**
	 * Default minimum delay between proposing own transactions as a peer
	 */
	private static final long OWN_BLOCK_DELAY=2000;


	protected final Server server;
	
	protected final Thread transactionThread; 
	
	/**
	 * Queue for incoming (unverified) transaction messages
	 */
	protected final ArrayBlockingQueue<Message> txMessageQueue;
	
	/**
	 * Queue for received Transactions submitted for clients of this Peer
	 */
	ArrayBlockingQueue<SignedData<ATransaction>> transactionQueue;

	public TransactionHandler(Server server) {
		this.server=server;
		
		txMessageQueue= new ArrayBlockingQueue<>(Constants.TRANSACTION_QUEUE_SIZE);
		transactionQueue=new ArrayBlockingQueue<>(Constants.TRANSACTION_QUEUE_SIZE);	
		
		transactionThread=new Thread(transactionHandlerLoop,"Transaction handler on port: "+server.getPort());
	}
	
	/**
	 * Offer a transaction for handling
	 * @param m Message offered
	 * @return True if queued for handling, false otherwise
	 */
	public boolean offerTransaction(Message m) {
		return txMessageQueue.offer(m);
	}
	
	protected final Runnable transactionHandlerLoop = new Runnable() {
		@Override
		public void run() {
			Stores.setCurrent(server.getStore());
			ArrayList<Message> messages=new ArrayList<>();
			while (server.isLive()) {
				try {
					Message m = txMessageQueue.poll(1000, TimeUnit.MILLISECONDS);
					if (m==null) continue;
					
					// We have at least one transaction to handle, drain queue to get the rest
					messages.add(m);
					txMessageQueue.drainTo(messages);
					
					// Process transaction messages
					for (Message msg: messages) {
						processMessage(msg);
					}
					
					// Wait for more transactions to accumulate before sending anything new
					Thread.sleep(Constants.MIN_BLOCK_TIME);
					
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
				// SECURITY: Client tried to send a badly signed transaction!
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
			// If we already have the transaction persisted, will set signature status
			sd=ACell.createPersisted(sd).getValue();
	
			// Put on Server's transaction queue. We are OK to block here
			transactionQueue.put(sd);
			
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
	
	/**
	 * Checks for pending transactions, and if found propose them as a new Block.
	 *
	 * @return True if a new block is published, false otherwise.
	 */
	protected Block maybeGenerateBlock(Peer peer) {
		long timestamp=Utils.getCurrentTimestamp();

		if (timestamp>=lastBlockPublishedTime+Constants.MIN_BLOCK_TIME) {
			// possibly have client transactions to publish
			transactionQueue.drainTo(newTransactions);
		}

		// possibly have own transactions to publish as a Peer
		maybeGetOwnTransactions(peer);

		int n = newTransactions.size();
		if (n == 0) return null;
		
		// TODO: smaller block if too many transactions?
		Block block = Block.create(timestamp, (List<SignedData<ATransaction>>) newTransactions);
		newTransactions.clear();
		lastBlockPublishedTime=Utils.getCurrentTimestamp();
		return block;
	}
	
	/**
	 * The list of new transactions to be added to the next Block. Accessed only in update loop
	 *
	 * Must all have been fully persisted.
	 */
	private ArrayList<SignedData<ATransaction>> newTransactions = new ArrayList<>();

	private long lastOwnTransactionTimestamp=0L;

	protected long lastBlockPublishedTime=0L;


	
	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	void maybeGetOwnTransactions(Peer p) {
		long ts=Utils.getCurrentTimestamp();

		// If we already posted own transaction recently, don't try again
		if (ts<(lastOwnTransactionTimestamp+OWN_BLOCK_DELAY)) return;

		// NOTE: beyond this point we only execute stuff when AUTO_MANAGE is set
		if (!Utils.bool(server.getConfig().get(Keywords.AUTO_MANAGE))) return;

		State s=p.getConsensusState();
		String desiredHostname=server.getHostname(); // Intended hostname
		AccountKey peerKey=p.getPeerKey();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return; // No peer record in consensus state?
		
		AString chn=ps.getHostname();
		String currentHostname=(chn==null)?null:chn.toString();
		
		// Try to set hostname if not correctly set
		trySetHostname:
		if (!Utils.equals(desiredHostname, currentHostname)) {
			log.debug("Trying to update own hostname from: {} to {}",currentHostname,desiredHostname);
			Address address=ps.getController();
			if (address==null) break trySetHostname;
			AccountStatus as=s.getAccount(address);
			if (as==null) break trySetHostname;
			if (!Utils.equals(peerKey, as.getAccountKey())) break trySetHostname;

			String code;
			if (desiredHostname==null) {
				code = String.format("(set-peer-data %s {:url nil})", peerKey);
			} else {
				code = String.format("(set-peer-data %s {:url \"%s\"})", peerKey, desiredHostname);
			}
			ACell message = Reader.read(code);
			ATransaction transaction = Invoke.create(address, as.getSequence()+1, message);
			newTransactions.add(p.getKeyPair().signData(transaction));
			lastOwnTransactionTimestamp=ts; // mark this timestamp
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
