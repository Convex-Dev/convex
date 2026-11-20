package convex.auth.did;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.init.InitTest;

public class DIDKeyAuthorizerTest {

	@Test
	void testDidKeyPossessionBinding() {
		AKeyPair owner=AKeyPair.generate();
		AKeyPair attacker=AKeyPair.generate();
		AString did=DID.forKey(owner.getAccountKey());

		assertTrue(DIDKeyAuthorizer.CONVEX.safeAuthorises(did,owner.getAccountKey()));
		assertFalse(DIDKeyAuthorizer.CONVEX.safeAuthorises(did,attacker.getAccountKey()));
	}

	@Test
	void testDidConvexAgainstPinnedState() {
		State state=InitTest.STATE;
		AString did=Strings.create("did:convex:"+InitTest.HERO.longValue());
		DIDKeyAuthorizer authorizer=DIDKeyAuthorizer.forState(state);

		assertTrue(authorizer.safeAuthorises(did,InitTest.HERO_KEYPAIR.getAccountKey()));
		assertFalse(authorizer.safeAuthorises(did,AKeyPair.generate().getAccountKey()));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAuthenticatedAlsoKnownAsKey() {
		AKeyPair current=AKeyPair.generate();
		AString web=Strings.create("did:web:identity.example");
		DIDKeyAuthorizer authorizer=DIDKeyAuthorizer.fromAlsoKnownAs(
			did -> did.equals(web)?List.of(
				Strings.create("did:web:alias.example"),
				DID.forKey(current.getAccountKey())):List.of());

		assertTrue(authorizer.safeAuthorises(web,current.getAccountKey()));
		assertFalse(authorizer.safeAuthorises(web,AKeyPair.generate().getAccountKey()));

		// A failing alias lookup is no alias, but a failure of the JVM itself propagates
		DIDKeyAuthorizer failing=DIDKeyAuthorizer.fromAlsoKnownAs(did -> { throw new IllegalStateException("lookup failed"); });
		assertFalse(failing.authorises(web,current.getAccountKey()));
		DIDKeyAuthorizer broken=DIDKeyAuthorizer.fromAlsoKnownAs(did -> { throw new InternalError("JVM failure"); });
		assertThrows(InternalError.class,() -> broken.authorises(web,current.getAccountKey()));
	}

	@Test
	void testFailingCheckIsNotAuthorisedButJvmFailurePropagates() {
		AKeyPair owner=AKeyPair.generate();
		AString did=DID.forKey(owner.getAccountKey());

		DIDKeyAuthorizer failing=(d,k) -> { throw new IllegalStateException("resolver unavailable"); };
		assertFalse(failing.safeAuthorises(did,owner.getAccountKey()));

		DIDKeyAuthorizer broken=(d,k) -> { throw new InternalError("JVM failure"); };
		assertThrows(InternalError.class,() -> broken.safeAuthorises(did,owner.getAccountKey()));
	}

	@Test
	void testBoundedSnapshotCacheAvoidsRepeatedResolution() {
		AKeyPair current=AKeyPair.generate();
		AString web=Strings.create("did:web:identity.example");
		AtomicInteger calls=new AtomicInteger();
		DIDKeyAuthorizer cached=DIDKeyAuthorizer.cached((did,key) -> {
			calls.incrementAndGet();
			return did.equals(web)&&key.equals(current.getAccountKey());
		},4);

		assertTrue(cached.safeAuthorises(web,current.getAccountKey()));
		assertTrue(cached.safeAuthorises(web,current.getAccountKey()));
		assertTrue(cached.safeAuthorises(web,current.getAccountKey()));
		assertTrue(calls.get()==1);
	}
}
