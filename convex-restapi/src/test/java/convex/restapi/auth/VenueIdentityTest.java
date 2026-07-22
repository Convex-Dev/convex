package convex.restapi.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VenueIdentityTest {

	@Test
	void derivesCanonicalWebDIDFromOrigin() {
		assertEquals("did:web:peer.example.com",
				VenueIdentity.fromBaseUrl("https://PEER.example.com/").toString());
		assertEquals("did:web:localhost%3A8080",
				VenueIdentity.fromBaseUrl("http://localhost:8080").toString());
		assertEquals("did:web:peer.example.com",
				VenueIdentity.fromBaseUrl("https://peer.example.com:443").toString());
	}

	@Test
	void rejectsRequestDependentOrAmbiguousOrigins() {
		assertThrows(IllegalArgumentException.class,()->VenueIdentity.fromBaseUrl(null));
		assertThrows(IllegalArgumentException.class,()->VenueIdentity.fromBaseUrl("peer.example.com"));
		assertThrows(IllegalArgumentException.class,()->VenueIdentity.fromBaseUrl("https://peer.example.com/api"));
		assertThrows(IllegalArgumentException.class,()->VenueIdentity.fromBaseUrl("https://user@peer.example.com"));
	}
}
