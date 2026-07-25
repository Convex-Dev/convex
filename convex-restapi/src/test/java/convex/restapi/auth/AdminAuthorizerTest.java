package convex.restapi.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.init.Init;

class AdminAuthorizerTest {

	private static final AString VENUE_DID=VenueIdentity.fromBaseUrl("https://venue.example.com");

	@Test
	void defaultsFollowOperationalAndCurrentConsensusControllerKeys() {
		AKeyPair operational=AKeyPair.generate();
		AKeyPair firstController=AKeyPair.generate();
		AKeyPair secondController=AKeyPair.generate();
		AKeyPair attacker=AKeyPair.generate();

		AtomicReference<Peer> current=new AtomicReference<>(peer(operational,firstController));
		AdminAuthorizer auth=new AdminAuthorizer(current::get,operational,VENUE_DID,null,Set.of());

		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(token(operational,VENUE_DID),"127.0.0.1","http",null,false));
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(token(firstController,VENUE_DID),"127.0.0.1","http",null,false));
		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(token(attacker,VENUE_DID),"127.0.0.1","http",null,false));

		current.set(peer(operational,secondController));
		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(token(firstController,VENUE_DID),"127.0.0.1","http",null,false),
			"controller rotation must revoke the old key without a cache or token list");
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(token(secondController,VENUE_DID),"127.0.0.1","http",null,false));
	}

	@Test
	void explicitKeysReplaceRatherThanExtendDefaults() {
		AKeyPair operational=AKeyPair.generate();
		AKeyPair configured=AKeyPair.generate();
		Peer peer=peer(operational,AKeyPair.generate());
		AdminAuthorizer auth=new AdminAuthorizer(()->peer,operational,VENUE_DID,
			Set.of(DID.forKey(configured.getAccountKey())),Set.of());

		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(token(operational,VENUE_DID),"127.0.0.1","http",null,false));
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(token(configured,VENUE_DID),"127.0.0.1","http",null,false));
	}

	@Test
	void requiresCorrectAudienceAndSecureRemoteTransport() {
		AKeyPair operational=AKeyPair.generate();
		Peer peer=peer(operational,AKeyPair.generate());
		AdminAuthorizer auth=new AdminAuthorizer(()->peer,operational,VENUE_DID,null,
			Set.of("10.0.0.10"));
		AString valid=token(operational,VENUE_DID);

		assertEquals(AdminAuthorizer.Decision.UNAUTHENTICATED,
			auth.check(token(operational,null),"127.0.0.1","http",null,false));
		assertEquals(AdminAuthorizer.Decision.UNAUTHENTICATED,
			auth.check(token(operational,VenueIdentity.fromBaseUrl("https://other.example.com")),
				"127.0.0.1","http",null,false));
		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(valid,"203.0.113.7","http",null,false));
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(valid,"203.0.113.7","https",null,false));
		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(valid,"203.0.113.7","http","https",true),
			"an untrusted client cannot promote HTTP by forging X-Forwarded-Proto");
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(valid,"10.0.0.10","http","https",true));
		assertEquals(AdminAuthorizer.Decision.FORBIDDEN,
			auth.check(valid,"127.0.0.1","http",null,true),
			"loopback HTTP is not treated as direct local access when proxy headers are present");
		assertEquals(AdminAuthorizer.Decision.ALLOW,
			auth.check(valid,"127.0.0.1","http","https",true),
			"a loopback TLS proxy may report the external HTTPS transport");
	}

	private static Peer peer(AKeyPair operational,AKeyPair controller) {
		State state=Init.createState(controller.getAccountKey(),List.of(operational.getAccountKey()));
		return Peer.create(operational,state);
	}

	private static AString token(AKeyPair signer,AString audience) {
		long now=System.currentTimeMillis()/1000;
		AString did=DID.forKey(signer.getAccountKey());
		AMap<AString,ACell> claims=Maps.of(
			JWT.SUB,did,
			JWT.ISS,did,
			JWT.IAT,now,
			JWT.EXP,now+300
		);
		if (audience!=null) claims=claims.assoc(JWT.AUD,audience);
		return JWT.signPublic(claims,signer);
	}
}
