package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.Symbols;
import convex.core.cvm.ops.Special;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.peer.TestNetwork;

public class QuerySimulationTest {
	@Test
	public void testLocalAndRemoteZeroBalanceQueries() throws Exception {
		TestNetwork network=TestNetwork.getInstance();
		try (ConvexLocal local=network.getLocalClient();
				ConvexRemote remote=Convex.connect(network.SERVER.getHostAddress())) {
			Address funded=local.getAddress();
			AKeyPair key=AKeyPair.generate();
			Address empty=local.createAccountSync(key.getAccountKey());
			assertQueryAndSimulation(local,funded,empty,key);
			assertQueryAndSimulation(remote,funded,empty,key);
		}
	}

	@Test
	public void testDirectZeroBalanceQueries() throws Exception {
		AKeyPair peerKey=AKeyPair.generate();
		try (ConvexDirect client=ConvexDirect.create(peerKey,
				Migrations.applyAll(Init.createTestState(List.of(peerKey.getAccountKey()))))) {
			Address funded=client.getAddress();
			AKeyPair key=AKeyPair.generate();
			Address empty=client.createAccountSync(key.getAccountKey());
			assertQueryAndSimulation(client,funded,empty,key);
		}
	}

	private void assertQueryAndSimulation(Convex client, Address funded, Address empty, AKeyPair key) throws Exception {
		client.setAddress(empty);
		client.setKeyPair(null);
		long sequence=client.getSequence(funded);
		Long balance=client.getBalance(funded);
		Result query=client.querySync("[*address* *balance*]");
		assertFalse(query.isError(),query::toString);
		assertEquals(Reader.read("["+empty+" 0]"),query.getValue());
		assertNull(query.getInfo().get(Keywords.FEES));
		// These compiled queries are also used by the GUI's account chooser.
		assertEquals(0L,client.getBalance());
		assertEquals(key.getAccountKey(),client.query(Special.forSymbol(Symbols.STAR_KEY)).get(5,TimeUnit.SECONDS).getValue());

		assertEquals(ErrorCodes.JUICE,client.simulateSync(Invoke.create(empty,1,"1")).getErrorCode());
		Invoke transaction=Invoke.create(funded,sequence+1,"(def simulation-only 23)");
		Result simulation=client.simulate(transaction).get(5,TimeUnit.SECONDS);
		assertFalse(simulation.isError(),simulation::toString);
		assertEquals(CVMLong.create(23),simulation.getValue());
		assertTrue(((CVMLong)simulation.getInfo().get(Keywords.FEES)).longValue()>0);
		assertEquals(ErrorCodes.UNDECLARED,client.querySync(Reader.read("simulation-only"),funded).getErrorCode());
		assertEquals(sequence,client.getSequence(funded));
		assertEquals(balance,client.getBalance(funded));
		assertEquals(empty,client.getAddress());
	}
}
