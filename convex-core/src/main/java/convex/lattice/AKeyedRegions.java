package convex.lattice;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;

/**
 * A component composed of named regions, at a host root or any nested index.
 *
 * <p>Registered children define this component's interests. Incoming entries and
 * path deliveries go through each child's {@link #mergeAt}; unknown keys and
 * absent entries do nothing. An explicitly present null is delivered to the child.
 * No lattice merge is inferred from a child's shape.</p>
 *
 * <p>This component has no private container snapshot. Its cursor shows the
 * destination; pending edits belong to its staged children. Sync publishes each
 * child at its own boundary, in registration order, without subsequently writing
 * the container. Unregistered regions are therefore preserved even when another
 * application changes them. Merge and sync are not atomic across children:
 * earlier successful work stands if a later child fails.</p>
 *
 * <p>Register children during construction. The first merge, path delivery or
 * sync freezes this component's registrations. Explicit children may be shared
 * with other applications: registration neither rebinds them nor changes their
 * context or lifecycle. Shared children may receive repeated or concurrent
 * deliveries and syncs, so their operations must support that use.</p>
 */
public abstract class AKeyedRegions extends ALatticeApplication<Index<Keyword,ACell>> {

	private final Map<Keyword,ALatticeComponent<?>> registrations=new LinkedHashMap<>();
	private volatile Map<Keyword,ALatticeComponent<?>> regions;

	/** Constructs a root application using the host's cursor and current context. */
	protected AKeyedRegions(LatticeHost<Index<Keyword,ACell>> host) {
		this(host.cursor(),host.getContext());
	}

	/** Constructs a container view without changing or publishing its destination. */
	protected AKeyedRegions(AForkableCursor<Index<Keyword,ACell>> cursor,LatticeContext context) {
		super(cursor);
		setContext(context);
	}

	/**
	 * Constructs a child with its region cursor and resolved context. The stored
	 * value type can differ from the child's working type, as for signed components.
	 */
	@FunctionalInterface
	public interface RegionFactory<V extends ACell,C extends ALatticeComponent<?>> {
		C create(AForkableCursor<V> cursor,LatticeContext context);
	}

	/**
	 * Registers an existing child, retaining the exact instance and its policy.
	 * The caller must supply a component bound to this region's intended destination;
	 * cursors do not expose enough binding information to verify this generally.
	 * Registration does not relocate the component or take exclusive ownership.
	 */
	protected final synchronized <C extends ALatticeComponent<?>> C region(Keyword key,C component) {
		checkRegistration(key);
		registrations.put(key,Objects.requireNonNull(component,"Region component must not be null"));
		return component;
	}

	/**
	 * Constructs and registers a child at this key. A missing containing index is
	 * created on the first changed write, never on construction or read. The factory
	 * receives the current context; it decides how the child uses that policy.
	 */
	@SuppressWarnings("unchecked")
	protected final synchronized <V extends ACell,C extends ALatticeComponent<?>> C createRegion(
			Keyword key,RegionFactory<V,C> factory) {
		checkRegistration(key);
		Objects.requireNonNull(factory,"Region factory must not be null");
		AForkableCursor<V> destination=ProjectionCursor.create(cursor,
			value -> value==null?null:(V)value.get(key),
			(value,child) -> {
				ACell previous=value==null?null:value.get(key);
				if (Utils.equals(previous,child)) return value;
				return (value==null?Index.<Keyword,ACell>none():value).assoc(key,child);
			});
		return region(key,factory.create(destination,getContext()));
	}

	private void checkRegistration(Keyword key) {
		Objects.requireNonNull(key,"Region key must not be null");
		if (regions!=null) throw new IllegalStateException("Region registrations are frozen");
		if (registrations.containsKey(key)) throw new IllegalArgumentException("Duplicate region: "+key);
	}

	private Map<Keyword,ALatticeComponent<?>> regions() {
		Map<Keyword,ALatticeComponent<?>> result=regions;
		if (result!=null) return result;
		synchronized (this) {
			if (regions==null) regions=Collections.unmodifiableMap(new LinkedHashMap<>(registrations));
			return regions;
		}
	}

	/** Checks present registered regions, leaving unknown keys outside this component's policy. */
	@Override public AString checkValue(ACell value) {
		if (value==null) return null;
		if (!(value instanceof Index<?,?> index)) return Strings.create("Expected a region index");
		// Inspection must not freeze registrations or initialise a child view.
		Map<Keyword,ALatticeComponent<?>> selected;
		synchronized (this) { selected=new LinkedHashMap<>(registrations); }
		for (var entry:selected.entrySet()) {
			Keyword key=entry.getKey();
			if (!index.containsKey(key)) continue;
			AString error=entry.getValue().checkValue(index.get(key));
			if (error!=null) return Strings.create(key+": "+error);
		}
		return null;
	}

	/** Delivers present registered entries, without publishing staged children. */
	@Override
	public Index<Keyword,ACell> merge(Index<Keyword,ACell> incoming) {
		var selected=regions();
		if (incoming!=null) {
			for (var entry:selected.entrySet()) {
				Keyword key=entry.getKey();
				if (incoming.containsKey(key)) entry.getValue().mergeAt(incoming.get(key));
			}
		}
		return cursor.get();
	}

	/** Resolves keys with the same blob comparison as the underlying index. */
	@SuppressWarnings("unchecked")
	@Override
	protected <V extends ACell> ALatticeComponent<V> child(ACell key) {
		var selected=regions();
		if (!(key instanceof ABlobLike<?> blob)) return null;
		for (var entry:selected.entrySet()) {
			if (entry.getKey().toBlob().equals(blob.toBlob())) return (ALatticeComponent<V>)entry.getValue();
		}
		return null;
	}

	/** Routes through registered components only; an unknown path is a no-op. */
	@Override
	public Index<Keyword,ACell> mergeAt(ACell value,ACell... path) {
		if (path.length==0) return super.mergeAt(value,path);
		ALatticeComponent<?> component=child(path[0]);
		if (component!=null) component.mergeAt(value,Arrays.copyOfRange(path,1,path.length));
		return cursor.get();
	}

	/** Freezes registrations before entering the application lifecycle. */
	@Override
	public Index<Keyword,ACell> sync() {
		regions();
		return super.sync();
	}

	/** Publishes children in registration order. The container has no staged snapshot. */
	@Override
	protected void syncChildren() {
		for (var component:regions().values()) component.sync();
	}
}
