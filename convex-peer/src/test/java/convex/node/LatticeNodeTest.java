package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.ErrorCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.StoreException;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;

/** Optional networking preserves the existing host, application boundaries and store ownership. */
class LatticeNodeTest {

	/** Node identity supplies group defaults without inventing keys or replacing group overrides. */
	@Test void primaryIdentityIsValidatedAndUsedAsTheGroupDefault() throws Exception {
		try (var store=new MemoryStore();
				var network=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,store),NodeConfig.port(-1)).launch()) {
			assertNull(network.getDID());
			assertNull(network.getKeyPair());
			var anonymous=network.createPropagator();
			assertNull(anonymous.getTransportKey());
			var primary=AKeyPair.createSeeded(703);
			var separate=AKeyPair.createSeeded(704);
			var did=DID.forKey(primary.getAccountKey());
			network.setIdentity(did,primary);
			var first=network.createPropagator();
			var second=network.createPropagator();
			assertEquals(primary.getAccountKey(),first.getTransportKey());
			assertEquals(primary.getAccountKey(),second.getTransportKey());
			assertNull(anonymous.getTransportKey(),"Existing groups retain their identity");
			first.setTransportKeyPair(separate);
			assertEquals(primary.getAccountKey(),second.getTransportKey());
			assertThrows(IllegalArgumentException.class,() -> network.setIdentity(did,separate));
			assertThrows(IllegalArgumentException.class,() -> network.setIdentity(did,null));
			assertThrows(IllegalArgumentException.class,() -> network.setIdentity(null,primary));
			assertSame(primary,network.getKeyPair());
			assertEquals(did,network.getDID());

			var indirect=Strings.create("did:web:node.example");
			assertThrows(IllegalArgumentException.class,() -> network.setIdentity(indirect,separate));
			network.setIdentity(indirect,separate,(subject,key) -> indirect.equals(subject) && separate.getAccountKey().equals(key));
			assertEquals(indirect,network.getDID());
			assertEquals(separate.getAccountKey(),network.createPropagator().getTransportKey());
			assertEquals(primary.getAccountKey(),second.getTransportKey());
			network.setIdentity(null,null);
			assertNull(network.createPropagator().getTransportKey());
			assertEquals(separate.getAccountKey(),first.getTransportKey(),"Clearing the default preserves overrides");
		}
	}

	/** New policies affect new assignments; existing sockets retain their group's live view. */
	@Test void groupsAndServingPoliciesCanChangeWhileRunning() throws Exception {
		try (var store=new MemoryStore()) {
			var host=LatticeHost.create(CVMLong.create(99),store);
			try (var network=new LatticeNode<>(host,NodeConfig.port(0)).launch()) {
				assertTrue(network.getServer().getPropagators().isEmpty());
				var first=network.createPropagator();
				first.setPublicationFilter(value -> CVMLong.ONE);
				network.serveAllInbound(first);
				try (var firstClient=ConvexRemote.connect(network.getHostAddress())) {
					assertEquals(CVMLong.ONE,queryRoot(firstClient));
					var second=network.createPropagator();
					second.setPublicationFilter(value -> CVMLong.TWO);
					network.serveAllInbound(second);
					try (var secondClient=ConvexRemote.connect(network.getHostAddress())) {
						assertEquals(CVMLong.TWO,queryRoot(secondClient));
						assertEquals(CVMLong.ONE,queryRoot(firstClient));
						first.setPublicationFilter(value -> CVMLong.create(3));
						assertEquals(CVMLong.create(3),queryRoot(firstClient));
						assertThrows(IllegalArgumentException.class,() -> first.setPublicationFilter(value -> null));
						var announced=first.nextAnnounce();
						host.sync();
						assertEquals(CVMLong.create(3),announced.get(5,TimeUnit.SECONDS),"Failed policy changes preserve the previous filter");
						assertTrue(network.removePropagator(first));
						assertFalse(network.removePropagator(first));
						assertFalse(first.isRunning());
						assertTrue(second.isRunning());
						assertEquals(CVMLong.TWO,queryRoot(secondClient));
						assertEquals(CVMLong.create(99),host.cursor().get(),"Serving policy never edits host state");
					}
				}
			}
		}
	}

	private static ACell queryRoot(ConvexRemote client) throws Exception {
		var message=Message.create(MessageType.LATTICE_QUERY,
			Vectors.create(MessageTag.LATTICE_QUERY,CVMLong.ONE,Vectors.empty()));
		var result=client.message(message).get(5,TimeUnit.SECONDS);
		assertFalse(result.isError(),result.toString());
		return result.getValue();
	}

	@Test void inboundAccessRequiresAnExplicitPolicy() throws Exception {
		try (var store=new MemoryStore()) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			try (var network=new LatticeNode<>(host,NodeConfig.port(0)).launch();
					var client=ConvexRemote.connect(network.getHostAddress())) {
				var group=network.createPropagator();
				assertTrue(group.isRunning());
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
			try (var sender=new LatticeNode<>(source,NodeConfig.port(0)).launch();
					var receiver=new LatticeNode<>(target,NodeConfig.port(-1)).launch()) {
				sender.setIdentity(DID.forKey(sourceKey.getAccountKey()),sourceKey);
				var targetKey=AKeyPair.createSeeded(702);
				receiver.setIdentity(DID.forKey(targetKey.getAccountKey()),targetKey);
				var sourceGroup=sender.createPropagator();
				sender.serveAllInbound(sourceGroup);
				var targetGroup=receiver.createPropagator();
				try (var peer=ConvexRemote.connect(sender.getHostAddress())) {
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
	}

	@Test void failedListenerLaunchReleasesTheHost() throws Exception {
		try (var store=new MemoryStore(); var occupied=new ServerSocket(0)) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			try (var failed=new LatticeNode<>(host,NodeConfig.port(occupied.getLocalPort()))) {
				var group=failed.createPropagator();
				assertThrows(IOException.class,failed::launch);
				assertFalse(group.isRunning());
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
				var rejected=new LatticePropagator(store);
				assertThrows(StoreException.class,() -> server.addPropagator(rejected));
				assertTrue(server.getPropagators().isEmpty(),"Failed dynamic attachment does not leave an orphan group");
				assertFalse(rejected.isRunning());
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
