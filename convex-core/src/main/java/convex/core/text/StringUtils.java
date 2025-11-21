package convex.core.text;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMChar;
import convex.core.data.util.BlobBuilder;

/**
 * Utility class for string operations
 */
public class StringUtils {

	/**
	 * Escape HTML special characters using CVM string functions
	 * @param text Text to escape
	 * @return HTML-escaped text
	 */
	public static AString escapeHtml(AString text) {
		if (text == null) return Strings.EMPTY;
		
		BlobBuilder bb = new BlobBuilder();
		final long len = text.count();
		
		for (long pos = 0; pos < len; ) {
			int c = text.charAt(pos);
			if (c == -1) {
				// Invalid UTF-8, skip one byte
				pos++;
				continue;
			}
			
			switch (c) {
				case '&': bb.append("&amp;"); break;
				case '<': bb.append("&lt;"); break;
				case '>': bb.append("&gt;"); break;
				case '"': bb.append("&quot;"); break;
				case '\'': bb.append("&#39;"); break;
				default: 
					// For valid Unicode code points, append the character properly
					if (Character.isValidCodePoint(c)) {
						bb.append(CVMChar.create(c));
					} else {
						// Invalid code point, skip
					}
					break;
			}
			
			// Move to next code point
			int utfLen = CVMChar.utfLength(c);
			if (utfLen < 0) utfLen = 1; // Move one byte for bad chars
			pos += utfLen;
		}
		
		return bb.getCVMString();
	}
}
