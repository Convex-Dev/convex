package convex.core.lang.impl;

import java.util.ArrayList;

import org.parboiled.common.Utils;

import convex.core.ErrorType;

/**
 * Class representing a function error value
 * 
 * "Computers are useless. They can only give you answers."
 * - Pablo Picasso
 * 
 */
public class ErrorValue extends AExceptional {

	private final ErrorType value;
	private final Object message;
	private final ArrayList<Object> trace=new ArrayList<>();

	public ErrorValue(ErrorType value, Object message) {
		this.value=value;
		this.message=message;
	}

	public static ErrorValue create(ErrorType value) {
		return new ErrorValue(value,null);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(ErrorType value, Object message) {
		return new ErrorValue(value,message);
	}

	public ErrorType getType() {
		return value;
	} 
	
	public void addTrace(Object a) {
		trace.add(a);
	}
	
	/**
	 * Gets the optional message associated with this error value, or null if not supplied.
	 * @param <T>
	 * @return The message carried with this error
	 */
	@SuppressWarnings("unchecked")
	public <T> T getMessage() {
		return (T)message;
	}

	@Override 
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("ErrorValue["+value+"]"+((message==null)?"":" : "+Utils.toString(message)));
		for (Object o:trace) {
			sb.append("\n");
			sb.append(o.toString());
		}
		return sb.toString();
	}

	public byte code() {
		return value.code();
	}
}
