package convex.social;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.AHashMap;
import convex.lattice.generic.OwnerLattice;

/**
 * Static definitions for the Convex social network lattice.
 *
 * <p>The social lattice is an {@link OwnerLattice} mapping owner keys
 * (Ed25519 public keys) to signed per-user state. Each user's state
 * is managed by {@link SocialLattice} containing their feed, profile,
 * and follows.</p>
 *
 * <p>Nodes opt in to social support by adding the social lattice to their
 * root lattice instance:</p>
 * <pre>{@code
 * KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
 * }</pre>
 */
public class Social {

	/**
	 * Keyword for the social section in a node's root lattice.
	 */
	public static final Keyword KEY_SOCIAL = Keyword.intern("social");

	/**
	 * The social lattice: OwnerLattice mapping owner keys to signed per-user state.
	 *
	 * <p>Each owner's value is wrapped in {@code SignedData} — only the owner's
	 * Ed25519 key can sign updates. The inner value is a {@link SocialLattice}
	 * containing :feed, :profile, and :follows.</p>
	 */
	public static final OwnerLattice<Index<Keyword, ACell>> SOCIAL_LATTICE =
		OwnerLattice.create(SocialLattice.INSTANCE);
}
