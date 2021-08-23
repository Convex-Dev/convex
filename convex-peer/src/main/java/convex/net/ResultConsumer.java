package convex.net;

import java.io.IOException;
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
import convex.core.store.Stores;
import convex.core.util.Utils;

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
			t.printStackTrace();
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
		Hash h = m.getPayload();
		Ref<?> r = Stores.current().refForHash(h);
		if (r != null) try {
			m.getConnection().sendData(r.getValue());
		} catch (IOException e) {
			log.debug("Error replying to MISSING DATA request",e);
		}
	}

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
	 * Method called when a normal (non-error) result is received.
	 *
	 * If this method throws a MissingDataException, missing data is requested and
	 * the result handling may be retried later.
	 *
	 * @param id The ID of the original message to which this result corresponds
	 * @param value The result value
	 */
	protected void handleResult(long id, Object value) {
		handleResult(value);
	}

	/**
	 * Method called when a normal (non-error) result is received.
	 *
	 * If this method throws a MissingDataException, missing data is requested and
	 * the result handling may be retried later.
	 *
	 * @param value The result value
	 */
	protected void handleResult(Object value) {
		log.debug("RESULT RECEIVED: {}", value);
	}

	/**
	 * Method called when a result is received.
	 *
	 * By default, delegates to handleResult and handleError
	 */
	protected void handleResultMessage(Message m) {
		Result result = m.getPayload();
		try {
			ACell.createPersisted(result);

			// we now have the full result, so notify those interested
			Object rv = result.getValue();
			long id = m.getID().longValue();
			Object err = result.getErrorCode();
			if (err!=null) {
				handleError(id, err, rv);
			} else {
				handleResult(id, rv);
			}
		} catch (MissingDataException e) {
			// If there is missing data, re-buffer the message
			// And wait for it to arrive later
			Hash hash = e.getMissingHash();
			try {
				if (m.getConnection().sendMissingData(hash)) {
					log.debug("Missing data {} requested by client for RESULT of type: {}",hash.toHexString(),Utils.getClassName(result));
					buffer(hash, m);
				} else {
					log.debug("Unable to request missing data");
				}
			} catch (IOException e1) {
				// Ignore. We probably lost this result?
				log.warn("IO Exception handling result - {}",e1);
			}
			return;
		}
	}

	/**
	 * Method called when an error result is received.
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
	protected void handleError(long id, Object code, Object errorMessage) {
		handleError(code,errorMessage);
	}

	/**
	 * Method called when an error result is received.
	 *
	 * Default behaviour is simply to log the error.
	 *
	 * If this method throws a MissingDataException, missing data is requested and
	 * the result handling may be retried later.
	 *
	 * @param code The error code received. May not be null, and is usually a Keyword
	 * @param errorMessage The error message associated with the result (may be null)
	 */
	protected void handleError(Object code, Object errorMessage) {
		log.debug("Error received: {} :  {}", code, errorMessage);
	}
}
