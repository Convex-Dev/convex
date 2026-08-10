package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.init.Init;
import convex.etch.Etch;
import convex.etch.EtchConfig;
import convex.etch.EtchConstants;
import convex.etch.EtchStore;

/** Tests the consensus Peer's storage durability boundaries. */
public class ServerPersistenceTest {

	private static final AKeyPair KEY_PAIR=AKeyPair.createSeeded(8675309);
	private static final State GENESIS=Init.createState(List.of(KEY_PAIR.getAccountKey()));

	@Test
	public void testOnlyOrderlyShutdownFlushes() throws Exception {
		testOnlyOrderlyShutdownFlushes("v1",EtchConfig.create(EtchConstants.VERSION_1));
		testOnlyOrderlyShutdownFlushes("v2",EtchConfig.create(EtchConstants.VERSION_2));
		testOnlyOrderlyShutdownFlushes("v3",EtchConfig.create(EtchConstants.VERSION_3));
	}

	private void testOnlyOrderlyShutdownFlushes(String name, EtchConfig etchConfig) throws Exception {
		File file=File.createTempFile("peer-checkpoint-"+name,".etch");
		file.deleteOnExit();

		CountingEtchStore store=new CountingEtchStore(Etch.create(file,etchConfig));
		Server server=null;
		Peer expected=null;
		try {
			Map<Keyword,Object> config=Maps.hashMapOf(
					Keywords.KEYPAIR,KEY_PAIR,
					Keywords.STATE,GENESIS,
					Keywords.STORE,store,
					Keywords.PORT,0,
					Keywords.PERSIST,true);
			server=API.launchPeer(config);

			// Startup and ordinary root publication are deliberately buffered.
			assertEquals(0,store.flushCount);
			expected=server.persistPeerData();
			assertEquals(0,store.flushCount);

			server.close();
			server=null;
			assertEquals(1,store.flushCount);
		} finally {
			if (server!=null) server.close();
			store.close();
		}

		try (EtchStore reopened=EtchStore.create(file,etchConfig)) {
			Peer restored=Peer.restorePeer(reopened,KEY_PAIR,KEY_PAIR.getAccountKey());
			assertNotNull(restored);
			assertEquals(expected.toData(),restored.toData());
		}
	}

	private static class CountingEtchStore extends EtchStore {
		private int flushCount;

		CountingEtchStore(Etch etch) {
			super(etch);
		}

		@Override
		public void flush() throws IOException {
			flushCount++;
			super.flush();
		}
	}
}
