package convex.core.lang.impl;

import java.util.ArrayList;
import java.util.List;

import org.parboiled.common.Utils;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Class representing a function error value
 * 
 * "Computers are useless. They can only give you answers."
 * - Pablo Picasso
 * 
 */
public class ErrorValue extends AExceptional {

	private final Object code;
	private final Object message;
	private final ArrayList<AString> trace=new ArrayList<>();

	private ErrorValue(Object code, Object message) {
		if (code==null) throw new IllegalArgumentException("Error code must not be null");
		this.code=code;
		this.message=message;
	}

	public static ErrorValue create(Object code) {
		return new ErrorValue(code,null);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message as CVM String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(Object code, AString message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message, must be valid CVM Value
	 * @return New ErrorValue instance
	 */
	public static ErrorValue createRaw(Object code, Object message) {
		return new ErrorValue(code,message);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message as Java String
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(Object code, String message) {
		return new ErrorValue(code,Strings.create(message));
	}

	/**
	 * Gets the Error Code for this ErrorVAlue instance. The Error Code may be any value, but
	 * by convention (and exclusively in Convex runtime code) it is a upper-case keyword e.g. :ASSERT
	 * 
	 * @return Error code value
	 */
	public Object getCode() {
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
	public Object getMessage() {
		return message;
	}

	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("ErrorValue["+code+"]"+((message==null)?"":" : "+Utils.toString(message)));
		for (Object o:trace) {
			sb.append("\n");
			sb.append(o.toString());
		}
		return sb.toString();
	}

	public List<AString> getTrace() {
		// TODO Auto-generated method stub
		return trace;
	}



}
