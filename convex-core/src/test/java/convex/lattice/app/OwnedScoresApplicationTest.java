package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.ASignedComponent;
import convex.lattice.LatticeHost;
import convex.lattice.OwnedIndex;
import convex.lattice.SingleRegion;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SignedLattice;

/**
 * An owner identity can author through several public keys. The host stores
 * {@code {:scores {did #Signed integer}}}: the map key is the team's identity,
 * while each envelope identifies the individual key that signed its score.
 *
 * <p>{@link Scores} binds a local authority predicate to its owner lattice.
 * {@link Score} receives that same bound rule and an explicit signer. Both local
 * authoring and incoming delivery therefore check the same authority data.
 * {@link MaxLattice} selects the larger score without synthesising a new payload,
 * allowing replicas to retain the selected envelope without a private key.</p>
 *
 * <p>{@code did:example:team} and {@link Authority} are test fixtures, not a DID
 * method or network resolver. Authority changes affect subsequent checks; they
 * do not automatically delete records that were accepted under an earlier rule.
 * The owner collection is staged, and application sync publishes it to the host.</p>
 */
class OwnedScoresApplicationTest {

	private static final Keyword SCORES=Keyword.intern("scores");
	private static final AString TEAM=Strings.intern("did:example:team");
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7410);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7411);
	private static final AKeyPair STRANGER=AKeyPair.createSeeded(7412);

	/**
	 * Local authority snapshots: each replacement is an immutable set of allowed
	 * keys. Verification performs no network I/O inside an atomic merge or edit.
	 */
	static final class Authority {
		private final Map<AString,Set<AccountKey>> keys=new ConcurrentHashMap<>();
		/** Replaces this owner's authorised keys; omitted previous keys are revoked. */
		void authorise(AString owner,AccountKey... signers) { keys.put(owner,Set.of(signers)); }
		boolean isAuthorised(AString owner,AccountKey signer) {
			return keys.getOrDefault(owner,Set.of()).contains(signer);
		}
	}

	/** Binds identity verification to supplied storage, with no private signing capability. */
	static final class Scores extends OwnedIndex<AString,AInteger> {
		private final Authority authority;
		Scores(AForkableCursor<Index<AString,SignedData<AInteger>>> destination,
				Authority authority) {
			super(destination,MaxLattice.INSTANCE,authority::isAuthorised);
			this.authority=authority;
		}
		@Override protected Scores rebind(AForkableCursor<Index<AString,SignedData<AInteger>>> cursor) {
			return new Scores(cursor,authority);
		}
		@Override public Scores fork() { return rebind(storage.fork()); }
		/** Opens a live handle; possession of the supplied key alone does not authorise an edit. */
		Score score(AString owner,AKeyPair signer) {
			return new Score(path(owner),signingFor(owner),signer);
		}
	}

	/** Authoring a higher score signs immediately into the collection's working state. */
	static final class Score extends ASignedComponent<AInteger> {
		Score(AForkableCursor<SignedData<AInteger>> destination,SignedLattice<AInteger> rule,AKeyPair signer) {
			super(destination,rule,signer::signData);
		}
		/** A lower or equal observation leaves the established value and signature unchanged. */
		void record(long value) { cursor().updateAndGet(old -> MaxLattice.INSTANCE.merge(old,CVMLong.create(value))); }
	}

	/** Different authorised signers update one owner slot; a replica verifies and keeps the winner's envelope. */
	@Test void twoAuthorisedUsersShareAnIdentityAndAReplicaNeedsNoSigningKey() {
		var authority=new Authority();
		authority.authorise(TEAM,ALICE.getAccountKey(),BOB.getAccountKey());
		var host=LatticeHost.create();
		var app=new SingleRegion<>(host,SCORES,new Scores(host.cursor().path(SCORES),authority).fork());
		var alice=app.component().score(TEAM,ALICE);
		var bob=app.component().score(TEAM,BOB);
		alice.record(10);
		bob.record(20);
		assertEquals(CVMLong.create(20),alice.cursor().get());
		assertTrue(host.cursor().get().isEmpty());
		app.sync();

		var replicaHost=LatticeHost.create();
		var replica=new SingleRegion<>(replicaHost,SCORES,new Scores(replicaHost.cursor().path(SCORES),authority).fork());
		replicaHost.addApplication(replica);
		replicaHost.merge(host.cursor().get());
		var accepted=replica.component().get(TEAM);
		assertSame(app.component().get(TEAM),accepted);
		assertEquals(BOB.getAccountKey(),accepted.getAccountKey());
		// A higher score does not bypass authorisation: ordering follows ownership checks.
		replicaHost.mergeAt(STRANGER.signData(CVMLong.create(100)),SCORES,TEAM);
		assertSame(accepted,replica.component().get(TEAM));
		replica.sync();
		assertEquals(host.cursor().get(),replicaHost.cursor().get());
	}

	/** Revocation changes the rule used by already-open handles and subsequent incoming deliveries. */
	@Test void existingViewsUseCurrentAuthorityWhenSigning() {
		var authority=new Authority();
		authority.authorise(TEAM,ALICE.getAccountKey());
		var scores=new Scores(new convex.lattice.cursor.Root<>(),authority);
		var alice=scores.score(TEAM,ALICE);
		var bob=scores.score(TEAM,BOB);
		alice.record(10);
		var before=scores.get(TEAM);
		// The existing handles retain a live verifier, not a cached authorisation decision.
		authority.authorise(TEAM,BOB.getAccountKey());
		assertThrows(IllegalStateException.class,() -> alice.record(20));
		assertSame(before,scores.get(TEAM));
		bob.record(20);
		assertEquals(BOB.getAccountKey(),scores.get(TEAM).getAccountKey());
		scores.mergeAt(ALICE.signData(CVMLong.create(30)),TEAM);
		assertEquals(CVMLong.create(20),scores.get(TEAM).getValue());
	}
}
