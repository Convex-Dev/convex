package convex.etch;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.util.Utils;

public class EtchUtils {
	
	public static FullValidator getFullValidator() {
		return new FullValidator();
	}

	/**
	 * An Etch validator that checks every index entry
	 */
	public static class FullValidator implements IEtchIndexVisitor {
		public long visited=0;
		public long entries=0;
		public long empty=0;
		public long values=0;
		public long indexPtrs=0;
		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException {
			visited++;
			
			int isize=e.indexSize(level);
			
			String ps="";
			for (int ll=0; ll<level; ll++) {
				int lsize=e.indexSize(ll);
				int hd=Integer.bitCount(lsize-1)/4;
				ps=ps+Utils.toHexString(digits[ll]).substring(8-hd);
			}
			
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
					
					Hash h=e.readValueKey(ptr);
					String hp=h.toHexString(ps.length());
					if (!hp.equals(ps)) {
						fail("Index "+ps+" inconsistent with hash "+h);
					}
					
					visitHash(e,h);
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
		}
		
		public void visitHash(Etch e,Hash h) {
			// Should be overriden if subclass wants to perform additional validation
		}

		public void fail(String msg) {
			throw new Error(msg);
		}
		
	};
	
	public static abstract class EtchCellVisitor implements IEtchIndexVisitor {
		@Override
		public void visit(Etch e, int level, int[] digits, long indexPointer) throws IOException {
			int isize=e.indexSize(level);			
			for (int i=0; i<isize; i++) {
				long slot=e.readSlot(indexPointer, i);
				if (slot==0) continue;
				
				long ptr=e.rawPointer(slot);
				long type=e.extractType(slot);			
				if ((ptr|type)!=slot) throw new Error("Inconsistent slot code?!?");
				
				if (type==Etch.PTR_INDEX) continue;
				
				ACell cell=e.readCell(ptr);
				
				visitCell(cell);
			}
		}
		
		protected abstract void visitCell(ACell cell);
	}


}
