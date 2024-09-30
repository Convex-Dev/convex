package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.Symbol;

public class CastTest extends ACVMTest {

	String[] casts = new String[] {"double","int", "long", "boolean", "char", "str","blob","address","symbol","keyword"};
	String[] vals = new String[] {"##Inf","1e308","0.0", "-0.0", "999999999999999999999999999", "9223372036854775807", "1","0","-1","\\c", "\"foo\"","\"\"","0xcafebabe1234567890","0x","#12",":foo","'baz","nil","true","false"};
	
	@Test 
	public void testAllCasts() {
		for (String c: casts) {
			for (String v: vals) {
				String cmd="("+c+" "+v+")";
				Context ctx=step(cmd);
				if (ctx.isError()) {
					ACell code=ctx.getErrorCode();
					if (ErrorCodes.ARGUMENT.equals(code)) {
						// anything to test?
					} else {
						assertEquals(ErrorCodes.CAST,code,()->"Unexpected "+code+" in "+cmd);
					}
				} else {
					// cast should be idempotent
					ACell r=ctx.getResult();
					assertNotNull(r);
					String pr=(r instanceof Symbol)?("'"+r):RT.print(r).toString();
					String cmd2="("+c+" "+pr+")";
					ACell code=Reader.read(cmd2);
					assertEquals(r,eval(code));
				}
			}
		}
	}
	
}
