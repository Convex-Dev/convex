/**
 * Local application use cases, written against small example hosts and domain
 * components. Tests can assemble complete applications in memory or persist and
 * reopen them with a store; no node runtime or network is required. Internal
 * component behaviour belongs in the corresponding class unit tests.
 * Applications use {@link convex.lattice.SingleRegion} or
 * {@link convex.lattice.Regions} to compose domain components with standard
 * routing and publication, including shared region instances.
 * Owned collections use {@link convex.lattice.OwnedIndex} with an application
 * verifier or {@link convex.lattice.KeyOwnedIndex} for public-key owners.
 *
 * <p>Start with {@link convex.lattice.app.FavouritesExample} for a complete small
 * domain model: an owner index, signed records, {@link convex.lattice.Timestamped}
 * ordering and an embedded {@link convex.lattice.SetComponent}. Its application
 * tests cover shared edits, incoming verification, replay, ties and publication;
 * its persistence test adds Etch without changing the domain components.</p>
 *
 * <p>The other examples each isolate a different application choice:</p>
 * <ul>
 *   <li>{@link convex.lattice.app.ApplicationPublicationTest}: a notebook batches
 *       unsigned edits and reconciles independent drafts at its signing boundary.</li>
 *   <li>{@link convex.lattice.app.OwnedScoresApplicationTest}: several authorised
 *       public keys act for one identity, with authority changes checked by existing views.</li>
 *   <li>{@link convex.lattice.app.TaskBoardApplicationTest}: transactions atomically
 *       change timestamped tasks with stable IDs before explicit publication;
 *       titles can change and completed tasks can reopen.</li>
 *   <li>{@link convex.lattice.app.SharedRegionsTest}: separate staged regions share
 *       a host without publishing or overwriting one another's state.</li>
 * </ul>
 *
 * <p>Network delivery and propagation scenarios belong in {@code convex.node.app}
 * in convex-peer. Combinations requiring higher-level domain modules belong in
 * convex-integration.</p>
 */
package convex.lattice.app;
