package convex.core.message;

import java.io.IOException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cpos.Belief;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.store.AStore;
import convex.core.util.Utils;

/**
 * <p>Class representing a message to / from a network participant</p>
 * 
 * <p>Encapsulates both message content and a means of return communication</p>.
 *
 * <p>This class is an immutable data structure, but NOT a representable on-chain
 * data structure, as it is part of the peer protocol layer.</p>
 *
 * <p>Messages contain a payload, which can be any data value.</p>
 */
public class Message {
	
	protected static final Logger log = LoggerFactory.getLogger(Message.class.getName());

	private static final Message BYE_MESSAGE = Message.create(MessageType.GOODBYE,Vectors.create(MessageTag.BYE));

	protected ACell payload;
	protected Blob messageData; // encoding of payload (possibly multi-cell)
	protected MessageType type;
	protected Predicate<Message> returnHandler;

	protected Message(MessageType type, ACell payload, Blob data, Predicate<Message> handler) {
		this.type = type;
		this.messageData=data;
		this.payload = payload;
		this.returnHandler=handler;
	}

	public static Message create(Predicate<Message> handler, MessageType type, Blob data) {
		return new Message(type, null,data,handler);
	}
	
	public static Message create(Blob data) throws BadFormatException {
		if (data.count()==0) throw new BadFormatException("Empty Message");
		return new Message(null, null,data,null);
	}
	
	public static Message create(MessageType type,ACell payload) {
		return new Message(type, payload,null,null);
	}
	
	public static Message create(MessageType type,ACell payload, Blob data) {
		return new Message(type, payload,data,null);
	}

	public static Message createDataResponse(ACell id, ACell... cells) {
		// This is a bit special because we don't want to have a full payload.
		Result result= Result.create(id,Vectors.create(cells));
		Message m = create(MessageType.RESULT,Result.create(id,Vectors.create(cells)));
		m.messageData=Format.encodeDataResult(result);		
		return m;
	}
	
	public static Message createDataRequest(ACell id, Hash... hashes) {
		int n=hashes.length;
		ACell[] cs=new ACell[n+2];
		cs[0]=MessageTag.DATA_REQUEST;
		cs[1]=id;
		for (int i=0; i<n; i++) {
			cs[i+2]=hashes[i];
		}
		return create(MessageType.DATA_REQUEST,Vectors.create(cs));
	}

	public static Message createBelief(Belief belief) {
		return create(MessageType.BELIEF,belief);
	}
	
	/**
	 * Create a Belief request message
	 * @return Message instance
	 */
	public static Message createBeliefRequest() {
		return create(MessageType.REQUEST_BELIEF,null);
	}

	public static Message createChallenge(SignedData<ACell> challenge) {
		return create(MessageType.CHALLENGE, challenge);
	}

	public static Message createResponse(SignedData<ACell> response) {
		return create(MessageType.RESPONSE, response);
	}

	public static Message createGoodBye() {
		return BYE_MESSAGE;
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T getPayload() throws BadFormatException {
		if (payload!=null) return (T) payload;
		if (messageData==null) return null; // no message data, so must actually be null
		
		// detect actual message data for null payload :-)
		if ((messageData.count()==1)&&(messageData.byteAt(0)==Tag.NULL)) return null;
		
		payload=Format.decodeMultiCell(messageData);
		
		return (T) payload;
	}
	
	/**
	 * Gets the encoded data for this message. Generates a single cell encoding if required.
	 * @return Blob containing message data
	 */
	public Blob getMessageData() {
		if (messageData!=null) return messageData;
		MessageType type=getType();
		switch (type) {
			case MessageType.BELIEF:
				// throw new Error("Received belief message should already have partial data encoding");
			default:
				messageData=Format.encodeMultiCell(payload,true);
		
		}
		return messageData;
	}

	/**
	 * Get the type of this message. May be UNKOWN if the message cannot be understood / processed
	 * @return
	 */
	public MessageType getType() {
		if (type==null) type=inferType();
		return type;
	}

	private MessageType inferType() {
		byte tag;
		if (hasData()) {
			// These can be inferred directly from top encoding tag
			tag=messageData.byteAt(0);
		} else {
			if (payload==null) return MessageType.UNKNOWN;
			tag=payload.getTag();
		}
		
		// Check tag first for special types
		if (tag==CVMTag.BELIEF) return MessageType.BELIEF;
		if (tag==Tag.SIGNED_DATA) return MessageType.BELIEF; // i.e. a SignedData<Order> or similar
		if (tag==CVMTag.RESULT) return MessageType.RESULT;
		
		try {
			ACell payload=getPayload();
			if (payload instanceof AVector) {
				AVector<?> v=(AVector<?>)payload;
				if (v.count()==0) return MessageType.UNKNOWN;
				Keyword mt=RT.ensureKeyword(v.get(0));
				if (mt==null) return MessageType.UNKNOWN;
				if (MessageTag.STATUS_REQUEST.equals(mt)) return MessageType.STATUS;
				if (MessageTag.QUERY.equals(mt)) return MessageType.QUERY;
				if (MessageTag.BYE.equals(mt)) return MessageType.GOODBYE;
				if (MessageTag.TRANSACT.equals(mt)) return MessageType.TRANSACT;
				if (MessageTag.DATA_REQUEST.equals(mt)) return MessageType.DATA_REQUEST;
			}
		} catch (Exception e) {
			// default fall-through to UNKNOWN. We don't know what it is supposed to be!
			try {
				ACell payload=getPayload();
				// System.out.println(PrintUtils.printRefTree(payload.getRef()));
				log.info("Can't infer message type with object "+Utils.getClassName(payload),e);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		return MessageType.UNKNOWN;
	}

	@Override
	public String toString() {
		try {
			ACell payload=getPayload();
			AString ps=RT.print(payload,10000);
			if (ps==null) return ("<BIG MESSAGE "+RT.count(getMessageData())+" TYPE ["+getType()+"]>");
			return ps.toString();
		} catch (MissingDataException e) {
			return "<PARTIAL MESSAGE [" + getType() + "] MISSING "+e.getMissingHash()+" ENC "+getMessageData().toHexString(16)+">";
		} catch (BadFormatException e) {
			return "<CORRUPTED MESSAGE ["+getType()+"]>: "+e.getMessage();
		}
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Message)) return false;
		Message other=(Message) o;
		if ((payload!=null)&&Utils.equals(payload, other.payload)) return true;

		if (getType()!=other.getType()) return false;
		return this.getMessageData().equals(other.getMessageData());
	}

	/**
	 * Gets the message ID for correlation, assuming this message type supports IDs.
	 *
	 * @return Message ID, or null if the message does not have a message ID
	 */
	public ACell getID()  {
		if (payload==null) throw new IllegalStateException("Attempting to get ID of message before Payload is decoded");
		switch (getType()) {	
			// Result is a special record type
			case RESULT: return getResultID();

			default: return getRequestID();
		}
	}
	
	/**
	 * Gets the request ID for this message, assuming it is a request expecting a response
	 * @return
	 */
	public ACell getRequestID() {
		// if (payload==null) throw new IllegalStateException("Attempting to get ID of message before Payload is decoded");
		try {
			switch (getType()) {	
			
				// ID in position 1
				case STATUS:
				case TRANSACT: 
				case QUERY:
				case DATA_REQUEST:{
					AVector<?> v=RT.ensureVector(getPayload());
					if (v.count()<2) return null;
					return RT.ensureLong(v.get(1));
				}
	
				default: return null;
			}
		} catch (Exception e) {
			log.warn("Unexpected error getting request ID",e);
			return null;
		}
	}
	
	/**
	 * Gets the result ID for this message, assuming it is a Result
	 * 
	 * This needs to work even if the payload is not yet decoded, for message routing (possibly with a different store)
	 * 
	 * @return
	 */
	public ACell getResultID() {
		if (payload!=null) {
			if (payload instanceof Result) {
				return ((Result)payload).getID();
			}
			return null;
		}
		
		if (hasData()) try {
			// Check tag is a Result
			byte tag=messageData.byteAt(0);
			if (tag!=CVMTag.RESULT) return null;
			
			// Peek at Result ID without loading whole payload
			return Result.peekResultID(messageData,0);
		} catch (Exception e) {
			log.warn("Unexpected error getting result ID: "+e.getMessage());
			return null;
		}
		
		return null;
	}
	
	/**
	 * Sets the message ID, if supported
	 * @param id ID to set for message
	 * @return Message with updated ID, or null if Message type does not support IDs
	 */
	@SuppressWarnings("unchecked")
	public Message withID(ACell id) {
		try {
			switch (getType()) {
	
				// Result is a special record type
				case RESULT: 
					return Message.create(type, ((Result)getPayload()).withID(id));
					
				// Using a vector [key ID ...]
				case STATUS: 
				case TRANSACT: 
				case QUERY:
				case DATA_REQUEST: {
					ACell o=getPayload();
					if (o instanceof AVector) {
						AVector<ACell> v = (AVector<ACell>)o; 
						if (v.count()<2) return null;
						// first element assumed to be ID
						return Message.create(type, v.assoc(1, id));
					}
				}
	
				default: return null;
			}
		} catch (BadFormatException | ClassCastException | IndexOutOfBoundsException e) {
			return null;
		}
	}


	/**
	 * Reports a result back to the originator of the message.
	 * 
	 * Will set a Result ID if necessary.
	 * 
	 * @param res Result record
	 * @return True if reported successfully, false otherwise
	 * @throws IllegalStateException if original message did not specify a return ID
	 */
	public boolean returnResult(Result res) {
		ACell id=getRequestID(); // what was the request ID of original message?
		if (id!=null) {
			// Make sure Result has correct result ID
			res=res.withID(id);
			Message msg=Message.createResult(res);
			return returnMessage(msg);
		} else {
			throw new IllegalStateException("Trying to return result with no original request ID");
		}
	}
	
	/**
	 * Returns a message back to the originator of the message.
	 * 
	 * Will set response ID if necessary.
	 * 
	 * @param m Message
	 * @return True if sent successfully, false otherwise
	 */
	public boolean returnMessage(Message m) {
		Predicate<Message> handler=returnHandler;
		if (handler==null) return false;
		return handler.test(m);
	}

	/**
	 * Return true if there is encoded message data
	 * @return
	 */
	public boolean hasData() {
		return messageData!=null;
	}

	public static Message createResult(Result res) {
		return create(MessageType.RESULT,res);
	}

	public static Message createResult(ACell id, ACell value, ACell error) {
		Result r=Result.create(id, value,error);
		return createResult(r);
	}

	/**
	 * Closes any connection associated with this message, probably because of bad behaviour
	 */
	public void closeConnection() {
		returnHandler=null;
	}

	public Message makeDataResponse(AStore store) throws BadFormatException {
		final int HEADER_OFFSET=2; // offset of hashes in request vector
		
		AVector<ACell> v = RT.ensureVector(getPayload());
		if ((v == null)||(v.isEmpty())) {
			throw new BadFormatException("Invalid data request payload");
		};
		if (v.count()>CPoSConstants.MISSING_LIMIT+HEADER_OFFSET) {
			throw new BadFormatException("Too many elements in Missing data request");
		}
		
		ACell id=v.get(1); // location of ID in request record
		//System.out.println("DATA REQ:"+ v);
		
		int n=v.size()-HEADER_OFFSET; // number of values requested (ignore header elements)
		
		ACell[] vals=new ACell[n];
		for (int i=0; i<n; i++) {
			Hash h=RT.ensureHash(v.get(i+HEADER_OFFSET));
			if (h==null) {
				throw new BadFormatException("Invalid data request hash");
			}
			
			Ref<?> r = store.refForHash(h);
			if (r != null) {
				ACell data = r.getValue();
				vals[i]=data;
			} else {
				// signal we don't have this data
				vals[i]=null;
			}
		}
		//System.out.println("DATA RESP:"+ v);
		// Create response. Will have null return connection
		Message resp=createDataResponse(id,vals);
		return resp;
	}

	public Result toResult() {
		try {
			MessageType type=getType();
			switch (type) {
			case MessageType.RESULT: 
				Result result=getPayload();
				return result;
				
			case MessageType.DATA: 
				// Wrap data responses in a successful Result
				return Result.create(getID(), getPayload(), null);
				
			default:
				return Result.create(getID(), Strings.create("Unexpected message type for Result: "+type), ErrorCodes.UNEXPECTED);
			}
		} catch (BadFormatException e) {
			return Result.fromException(e).withSource(SourceCodes.CLIENT);
		}
	}

	/**
	 * Create an instance with the given message data
	 * @param type Message type
	 * @param payload Message payload
	 * @param handler Handler for Results
	 * @return New MessageLocal instance
	 */
	public static Message create(MessageType type, ACell payload, Predicate<Message> handler) {
		return new Message(type,payload,null,handler);
	}
	
	/**
	 * Updates this message with a new result handler
	 * @param resultHandler New result handler to set (may be null to remove handler)
	 * @return Updated Message. May be the same Message if no change to result handler
	 */
	public Message withResultHandler(Predicate<Message> resultHandler) {
		if (this.returnHandler==resultHandler) return this;
		return new Message(type,payload,messageData,resultHandler);
	}

	public static Message createQuery(long id, String code, Address address) {
		return createQuery(id,Reader.read(code),address);
	}
	
	public static Message createQuery(long id, ACell code, Address address) {
		AVector<?> v=Vectors.create(MessageTag.QUERY,CVMLong.create(id),code,address);
		return create(MessageType.QUERY,v);
	}

	public static Message createTransaction(long id, SignedData<ATransaction> signed) {
		AVector<?> v=Vectors.create(MessageTag.TRANSACT,CVMLong.create(id),signed);
		return create(MessageType.TRANSACT,v);
	}
	
	/**
	 * Sends a STATUS Request Message on this connection.
	 *
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 * @throws IOException If IO error occurs
	 */
	public static Message createStatusRequest(long id) {
		CVMLong idPayload = CVMLong.create(id);
		AVector<?> v=Vectors.create(MessageTag.STATUS_REQUEST,idPayload);
		return create(MessageType.STATUS,v);
	}

	/**
	 * Return the Hash of the Message payload
	 * @return Hash, or null if message format is invalid
	 */
	public Hash getHash() {
		try {
			return getPayload().getHash();
		} catch (BadFormatException e) {
			return null;
		}
	}



}
