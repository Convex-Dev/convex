package convex.lattice;

import java.util.Objects;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * A staged map of owner identities to signed values. The instance-bound owner
 * lattice supplies one verification rule for whole-map delivery, owner-path
 * delivery and local authoring. Merging and publication use public information
 * only and never consult this component's context. A merge requiring a newly
 * signed payload retains the existing envelope until an authorised domain
 * component authors the result. Unresolved or unauthorised owners cannot introduce data.
 *
 * <p>Domain subclasses of {@link ASignedComponent} are constructed at {@link #path}
	 * with an owner-bound rule from {@link #signingFor}. They share this map's working
 * state and sign each edit. Independent drafts
 * defer signing until their own sync, which stops at this map's working state.
 * Neither opening a view nor opening a draft creates an entry. This component's
 * sync publishes the map only; it does not publish outstanding drafts.</p>
 *
 * @param <K> Owner identity type
 * @param <V> Unsigned value type
 * @param <M> Concrete map of signed values
 */
public abstract class AOwnedMap<K extends ACell,V extends ACell,M extends AMap<K,SignedData<V>>>
		extends ALatticeComponent<M> {

	private final AOwnerLattice<K,V,M> owners;

	protected AOwnedMap(AOwnerLattice<K,V,M> owners,ACursor<M> destination) {
		super(convex.lattice.cursor.ForkedCursor.create(destination));
		this.owners=owners;
	}

	public final AOwnerLattice<K,V,M> getLattice() { return owners; }

	/** The same owner-bound rule used for delivery, publication and local authoring. */
	public final SignedLattice<V> signingFor(K owner) { return rule(owner); }

	/**
	 * Checks container and envelope shape. Signature, owner authorisation and
	 * payload acceptance remain the merge rule's responsibility.
	 */
	@SuppressWarnings("unchecked")
	@Override public AString checkValue(ACell value) {
		if (value==null) return null;
		if (!(value instanceof AMap<?,?> map)) return Strings.create("Expected an owner map");
		try {
			if (!owners.checkForeign((M)value)) return Strings.create("Incompatible owner map");
		} catch (ClassCastException e) {
			return Strings.create("Incompatible owner map type");
		}
		for (var entry:map.entrySet()) {
			ACell envelope=entry.getValue();
			if (envelope!=null && !(envelope instanceof SignedData<?>)) {
				return Strings.create("Expected a signed envelope at "+entry.getKey());
			}
		}
		return null;
	}

	/** Merges verified envelopes without resolving signing capabilities. */
	@Override
	public M merge(M incoming) {
		return cursor().updateAndGet(own -> owners.merge(own,incoming));
	}

	/** Publication conflicts use the same public merge as incoming deliveries. */
	@Override
	protected M mergePublication(M own,M other) {
		return owners.merge(own,other);
	}

	/** Delivers one complete owner envelope; paths inside an envelope introduce no data. */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Override
	protected M mergeAtPath(ACell value,ACell... path) {
		if (path.length!=1) return cursor().get();
		SignedLattice<V> rule=owners.signingFor(path[0]);
		if (rule==null) return cursor().get();
		// Keep foreign values untyped until the same entry validation as whole-map delivery.
		AMap incoming=owners.zero();
		return merge((M)incoming.assoc(rule.getOwner(),value));
	}

	/** Returns the current signed entry, or null when absent. Does not create state. */
	public SignedData<V> get(K owner) {
		M map=cursor().get();
		return map==null?null:map.get(owner);
	}

	/**
	 * Returns the signed storage path for an owner, for direct domain construction.
	 * A missing containing map is created only on a changed write. Navigation does
	 * not sign or grant authority; a signed component binds the owner rule separately.
	 */
	public AForkableCursor<SignedData<V>> path(K owner) {
		return slot(rule(owner));
	}

	/**
	 * Opens an independent draft with explicit signing capabilities. Its sync signs
	 * into this map's working state; this map's sync subsequently publishes outward.
	 */
	public SignedComponent<V> draft(K owner,Function<V,SignedData<V>> signer) {
		SignedLattice<V> rule=rule(owner);
		return new SignedComponent<>(rule,slot(rule),signer);
	}

	private SignedLattice<V> rule(K owner) {
		SignedLattice<V> rule=owners.signingFor(Objects.requireNonNull(owner));
		if (rule==null) throw new IllegalArgumentException("Invalid owner: "+owner);
		return rule;
	}

	private AForkableCursor<SignedData<V>> slot(SignedLattice<V> rule) {
		return cursor().withDefault(owners.zero()).path(rule.getOwner());
	}
}
