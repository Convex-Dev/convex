package convex.core.store;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;

/**
 * Composable primitives for moving lattice data between stores.
 *
 * These compose with the store persistence invariants (see
 * convex-core/docs/ETCH_GC.md): destination stores record only status proven
 * by the write, descent prunes on subtrees already fully persisted in the
 * destination (INV-1), and stores never return or cache refs bound to another
 * store. Transfer is therefore a thin, named composition over the standard
 * persistence machinery; verify is the independent completeness check.
 */
public class StoreTransfer {

	/**
	 * Transfers the tree reachable from a Ref into the destination store at
	 * PERSISTED level.
	 *
	 * The source store is implicit: values resolve through the Ref's own store
	 * binding (or directly from memory for direct refs). Descent is post-order
	 * (children before parents) and prunes wherever the destination already has
	 * an entry at sufficient status, so repeat transfers are cheap no-ops.
	 *
	 * Strictness follows the destination store's persistence semantics: an Etch
	 * destination is strict (missing source data propagates as
	 * MissingDataException, source read failures as StoreException), while
	 * MemoryStore is lenient by design (remote-acquisition semantics: stores
	 * what it can and caps the achieved status). Callers wanting lenient
	 * behaviour over Etch (e.g. CLI tools skipping damaged subtrees) handle
	 * missing data per-subtree themselves.
	 *
	 * @param <T> Type of value
	 * @param dest Destination store
	 * @param ref Ref to the root of the tree to transfer
	 * @return Ref to the transferred value, bound to the destination store
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> Ref<T> transfer(AStore dest, Ref<T> ref) throws IOException {
		return transfer(dest, ref, Ref.PERSISTED);
	}

	/**
	 * Transfers the tree reachable from a Ref into the destination store at the
	 * given status level.
	 *
	 * Status policy is the caller's: PERSISTED for plain data movement,
	 * ANNOUNCED when migrating a peer's own store (a peer must not lose its
	 * announcement commitments across a store migration). A STORED-level
	 * transfer copies the top entry only, making no subtree claim.
	 *
	 * @param <T> Type of value
	 * @param dest Destination store
	 * @param ref Ref to the root of the tree to transfer
	 * @param status Status level to transfer at (see Ref status constants)
	 * @return Ref to the transferred value, bound to the destination store
	 * @throws IOException in case of IO error during persistence
	 */
	public static <T extends ACell> Ref<T> transfer(AStore dest, Ref<T> ref, int status) throws IOException {
		return dest.storeTopRef(ref, status, null);
	}

	/**
	 * Verifies that the entire tree reachable from the given hash is present in
	 * a store. Walks the tree resolving from the given store only — no fallback
	 * to other stores or in-memory values beyond the store's own cache.
	 *
	 * Iterative and duplicate-safe: shared subtrees are checked once.
	 *
	 * @param store Store to verify against
	 * @param rootHash Hash of the tree root
	 * @return List of missing hashes, empty if the tree is fully present
	 */
	public static List<Hash> verify(AStore store, Hash rootHash) {
		List<Hash> missing = new ArrayList<>();
		HashSet<Hash> seen = new HashSet<>();
		ArrayDeque<Hash> stack = new ArrayDeque<>();
		stack.push(rootHash);
		while (!stack.isEmpty()) {
			Hash h = stack.pop();
			if (!seen.add(h)) continue;
			Ref<ACell> r = store.refForHash(h);
			if (r == null) {
				missing.add(h);
				continue;
			}
			Cells.visitBranchRefs(r.getValue(), br -> stack.push(br.getHash()));
		}
		return missing;
	}
}
