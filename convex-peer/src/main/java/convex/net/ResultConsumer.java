package convex.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.store.Stores;
import convex.core.util.Utils;
import convex.net.message.Message;

/**
 * Consumer<Message> abstract base class for awaiting results.
 *
 * Provides basic buffering of:
 * - Missing data until all data is available.
 */
public abstract class ResultConsumer implements Consumer<Message> {

	private static final Logger log = LoggerFactory.getLogger(ResultConsumer.class.getName());

	@Override
	public void accept(Message m) {
		try {
			MessageType type = m.getType();
			switch (type) {
				case DATA: {
					handleDataProvided(m);
					break;
				}
				case MISSING_DATA: {
					handleMissingDataRequest(m);
					break;
				}
				case RESULT: {
					handleResultMessage(m);
					break;
				}
				default: {
					log.error("Message type ignored: ", type);
				}
			}
		} catch (Throwable t) {
			log.warn("Failed to accept message! {}",t);
		}
	}

	private void handleDataProvided(Message m) {
		// Just store the data, can't guarantee full persistence yet
		try {
			ACell o = m.getPayload();
			Ref<?> r = Ref.get(o);
			r.persistShallow();
			Hash h=r.getHash();
			log.trace("Recieved DATA for hash {}",h);
			unbuffer(h);
		} catch (MissingDataException e) {
			// ignore?
		}
	}

	private void handleMissingDataRequest(Message m) {
		// try to be helpful by returning sent data
		Hash h = RT.ensureHash(m.getPayload());
		if (h==null) return; // not a valid payload so ignore
		
		Ref<?> r = Stores.current().refForHash(h);
		if (r != null) try {
			boolean sent=m.sendData(r.getValue());
			if (!sent) {
				log.warn("Unable to satisfy missing data request");
			}
		} catch (Exception e) {
			log.warn("Error replying to MISSING DATA request",e);
		}
	}

	/**
	 * Map for messages delayed due to missing data
	 */
	private HashMap<Hash, ArrayList<Message>> bufferedMessages = new HashMap<>();

	private synchronized void buffer(Hash hash, Message m) {
		ArrayList<Message> msgs = bufferedMessages.get(hash);
		if (msgs == null) {
			msgs = new ArrayList<Message>();
			bufferedMessages.put(hash, msgs);
		}
		msgs.add(m);
	}

	/**
	 * Unbuffer and replay messages for a given hash
	 *
	 * @param hash
	 */
	protected synchronized void unbuffer(Hash hash) {
		ArrayList<Message> msgs = bufferedMessages.get(hash);
		if (msgs != null) {
			bufferedMessages.remove(hash);
			for (Message m : msgs) {
				accept(m);
			}
		}
	}

	/**
	 * Method called when a result is received.
	 *
	 * By default, delegates to handleResult and handleError
	 */
	private final void handleResultMessage(Message m) {
		Result result = m.getPayload();
		try {
			ACell.createPersisted(result);

			// we now have the full result, so notify those interested
			long id=m.getID().longValue();
			handleResult(id,result);
		} catch (MissingDataException e) {
			// If there is missing data, re-buffer the message
			// And wait for it to arrive later
			Hash hash = e.getMissingHash();
			try {
				if (m.sendMissingData(hash)) {
					log.debug("Missing data {} requested by client for RESULT of type: {}",hash.toHexString(),Utils.getClassName(result));
					buffer(hash, m);
				} else {
					log.debug("Unable to request missing data");
				}
			} catch (Exception e1) {
				// Ignore. We probably lost this result?
				log.warn("Exception handling result - {}",e1);
			}
			return;
		}
	}
	
	/**
	 * Handler for a fully received Result. May be overridden.
	 * 
	 * @param id ID of message received
	 * @param result Result value
	 */
	protected void handleResult(long id, Result result) {
		ACell rv = result.getValue();
		ACell err = result.getErrorCode();
		if (err!=null) {
			handleError(id, err, rv);
		} else {
			handleNormalResult(id, rv);
		}
	}

	/**
	 * Method called when an error result is received. May be overriden.
	 *
	 * Default behaviour is simply to log the error.
	 *
	 * If this method throws a MissingDataException, missing data is requested and
	 * the result handling may be retried later.
	 *
	 * @param id The ID of the original message to which this result corresponds
	 * @param code The error code received. May not be null, and is usually a Keyword
	 * @param errorMessage The error message associated with the result (may be null)
	 */
	protected void handleError(long id, ACell code, ACell errorMessage) {
		log.warn("UNHANDLED ERROR RECEIVED: {} :  {}", code, errorMessage);
	}
	
	/**
	 * Method called when a normal (non-error) result is received.
	 *
	 * If this method throws a MissingDataException, missing data is requested and
	 * the result handling may be retried later.
	 *
	 * @param id The ID of the original message to which this result corresponds
	 * @param value The result value
	 */
	protected void handleNormalResult(long id, ACell value) {
		log.warn("UNHANDLED RESULT RECEIVED: id={}, value={}", id,value);
	}


}
