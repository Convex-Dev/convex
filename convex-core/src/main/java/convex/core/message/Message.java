package convex.core.message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

import convex.core.ErrorCodes;
import convex.core.crypto.AKeyPair;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.cvm.CVMEncoder;
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
import convex.core.exceptions.PartialMessageException;
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
	/** Domain separator for authenticated lattice-node routes. */
	public static final AString LATTICE_PEER_CHALLENGE_CONTEXT =
		Strings.intern("convex-lattice-peer-v1");

	private static final Message BYE_MESSAGE = Message.create(MessageType.GOODBYE,Vectors.create(MessageTag.BYE));

	protected ACell payload;
	protected Blob messageData; // encoding of payload (possibly multi-cell)
	protected MessageType type;
	protected AConnection connection;

	protected Message(MessageType type, ACell payload, Blob data, AConnection connection) {
		this.type = type;
		this.messageData=data;
		this.payload = payload;
		this.connection=connection;
	}

	public static Message create(AConnection conn, Blob data) {
		return new Message(null, null,data,conn);
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
		m.messageData=Format.encodeDataResult(result,CPoSConstants.MAX_MESSAGE_LENGTH);
		return m;
	}

	/**
	 * Creates one unsolicited DATA message containing independently addressable
	 * cells. The cells are encoded once as multi-cell children of a {@code :DATA}
	 * envelope; receivers may stage them before a later composite root arrives.
	 *
	 * @param cells non-embedded cells to send
	 * @param maxMessageLength maximum encoded message body length
	 * @return bounded DATA message
	 */
	public static Message createDataMessage(List<? extends ACell> cells, int maxMessageLength) {
		if (cells.isEmpty()) throw new IllegalArgumentException("DATA message requires at least one cell");
		if (cells.size()>CPoSConstants.MISSING_LIMIT) {
			throw new IllegalArgumentException("Too many cells in DATA message: "+cells.size());
		}

		ACell[] values=new ACell[cells.size()+1];
		values[0]=MessageTag.DATA;
		for (int i=0; i<cells.size(); i++) {
			ACell cell=cells.get(i);
			if (cell==null || cell.isEmbedded()) {
				throw new IllegalArgumentException("DATA cells must be non-null and non-embedded");
			}
			values[i+1]=cell;
		}
		AVector<?> payload=Vectors.create(values);
		Blob data=Format.encodeDelta(dataDelta(cells,payload),maxMessageLength);
		return create(MessageType.DATA,payload,data);
	}

	/**
	 * Builds the delta for a DATA message: the cells, then the payload Vector's own
	 * branch nodes, then the payload as the top cell. A Vector of more than one chunk
	 * of cells is a tree whose chunk leaves are cells in their own right; without them
	 * the receiver cannot read any element, not even the DATA tag, and the message is
	 * undecodable. The walk stops at the cells themselves, which may be Vectors too.
	 */
	private static ArrayList<ACell> dataDelta(List<? extends ACell> cells, AVector<?> payload) {
		ArrayList<ACell> delta=new ArrayList<>(cells.size()+2);
		delta.addAll(cells);
		HashSet<Hash> elements=new HashSet<>(cells.size()*2);
		for (ACell cell: cells) elements.add(Cells.getHash(cell));
		addVectorNodes(payload,elements,delta);
		delta.add(payload);
		return delta;
	}

	private static void addVectorNodes(ACell node, HashSet<Hash> elements, ArrayList<ACell> delta) {
		Cells.visitBranchRefs(node, ref -> {
			if (elements.contains(ref.getHash())) return; // a DATA cell, already in the delta
			ACell child=ref.getValue();
			if (child==null) return;
			delta.add(child);
			addVectorNodes(child,elements,delta);
		});
	}

	/**
	 * Partitions cells into DATA messages whose encoded bodies do not exceed the
	 * supplied limit. Embedded cells are omitted because their encodings already
	 * travel inside their nearest non-embedded parent.
	 *
	 * @param cells cells to partition
	 * @param maxMessageLength maximum encoded body length for each batch
	 * @return bounded DATA messages in input order
	 */
	public static List<Message> createDataMessages(List<? extends ACell> cells, int maxMessageLength) {
		return createDataMessages(cells,maxMessageLength,CPoSConstants.MAX_MESSAGE_LENGTH);
	}

	/**
	 * Partitions cells into a bounded number of DATA message bytes. The total
	 * limit bounds transient encoded materialisation for one propagation attempt;
	 * cells beyond the budget are deliberately left for pull-based recovery.
	 *
	 * @param cells cells to partition
	 * @param maxMessageLength maximum encoded body length for each batch
	 * @param maxTotalLength maximum combined encoded body length to materialise
	 * @return bounded DATA messages in input order
	 */
	public static List<Message> createDataMessages(List<? extends ACell> cells,
			int maxMessageLength, long maxTotalLength) {
		if (maxMessageLength<1 || maxMessageLength>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("DATA message limit must be between 1 and "
				+CPoSConstants.MAX_MESSAGE_LENGTH+": "+maxMessageLength);
		}
		if (maxTotalLength<1 || maxTotalLength>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("DATA materialisation limit must be between 1 and "
				+CPoSConstants.MAX_MESSAGE_LENGTH+": "+maxTotalLength);
		}
		ArrayList<Message> messages=new ArrayList<>();
		ArrayList<ACell> batch=new ArrayList<>();
		long totalLength=0;
		for (ACell cell:cells) {
			if (cell==null || cell.isEmbedded()) continue;
			batch.add(cell);
			boolean tooMany=batch.size()>CPoSConstants.MISSING_LIMIT;
			boolean tooLarge=!tooMany && dataMessageLength(batch)>maxMessageLength;
			if (tooMany || tooLarge) {
				batch.remove(batch.size()-1);
				if (batch.isEmpty()) {
					throw new IllegalArgumentException("Cell cannot fit in DATA message limit of "
						+maxMessageLength+" bytes");
				}
				long messageLength=dataMessageLength(batch);
				if (totalLength+messageLength>maxTotalLength) return messages;
				Message message=createDataMessage(batch,maxMessageLength);
				messages.add(message);
				totalLength+=messageLength;
				batch=new ArrayList<>();
				batch.add(cell);
				if (dataMessageLength(batch)>maxMessageLength) {
					throw new IllegalArgumentException("Cell cannot fit in DATA message limit of "
						+maxMessageLength+" bytes");
				}
			}
		}
		if (!batch.isEmpty()) {
			long messageLength=dataMessageLength(batch);
			if (totalLength+messageLength<=maxTotalLength) {
				messages.add(createDataMessage(batch,maxMessageLength));
			}
		}
		return messages;
	}

	private static long dataMessageLength(List<? extends ACell> cells) {
		ACell[] values=new ACell[cells.size()+1];
		values[0]=MessageTag.DATA;
		for (int i=0; i<cells.size(); i++) values[i+1]=cells.get(i);
		AVector<?> payload=Vectors.create(values);
		return Format.getDeltaEncodingLength(dataDelta(cells,payload));
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

	public static Message createChallenge(long id, SignedData<ACell> challenge) {
		return createChallenge(CVMLong.create(id),challenge);
	}

	public static Message createChallenge(CVMLong id, SignedData<ACell> challenge) {
		AVector<?> v=Vectors.create(MessageTag.CHALLENGE,id,challenge);
		return create(MessageType.CHALLENGE, v);
	}

	/**
	 * Responds to a CHALLENGE message using the given key pair.
	 *
	 * <p>Extracts the signed challenge data from the message, validates the
	 * format, checks the target key matches, optionally validates the contextID,
	 * then signs and returns a response.
	 *
	 * <p>On any error, returns an error Result so the caller gets a definite answer.
	 *
	 * @param keyPair Key pair to sign the response with (must match the target key in the challenge)
	 * @param contextValidator Optional predicate to validate contextID (element 2). Null to skip.
	 */
	@SuppressWarnings("unchecked")
	public void respondToChallenge(AKeyPair keyPair, Predicate<ACell> contextValidator) {
		try {
			// Message payload is [tag, id, signedData]
			AVector<ACell> msgPayload = getPayload();
			if (msgPayload == null || msgPayload.count() != 3) {
				returnResult(Result.error(ErrorCodes.FORMAT, Strings.create("Invalid challenge format")));
				return;
			}
			ACell rawChallenge=msgPayload.get(2);
			if (!(rawChallenge instanceof SignedData<?> rawSigned)) {
				returnResult(Result.error(ErrorCodes.FORMAT, Strings.create("Missing signed data")));
				return;
			}
			returnResult(answerChallenge(keyPair,(SignedData<ACell>)rawSigned,contextValidator));
		} catch (Exception e) {
			try {
				returnResult(Result.error(ErrorCodes.UNEXPECTED, Strings.create(e.getMessage())));
			} catch (Exception e2) {
				// best effort
			}
		}
	}

	/**
	 * Validates and answers one signed possession challenge. This shared path is used
	 * by both message-based and direct in-process transports.
	 */
	@SuppressWarnings("unchecked")
	public static Result answerChallenge(AKeyPair keyPair,SignedData<ACell> signedData,
			Predicate<ACell> contextValidator) {
		try {
			if (keyPair==null) return Result.error(ErrorCodes.TRUST,Strings.create("No signing key"));
			if (signedData==null) return Result.error(ErrorCodes.FORMAT,Strings.create("Missing signed data"));
			if (!signedData.checkSignature()) {
				return Result.error(ErrorCodes.TRUST,Strings.create("Invalid challenge signature"));
			}
			ACell challenge=signedData.getValue();
			if (!(challenge instanceof AVector<?> rawValues)) {
				return Result.error(ErrorCodes.FORMAT,Strings.create("Invalid challenge data"));
			}
			AVector<ACell> values=(AVector<ACell>)rawValues;
			long n=values.count();
			Hash token=(n>=1)?RT.ensureHash(values.get(0)):null;
			if (n<2 || n>3 || token==null) {
				return Result.error(ErrorCodes.FORMAT,Strings.create("Invalid challenge elements"));
			}

			ACell rawTarget=values.get(1);
			if (rawTarget!=null) {
				AccountKey targetKey=RT.ensureAccountKey(rawTarget);
				if (targetKey==null || !keyPair.getAccountKey().equals(targetKey)) {
					return Result.error(ErrorCodes.TRUST,Strings.create("Wrong target key"));
				}
			}

			ACell contextID=(n==3)?values.get(2):null;
			if (contextValidator!=null && !contextValidator.test(contextID)) {
				return Result.error(ErrorCodes.TRUST,Strings.create("Context mismatch"));
			}

			AccountKey challengerKey=signedData.getAccountKey();
			AVector<ACell> responseValues=(contextID!=null)
				?Vectors.of(token,challengerKey,contextID)
				:Vectors.of(token,(ACell)challengerKey);
			return Result.value(keyPair.signData(responseValues));
		} catch (Exception e) {
			return Result.error(ErrorCodes.UNEXPECTED,Strings.create(e.getMessage()));
		}
	}

	/**
	 * Builds and signs a challenge vector: {@code [token, targetKey, contextID?]}.
	 *
	 * @param kp        Key pair to sign with
	 * @param token     Random nonce
	 * @param targetKey Expected key of the challenged party, or null to accept any
	 * @param contextID Optional context (e.g. network ID), or null to omit
	 * @return Signed challenge data
	 */
	public static SignedData<ACell> signChallenge(AKeyPair kp, Hash token, AccountKey targetKey, ACell contextID) {
		AVector<ACell> challenge = (contextID != null)
			? Vectors.of(token, targetKey, contextID)
			: Vectors.of(token, (ACell) targetKey);
		return kp.signData(challenge);
	}

	/**
	 * Validates a challenge response. Checks that the signed response contains
	 * the expected token, own key, and optional context ID.
	 *
	 * @param result      The Result from the challenge response
	 * @param token       The random token sent in the challenge
	 * @param ownKey      The challenger's own AccountKey (expected in slot 1)
	 * @param contextID   Optional context ID (expected in slot 2 if present), or null
	 * @param expectedKey Expected signer key, or null to accept any
	 * @return The verified remote AccountKey, or null if validation fails
	 */
	@SuppressWarnings("unchecked")
	public static AccountKey verifyChallengeResponse(Result result, Hash token, AccountKey ownKey, ACell contextID, AccountKey expectedKey) {
		if (result == null || result.isError()) return null;
		ACell rv = result.getValue();
		if (!(rv instanceof SignedData)) return null;
		SignedData<ACell> response = (SignedData<ACell>) rv;
		if (!response.checkSignature()) return null;
		AccountKey remoteKey = response.getAccountKey();

		if (expectedKey != null && !expectedKey.equals(remoteKey)) return null;

		ACell inner = response.getValue();
		if (!(inner instanceof AVector)) return null;
		AVector<ACell> values = (AVector<ACell>) inner;
		long n = values.count();
		if (n != ((contextID == null) ? 2 : 3)) return null;
		if (!token.equals(values.get(0))) return null;
		if (!ownKey.equals(values.get(1))) return null;
		if (contextID != null && !Utils.equals(contextID, values.get(2))) return null;

		return remoteKey;
	}

	public static Message createGoodBye() {
		return BYE_MESSAGE;
	}

	/**
	 * Gets the cached decoded payload for this message. Does not trigger decoding.
	 * Returns null if the message has not yet been decoded.
	 *
	 * To decode, use {@link #getPayload(AStore)} with a store for partial messages
	 * (e.g. delta-encoded beliefs with external branches), or with null for
	 * complete messages (storeless decode producing a RefDirect tree).
	 *
	 * @param <T> Expected payload type
	 * @return Payload value, or null if not yet decoded
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T getPayload() {
		return (T) payload;
	}

	/**
	 * Gets the payload for this message, decoding if necessary.
	 *
	 * If store is non-null, uses the store to resolve any branches not contained
	 * within the message itself (partial messages where some branches reference
	 * previously persisted data).
	 *
	 * If store is null, performs storeless decode producing a RefDirect tree.
	 * All branches must be present in the message data (complete message).
	 * Throws PartialMessageException if storeless decode encounters a branch that
	 * cannot be resolved — the format may be correct but the message is partial
	 * and requires a store.
	 *
	 * @param <T> Expected payload type
	 * @param store Store for resolving external branches, or null for storeless decode
	 * @return Payload value
	 * @throws BadFormatException If the message data is malformed
	 * @throws PartialMessageException If storeless decode encounters an unresolvable branch
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T getPayload(AStore store) throws BadFormatException {
		if (payload!=null) return (T) payload;
		if (messageData==null) return null; // no message data, so must actually be null

		// detect actual message data for null payload :-)
		if ((messageData.count()==1)&&(messageData.byteAt(0)==Tag.NULL)) return null;

		if (store!=null) {
			payload=store.decodeMultiCell(messageData);
		} else {
			// Storeless decode via CVMEncoder: produces RefDirect tree.
			// Throws PartialMessageException if any branch is unresolvable.
			payload=CVMEncoder.INSTANCE.decodeMultiCell(messageData);
		}

		return (T) payload;
	}
	
	/**
	 * Gets the encoded data for this message. Generates a multi-cell encoding of the
	 * payload if required, applying the maximum message length: this is the boundary
	 * at which a payload too large for one legal frame is rejected, never truncated.
	 * @return Blob containing message data
	 * @throws IllegalArgumentException if the payload cannot be encoded within the maximum message length
	 */
	public Blob getMessageData() {
		if (messageData!=null) return messageData;
		MessageType type=getType();
		switch (type) {
			case MessageType.BELIEF:
				// throw new Error("Received belief message should already have partial data encoding");
			default:
				messageData=Format.encodeMultiCell(payload,true,CPoSConstants.MAX_MESSAGE_LENGTH);

		}
		return messageData;
	}

	/**
	 * Get the type of this message. May be UNKNOWN if the message cannot be understood / processed
	 * @return Type of message
	 */
	public MessageType getType() {
		if (type==null||(type==MessageType.UNKNOWN&&payload!=null)) type=inferType();
		return type;
	}

	private MessageType inferType() {
		byte tag;
		if (hasData()) {
			tag=messageData.byteAt(0);
		} else {
			if (payload==null) return MessageType.UNKNOWN;
			tag=payload.getTag();
		}

		// Types identifiable from top-level encoding tag alone
		if (tag==CVMTag.BELIEF) return MessageType.BELIEF;
		if (tag==Tag.SIGNED_DATA) return MessageType.BELIEF; // i.e. a SignedData<Order> or similar
		if (tag==CVMTag.RESULT) return MessageType.RESULT;

		// Vector-based types require decoded payload to inspect keyword tag
		ACell pl=payload;
		if (pl==null) return MessageType.UNKNOWN;

		try {
			if (pl instanceof AVector) {
				AVector<?> v=(AVector<?>)pl;
				if (v.count()==0) return MessageType.UNKNOWN;
				Keyword mt=RT.ensureKeyword(v.get(0));
				if (mt==null) return MessageType.UNKNOWN;
				if (MessageTag.STATUS_REQUEST.equals(mt)) return MessageType.STATUS;
				if (MessageTag.QUERY.equals(mt)) return MessageType.QUERY;
				if (MessageTag.BYE.equals(mt)) return MessageType.GOODBYE;
				if (MessageTag.TRANSACT.equals(mt)) return MessageType.TRANSACT;
				if (MessageTag.DATA.equals(mt)) return MessageType.DATA;
				if (MessageTag.DATA_REQUEST.equals(mt)) return MessageType.DATA_REQUEST;
				if (MessageTag.LATTICE_VALUE.equals(mt)) return MessageType.LATTICE_VALUE;
				if (MessageTag.LATTICE_QUERY.equals(mt)) return MessageType.LATTICE_QUERY;
				if (MessageTag.PING.equals(mt)) return MessageType.PING;
				if (MessageTag.CHALLENGE.equals(mt)) return MessageType.CHALLENGE;
			}
		} catch (Exception e) {
			// fall-through to UNKNOWN
		}

		return MessageType.UNKNOWN;
	}

	@Override
	public String toString() {
		try {
			ACell pl=payload; // use cached payload only, don't force decode
			if (pl==null) {
				return "<UNDECODED MESSAGE [" + getType() + "] ENC "+getMessageData().toHexString(16)+">";
			}
			AString ps=RT.print(pl,10000);
			if (ps==null) return ("<BIG MESSAGE "+RT.count(getMessageData())+" TYPE ["+getType()+"]>");
			return ps.toString();
		} catch (MissingDataException e) {
			return "<PARTIAL MESSAGE [" + getType() + "] MISSING "+e.getMissingHash()+" ENC "+getMessageData().toHexString(16)+">";
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
	
	@Override
	public int hashCode() {
		ACell pl=payload;
		if (pl!=null) return Utils.hashCode(pl);
		return getMessageData().hashCode();
	}

	/**
	 * Gets the message ID for correlation, assuming this message type supports IDs.
	 *
	 * @return Message ID, or null if the message does not have a message ID
	 */
	public ACell getID() {
		if (payload==null) {
			// Try to peek at Result ID from raw data without decoding
			try {
				return getResultID();
			} catch (BadFormatException e) {
				return null;
			}
		}
		switch (getType()) {
			// Result is a special record type
			case RESULT: try {
				return getResultID();
			} catch (BadFormatException e) {
				return null;
			}

			default: return getRequestID();
		}
	}
	
	/**
	 * Gets the request ID for this message, assuming it is a request expecting a response.
	 * Returns null if the message has not been decoded yet or does not have an ID.
	 * @return ID of message (usually an Integer) or null if no ID present
	 */
	public ACell getRequestID() {
		if (payload==null) return null; // not yet decoded, can't extract ID
		switch (getType()) {
			// The optimistic [:LV path value] form is necessarily unsolicited. Only
			// the confirmed four-field form has an ID in position 1.
			case LATTICE_VALUE: {
				AVector<?> v=RT.ensureVector(getPayload());
				if (v==null || v.count()!=4) return null;
				return RT.ensureLong(v.get(1));
			}

			// ID in position 1
			case STATUS:
			case TRANSACT:
			case QUERY:
			case DATA_REQUEST:
			case LATTICE_QUERY:
			case PING:
			case CHALLENGE:{
				AVector<?> v=RT.ensureVector(getPayload());
				if (v==null || v.count()<2) return null;
				return RT.ensureLong(v.get(1));
			}

			default: return null;
		}
	}
	
	/**
	 * Gets the result ID for this message, assuming it is a Result
	 * 
	 * This needs to work even if the payload is not yet decoded, for message routing (possibly with a different store)
	 * 
	 * @return ID of Result, or null if no ID present
	 * @throws BadFormatException If a Result with malformed ID
	 */
	public ACell getResultID() throws BadFormatException {
		if (payload!=null) {
			if (payload instanceof Result) {
				return ((Result)payload).getID();
			}
			return null;
		}
		
		if (hasData()) {
			// Check tag is a Result
			byte tag=messageData.byteAt(0);
			if (tag!=CVMTag.RESULT) return null;
			
			// Peek at Result ID without loading whole payload
			return Result.peekResultID(messageData,0);
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

				// Adding an ID to an optimistic push upgrades it to a confirmed push
				// without overwriting its path.
				case LATTICE_VALUE: {
					ACell o=getPayload();
					if (!(o instanceof AVector)) return null;
					AVector<ACell> v=(AVector<ACell>)o;
					if (v.count()==4) return Message.create(type,v.assoc(1,id));
					if (v.count()==3) {
						return Message.create(type,Vectors.create(
							v.get(0),id,v.get(1),v.get(2)));
					}
					return null;
				}
					
				// Using a vector [key ID ...]
				case STATUS: 
				case TRANSACT: 
				case QUERY:
				case DATA_REQUEST:
				case LATTICE_QUERY:
				case PING:
				case CHALLENGE: {
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
		} catch (ClassCastException | IndexOutOfBoundsException e) {
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
		return returnMessage(createReturnResult(res));
	}

	/**
	 * Returns a Result for this message, waiting a bounded time if the connection's
	 * shared outbound capacity is exhausted. For handler threads only; see
	 * {@link AConnection#returnMessageBlocking(Message)}.
	 *
	 * @param res Result to return
	 * @return true if the result was accepted for delivery
	 */
	public boolean returnResultBlocking(Result res) {
		Message msg=createReturnResult(res);
		AConnection conn=connection;
		if (conn==null) throw new IllegalStateException("No connection for return message");
		return conn.returnMessageBlocking(msg);
	}

	private Message createReturnResult(Result res) {
		ACell id=getRequestID(); // what was the request ID of original message?
		if (id!=null) {
			// Make sure Result has correct result ID
			return Message.createResult(res.withID(id));
		} else {
			throw new IllegalStateException("Trying to return result with no original request ID in "+this);
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
		AConnection conn=connection;
		if (conn==null) throw new IllegalStateException("No connection for return message");
		return conn.returnMessage(m);
	}

	/**
	 * Return true if there is encoded message data
	 * @return True if message data is constructed, false otherwise
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
		AConnection conn=connection;
		if (conn!=null) {
			conn.close();
			connection=null;
		}
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
	}

	/**
	 * Updates this message with the given connection for return routing
	 * @param conn Connection to use for returning messages, or null to remove
	 * @return Updated Message
	 */
	public Message withConnection(AConnection conn) {
		if (this.connection==conn) return this;
		return new Message(type,payload,messageData,conn);
	}

	/**
	 * Gets the connection associated with this message, or null if none
	 * @return AConnection instance, or null
	 */
	public AConnection getConnection() {
		return connection;
	}

	public static Message createQuery(long id, String code, Address address) {
		return createQuery(id,Reader.read(code),address);
	}
	
	public static Message createQuery(long id, ACell code, Address address) {
		return createQuery(CVMLong.create(id),code,address);
	}

	public static Message createQuery(CVMLong id, ACell code, Address address) {
		AVector<?> v=Vectors.create(MessageTag.QUERY,id,code,address);
		return create(MessageType.QUERY,v);
	}

	public static Message createTransaction(long id, SignedData<ATransaction> signed) {
		return createTransaction(CVMLong.create(id),signed);
	}

	public static Message createTransaction(CVMLong id, SignedData<ATransaction> signed) {
		AVector<?> v=Vectors.create(MessageTag.TRANSACT,id,signed);
		return create(MessageType.TRANSACT,v);
	}
	
	/**
	 * Sends a STATUS Request Message on this connection.
	 *
	 * @return The ID of the message sent, or -1 if send buffer is full.
	 */
	public static Message createStatusRequest(long id) {
		return createStatusRequest(CVMLong.create(id));
	}

	public static Message createStatusRequest(CVMLong id) {
		AVector<?> v=Vectors.create(MessageTag.STATUS_REQUEST,id);
		return create(MessageType.STATUS,v);
	}

	/**
	 * Creates a PING message for connection liveness testing.
	 * @param id Request ID for result correlation
	 * @return PING message
	 */
	public static Message createPing(long id) {
		return createPing(CVMLong.create(id));
	}

	public static Message createPing(CVMLong id) {
		return create(MessageType.PING, Vectors.of(MessageTag.PING, id));
	}

	/**
	 * Return the Hash of the Message payload
	 * @return Hash, or null if message format is invalid
	 */
	public Hash getHash() {
		return getPayload().getHash();	
	}

}
