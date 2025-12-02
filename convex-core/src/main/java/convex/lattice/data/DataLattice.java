package convex.lattice.data;

import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.lattice.ALattice;

/**
 * Lattice that implements an Index hash(x) -> x
 * 
 * This is used for a general data lattice of retained cells
 */
public class DataLattice extends ALattice<Index<Hash,ACell>> {

	public static DataLattice INSTANCE=new DataLattice();

	@Override
	public Index<Hash, ACell> merge(Index<Hash, ACell> ownValue, Index<Hash, ACell> otherValue) {
		Index<Hash,ACell> result=ownValue;
		if (result==null) result=zero();
		
		ArrayList<ACell> newValues=new ArrayList<>();
		result.mergeDifferences(result, (a,b)->{
			if (a!=null) return a; // keep own value
			newValues.add(b);
			return a;
		});
		
		// Add new values to result. Note we recompute hash so we don't need to verify
		for (ACell nv: newValues) {
			result=result.assoc(Hash.get(nv),nv);
		}
		
		return result;
	}

	@Override
	public boolean checkForeign(Index<Hash, ACell> value) {
		return (value instanceof Index);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<Hash, ACell> zero() {
		return (Index<Hash, ACell>) Index.EMPTY;
	}

}
