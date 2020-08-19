package convex.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.Result;
import convex.core.crypto.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.MissingDataException;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Consumer<Message> abstract base class for awaiting results.
 * 
 * Provides basic buffering of results until all data is available.
 */
public abstract class ResultConsumer implements Consumer<Message> {

	private static final Logger log = Logger.getLogger(ResultConsumer.class.getName());
	
	private static final Level LEVEL_RESULT= Level.FINER;
	private static final Level LEVEL_ERROR = Level.FINER;
	private static final Level LEVEL_MISSING = Level.WARNING;
		
	@Override
	public final void accept(Message m) {
		try {
			MessageType type = m.getType();
			switch (type) {
				case DATA: {
					// Just store the data, can't guarantee full persistence yet
					try {
						Object o = m.getPayload();
						Ref<?> r = Ref.create(o);
						r.persistShallow();
						unbuffer(r.getHash());
					} catch (MissingDataException e) {
						// ignore?
					}
					break;
				}
				case MISSING_DATA: {
					// try to be helpful by returning sent data
					Hash h = m.getPayload();
					Ref<?> r = Stores.current().refForHash(h);
					if (r != null) try {
						m.getPeerConnection().sendData(r.getValue());
					} catch (IOException e) {
						log.warning(e.getMessage());
					}
					break;
				}
				case RESULT: {
					handleResultMessage(m);
					break;
				}
				default: {
					log.info("Message type ignored: " + type);
				}
			}
		} catch (Throwable t) {
			log.warning("Failed to accept message!");
			t.printStackTrace();
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
		log.log(LEVEL_RESULT,"RESULT RECEIVED: " + value);
	}
	
	/**
	 * Method called when a result is received.
	 * 
	 * By default, delegates to handleResult and handleError
	 */
	protected void handleResultMessage(Message m) {
		Result result = m.getPayload();
		try {
			Ref.createPersisted(result);

			// we now have the full result, so notify those interested
			Object rv = result.getValue();
			long id = m.getID();
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
				if (m.getPeerConnection().sendMissingData(hash)) {
					log.log(LEVEL_MISSING,"Missing data "+hash.toHexString()+" requested by client for RESULT of type: "+Utils.getClassName(result));
					buffer(hash, m);
				} else {
					log.log(LEVEL_MISSING,"Unable to request missing data");
				}
			} catch (IOException e1) {
				// Ignore. We probably lost this result?
				log.log(LEVEL_MISSING,"IO Exception handling result - "+e1.getMessage());
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
		log.log(LEVEL_ERROR,"Error received: " + code + " : " + errorMessage);
	}
}
