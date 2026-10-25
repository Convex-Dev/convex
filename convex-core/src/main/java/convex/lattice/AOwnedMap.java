package convex.lattice;

import java.util.Objects;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * A map of owner identities to signed values over supplied working storage.
 * The instance-bound owner lattice supplies one verification rule for whole-map delivery, owner-path
 * delivery and local authoring. Standard merging and publication use public
 * information only and require no signing capability. A merge requiring a newly
 * signed payload retains the existing envelope until an authorised domain
 * component authors the result. Authoring domains may override {@link #mergeValues}
 * with an explicit signing strategy. Unresolved or unauthorised owners cannot introduce data.
 *
 * <p>Domain subclasses of {@link ASignedComponent} are constructed at {@link #path}
 * with an owner-bound rule from {@link #signingFor}. They share this map's working
 * state and sign each edit. Independent drafts
 * defer signing until their own sync, which stops at this map's working state.
 * Construction binds the supplied cursor; call fork on a concrete collection
 * when it needs an independent publication boundary. Neither opening a view nor
 * opening a draft creates an entry. This component's
 * sync publishes the map only; it does not publish outstanding drafts.</p>
 *
 * @param <K> Owner identity type
 * @param <V> Unsigned value type
 * @param <M> Concrete map of signed values
 */
public abstract class AOwnedMap<K extends ACell,V extends ACell,M extends AMap<K,SignedData<V>>>
		extends ALatticeComponent<M> {

	private final AOwnerLattice<K,V,M> owners;
	private final AMapComponent<K,SignedData<V>,M> entries;

	protected AOwnedMap(AOwnerLattice<K,V,M> owners,AForkableCursor<M> cursor) {
		super(cursor);
		this.owners=owners;
		entries=new AMapComponent<>(cursor) {
			@SuppressWarnings("unchecked")
			@Override protected M requireMap(ACell value) {
				if (value==null) return null;
				try {
					if (owners.checkForeign((M)value)) return (M)value;
				} catch (ClassCastException e) {
					throw new IllegalStateException("Incompatible owner map",e);
				}
				throw new IllegalStateException("Incompatible owner map");
			}
			@Override protected M createContainer() { return AOwnedMap.this.createContainer(); }
		};
	}

	public final AOwnerLattice<K,V,M> getLattice() { return owners; }

	/**
	 * Creates storage for a changed local owner-slot write when the map is absent.
	 * Return a non-null compatible map, optionally with initial entries, or throw to
	 * require explicit initialisation. This hook must be pure: atomic updates may
	 * retry it. Reads and unchanged writes do not call it. Incoming delivery and
	 * publication continue to use the merge policy.
	 */
	protected M createContainer() { return owners.zero(); }

	/** The same owner-bound rule used for delivery, publication and local authoring. */
	public final SignedLattice<V> signingFor(K owner) { return rule(owner); }

	/** Merges verified envelopes using this component's merge policy. */
	@Override
	public M merge(M incoming) {
		return storage.updateAndGet(own -> mergeWorkingValues(own,incoming));
	}

	/** Publication conflicts use the same merge policy as incoming deliveries. */
	@Override
	protected M mergePublication(M own,M other) {
		return mergeWorkingValues(own,other);
	}

	/**
	 * Public verification and merge by default. An authoring domain may override
	 * this with {@link AOwnerLattice#mergeAndSign} and its explicit signing strategy.
	 * Both incoming delivery and fork reconciliation use this same operation.
	 */
	protected M mergeValues(M own,M other) {
		return owners.merge(own,other);
	}

	private M mergeWorkingValues(M own,M other) {
		M merged=mergeValues(own,other);
		// Empty or wholly rejected delivery must not materialise an absent collection.
		return own==null && merged!=null && merged.isEmpty()?null:merged;
	}

	/** Whole-map deliveries must have this collection's representation. */
	@SuppressWarnings("unchecked")
	@Override public M mergeAt(ACell value,ACell... path) {
		if (path.length!=0) return mergeAtPath(value,path);
		if (value!=null) {
			try {
				if (!owners.checkForeign((M)value)) return storage.get();
			} catch (ClassCastException e) {
				return storage.get();
			}
		}
		return merge((M)value);
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

	/** Returns the current signed entry, or null when absent. Rejects incompatible storage. */
	public SignedData<V> get(K owner) {
		return entries.get(owner);
	}

	/**
	 * Returns the signed storage path for an owner, for direct domain construction.
	 * A missing containing map uses {@link #createContainer()} only on a changed write.
	 * Access rejects incompatible containers. Navigation does
	 * not sign or grant authority; a signed component binds the owner rule separately.
	 */
	public AForkableCursor<SignedData<V>> path(K owner) {
		return slot(rule(owner));
	}

	/**
	 * Opens an unsigned editing view with an explicit signer, checked against the
	 * same owner rule as incoming delivery. Construction is inert. This does not
	 * give the collection a signer or change its public merge policy.
	 */
	public AForkableCursor<V> authoringCursor(K owner,Function<V,SignedData<V>> signer) {
		Objects.requireNonNull(signer);
		SignedLattice<V> rule=rule(owner);
		return SignedCursor.create(slot(rule),value -> rule.sign(value,signer));
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

	@SuppressWarnings("unchecked")
	private AForkableCursor<SignedData<V>> slot(SignedLattice<V> rule) {
		return entries.path((K)rule.getOwner());
	}
}
