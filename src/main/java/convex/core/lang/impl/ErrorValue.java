package convex.core.lang.impl;

import java.util.ArrayList;
import java.util.List;

import org.parboiled.common.Utils;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Class representing an Error value produced by the CVM.
 * 
 * See "Error Handling" CAD.
 * 
 * Contains:
 * <ul>
 * <li>An immutable Error Code</li>
 * <li>An immutable Error Message</li>
 * <li>A mutable error trace (for information purposes outside the CVM)</li>
 * </ul>
 * 
 * "Computers are useless. They can only give you answers."
 * - Pablo Picasso
 * 
 */
public class ErrorValue extends AExceptional {

	private final ACell code;
	private final ACell message;
	private final ArrayList<AString> trace=new ArrayList<>();

	private ErrorValue(ACell code, ACell message) {
		if (code==null) throw new IllegalArgumentException("Error code must not be null");
		this.code=code;
		this.message=message;
	}

	public static ErrorValue create(ACell code) {
		return new ErrorValue(code,null);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message as CVM String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(ACell code, AString message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message, must be valid CVM Value
	 * @return New ErrorValue instance
	 */
	public static ErrorValue createRaw(ACell code, ACell message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message as Java String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(ACell code, String message) {
		return new ErrorValue(code,Strings.create(message));
	}

	/**
	 * Gets the Error Code for this ErrorVAlue instance. The Error Code may be any value, but
	 * by convention (and exclusively in Convex runtime code) it is a upper-case keyword e.g. :ASSERT
	 * 
	 * @return Error code value
	 */
	public ACell getCode() {
		return code;
	} 
	
	public void addTrace(String traceMessage) {
		trace.add(Strings.create(traceMessage));
	}
	
	/**
	 * Gets the optional message associated with this error value, or null if not supplied.
	 * @param <T>
	 * @return The message carried with this error
	 */
	public ACell getMessage() {
		return message;
	}

	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("ErrorValue["+code+"]"+((message==null)?"":" : "+Utils.toString(message)));
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



}
