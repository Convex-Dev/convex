package convex.restapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Block;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Log;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.core.util.StateWatcher;
import convex.restapi.api.ConsensusLogScanner.LogEvent;

class ConsensusLogScannerTest {

	private static final AKeyPair KEY_PAIR=AKeyPair.createSeeded(637);
	private static final State GENESIS=Init.createTestState(List.of(KEY_PAIR.getAccountKey()));

	@Test
	void coalescedPeerUpdateIncludesLogsFromEveryFinalisedBlock() throws Exception {
		Peer peer=Peer.create(KEY_PAIR,GENESIS);
		long timestamp=peer.getTimestamp();

		peer=peer.proposeBlock(Block.of(timestamp,KEY_PAIR.signData(
			Invoke.create(Init.GENESIS_ADDRESS,1,"(log :FIRST)"))));
		peer=peer.proposeBlock(Block.of(timestamp+1,KEY_PAIR.signData(
			Invoke.create(Init.GENESIS_ADDRESS,2,"(log :SECOND)"))));
		peer=peer.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs().updateState();
		assertEquals(2,peer.getStatePosition());

		ArrayList<LogEvent> events=new ArrayList<>();
		ConsensusLogScanner scanner=new ConsensusLogScanner(0,events::add);
		try (StateWatcher<Peer> watcher=new StateWatcher<>(scanner)) {
			watcher.updateAndWait(peer);
			watcher.updateAndWait(peer);
		}

		assertEquals(2,events.size());
		checkEvent(events.get(0),0,":FIRST");
		checkEvent(events.get(1),1,":SECOND");
		assertEquals(2,scanner.getNextBlock());
	}

	private static void checkEvent(LogEvent event, long blockIndex, String expectedValue) {
		assertEquals(blockIndex,event.blockIndex());
		assertEquals(0,event.transactionIndex());
		assertEquals(0,event.logIndex());

		AVector<ACell> entry=event.entry();
		assertEquals(Vectors.of(blockIndex,0),entry.get(Log.P_LOCATION));
		assertEquals(Vectors.of(Reader.read(expectedValue)),entry.get(Log.P_VALUES));
	}
}
