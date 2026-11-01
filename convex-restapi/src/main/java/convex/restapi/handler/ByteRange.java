package convex.restapi.handler;

/**
 * A single HTTP byte range (RFC 9110 section 14) resolved against a
 * representation size, with both positions inclusive.
 *
 * Only one range is served. The server may ignore a Range header and send the
 * whole representation, and does so for a unit other than bytes, several
 * ranges, malformed syntax or an empty representation.
 */
record ByteRange(long first, long last) {

	/** A range wholly beyond the representation, answered with 416 */
	static final ByteRange UNSATISFIABLE=new ByteRange(-1,-1);

	/**
	 * Parses a Range header against a representation size.
	 * @param header Range header value, may be null
	 * @param size Representation size in bytes
	 * @return Range to serve, UNSATISFIABLE, or null to ignore the header
	 */
	static ByteRange parse(String header, long size) {
		if ((header==null)||(size<=0)) return null;
		int eq=header.indexOf('=');
		if (eq<0) return null;
		if (!header.substring(0,eq).strip().equalsIgnoreCase("bytes")) return null;
		String spec=header.substring(eq+1).strip();
		if (spec.indexOf(',')>=0) return null;
		int dash=spec.indexOf('-');
		if (dash<0) return null;
		String firstText=spec.substring(0,dash);
		String lastText=spec.substring(dash+1);
		long lastPos=digits(lastText);

		if (firstText.isEmpty()) {
			// Suffix range: the final lastPos bytes
			if (lastPos<0) return null;
			if (lastPos==0) return UNSATISFIABLE;
			return new ByteRange(Math.max(0,size-lastPos),size-1);
		}

		long firstPos=digits(firstText);
		if (firstPos<0) return null;
		if (lastText.isEmpty()) {
			lastPos=Long.MAX_VALUE;
		} else if (lastPos<firstPos) {
			return null;
		}
		if (firstPos>=size) return UNSATISFIABLE;
		return new ByteRange(firstPos,Math.min(lastPos,size-1));
	}

	/**
	 * Parses decimal digits, saturating at Long.MAX_VALUE
	 * @return Value, or -1 if empty or not all digits
	 */
	private static long digits(String s) {
		if (s.isEmpty()) return -1;
		long value=0;
		for (int i=0; i<s.length(); i++) {
			char c=s.charAt(i);
			if ((c<'0')||(c>'9')) return -1;
			value=(value>(Long.MAX_VALUE-9)/10)?Long.MAX_VALUE:value*10+(c-'0');
		}
		return value;
	}

	/**
	 * @param size Representation size in bytes
	 * @return Content-Range header value for this range
	 */
	String contentRange(long size) {
		return "bytes "+first+"-"+last+"/"+size;
	}
}
