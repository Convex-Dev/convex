package convex.net;

import java.io.IOException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Belief;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.store.AStore;

/**
 * <p>Class representing a message to / from a specific connection</p>
 * 
 * <p>Encapsulates both message content and a means of return communication</p>.
 *
 * <p>This class is an immutable data structure, but NOT a representable on-chain
 * data structure, as it is part of the peer protocol layer.</p>
 *
 * <p>Messages may contain a Payload, which can be any Data Value.</p>
 */
public class Message {
	
	protected static final Logger log = LoggerFactory.getLogger(Message.class.getName());

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

	public static Message create(Connection conn, MessageType type, Blob data) {
		Predicate<Message> handler=t -> {
			try {
				return conn.sendMessage(t);
			} catch (IOException e) {
				return false;
			}
		};
		return new Message(type, null,data,handler);
	}
	
	public static Message create(MessageType type,ACell payload) {
		return new Message(type, payload,null,null);
	}
	
	public static Message create(MessageType type,ACell payload, Blob data) {
		return new Message(type, payload,data,null);
	}

	public static Message createDataResponse(CVMLong id, ACell... cells) {
		int n=cells.length;
		ACell[] cs=new ACell[n+1];
		cs[0]=id;
		for (int i=0; i<n; i++) {
			cs[i+1]=cells[i];
		}
		return create(MessageType.DATA,Vectors.create(cs));
	}
	
	public static Message createDataRequest(CVMLong id, Hash... hashes) {
		int n=hashes.length;
		ACell[] cs=new ACell[n+1];
		cs[0]=id;
		for (int i=0; i<n; i++) {
			cs[i+1]=hashes[i];
		}
		return create(MessageType.REQUEST_DATA,Vectors.create(cs));
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
		return create(MessageType.GOODBYE, null);
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> T getPayload() throws BadFormatException {
		if (payload!=null) return (T) payload;
		if (messageData==null) return null; // no message data, so must actually be null
		
		// detect actual message data for null payload :-)
		if ((messageData.count()==1)&&(messageData.byteAt(0)==Tag.NULL)) return null;
		
		switch(type) {
		case MessageType.DATA:
			ACell[] cells=Format.decodeCells(messageData);
			payload=Vectors.create(cells);
			break;
		default:
			payload=Format.decodeMultiCell(messageData);
		}
		
		return (T) payload;
	}
	
	/**
	 * Gets the encoded data for this message. Generates a single cell encoding if required.
	 * @return Blob containing message data
	 */
	public Blob getMessageData() {
		if (messageData!=null) return messageData;
		
		// default to single cell encoding
		// TODO: alternative depths for different types
		switch (type) {
		case MessageType.RESULT:
		case MessageType.QUERY:
		case MessageType.TRANSACT:
		case MessageType.REQUEST_DATA:
		   messageData=Format.encodeMultiCell(payload,true);
		   break;
		   
		case MessageType.DATA:
			@SuppressWarnings("unchecked") 
			AVector<ACell> v=(AVector<ACell>) payload;
			messageData=Format.encodeCells(v);		 
			break;
			
		default:
			messageData=Format.encodedBlob(payload);
		}
		
		return messageData;
	}

	public MessageType getType() {
		return type;
	}

	@Override
	public String toString() {
		try {
			ACell payload=getPayload();
			AString ps=RT.print(getPayload(),10000);
			if (ps==null) ps=Strings.create("<"+RT.count(messageData)+" bytes as "+RT.getType(payload)+">");
			return "#message {:type " + getType() + " :payload " + ps + "}";
		} catch (MissingDataException e) {
			return "#message {:type " + getType() + " :payload <partial, some still missing>}";
		} catch (BadFormatException e) {
			return "#message <CORRUPED "+getType()+": "+e.getMessage();
		}
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Message)) return false;
		Message other=(Message) o;
		if (getType()!=other.getType()) return false;
		return this.getMessageData().equals(other.getMessageData());
	}

	/**
	 * Gets the message ID for correlation, assuming this message type supports IDs.
	 *
	 * @return Message ID, or null if the message does not have a message ID
	 */
	public CVMLong getID()  {
		try {
			switch (type) {
				// Query and transact use a vector [ID ...]
				case QUERY:
				case TRANSACT: return (CVMLong) ((AVector<?>)getPayload()).get(0);
	
				// Result is a special record type
				case RESULT: return (CVMLong)((Result)getPayload()).getID();
	
				// Status ID is the single value
				case STATUS: return (CVMLong)(getPayload());
				
				case DATA: {
					ACell o=getPayload();
					if (o instanceof AVector) {
						AVector<?> v = (AVector<?>)o; 
						if (v.count()==0) return null;
						// first element might be ID, otherwise null
						return RT.ensureLong(v.get(0));
					}
				}
	
				default: return null;
			}
		} catch (BadFormatException e) {
			return null;
		}
	}
	
	/**
	 * Sets the message ID, if supported
	 * @param id ID to set for message
	 * @return Message with updated ID, or null if message does not support IDs
	 */
	@SuppressWarnings("unchecked")
	public Message withID(CVMLong id) {
		try {
			switch (type) {
				// Query and transact use a vector [ID ...]
				case QUERY:
				case TRANSACT: 
					return Message.create(type, ((AVector<ACell>)getPayload()).assoc(0, id));
	
				// Result is a special record type
				case RESULT: 
					return Message.create(type, ((Result)getPayload()).withID(id));
	
				// Status ID is the single value
				case STATUS: 
					return Message.create(type, id);
				
				case DATA: {
					ACell o=getPayload();
					if (o instanceof AVector) {
						AVector<ACell> v = (AVector<ACell>)o; 
						if (v.count()==0) return null;
						// first element assumed to be ID
						return Message.create(type, v.assoc(0, id));
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
	 */
	public boolean returnResult(Result res) {
		ACell id=getID();
		if (id!=null) res=res.withID(id);
	
		Message msg=Message.createResult(res);
		return returnMessage(msg);
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

	public boolean hasData() {
		return messageData!=null;
	}

	public static Message createResult(Result res) {
		return create(MessageType.RESULT,res);
	}

	public static Message createResult(CVMLong id, ACell value, ACell error) {
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
		AVector<ACell> v = RT.ensureVector(getPayload());
		if ((v == null)||(v.isEmpty())) {
			throw new BadFormatException("Invalid data request payload");
		};
		//System.out.println("DATA REQ:"+ v);
		long n=v.count();
		for (int i=1; i<n; i++) {
			Hash h=RT.ensureHash(v.get(i));
			if (h==null) {
				throw new BadFormatException("Invalid data request hash");
			}
			
			Ref<?> r = store.refForHash(h);
			if (r != null) {
				ACell data = r.getValue();
				v=v.assoc(i, data);
			} else {
				// signal we don't have this data
				v=v.assoc(i, null);
			}
		}
		//System.out.println("DATA RESP:"+ v);
		// Create response. Will have null return connection
		Message resp=create(MessageType.DATA,v);
		return resp;
	}

	public Result toResult() {
		try {
			MessageType type=getType();
			switch (type) {
			case MessageType.RESULT: 
				Result result=getPayload();
				return result;
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

}
