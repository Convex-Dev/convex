package convex.core.util;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import convex.core.data.BlobBuilder;

public class Text {
	private static final int WHITESPACE_LENGTH = 32;
	private static String WHITESPACE_32 = "                                "; // 32 spaces

	public static String whiteSpace(int length) {
		if (length < 0) throw new IllegalArgumentException("Negative whitespace requested!");
		if (length == 0) return "";

		if (length <= WHITESPACE_LENGTH) {
			return WHITESPACE_32.substring(0, length);
		}

		StringBuilder sb = new StringBuilder(length);
		for (int i = WHITESPACE_LENGTH; i <= length; i += WHITESPACE_LENGTH) {
			sb.append(WHITESPACE_32);
		}
		sb.append(whiteSpace(length & 0x1F));
		return sb.toString();
	}

	public static String leftPad(String s, int length) {
		if (s == null) s = "";
		int spaces = length - s.length();
		if (spaces < 0) throw new IllegalArgumentException("String [" + s + "] too long for pad length: " + length);
		return whiteSpace(spaces) + s;
	}

	public static String leftPad(long value, int length) {
		return leftPad(Long.toString(value), length);
	}

	public static String rightPad(String s, int length) {
		if (s == null) s = "";
		int spaces = length - s.length();
		if (spaces < 0) throw new IllegalArgumentException("String [" + s + "] too long for pad length: " + length);
		return s + whiteSpace(spaces);
	}

	public static String rightPad(long value, int length) {
		return rightPad(Long.toString(value), length);
	}

	static DecimalFormat balanceFormatter = new DecimalFormat("#,###");

	public static String toFriendlyNumber(long value) {
		return balanceFormatter.format(value);
	}
	
	static DecimalFormat percentFormatter = new DecimalFormat("##.###%");
	public static String toPercentString(double value) {
		return percentFormatter.format(value);
	}

	public static String toFriendlyIntString(double value) {
		return toFriendlyNumber((long) value);
	}

	static final DateTimeFormatter formatter = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

	public static String dateFormat(long timestamp) {
		return formatter.format(Instant.ofEpochMilli(timestamp));
	}

	/**
	 * Writes a UTF-8 byte, escaped as necessary for Java. This works because only single byte ASCII
	 * characters need escaping.
	 * 
	 * @param sb BlobBuilder to append UTF-8 bytes
	 * @param b Byte to write
	 */
	public static void writeEscapedByte(BlobBuilder sb, byte b) {
		char c=(char) b;
		switch (c) {
		    case '"': sb.append('\\'); sb.append('"'); return;
		    case '\\': sb.append('\\'); sb.append('\\'); return;
		    // case '\'': sb.append('\\'); sb.append('\''); return; See #407
		    case '\t': sb.append('\\'); sb.append('t'); return;
		    case '\b': sb.append('\\'); sb.append('b'); return;
		    case '\n': sb.append('\\'); sb.append('n'); return;
		    case '\r': sb.append('\\'); sb.append('r'); return;
		    case '\f': sb.append('\\'); sb.append('f'); return;
			default: sb.append(b);
		}
	}

}
