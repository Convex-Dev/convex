package convex.net;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.Stores;

/**
 * Consumer<Message> abstract base class for default behaviour when awaiting results.
 */
public abstract class ResultConsumer implements Consumer<Message> {

	private static final Logger log = LoggerFactory.getLogger(ResultConsumer.class.getName());

	@Override
	public void accept(Message m) {
		MessageType type = m.getType();
		switch (type) {
			case DATA: {
				handleDataProvided(m);
				break;
			}
			case REQUEST_DATA: {
				handleDataRequest(m);
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
	}

	private void handleDataProvided(Message m) {
		// Ignore
	}

	private void handleDataRequest(Message m) {
		try {
			Message response = m.makeDataResponse(Stores.current());
			m.returnMessage(response);
		} catch (BadFormatException e) {
			// Request was bad
			m.closeConnection();
		}
	}

	/**
	 * Method called when a result is received.
	 *
	 * By default, delegates to handleResult and handleError
	 */
	private final void handleResultMessage(Message m) {
		try {
			Result result = m.getPayload();
			// we now have the full result, so notify those interested
			ACell cid=m.getID();
			handleResult(cid,result);
		} catch (BadFormatException | MissingDataException e) {
			// If there is missing data, re-buffer the message
			// Ignore. We probably lost this result?
			log.warn("Exception handling result",e);
		}
	}
	
	/**
	 * Handler for a fully received Result. May be overridden.
	 * 
	 * @param id ID of message received (or -1 if no message ID present)
	 * @param result Result value
	 */
	protected void handleResult(ACell id, Result result) {
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
	 * @param id The ID of the original message to which this result corresponds
	 * @param code The error code received. May not be null, and is usually a Keyword
	 * @param errorMessage The error message associated with the result (may be null)
	 */
	protected void handleError(ACell id, ACell code, ACell errorMessage) {
		log.warn("UNHANDLED ERROR RECEIVED: {} :  {}", code, errorMessage);
	}
	
	/**
	 * Method called when a normal (non-error) result is received.
	 *
	 * @param id The ID of the original message to which this result corresponds
	 * @param value The result value
	 */
	protected void handleNormalResult(ACell id, ACell value) {
		log.debug("UNHANDLED RESULT RECEIVED: id={}, value={}", id,value);
	}


}
