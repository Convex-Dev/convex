package convex.core.cvm.exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import convex.core.ErrorCodes;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Strings;

/**
 * Class representing an Error value produced by the CVM.
 * 
 * See "Error Handling" CAD11.
 * 
 * Contains:
 * <ul>
 * <li>An immutable Error Code</li>
 * <li>An immutable Error Message</li>
 * <li>A mutable error trace (for information purposes outside the CVM)</li>
 * <li>Address where the error occurred</li>
 * </ul>
 * 
 * "Computers are useless. They can only give you answers."
 * - Pablo Picasso
 * 
 */
public class ErrorValue extends AThrowable {

	private static final HashMap<Keyword,ErrorValue> defaultErrors= new HashMap<>();
	
	private final ACell message;
	private final ArrayList<AString> trace=new ArrayList<>();
	private ACell log;
	private Address address=null;
	
	static {
		addDefaultError(ErrorCodes.ARGUMENT,"Invalid argument");
		addDefaultError(ErrorCodes.NOBODY,"Account does not exist");
		addDefaultError(ErrorCodes.FUNDS,"Funds not available");
		addDefaultError(ErrorCodes.JUICE,"Out of juice");
		addDefaultError(ErrorCodes.CAST,"Illegal type cast");
		addDefaultError(ErrorCodes.ASSERT,"Assertion failed");
		addDefaultError(ErrorCodes.ARITY,"Wrong number of arguments");
		addDefaultError(ErrorCodes.BOUNDS,"Out of bounds");
		addDefaultError(ErrorCodes.TODO,"Not implemented");
		addDefaultError(ErrorCodes.MEMORY,"Out of memory");
	}

	private ErrorValue(ACell code, ACell message) {
		super (code);
		this.message=message;
	}

	private static void addDefaultError(Keyword code, String message) {
		defaultErrors.put(code,create(code,message));
	}

	public static ErrorValue create(Keyword code) {
		if (defaultErrors.containsKey(code)) {
			return defaultErrors.get(code);
		}
		
		return new ErrorValue(code,null);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param code Keyword error code
	 * @param message Off-chain message as CVM String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(Keyword code, AString message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param code Type of error
	 * @param message Off-chain message, must be valid CVM Value
	 * @return New ErrorValue instance
	 */
	public static ErrorValue createRaw(ACell code, ACell message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param code Code of error
	 * @param message Off-chain message as Java String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(Keyword code, String message) {
		return new ErrorValue(code,Strings.create(message));
	}


	@Override 
	public void addTrace(String traceMessage) {
		trace.add(Strings.create(traceMessage));
	}
	
	/**
	 * Stores the CVM local log at the point of the error
	 * @param log Sets the CVM log value for this error
	 */
	public void addLog(ACell log) {
		this.log=log;
	}
	
	@Override
	public boolean isCatchable() {
		return true;
	}
	
	/**
	 * Sets the address which is the source of this error
	 * @param a Address of error cause
	 */
	public void setAddress(Address a) {
		this.address=a;
	}
	
	/**
	 * Gets the address which is the source of this error
	 * @return Address of account where error occurred
	 */
	public Address getAddress() {
		return address;
	}
	
	@Override
	public ACell getMessage() {
		return message;
	}

	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("ErrorValue["+code+"]"+((message==null)?"":" : "+message));
		if (trace!=null) {
			for (Object o:trace) {
				sb.append("\n");
				sb.append(o.toString());
			}
		}
			
		return sb.toString();
	}

	/**
	 * Gets the trace for this Error.
	 * 
	 * The trace List is mutable, and may be used to implement accumulation of additional trace entries.
	 * 
	 * @return List of trace entries.
	 */
	public List<AString> getTrace() {
		return trace;
	}
	
	/**
	 * Gets the CVM local log at the time of the Error.
	 * 
	 * @return List of trace entries.
	 */
	public ACell getLog() {
		return log;
	}



}
