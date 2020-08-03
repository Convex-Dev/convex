package convex.core.lang.impl;

import java.util.ArrayList;

import org.parboiled.common.Utils;

/**
 * Class representing a function error value
 * 
 * "Computers are useless. They can only give you answers."
 * - Pablo Picasso
 * 
 */
public class ErrorValue extends AExceptional {

	private final Object value;
	private final Object message;
	private final ArrayList<Object> trace=new ArrayList<>();

	public ErrorValue(Object value, Object message) {
		this.value=value;
		this.message=message;
	}

	public static ErrorValue create(Object value) {
		return new ErrorValue(value,null);
	}
	
	/**
	 * Creates an ErrorValue with the specified type and message. Message may be null.
	 * @param value Type of error
	 * @param message Off-chain message
	 * @return New ErrorValue instance
	 */
	public static ErrorValue create(Object value, Object message) {
		return new ErrorValue(value,message);
	}

	/**
	 * Gets the Error Code for this ErrorVAlue instance. The Error Code may be any value, but
	 * by convention (and exclusively in Convex runtime code) it is a upper-case keyword e.g. :ASSERT
	 * 
	 * @return Error code value
	 */
	public Object getCode() {
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
}
