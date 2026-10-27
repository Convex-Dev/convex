package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * KV entries and a monotonic garbage-collection cutoff.
 *
 * <p>Legacy stores are flat entry indexes. After the first collection, state is
 * {@code [entries cutoff]}. Entries at or before the cutoff are rejected unless
 * explicitly retained by that collection. A retained entry carries a separate
 * retention timestamp, preserving its original write timestamp. All replicas
 * must support this format before collection is enabled.</p>
 *
 * <p>Delivery supplies the complete store: a partial entry cannot carry the
 * cutoff or establish absence. Components still edit individual entries locally.</p>
 */
public final class KVStoreLattice extends ALattice<ACell> {

	public static final KVStoreLattice INSTANCE=new KVStoreLattice();

	private KVStoreLattice() {}

	/** Gets the entries without materialising an absent store. */
	@SuppressWarnings("unchecked")
	public static Index<AString,AVector<ACell>> entries(ACell state) {
		if (state==null) return null;
		if (state instanceof Index<?,?> index) return (Index<AString,AVector<ACell>>)index;
		if (state instanceof AVector<?> vector && vector.count()==2
				&& vector.get(0) instanceof Index<?,?> index && vector.get(1) instanceof CVMLong) {
			return (Index<AString,AVector<ACell>>)index;
		}
		throw new IllegalArgumentException("Invalid KV store state");
	}

	/** The last collection cutoff, or null for an uncollected legacy store. */
	public static CVMLong cutoff(ACell state) {
		entries(state); // also checks the envelope
		return state instanceof AVector<?> vector?(CVMLong)vector.get(1):null;
	}

	/** Replaces entries while preserving the store's collection boundary and representation. */
	public static ACell withEntries(ACell state,Index<AString,AVector<ACell>> entries) {
		CVMLong cutoff=cutoff(state);
		return cutoff==null?entries:Vectors.of(entries,cutoff);
	}

	private static CVMLong maximum(CVMLong a,CVMLong b) {
		return a==null?b:b==null?a:a.longValue()>=b.longValue()?a:b;
	}

	private static boolean survives(AVector<ACell> entry,CVMLong cutoff) {
		if (cutoff==null || KVEntry.getUTime(entry).longValue()>cutoff.longValue()) return true;
		CVMLong retained=KVEntry.getRetainedAt(entry);
		return retained!=null && retained.longValue()>=cutoff.longValue();
	}

	/** Filters only when crossing a newer collection boundary; ordinary merges remain differential. */
	private static Index<AString,AVector<ACell>> filter(Index<AString,AVector<ACell>> entries,
			CVMLong previous,CVMLong cutoff) {
		if (entries==null) return Index.none();
		if (cutoff==null || (previous!=null && previous.longValue()>=cutoff.longValue())) return entries;
		Index<AString,AVector<ACell>> result=entries;
		for (var entry:entries.entrySet()) {
			if (!KVEntry.isValid(entry.getValue()) || !survives(entry.getValue(),cutoff)) result=result.dissoc(entry.getKey());
		}
		return result;
	}

	@Override
	public ACell merge(ACell own,ACell other) {
		if (other==null || Utils.equals(own,other)) return own;
		if (!checkForeign(other)) return own;
		CVMLong ownCutoff=cutoff(own), otherCutoff=cutoff(other);
		CVMLong cutoff=maximum(ownCutoff,otherCutoff);
		var a=filter(entries(own),ownCutoff,cutoff);
		var b=filter(entries(other),otherCutoff,cutoff);
		var merged=a.mergeDifferences(b,new MergeFunction<AVector<ACell>>() {
			@Override public AVector<ACell> merge(AVector<ACell> x,AVector<ACell> y) {
				if (y==null) return x;
				if (!KVEntry.isValid(y) || !survives(y,cutoff)) return x;
				return KVEntryLattice.INSTANCE.merge(x,y);
			}
		});
		return cutoff==null?merged:Vectors.of(merged,cutoff);
	}

	@Override public ACell zero() { return Index.none(); }

	@Override public boolean checkForeign(ACell state) {
		if (state==null) return false;
		try {
			entries(state);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** A complete store is the replication boundary, including its cutoff. */
	@Override public <T extends ACell> ALattice<T> path(ACell key) { return null; }
}
