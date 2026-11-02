package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.auth.did.DIDResolution;
import convex.auth.did.DIDResolver;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class PeerResolverTest {

	private static final AccountKey KEY=AKeyPair.createSeeded(7201).getAccountKey();
	private static final AccountKey ROTATED=AKeyPair.createSeeded(7202).getAccountKey();
	private static final String WEB="did:web:files.example.com";

	@Test
	void parsesTransportNamesAsTheyWereReadBefore() {
		assertEquals(new PeerName(URI.create("tcp://peer.example:18888"),null),PeerResolver.parse("peer.example:18888"));
		assertEquals(new PeerName(URI.create("tls://peer.example:19888"),null),PeerResolver.parse(" tls://peer.example:19888 "));
		assertEquals(new PeerName(URI.create("tcp://peer.example:18888"),KEY),
			PeerResolver.parse(KEY.toHexString()+"@tcp://peer.example:18888"));
		assertEquals(new PeerName(URI.create("tcp://peer.example:18888"),KEY),
			PeerResolver.parse("tcp://peer.example:18888",DID.forKey(KEY).toString()));
	}

	@Test
	void parsesPeerDids() {
		assertEquals(new PeerName(URI.create(WEB),null),PeerResolver.parse(WEB));
		assertEquals(new PeerName(URI.create("did:web:example.com%3A8443:nodes:a"),KEY),
			PeerResolver.parse(KEY.toHexString()+"@did:web:example.com%3a8443:nodes:a"));
		AString didKey=DID.forKey(KEY);
		assertEquals(new PeerName(URI.create(didKey.toString()),null),PeerResolver.parse(didKey.toString()));
	}

	@Test
	void refusesMalformedAndUnsupportedNames() {
		for (String name:new String[] {"","peer.example","tcp://peer.example","wss://peer.example:443",
				"udp://peer.example:1","did:convex:13","did:example:abc","did:key:zabc","did:web:example.com/path",
				"did:web:example.com#frag","@tcp://peer.example:1","nothex@tcp://peer.example:1",
				"tcp://peer.example:1@"}) {
			assertThrows(IllegalArgumentException.class,() -> PeerResolver.parse(name),name);
		}
	}

	@Test
	void transportNamesResolveToTheirEndpoint() {
		PeerResolver resolver=new PeerResolver(did -> {throw new AssertionError("resolved "+did);},null);
		PeerTarget open=resolver.resolve(PeerResolver.parse("tls://peer.example:19888")).join();
		assertTrue(open.keys().isEmpty());
		assertEquals(Vectors.of(Strings.create("tls://peer.example:19888")),open.endpoints());
		assertTrue(open.admits(ROTATED));

		PeerTarget pinned=resolver.resolve(PeerResolver.parse(KEY.toHexString()+"@peer.example:18888")).join();
		assertEquals(Vectors.of(KEY),pinned.keys());
		assertTrue(pinned.admits(KEY));
		assertFalse(pinned.admits(ROTATED));
	}

	@Test
	void didWebResolvesToItsKeysAndLatticeEndpoints() {
		PeerResolver resolver=resolver(document(WEB,"""
			"service": [{"id": "#lattice", "type": "ConvexLatticeNode",
			  "serviceEndpoint": ["wss://files.example.com/lattice", "tls://files.example.com:19888", {"uri": "x"},
			    "tcp://files.example.com:19888"]}]
			""",KEY,ROTATED),null);

		PeerTarget target=resolver.resolve(PeerResolver.parse(WEB)).join();

		assertEquals(Vectors.of(KEY,ROTATED),target.keys());
		assertEquals(Vectors.of(Strings.create("tls://files.example.com:19888"),Strings.create("tcp://files.example.com:19888")),
			target.endpoints());
	}

	@Test
	void anExpectedKeyNarrowsADidToThatKey() {
		AMap<AString,ACell> document=document(WEB,LATTICE_SERVICE,KEY,ROTATED);
		PeerTarget target=resolver(document,null).resolve(PeerResolver.parse(ROTATED.toHexString()+"@"+WEB)).join();
		assertEquals(Vectors.of(ROTATED),target.keys());

		AccountKey stranger=AKeyPair.createSeeded(7203).getAccountKey();
		assertFailsWith("does not list key",resolver(document,null).resolve(PeerResolver.parse(stranger.toHexString()+"@"+WEB)));
	}

	@Test
	void aDidWithoutLatticeServiceIsLocatedByKey() {
		AVector<AString> recorded=Vectors.of(Strings.create("tcp://10.0.0.5:18888"),Strings.create("wss://x.example/l"));
		Map<AccountKey,AVector<AString>> records=Map.of(KEY,recorded);
		PeerResolver resolver=new PeerResolver(DIDResolver.KEY,records::get);

		PeerTarget target=resolver.resolve(PeerResolver.parse(DID.forKey(KEY).toString())).join();

		assertEquals(Vectors.of(KEY),target.keys());
		assertEquals(Vectors.of(Strings.create("tcp://10.0.0.5:18888")),target.endpoints());
	}

	@Test
	void aPeerWithNoKnownAddressFails() {
		PeerResolver noRecords=new PeerResolver(DIDResolver.KEY,null);
		assertFailsWith("No address known",noRecords.resolve(PeerResolver.parse(DID.forKey(KEY).toString())));
		PeerResolver emptyRecords=new PeerResolver(DIDResolver.KEY,key -> null);
		assertFailsWith("No address known",emptyRecords.resolve(PeerResolver.parse(DID.forKey(KEY).toString())));
	}

	@Test
	void resolutionFailuresAreReported() {
		PeerResolver notFound=new PeerResolver(did -> CompletableFuture.completedFuture(
			DIDResolution.error(DIDResolution.NOT_FOUND,"gone")),null);
		assertFailsWith("NOT_FOUND",notFound.resolve(PeerResolver.parse(WEB)));

		PeerResolver deactivated=new PeerResolver(did -> CompletableFuture.completedFuture(DIDResolution.deactivated(null)),null);
		assertFailsWith("deactivated",deactivated.resolve(PeerResolver.parse(WEB)));

		PeerResolver keyless=resolver(document(WEB,LATTICE_SERVICE),null);
		assertFailsWith("lists no Ed25519 key",keyless.resolve(PeerResolver.parse(WEB)));
	}

	@Test
	void everyResolutionFetchesAfresh() {
		AtomicInteger fetches=new AtomicInteger();
		AMap<AString,ACell> document=document(WEB,LATTICE_SERVICE,KEY);
		PeerResolver resolver=new PeerResolver(did -> {
			fetches.incrementAndGet();
			return CompletableFuture.completedFuture(DIDResolution.of(document,null));
		},null);
		PeerName name=PeerResolver.parse(WEB);
		resolver.resolve(name).join();
		resolver.resolve(name).join();
		assertEquals(2,fetches.get());
	}

	@Test
	void theDefaultResolverHandlesDidKeyWithoutNetwork() {
		PeerResolver resolver=PeerResolver.create();
		// did:key needs no fetch; with no records it has nowhere to dial
		assertFailsWith("No address known",resolver.resolve(PeerResolver.parse(DID.forKey(KEY).toString())));
		assertNull(PeerResolver.defaultDIDResolver().resolve(DID.forKey(KEY)).join().getErrorType());
	}

	private static final String LATTICE_SERVICE="""
		"service": [{"id": "#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "tcp://files.example.com:19888"}]
		""";

	private static PeerResolver resolver(AMap<AString,ACell> document,java.util.function.Function<AccountKey,AVector<AString>> records) {
		return new PeerResolver(did -> CompletableFuture.completedFuture(DIDResolution.of(document,null)),records);
	}

	/** A DID document listing the keys under authentication, with further members. */
	private static AMap<AString,ACell> document(String did,String members,AccountKey... keys) {
		StringBuilder methods=new StringBuilder();
		StringBuilder auth=new StringBuilder();
		for (int i=0; i<keys.length; i++) {
			if (i>0) {
				methods.append(',');
				auth.append(',');
			}
			methods.append("{\"id\": \"#k").append(i).append("\", \"type\": \"Multikey\", \"controller\": \"").append(did)
				.append("\", \"publicKeyMultibase\": \"").append(Multikey.encodePublicKey(keys[i])).append("\"}");
			auth.append("\"#k").append(i).append('"');
		}
		String json="{\"id\": \""+did+"\", \"verificationMethod\": ["+methods+"], \"authentication\": ["+auth+"], "+members+"}";
		return RT.ensureMap(JSON.parse(json));
	}

	private static void assertFailsWith(String message,CompletableFuture<PeerTarget> future) {
		CompletionException e=assertThrows(CompletionException.class,future::join);
		assertTrue(e.getCause().getMessage().contains(message),e.getCause().getMessage());
	}
}
