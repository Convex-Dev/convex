package convex.p2p;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.AccessList;
import convex.lattice.cursor.AForkableCursor;
import convex.net.PeerName;

/**
 * The seed nodes this node joins the network through, kept as an {@link AccessList}
 * at {@code :seeds} in its {@code :local} P2P record beside its contact records
 * ({@link ContactRecords}), or in memory for a node without a key. Each entry is
 * keyed by a peer URI and may name the key the peer must prove:
 *
 * <pre>
 * {&lt;peer URI&gt; {:key &lt;expected key&gt;}}
 * </pre>
 *
 * <p>This is the live list: the node reads it each time it needs seeds. Each distinct
 * URI is one entry, though several may reach the same node. Unknown entry fields are
 * kept, and an entry that cannot be read as a {@link PeerName} is skipped. At most
 * {@link #MAX_SEEDS} seeds are listed. How names are written and parsed is up to
 * whatever adds them.</p>
 */
final class Seeds {

	/** The field of this node's {@code :local} P2P record holding the list. */
	static final Keyword KEY=Keyword.intern("seeds");

	/** Entry field: the key the seed must prove. */
	static final Keyword EXPECTED_KEY=Keyword.intern("key");

	/** Most seeds listed. */
	static final int MAX_SEEDS=16;

	private final AccessList<AString> list;

	Seeds(AForkableCursor<Index<AString,ACell>> cursor,Supplier<CVMLong> clock) {
		this.list=new AccessList<AString>(cursor,clock);
	}

	/**
	 * Lists a seed, replacing any entry at its URI. Returns whether no seed was listed
	 * at its URI before.
	 *
	 * @throws IllegalStateException if {@link #MAX_SEEDS} seeds at other URIs are listed
	 * @throws IllegalArgumentException if its URI is too long to list
	 */
	synchronized boolean add(PeerName name) {
		AString uri=uri(name);
		if (list.get(uri)==null && count()>=MAX_SEEDS) {
			throw new IllegalStateException("At most "+MAX_SEEDS+" seeds may be listed");
		}
		AHashMap<Keyword,ACell> entry=(name.key()==null)?Maps.empty():Maps.of(EXPECTED_KEY,name.key());
		return list.add(uri,entry);
	}

	/** Removes the seed at a name's URI, whatever key it names. Returns whether one was listed. */
	synchronized boolean remove(PeerName name) {
		return list.remove(uri(name));
	}

	/** Whether a name is listed as it is: the entry at its URI names the same key. */
	boolean contains(PeerName name) {
		AString uri=uri(name);
		return name.equals(read(uri,list.get(uri)));
	}

	/** The seeds listed, in the list's order. */
	List<PeerName> all() {
		List<PeerName> names=new ArrayList<>();
		for (AString uri:uris()) {
			PeerName name=read(uri,list.get(uri));
			if (name!=null) names.add(name);
		}
		return names;
	}

	/** Seeds listed, readable or not. */
	private int count() {
		int n=0;
		for (AString uri:uris()) if (list.get(uri)!=null) n++;
		return n;
	}

	/** The URIs stored, including expired and unreadable entries. */
	private List<AString> uris() {
		List<AString> uris=new ArrayList<>();
		ACell stored=list.cursor().getOrDefault(null);
		if (!(stored instanceof Index<?,?> index)) return uris;
		long n=index.count();
		for (long i=0; i<n; i++) {
			if (index.entryAt(i).getKey() instanceof AString uri) uris.add(uri);
		}
		return uris;
	}

	private static AString uri(PeerName name) {
		if (name==null) throw new IllegalArgumentException("Seed name must not be null");
		return Strings.create(name.uri().toString());
	}

	/** An entry as a peer name, or null if there is none or it cannot be read. */
	private static PeerName read(AString uri,AHashMap<Keyword,ACell> entry) {
		if (entry==null) return null;
		ACell key=entry.get(EXPECTED_KEY);
		AccountKey expected=(key==null)?null:RT.ensureAccountKey(key);
		if (key!=null && expected==null) return null;
		try {
			return new PeerName(new URI(uri.toString()),expected);
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
