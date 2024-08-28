package convex.core;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.ARecordGeneric;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ResultException;
import convex.core.lang.Context;
import convex.core.lang.RecordFormat;
import convex.core.lang.exception.AExceptional;
import convex.core.lang.exception.ErrorValue;

/**
 * Class representing the result of a Query or Transaction.
 * 
 * A Result is typically used to communicate the outcome of a Query or a Transaction from a Peer to a Client.
 * 
 * Contains:
 * <ol>
 * <li>Message ID</li>
 * <li>Result value</li>
 * <li>Error Code</li>
 * <li>Log Records</li>
 * <li>Additional info</li>
 * </ol>
 * 
 * 
 */
public final class Result extends ARecordGeneric {

	private static final RecordFormat RESULT_FORMAT=RecordFormat.of(Keywords.ID,Keywords.RESULT,Keywords.ERROR,Keywords.LOG,Keywords.INFO);
	private static final long FIELD_COUNT=RESULT_FORMAT.count();

	private static final long ID_POS=RESULT_FORMAT.indexFor(Keywords.ID);
	private static final long RESULT_POS=RESULT_FORMAT.indexFor(Keywords.RESULT);
	private static final long ERROR_POS=RESULT_FORMAT.indexFor(Keywords.ERROR);
	private static final long INFO_POS=RESULT_FORMAT.indexFor(Keywords.INFO);
	private static final long LOG_POS=RESULT_FORMAT.indexFor(Keywords.LOG);
	
	// internal value used for empty logs
	private static final AVector<AVector<ACell>> EMPTY_LOG = null;
	
	private Result(AVector<ACell> values) {
		super(RESULT_FORMAT, values);
	}
	
	public static Result buildFromVector(AVector<ACell> values) {
		return new Result(values);
	}
	
	/**
	 * Create a Result
	 * @param id ID of Result message
	 * @param value Result Value
	 * @param errorCode Error Code (may be null for success)
	 * @param info Additional info
	 * @return Result instance
	 */
	public static Result create(CVMLong id, ACell value, ACell errorCode, AHashMap<Keyword,ACell> info) {
		return buildFromVector(Vectors.of(id,value,errorCode,EMPTY_LOG,info));
	}
	
	/**
	 * Create a Result
	 * @param id ID of Result message
	 * @param value Result Value
	 * @param errorCode Error Code (may be null for success)
	 * @return Result instance
	 */
	public static Result create(CVMLong id, ACell value, ACell errorCode) {
		return create(id,value,errorCode,null);
	}

	/**
	 * Create a Result
	 * @param id ID of Result message
	 * @param value Result Value
	 * @return Result instance
	 */
	public static Result create(CVMLong id, ACell value) {
		return create(id,value,null,null);
	}
	
	public static Result error(Keyword errorCode, AString message) {
		return error(errorCode,message,null);
	}
	
	public static Result error(Keyword errorCode, String message) {
		return error(errorCode,Strings.create(message),null);
	}

	private static Result error(Keyword errorCode, AString message, AHashMap<Keyword,ACell> info) {
		return create(CVMLong.ZERO,message,errorCode,info);
	}

	/**
	 * Returns the message ID for this result. Message ID is an arbitrary ID assigned by a client requesting a transaction.
	 * 
	 * @return ID from this result
	 */
	public ACell getID() {
		return values.get(ID_POS);
	}
	
	/**
	 * Returns the value for this result. The value is the result of transaction execution (may be an error message if the transaction failed)
	 * 
	 * @param <T> Type of Value
	 * @return ID from this result
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T getValue() {
		return (T)values.get(RESULT_POS);
	}
	
	/**
	 * Returns the stack trace for this result. May be null
	 * 
	 * @return Trace vector from this result
	 */
	@SuppressWarnings("unchecked")
	public AVector<AString> getTrace() {
		AMap<Keyword,ACell> info=getInfo();
		if (info instanceof AMap) {
			AMap<Keyword,ACell> m=(AMap<Keyword, ACell>) info;
			return (AVector<AString>) m.get(Keywords.TRACE);
		}
		return null;
	}
	
	/**
	 * Returns the info for this Result. May be null
	 * 
	 * @return Info map from this result
	 */
	@SuppressWarnings("unchecked")
	public AMap<Keyword,ACell> getInfo() {
		return (AMap<Keyword, ACell>) values.get(INFO_POS);
	}
	
	/**
	 * Returns this Result with extra info field
	 * @param k
	 * @param v
	 * @return
	 */
	public Result withInfo(Keyword k, ACell v) {
		AMap<Keyword, ACell> info = getInfo();
		if (info==null) info=Maps.empty();
		info=info.assoc(k, v);
		return new Result(values.assoc(INFO_POS, info));
	}
	

	public Result withSource(Keyword source) {
		return withInfo(Keywords.SOURCE, source);
	}
	
	/**
	 * Returns the log for this Result. May be an empty vector.
	 * 
	 * @return Log Vector from this Result
	 */
	@SuppressWarnings("unchecked")
	public AVector<AVector<ACell>> getLog() {
		AVector<AVector<ACell>> log=(AVector<AVector<ACell>>) values.get(LOG_POS);
		if (log==null) log=Vectors.empty();
		return log;
	}
	
	/**
	 * Returns the Error Code from this Result. Normally this should be a Keyword.
	 * 
	 * Will be null if no error occurred.
	 * 
	 * @return Error code from this result
	 */
	public ACell getErrorCode() {
		return values.get(ERROR_POS);
	}
	
	/**
	 * Returns the error source code from this Result (see CAD11). This  a Keyword.
	 * 
	 * Will be null if :source info not available
	 * 
	 * @return Source code keyword from this result,. or null if not present / invalid
	 */
	public Keyword getSource() {
		AMap<Keyword, ACell> info = getInfo();
		if (info==null) return null;;
		ACell source=info.get(Keywords.SOURCE);
		if (source instanceof Keyword) return (Keyword)source;
		return null;
	}
	
	@Override
	public AVector<ACell> values() {
		return values;
	}

	@Override
	protected Result withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Result(newValues);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		
		String problem=checkValues(values);
		
		if (problem!=null) {
			throw new InvalidDataException(problem, this);
		}
	}
	
	private static String checkValues(AVector<ACell> values) {
		if (values.count()!=FIELD_COUNT) return "Wrong number of fields for Result";

		ACell id=values.get(ID_POS);
		if ((id!=null)&&!(id instanceof CVMLong)) {
			return "Result ID must be a CVM long value";
		}
		
		ACell info=values.get(INFO_POS);
		if ((info!=null)&&!(info instanceof AHashMap)) {
			return "Result info must be a hash map";
		}
		
		ACell log=values.get(LOG_POS);
		if ((log!=null)&&!(log instanceof AVector)) {
			return "Result log must be a Vector";
		}
		return null;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.RESULT;
		pos=values.encodeRaw(bs,pos);
		return pos;
	}
	
	/**
	 * Reads a Result from a Blob encoding. Assumes tag byte already checked.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Result read(Blob b, int pos) throws BadFormatException {
		int epos=pos; 
		// include tag location since we are reading raw Vector (will ignore tag)
		AVector<ACell> v=Vectors.read(b,epos);
		epos+=Format.getEncodingLength(v);
		
		String problem=checkValues(v);
		if (problem!=null) throw new BadFormatException(problem);
		
		Blob enc=v.getEncoding();
		Result r=buildFromVector(v);
		v.attachEncoding(null); // This is an invalid encoding for vector, see above
		r.attachEncoding(enc);
		return r;
	}


	/**
	 * Tests is the Result represents an Error
	 * @return True if error, false otherwise
	 */
	public boolean isError() {
		return getErrorCode()!=null;
	}

	/**
	 * Constructs a Result from a Context
	 * @param id Id for Result
	 * @param rc ResultContext instance from which to extract Result
	 * @return New Result instance
	 */

	public static Result fromContext(CVMLong id,ResultContext rc) {
		Context ctx=rc.context;
		Object result=ctx.getValue();
		ACell errorCode=null;
		AHashMap<Keyword,ACell> info=Maps.empty();
		if (result instanceof AExceptional) {
			AExceptional ex=(AExceptional)result;
			result=ex.getMessage();
			errorCode=ex.getCode();
			if (ex instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue) ex;
				AVector<?> trace=Vectors.create(ev.getTrace());
				Address errorAddress=ev.getAddress();
				// Maps.of(Keywords.TRACE,trace,Keywords.ADDRESS,address);
				info=info.assoc(Keywords.TRACE, trace);
				info=info.assoc(Keywords.EADDR, errorAddress);
			}
		}
		if (rc.memUsed>0) info=info.assoc(Keywords.MEM, CVMLong.create(rc.memUsed));
		if (rc.totalFees>0) info=info.assoc(Keywords.FEES, CVMLong.create(rc.totalFees));
		if (rc.juiceUsed>0) info=info.assoc(Keywords.JUICE, CVMLong.create(rc.juiceUsed));
		if (rc.source!=null) info=info.assoc(Keywords.SOURCE, rc.source);
		
		return create(id,(ACell)result,errorCode,info);
	}
	
	public Result withExtraInfo(Map<Keyword,ACell> extInfo) {
		if ((extInfo!=null)&&(!extInfo.isEmpty())) {
			AMap<Keyword,ACell> info=getInfo();
			if (info==null) info=Maps.empty();
			for (Map.Entry<Keyword,ACell> me: extInfo.entrySet()) {

				info=info.assoc(me.getKey(), me.getValue());
			}
			return new Result(values.assoc(INFO_POS, info));
		}
		return this;
	}
	
	/**
	 * Constructs a Result from a Context. No ResultContext implies we are not in a top level transaction, so minimise work
	 * @param ctx Context
	 * @return New Result instance
	 */
	public static Result fromContext(Context ctx) {
		ACell rval=(ctx.isExceptional())?ctx.getExceptional().getMessage():ctx.getResult();
		
		return create(null,rval,ctx.getErrorCode(),null);
	}

	/**
	 * Updates result with a given message ID. Used to tag Results for return to Clients
	 * @param id New Result message ID
	 * @return Updated Result
	 */
	public Result withID(ACell id) {
		return withValues(values.assoc(ID_POS, id));
	}

	@Override
	public byte getTag() {
		return Tag.RESULT;
	}

	@Override
	public RecordFormat getFormat() {
		return RESULT_FORMAT;
	}

	/**
	 * Constructs a result from a caught exception 
	 * @param e Exception caught
	 * @return
	 */
	public static Result fromException(Throwable e) {
		if (e instanceof TimeoutException) {
			String msg=e.getMessage();
			return Result.error(ErrorCodes.TIMEOUT,Strings.create(msg));
		}
		if (e instanceof IOException) {
			String msg=e.getMessage();
			return Result.error(ErrorCodes.IO,Strings.create(msg));
		}
		if (e instanceof BadFormatException) {
			String msg=e.getMessage();
			return Result.error(ErrorCodes.FORMAT,Strings.create(msg));
		}
		if (e instanceof ResultException) {
			return ((ResultException) e).getResult();
		}
		if (e instanceof ExecutionException) {
			// use the underlying cause
			return fromException(e.getCause());
		}
		if (e instanceof InterruptedException) {
			return interruptThread();
		}
		return Result.error(ErrorCodes.EXCEPTION,Strings.create(e.getMessage()));
	}

	// Standard result in case of interrupts
	// Note interrupts are always caused by CLIENT from a local perspective
	private static final Result INTERRUPTED_RESULT=Result.error(ErrorCodes.INTERRUPTED,Strings.create("Interrupted!")).withSource(SourceCodes.CLIENT);
	
	/**
	 * Returns a Result representing a thread interrupt, AND sets the interrupt status on the current thread
	 * @return
	 */
	public static Result interruptThread() {
		Thread.currentThread().interrupt();
		return INTERRUPTED_RESULT;
	}





}
