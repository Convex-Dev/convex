package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.StoreException;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;

/** Optional networking preserves the existing host, application boundaries and store ownership. */
class LatticeNodeTest {

	@Test void inboundAccessRequiresAnExplicitPolicy() throws Exception {
		try (var store=new MemoryStore()) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			try (var network=new LatticeNode<>(host,NodeConfig.port(0),new LatticePropagator(store)).launch();
					var client=ConvexRemote.connect(network.getHostAddress())) {
				assertSame(host,network.getServer().getHost());
				assertEquals(ErrorCodes.TRUST,client.message(Message.createPing(1))
					.get(5,TimeUnit.SECONDS).getErrorCode());
			}
			host.cursor().set(CVMLong.TWO);
			host.sync();
			assertEquals(CVMLong.TWO,store.getRootData(),"Closing networking leaves the host and store usable");
		}
	}

	@Test void publicNetworkingDeliversThroughTheExistingApplication() throws Exception {
		try (var sourceStore=new MemoryStore(); var targetStore=new MemoryStore()) {
			var source=LatticeHost.create(Sets.<CVMLong>of(1),sourceStore);
			var target=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),targetStore);
			var app=target.addApplication(new LatticeApplication<>(SetLattice.<CVMLong>create(),target));
			var sourceKey=AKeyPair.createSeeded(701);
			var sourceGroup=new LatticePropagator(sourceStore);
			sourceGroup.setTransportKeyPair(sourceKey);
			var targetGroup=new LatticePropagator(targetStore);
			targetGroup.setTransportKeyPair(AKeyPair.createSeeded(702));
			try (var sender=new LatticeNode<>(source,NodeConfig.port(0),sourceGroup).serveAllInbound().launch();
					var receiver=new LatticeNode<>(target,NodeConfig.port(-1),targetGroup).launch();
					var peer=ConvexRemote.connect(sender.getHostAddress())) {
				targetGroup.addPeer(sourceKey.getAccountKey(),peer).get(5,TimeUnit.SECONDS);
				receiver.getServer().pull(targetGroup,peer).get(5,TimeUnit.SECONDS);
				assertEquals(Sets.of(1),app.cursor().get());
				assertTrue(target.cursor().get().isEmpty(),"Network delivery respects the staged boundary");
				app.sync();
				target.sync();
				assertEquals(Sets.of(1),targetStore.getRootData());
			}
		}
	}

	@Test void failedListenerLaunchReleasesTheHost() throws Exception {
		try (var store=new MemoryStore(); var occupied=new ServerSocket(0)) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			try (var failed=new LatticeNode<>(host,NodeConfig.port(occupied.getLocalPort()),new LatticePropagator(store))) {
				assertThrows(IOException.class,failed::launch);
				assertFalse(failed.getServer().isRunning());
				assertFalse(failed.getListener().isRunning());
				try (var replacement=new NodeServer<>(host)) {
					replacement.launch();
					failed.close(); // Stale cleanup must not detach the replacement.
					assertThrows(IllegalStateException.class,() -> host.attachPublisher(value -> value));
				}
				host.cursor().set(CVMLong.TWO);
				host.sync();
				assertEquals(CVMLong.TWO,store.getRootData());
			}
		}
	}

	/** Failures during initial or final root publication must release the attachment too. */
	@Test void persistenceFailuresReleaseTheHost() throws Exception {
		class FailingStore extends MemoryStore {
			boolean fail;
			@Override public <T extends ACell> Ref<T> setRootData(T value) {
				if (fail) throw new StoreException("test publication failure",new IOException("test publication failure"));
				return super.setRootData(value);
			}
		}
		try (var store=new FailingStore()) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			try (var server=new NodeServer<>(host)) {
				store.fail=true;
				assertThrows(IOException.class,server::launch);
				store.fail=false;
				server.launch();
				store.fail=true;
				assertThrows(IOException.class,server::close);
				store.fail=false;
				try (var replacement=new NodeServer<>(host)) {
					replacement.launch();
				}
				host.cursor().set(CVMLong.TWO);
				host.sync();
				assertEquals(CVMLong.TWO,store.getRootData());
			}
		}
	}
}
