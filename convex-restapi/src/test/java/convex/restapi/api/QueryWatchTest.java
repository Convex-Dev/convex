package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.restapi.api.QueryWatch.Event;

class QueryWatchTest {

	private static final AKeyPair KEY_PAIR=AKeyPair.createSeeded(637_1);
	private static final State GENESIS=Init.createTestState(List.of(KEY_PAIR.getAccountKey()));

	@Test
	void emitsInitialAndChangedResultsButSuppressesUnchangedResults() throws InvalidDataException {
		Peer initial=Peer.create(KEY_PAIR,GENESIS);
		Peer updated=advance(initial);

		QueryWatch constant=new QueryWatch(Reader.read("42"),Init.GENESIS_ADDRESS);
		Event first=constant.evaluate(initial);
		assertNotNull(first);
		assertEquals(CVMLong.create(42),first.result().getValue());
		assertNull(constant.evaluate(initial));
		assertNull(constant.evaluate(updated));

		QueryWatch timestamp=new QueryWatch(Reader.read("*timestamp*"),Init.GENESIS_ADDRESS);
		Event before=timestamp.evaluate(initial);
		Event after=timestamp.evaluate(updated);
		assertNotNull(before);
		assertNotNull(after);
		assertEquals(updated.getStatePosition(),after.position());
	}

	@Test
	void appliesWatchSpecificJuiceCeiling() {
		Peer peer=Peer.create(KEY_PAIR,GENESIS);
		QueryWatch query=new QueryWatch(Reader.read("*juice-limit*"),Init.GENESIS_ADDRESS);
		assertEquals(CVMLong.create(QueryWatch.MAX_QUERY_JUICE),query.evaluate(peer).result().getValue());

		QueryWatch unboundedLoop=new QueryWatch(
			Reader.read("(loop [x 0] (recur (inc x)))"),Init.GENESIS_ADDRESS);
		assertEquals(ErrorCodes.JUICE,unboundedLoop.evaluate(peer).result().getErrorCode());
	}

	@Test
	void encodesEquivalentBoundedJSONAndCVXEnvelopes() {
		Peer peer=Peer.create(KEY_PAIR,GENESIS);
		Event event=new QueryWatch(Reader.read("(+ 1 2)"),Init.GENESIS_ADDRESS).evaluate(peer);

		AMap<ACell,ACell> json=JSON.parse(QueryWatch.encode(event,WatchFormat.JSON,4096));
		AMap<ACell,ACell> jsonResult=json.getIn("result");
		assertEquals(CVMLong.create(3),jsonResult.getIn("value"));

		AMap<ACell,ACell> cvx=Reader.read(QueryWatch.encode(event,WatchFormat.CVX,4096));
		Result cvxResult=cvx.getIn(Reader.read(":result"));
		assertEquals(CVMLong.create(3),cvxResult.getValue());

		Event large=new Event(0,Result.create(null,Strings.create("x".repeat(5000))));
		assertNull(QueryWatch.encode(large,WatchFormat.JSON,1024));
		assertNull(QueryWatch.encode(large,WatchFormat.CVX,1024));
	}

	private static Peer advance(Peer peer) throws InvalidDataException {
		long timestamp=peer.getTimestamp()+1;
		peer=peer.proposeBlock(Block.of(timestamp,KEY_PAIR.signData(
			Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 1 2)"))));
		return peer.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs().updateState();
	}
}
