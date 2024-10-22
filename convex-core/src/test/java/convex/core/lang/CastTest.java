package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMChar;
import convex.core.data.type.AType;

import static convex.test.Assertions.*;

public class CastTest extends ACVMTest {

	static final String[] casts = new String[] {"double","int", "long", "boolean","blob","address", "str","name","symbol","keyword","char"};
	static final String[] vals = new String[] {"##Inf","1e308","0.0", "-0.0", "999999999999999999999999999", "9223372036854775807", "1","0","-1","0xcafebabe1234567890","0x41","0x","\\c","\\u0474", "\"hello\"","\"\"","#12",":foo","'baz","true","false","nil"};
	
	@Test
	public void testRoundTrips() {
		assertCVMEquals(1L,eval("(long (address 1))"));
		assertCVMEquals(Address.ZERO,eval("(address (blob #0))"));
	}
	
	@Test 
	public void testAllCasts() {
		for (String c: casts) {
			for (String v: vals) {
				String cmd="("+c+" "+v+")";
				Context ctx=step(cmd);
				if (ctx.isError()) {
					ACell code=ctx.getErrorCode();
					if (ErrorCodes.ARGUMENT.equals(code)) {
						// the default value should be in range
						AType type=RT.getType(Reader.read(v));
						Context nctx=step("("+c+" "+RT.print(type.defaultValue())+")");
						if (nctx.isError()) {
							fail("ARGUMENT fallback not working in: "+cmd);
						}
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
	
	public static void main(String... args) {
		int nc=casts.length;
		int nv=vals.length;
		Context context=new CastTest().context();
		
		StringBuilder line=new StringBuilder();
		line.append("CASTS");
		line.append('\t');
		line.append("TYPE");
		line.append('\t');
		for (int j=0; j<nc; j++) {
			String c=casts[j];
			line.append(c);
			line.append('\t');
		}
		System.out.println(line.toString());

		
		for (int i=0; i<nv; i++) {
			line.setLength(0);
			String v=vals[i];
			line.append(RT.print(v));
			line.append('\t');
			line.append(RT.getType(Reader.read(v)).toString());
			line.append('\t');
			for (int j=0; j<nc; j++) {
				String c=casts[j];
				String cmd="("+c+" "+v+")";
				Context ctx=step(context,cmd);
				String result;
				if (ctx.isError()) {
					result=ctx.getErrorCode().toString();
				} else {
					ACell r=ctx.getResult();
					if (r.equals(CVMChar.ZERO)) r=Strings.create("<NULL>");
					result=RT.print(r).toString();
				}
				line.append(RT.print(result));
				line.append('\t');
			}
			System.out.println(line.toString());
		}
	}
	
}
