package etch;

import java.io.IOException;

import convex.core.util.Utils;

public class EtchUtils {

	public static IEtchIndexVisitor fullValidator=new IEtchIndexVisitor() {

		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) {
			try {
				int isize=e.indexSize(level);
				if (isize<=0) fail("Bad index size:"+isize);
				
				for (int i=0; i<isize; i++) {
					long slot=e.readSlot(indexPointer, i);
					long ptr=e.rawPointer(slot);
					long type=e.extractType(slot);
					
					if ((ptr|type)!=slot) fail("Inconsistent slot code?!?");
				}
			} catch (IOException e1) {
				throw Utils.sneakyThrow(e1);
			}
		}

		private void fail(String msg) {
			throw new Error(msg);
		}
		
	};
}
