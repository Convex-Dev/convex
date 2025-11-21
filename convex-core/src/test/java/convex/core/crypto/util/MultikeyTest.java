package convex.core.crypto.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

public class MultikeyTest {

	String secret="z3u2en7t5LR2WtQH5PfFqMqwVHBeXouLzo6haApm8XHqvjxq";
	String pubkey="z6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2";
	
	@Test public void testSecretRoundTrip() {
		Blob b=Multikey.decodeSecretKey(secret);
		
		AKeyPair kp=AKeyPair.create(b);
		AccountKey pak=kp.getAccountKey();
		
		assertEquals(pak,Multikey.decodePublicKey(pubkey));
		
		AString pub2=Multikey.encodePublicKey(pak);
		assertEquals(pubkey,pub2.toString());
	}
}
