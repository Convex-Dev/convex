package convex.etch;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.AStore;
import convex.core.util.Utils;

public class EtchUtils {

	/**
	 * Ensures everything in the source Etch store is persisted in the
	 * destination store: a full index scan drives a transfer of each entry at
	 * its recorded status (STORED entries copy the top cell only; PERSISTED and
	 * above descend, pruning on subtrees the destination already holds).
	 *
	 * The SOURCE must not be undergoing any writes for the duration of the
	 * migration: the underlying index scan (Etch.visitIndex) is racy under
	 * concurrent index restructuring and may miss entries. The destination may
	 * be non-empty and in live use. The destination root is not modified.
	 * See convex-core/docs/ETCH_GC.md.
	 *
	 * @param source Source Etch store to migrate from
	 * @param dest Destination store (any AStore implementation)
	 * @return Number of source entries processed
	 * @throws IOException in case of IO error during migration
	 */
	public static long migrate(EtchStore source, AStore dest) throws IOException {
		return migrate(source.getEtch(), dest);
	}

	/**
	 * Etch-level migrate variant: used where the source file is not a store's
	 * main file (e.g. a GC target being reverse-migrated by cancelGC). The
	 * source's store binding is used for decoding. Same write-quiescence
	 * requirement as the store-level variant.
	 *
	 * @param source Source Etch file to migrate from (must be write-quiescent)
	 * @param dest Destination store
	 * @return Number of source entries processed
	 * @throws IOException in case of IO error during migration
	 */
	public static long migrate(Etch source, AStore dest) throws IOException {
		long[] count = {0};
		source.visitIndex(new EtchCellVisitor() {
			@Override
			protected void visitCell(ACell cell) {
				Ref<ACell> ref = cell.getRef();
				// Cap at MAX_STATUS: internal/marked levels are store-local concerns
				int status = Math.max(Ref.STORED, Math.min(ref.getStatus(), Ref.MAX_STATUS));
				try {
					dest.storeTopRef(ref, status, null);
				} catch (IOException e) {
					// OK: enclosing method declares IOException
					throw Utils.sneakyThrow(e);
				}
				count[0]++;
			}
		});
		return count[0];
	}
	
	/**
	 * Verifies that the entire tree reachable from the given hash is present in
	 * a single Etch file: entry presence is checked against this file ONLY
	 * (unlike store-level reads, which may fall back to caches or other files).
	 * Iterative and duplicate-safe; no pruning — a full independent walk.
	 *
	 * @param e Etch file to verify against
	 * @param rootHash Hash of the tree root (nil/empty roots are trivially complete)
	 * @return List of missing hashes, empty if the tree is fully present
	 * @throws IOException in case of IO error
	 */
	public static List<Hash> verify(Etch e, Hash rootHash) throws IOException {
		List<Hash> missing = new ArrayList<>();
		HashSet<Hash> seen = new HashSet<>();
		ArrayDeque<Hash> stack = new ArrayDeque<>();
		// nil / empty roots are recognised without a store entry (as in
		// AStore.getRootRef), so there is nothing to verify
		if (!(Hash.NULL_HASH.equals(rootHash) || Hash.EMPTY_HASH.equals(rootHash))) {
			stack.push(rootHash);
		}
		while (!stack.isEmpty()) {
			Hash h = stack.pop();
			if (!seen.add(h)) continue;
			Ref<ACell> r = e.read(h);
			if (r == null) {
				missing.add(h);
				continue;
			}
			Cells.visitBranchRefs(r.getValue(), br -> stack.push(br.getHash()));
		}
		return missing;
	}

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
