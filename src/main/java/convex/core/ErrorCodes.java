package convex.core;

import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.ErrorValue;

public class ErrorCodes {
	public static final byte SEQUENCE = (byte) 0x10;
	
	public static final byte FUNDS = (byte) 0x20;
	public static final byte JUICE = (byte) 0x21;
	public static final byte DEPTH = (byte) 0x22;
	public static final byte MEMORY = (byte) 0x23;
	
	public static final byte NOBODY = (byte) 0x30;
	public static final byte ARITY = (byte) 0x40;
	public static final byte UNDECLARED = (byte) 0x41;
	public static final byte CAST = (byte) 0x42;
	public static final byte ARGUMENT = (byte) 0x43;
	public static final byte BOUNDS = (byte) 0x44;
	public static final byte STATE = (byte) 0x45;
	public static final byte COMPILE = (byte) 0x46;
	public static final byte EXPAND = (byte) 0x47;
	public static final byte ASSERT = (byte) 0x50;

	public static final byte UNEXPECTED = (byte) 0x60;


	public static byte extract(AExceptional err) {
		if (err instanceof ErrorValue) {
			return ((ErrorValue) err).code();
		}
		return UNEXPECTED;
	}
}
