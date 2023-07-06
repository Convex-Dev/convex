package etch;

import java.io.IOException;

import convex.core.util.Utils;

public class EtchUtils {
	
	public static FullValidator getFullValidator() {
		return new FullValidator();
	}

	public static class FullValidator implements IEtchIndexVisitor {
		public long visited=0;
		public long entries=0;
		public long empty=0;
		public long values=0;
		public long indexPtrs=0;
		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) {
			visited++;
			
			try {
				int isize=e.indexSize(level);
				entries+=isize;
				
				if (isize<=0) fail("Bad index size:"+isize);
				
				for (int i=0; i<isize; i++) {
					long slot=e.readSlot(indexPointer, i);
					long ptr=e.rawPointer(slot);
					long type=e.extractType(slot);
					
					if ((ptr|type)!=slot) fail("Inconsistent slot code?!?");
					
					if (slot==0) {
						empty++;
					} else if (type!=Etch.PTR_INDEX) {
						values++;
					} else {
						indexPtrs++;
					}
					
					if (type==Etch.PTR_START) {
						int ipp=(i+1)%isize; // next slot
						long nextSlot=e.readSlot(indexPointer, ipp);
						if (e.extractType(nextSlot)!=Etch.PTR_CHAIN) {
							fail("Invalid slot after chain start: "+Utils.toHexString(nextSlot));
						}
					}
					
					if (type==Etch.PTR_CHAIN) {
						int imm=(i+isize-1)%isize; // prev slot
						long prevSlot=e.readSlot(indexPointer, imm);
						long pt=e.extractType(prevSlot);
						if (!((pt==Etch.PTR_CHAIN)||(pt==Etch.PTR_START))) {
							fail("Invalid slot before chain entry: "+Utils.toHexString(prevSlot));
						}
					}

					
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
