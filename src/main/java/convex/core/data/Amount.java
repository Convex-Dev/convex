package convex.core.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Text;

/**
 * Class representing a valid token amount
 * 
 * "It is the preoccupation with possessions, more than anything else, that
 * prevents us from living freely and nobly." - Bertrand Russell
 *
 */
public class Amount extends ACell implements Comparable<Amount> {

	public static final Amount ZERO = new Amount(0);

	private long value;

	public static final int DECIMALS = 6;

	// allowable ranges for token amounts
	public static final long MIN_AMOUNT = 0L;
	public static final long MAX_AMOUNT = powerOf10(18);
	public static final long POUND_VALUE = powerOf10(DECIMALS);

	public static final int MAX_BYTE_LENGTH = 11; // tag + 10 bytes with 7 bits is sufficient for any long

	private Amount(long value) {
		this.value = value;
	}

	public static long powerOf10(int e) {
		if ((e < 0) || (e > 18)) throw new IllegalArgumentException("Power of 10 not valid: " + e);
		if (e > 9) return 1000000000 * powerOf10(e - 9);
		switch (e) {
		case 0:
			return 1;
		case 1:
			return 10;
		case 2:
			return 100;
		case 3:
			return 1000;
		case 4:
			return 10000;
		case 5:
			return 100000;
		case 6:
			return 1000000;
		case 7:
			return 10000000;
		case 8:
			return 100000000;
		case 9:
			return 1000000000;
		default:
			throw new Error("Unexpected power of 10: " + e);
		}
	}

	public static int trailingZeros(long a) {
		if (a == 0) return 0;
		long b = a / 1000000L;
		if ((b == 0) || (b * 1000000 != a)) return trailingZerosUpTo6(a);
		return 6 + trailingZeros(b);
	}

	private static int trailingZerosUpTo6(long a) {
		long b = a / 1000;
		if (b * 1000 != a) return trailingZerosUpTo3(a);
		return 3 + trailingZerosUpTo3(b);
	}

	private static int trailingZerosUpTo3(long a) {
		long b = a / 10;
		if (b * 10 != a) return 0;
		return 1 + trailingZerosUpTo3(b);
	}

	public static Amount create(long value) {
		if (value > MAX_AMOUNT) {
			throw new IllegalArgumentException("Amount out of range: " + value);
		}
		if (value < 0) {
			throw new IllegalArgumentException("Negative amount: " + value);
		}
		return new Amount(value);
	}

	public static Amount create(BigInteger bigValue) {
		long value = bigValue.longValueExact();
		return create(value);
	}

	public long getValue() {
		return value;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		int scale = Math.min(15, trailingZeros(value));

		b = b.put((byte) (Tag.AMOUNT + scale));

		long pot = powerOf10(scale);
		long scaledValue = value / pot;
		return Format.writeVLCLong(b, scaledValue);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		throw new UnsupportedOperationException("Amount always requires a tag");
	}

	public static Amount read(byte tag, ByteBuffer b) throws BadFormatException {
		long scaledValue = Format.readVLCLong(b);
		if (scaledValue == 0) return ZERO;

		int hiTag = (tag & 0xF0);
		if (hiTag != Tag.AMOUNT) {
			throw new BadFormatException("Bad amount tag");
		}
		int scale = (tag & 0xF);
		if ((scale < 15) && (scaledValue % 10 == 0)) {
			throw new BadFormatException("Non-canonical amount format");
		}

		try {
			long value = Math.multiplyExact(scaledValue, powerOf10(scale)); // SECURITY: need checked multiply
			return create(value);
		} catch (Throwable t) {
			throw new BadFormatException("Error reading Amount: " + t.getMessage());
		}
	}

	@Override
	public int estimatedEncodingSize() {
		return MAX_BYTE_LENGTH;
	}

	public Amount subtract(Amount amount) {
		long v = amount.getValue();
		if (v == 0L) return this;
		return create(this.value - v);
	}

	public Amount add(Amount amount) {
		long v = amount.getValue();
		if (v == 0L) return this;
		return create(this.value + v);
	}

	public Amount add(long v) {
		if ((v < 0) || (v > Amount.MAX_AMOUNT)) {
			throw new IllegalArgumentException("Bad amount to add: " + v);
		}
		return create(this.value + v);
	}

	/**
	 * Parses a String to get a valid Amount
	 * 
	 * @param string String of the format "0.000" or "£0.0000"
	 * @throws RuntimeException if amount cannot be parsed sucessfully
	 * @return A valid amount
	 */
	public static Amount parse(String string) {
		if (string.charAt(0) == '£') string = string.substring(1);
		BigDecimal bd = new BigDecimal(string);
		try {
			return Amount.toCanonicalAmount(bd);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Failed to parse amount: " + string);
		}
	}

	@Override
	public int compareTo(Amount o) {
		long ovalue = o.value;
		if (value < ovalue) return -1;
		if (value > ovalue) return 1;
		return 0;
	}

	@Override
	public boolean equals(ACell o) {
		if (o instanceof Amount) return equals((Amount) o);
		return false;
	}

	@Override
	public boolean equals(Object a) {
		if (a instanceof Amount) return equals((Amount) a);
		return false;
	}

	public boolean equals(Amount a) {
		return a.value == this.value;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(value);
	}

	public String toDecimalString() {
		StringBuilder sb = new StringBuilder();
		// sb.append('£');

		long pounds = value / POUND_VALUE;
		sb.append(Long.toString(pounds));

		long rem = value - pounds * POUND_VALUE;
		if (rem != 0L) {
			sb.append('.');
			String s = Long.toString(POUND_VALUE + rem); // must have at least one non-zero char after initial 1
			int lastChar = s.length() - 1; // position to start scanning for non-zero chars
			while (s.charAt(lastChar) == '0')
				lastChar--;
			sb.append(s.substring(1, lastChar + 1)); // skip initial 1, take up to non-zero lastChar inclusive
		}
		return sb.toString();
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#amt \"");
		sb.append(Text.toFriendlyBalance(value));
		sb.append("\"");
	}

	public double toDouble() {
		return (double) value;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (value < 0) throw new InvalidDataException("Negative amount", this);
		if (value > MAX_AMOUNT) throw new InvalidDataException("Excessive amount", this);

	}

	public String toFriendlyString() {
		return Text.toFriendlyBalance(value);
	}

	/**
	 * Converts a BigDecimal to a valid Amount, i.e. a positive decimal value no
	 * greater than MAX_AMOUNT and without too many decimal places
	 * 
	 * @param value
	 * @return Amount instance with the given value
	 */
	public static Amount toCanonicalAmount(BigDecimal value) {
		value = value.setScale(DECIMALS); // throws exception if not possible
		return create(value.unscaledValue());
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	protected boolean isEmbedded() {
		return true;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(value);
	}


}
