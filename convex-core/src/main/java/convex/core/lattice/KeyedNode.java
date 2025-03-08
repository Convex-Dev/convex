package convex.core.lattice;

import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.Utils;

/**
 * Lattice implementation that handles a set of keyword-mapped child lattices
 */
public class KeyedNode extends ALattice<AMap<Keyword,?>> {

	private final ArrayList<ALattice<?>> lattices;
	private final ArrayList<Keyword> keys;
	
	private KeyedNode(ArrayList<ALattice<?>> lattices, ArrayList<Keyword> keys) {
		this.lattices=lattices;
		this.keys=keys;
	}
	
	public static KeyedNode create(Object... keysAndValues) {
		int n2=keysAndValues.length;
		int n=n2/2;
		
		if (n*2!=n2) throw new IllegalArgumentException("Must have pairs of keys and values");
		
		ArrayList<ALattice<?>> lattices=new ArrayList<>(n);
		ArrayList<Keyword> keys =new ArrayList<>(n);
		
		for (int i=0; i<n; i++) {
			Keyword k=Keyword.create(keysAndValues[2*i]);
			if (k==null) throw new IllegalArgumentException("Invalid key name");
			
			ALattice<?> v=(ALattice<?>) (keysAndValues[2*i+1]);
			if (v==null) throw new NullPointerException("null lattice");
			
			lattices.add(v);
			keys.add(k);
		}
		
		return new KeyedNode(lattices,keys);
	}
	
	@Override
	public AMap<Keyword, ?> merge(AMap<Keyword, ?> ownValue, AMap<Keyword, ?> otherValue) {
		if (ownValue==null) return otherValue;
		if (otherValue==null) return ownValue;

		AMap<Keyword, ?> result=ownValue;
		
		int n=lattices.size();
		for (int i=0; i<n; i++) {
			@SuppressWarnings("unchecked")
			ALattice<ACell> lattice=(ALattice<ACell>) lattices.get(i);
			Keyword key=keys.get(i);
			
			if (!otherValue.containsKey(key)) continue;
			
			ACell a=ownValue.get(key);
			ACell b=otherValue.get(key);
			
			ACell m=lattice.merge(a, b); // child merge
			
			if (!Utils.equals(m, a)) {
				result=result.assoc(key, m);
			}
		}
		
		return result;
	}

	@Override
	public AMap<Keyword, ?> zero() {
		return Maps.empty();
	}

}
