package convex.core.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;

public class ChallengeSignerTest {

	private static final ACell CONTEXT=Message.LATTICE_PEER_CHALLENGE_CONTEXT;

	@Test public void testChallengeAndAnswerInItsContext() {
		AKeyPair challengerKey=AKeyPair.generate();
		AKeyPair responderKey=AKeyPair.generate();
		ChallengeSigner challenger=ChallengeSigner.create(challengerKey,CONTEXT);
		ChallengeSigner responder=ChallengeSigner.create(responderKey,CONTEXT);
		assertEquals(responderKey.getAccountKey(),responder.getAccountKey());
		assertEquals(CONTEXT,responder.getContext());

		Hash token=Blob.createRandom(new Random(7),16).getHash();
		SignedData<ACell> challenge=challenger.challenge(token,responderKey.getAccountKey());
		assertEquals(challengerKey.getAccountKey(),challenge.getAccountKey());
		Result answer=responder.answer(challenge);
		assertEquals(responderKey.getAccountKey(),Message.verifyChallengeResponse(
			answer,token,challengerKey.getAccountKey(),CONTEXT,responderKey.getAccountKey()));

		// A challenge naming no key is answered too
		assertFalse(responder.answer(challenger.challenge(token,null)).isError());
	}

	@Test public void testRefusesOtherContextsAndKeys() {
		AKeyPair challengerKey=AKeyPair.generate();
		AKeyPair responderKey=AKeyPair.generate();
		ChallengeSigner responder=ChallengeSigner.create(responderKey,CONTEXT);
		Hash token=Blob.createRandom(new Random(8),16).getHash();

		assertTrue(responder.answer(Message.signChallenge(challengerKey,token,
			responderKey.getAccountKey(),CVMLong.create(42))).isError(),"another context is refused");
		assertTrue(responder.answer(Message.signChallenge(challengerKey,token,
			responderKey.getAccountKey(),null)).isError(),"a challenge without a context is refused");
		assertTrue(responder.answer(Message.signChallenge(challengerKey,token,
			AKeyPair.generate().getAccountKey(),CONTEXT)).isError(),"a challenge for another key is refused");

		ChallengeSigner contextless=ChallengeSigner.create(responderKey,null);
		assertFalse(contextless.answer(Message.signChallenge(challengerKey,token,null,null)).isError());
		assertTrue(contextless.answer(Message.signChallenge(challengerKey,token,null,CONTEXT)).isError(),
			"a signer without a context refuses one with a context");
	}

	@Test public void testNeedsAKey() {
		assertThrows(IllegalArgumentException.class,() -> ChallengeSigner.create(null,CONTEXT));
	}
}
