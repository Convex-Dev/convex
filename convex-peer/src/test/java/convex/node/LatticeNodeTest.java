package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.auth.did.DIDResolution;
import convex.core.ErrorCodes;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.StoreException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.util.JSON;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.generic.SetLattice;
import convex.net.PeerResolver;
import convex.net.Transports;

/** Optional networking preserves the existing host, application boundaries and store ownership. */
class LatticeNodeTest {

	/** A group reads its own configuration map; a key in the node's map is not inherited (#769). */
	@Test void groupLimitsComeFromTheGroupConfig() throws Exception {
		var nodeConfig=NodeConfig.create(Maps.of(NodeConfig.PORT,CVMLong.create(-1),
			LatticePropagatorConfig.MAX_DESIRED_PEERS,CVMLong.create(3)));
		var groupConfig=LatticePropagatorConfig.create(Maps.of(
			LatticePropagatorConfig.MAX_DESIRED_PEERS,CVMLong.create(5)));
		try (var store=new MemoryStore();
				var network=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,store),nodeConfig).launch()) {
			assertEquals(LatticePropagatorConfig.DEFAULT_MAX_DESIRED_PEERS,
				network.createPropagator().getConnectionManager().getMaxDesiredPeers());
			assertEquals(5,network.createPropagator(groupConfig).getConnectionManager().getMaxDesiredPeers());
		}
	}

	/**
	 * Keeping a peer, and forgetting it, run on network threads, so neither may wait for
	 * the node's monitor: close holds it while it waits for those threads to close
	 * connections. The test holds the monitor as close does.
	 */
	@Test void keepingAPeerNeverWaitsForTheNodesLifecycle() throws Exception {
		try (var store=new MemoryStore();
				var node=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,store),NodeConfig.port(-1));
				var others=Executors.newVirtualThreadPerTaskExecutor()) {
			var group=node.createPropagator();
			synchronized (node) {
				var kept=others.submit(() -> node.keepPeer(group,PeerResolver.parse("127.0.0.1:1"),null))
					.get(5,TimeUnit.SECONDS);
				assertTrue(others.submit(() -> kept.cancel(false)).get(5,TimeUnit.SECONDS));
			}
		}
	}

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

	private static ACell pingError(ConvexRemote client) throws Exception {
		return client.message(Message.createPing(1)).get(5,TimeUnit.SECONDS).getErrorCode();
	}

	/** A second port serves a group of its own; a group is registered on, and removed from, every listener. */
	@Test void aSecondListenerServesAGroupOfItsOwn() throws Exception {
		try (var store=new MemoryStore()) {
			var host=LatticeHost.create(CVMLong.create(99),store);
			try (var network=new LatticeNode<>(host,NodeConfig.port(0))) {
				var open=network.createPropagator(LatticePropagatorConfig.create(),value -> CVMLong.ONE);
				var trusted=network.createPropagator(LatticePropagatorConfig.create(),value -> CVMLong.TWO);
				assertThrows(IllegalArgumentException.class,() -> network.createPropagator(LatticePropagatorConfig.create(),null));
				assertEquals(List.of(open,trusted),network.getServer().getPropagators(),"a refused filter attaches no group");
				network.serveAllInbound(open);
				var second=network.addListener(NodeConfig.port(0));
				network.launch();
				assertTrue(second.isRunning(),"the node opens every listener");
				try (var client=ConvexRemote.connect(second.getHostAddress())) {
					assertEquals(ErrorCodes.TRUST,pingError(client),"a new listener denies until given a selector");
				}
				second.setSelector(connection -> trusted);
				try (var openClient=ConvexRemote.connect(network.getHostAddress());
						var trustedClient=ConvexRemote.connect(second.getHostAddress())) {
					assertEquals(CVMLong.ONE,queryRoot(openClient));
					assertEquals(CVMLong.TWO,queryRoot(trustedClient));
				}
				assertTrue(network.removePropagator(trusted));
				try (var client=ConvexRemote.connect(second.getHostAddress())) {
					assertEquals(ErrorCodes.TRUST,pingError(client),"a removed group is unregistered from every listener");
				}
				network.stopIngress();
				assertFalse(network.getListener().isRunning());
				assertFalse(second.isRunning(),"stopping ingress closes every listener");
			}
		}
	}

	/** A listener added while running opens at once and can assign existing groups; one that cannot bind fails alone. */
	@Test void aListenerAddedWhileRunningOpensAtOnce() throws Exception {
		try (var store=new MemoryStore(); var occupied=new ServerSocket(0)) {
			var host=LatticeHost.create(CVMLong.ONE,store);
			LatticeListener added;
			try (var network=new LatticeNode<>(host,NodeConfig.port(-1)).launch()) {
				var group=network.createPropagator(LatticePropagatorConfig.create(),value -> CVMLong.TWO);
				assertThrows(IOException.class,() -> network.addListener(NodeConfig.port(occupied.getLocalPort())));
				added=network.addListener(NodeConfig.port(0));
				assertTrue(added.isRunning());
				added.setSelector(connection -> group);
				try (var client=ConvexRemote.connect(added.getHostAddress())) {
					assertEquals(CVMLong.TWO,queryRoot(client));
				}
			}
			assertFalse(added.isRunning(),"closing the node closes every listener");
		}
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
			var app=target.addApplication(new LatticeComponent<>(SetLattice.<CVMLong>create(),target.cursor().fork()));
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

	/** A node reached only by its address is admitted under the key it proves (#629). */
	@Test void nodeReachedByAddressIsAdmittedUnderTheKeyItProves() throws Exception {
		try (var serverStore=new MemoryStore(); var clientStore=new MemoryStore()) {
			var serverKey=AKeyPair.createSeeded(705);
			var clientKey=AKeyPair.createSeeded(706);
			try (var server=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,serverStore),NodeConfig.port(0)).launch();
					var client=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,clientStore),NodeConfig.port(-1)).launch()) {
				server.setIdentity(DID.forKey(serverKey.getAccountKey()),serverKey);
				server.serveAllInbound(server.createPropagator());
				client.setIdentity(DID.forKey(clientKey.getAccountKey()),clientKey);
				var manager=client.createPropagator().getConnectionManager();

				var peer=manager.connectPeer(Transports.endpoint(server.getHostAddress())).get(10,TimeUnit.SECONDS);
				assertEquals(serverKey.getAccountKey(),peer.getVerifiedPeer());
				assertSame(peer,manager.getConnection(serverKey.getAccountKey()));
				assertTrue(manager.getAuthenticatedRouteKeys().contains(serverKey.getAccountKey()));
			}
		}
	}

	/** A node named by a did:web is dialled at its document's lattice endpoint and must prove a key it lists. */
	@Test void nodeNamedByDIDIsAdmittedUnderAKeyItsDocumentLists() throws Exception {
		try (var serverStore=new MemoryStore(); var clientStore=new MemoryStore()) {
			var serverKey=AKeyPair.createSeeded(707);
			var clientKey=AKeyPair.createSeeded(708);
			try (var server=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,serverStore),NodeConfig.port(0)).launch();
					var client=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,clientStore),NodeConfig.port(-1)).launch()) {
				server.setIdentity(DID.forKey(serverKey.getAccountKey()),serverKey);
				server.serveAllInbound(server.createPropagator());
				client.setIdentity(DID.forKey(clientKey.getAccountKey()),clientKey);
				String did="did:web:files.example.com";
				String document="""
					{"id": "%1$s",
					 "verificationMethod": [{"id": "#node", "type": "Multikey", "controller": "%1$s",
					   "publicKeyMultibase": "%2$s"}],
					 "authentication": ["#node"],
					 "service": [{"id": "#lattice", "type": "ConvexLatticeNode", "serviceEndpoint": "%3$s"}]}
					""".formatted(did,Multikey.encodePublicKey(serverKey.getAccountKey()),
						Transports.endpoint(server.getHostAddress()));
				client.setPeerResolver(new PeerResolver(name -> CompletableFuture.completedFuture(
					DIDResolution.of(RT.ensureMap(JSON.parse(document)),null)),null));
				var group=client.createPropagator();

				var peer=client.connectPeer(group,PeerResolver.parse(did)).get(10,TimeUnit.SECONDS);
				assertEquals(serverKey.getAccountKey(),peer.getVerifiedPeer());
				var manager=group.getConnectionManager();
				assertSame(peer,manager.getConnection(serverKey.getAccountKey()));
				assertNull(manager.getDesiredPeers().get(serverKey.getAccountKey()).transports,"held, not redialled");
			}
		}
	}

	/** A node admitting challengers gets a route back to a node that dialled it, so its updates reach that node. */
	@Test void admittedChallengerReceivesTheDialledNodesUpdates() throws Exception {
		try (var servedStore=new MemoryStore(); var diallerStore=new MemoryStore()) {
			var servedHost=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),servedStore);
			var diallerHost=LatticeHost.<ASet<CVMLong>>create(Sets.empty(),diallerStore);
			var app=diallerHost.addApplication(new LatticeComponent<>(SetLattice.<CVMLong>create(),diallerHost.cursor().fork()));
			var servedKey=AKeyPair.createSeeded(707);
			var diallerKey=AKeyPair.createSeeded(708);
			try (var served=new LatticeNode<>(servedHost,NodeConfig.port(0)).launch();
					var dialler=new LatticeNode<>(diallerHost,NodeConfig.port(-1)).launch()) {
				served.setIdentity(DID.forKey(servedKey.getAccountKey()),servedKey);
				var servedGroup=served.createPropagator();
				served.serveAllInbound(servedGroup);
				servedGroup.setChallengerAdmission(key -> true);
				dialler.setIdentity(DID.forKey(diallerKey.getAccountKey()),diallerKey);
				var diallerGroup=dialler.createPropagator();
				var received=new CompletableFuture<Void>();
				diallerGroup.setInboundLatticeListener((connection,group,path,value,changed) -> {
					if (app.cursor().get().contains(CVMLong.ONE)) received.complete(null);
				});

				diallerGroup.getConnectionManager().connectPeer(Transports.endpoint(served.getHostAddress()))
					.get(10,TimeUnit.SECONDS);
				servedGroup.getConnectionManager().whenInboundConnectionUpgraded(diallerKey.getAccountKey())
					.get(10,TimeUnit.SECONDS);
				servedHost.cursor().set(Sets.of(1));
				servedHost.sync();
				received.get(10,TimeUnit.SECONDS);
				assertEquals(Sets.of(1),app.cursor().get());
			}
		}
	}

	/** Without admission, serving inbound gives a node that dialled in no route back. */
	@Test void servingInboundAloneGivesNoRouteBack() throws Exception {
		try (var servedStore=new MemoryStore(); var diallerStore=new MemoryStore()) {
			var servedKey=AKeyPair.createSeeded(709);
			var diallerKey=AKeyPair.createSeeded(710);
			try (var served=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,servedStore),NodeConfig.port(0)).launch();
					var dialler=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,diallerStore),NodeConfig.port(-1)).launch()) {
				served.setIdentity(DID.forKey(servedKey.getAccountKey()),servedKey);
				var servedGroup=served.createPropagator();
				served.serveAllInbound(servedGroup);
				dialler.setIdentity(DID.forKey(diallerKey.getAccountKey()),diallerKey);
				dialler.createPropagator().getConnectionManager()
					.connectPeer(Transports.endpoint(served.getHostAddress())).get(10,TimeUnit.SECONDS);
				// Admission is decided before the challenge is answered, so it would have begun by now
				assertTrue(servedGroup.getAuthenticatingInboundKeys().isEmpty());
				assertFalse(servedGroup.getConnectionManager().hasUpgradedInboundConnection(diallerKey.getAccountKey()));
			}
		}
	}

	/** Only a challenge naming this node, from a key the application admits, earns a challenge back. */
	@Test void onlyANamedChallengeFromAnAdmittedKeyIsChallengedBack() throws Exception {
		try (var store=new MemoryStore()) {
			var servedKey=AKeyPair.createSeeded(711);
			var admittedKey=AKeyPair.createSeeded(712);
			var otherKey=AKeyPair.createSeeded(713);
			try (var served=new LatticeNode<>(LatticeHost.create(CVMLong.ONE,store),NodeConfig.port(0)).launch()) {
				served.setIdentity(DID.forKey(servedKey.getAccountKey()),servedKey);
				var group=served.createPropagator();
				served.serveAllInbound(group);
				group.setChallengerAdmission(admittedKey.getAccountKey()::equals);
				var manager=group.getConnectionManager();
				var context=Message.LATTICE_PEER_CHALLENGE_CONTEXT;
				try (var admitted=ConvexRemote.connect(served.getHostAddress());
						var other=ConvexRemote.connect(served.getHostAddress())) {
					admitted.setKeyPair(admittedKey);
					other.setKeyPair(otherKey);

					assertEquals(servedKey.getAccountKey(),admitted.verifyPeer(null,context).get(10,TimeUnit.SECONDS));
					assertTrue(group.getAuthenticatingInboundKeys().isEmpty(),"a challenge naming no key admits nothing");
					assertFalse(manager.hasUpgradedInboundConnection(admittedKey.getAccountKey()));

					assertEquals(servedKey.getAccountKey(),other.verifyPeer(servedKey.getAccountKey(),context).get(10,TimeUnit.SECONDS));
					assertTrue(group.getAuthenticatingInboundKeys().isEmpty(),"a key the application refuses is not challenged back");
					assertFalse(manager.hasUpgradedInboundConnection(otherKey.getAccountKey()));

					assertEquals(servedKey.getAccountKey(),admitted.verifyPeer(servedKey.getAccountKey(),context).get(10,TimeUnit.SECONDS));
					manager.whenInboundConnectionUpgraded(admittedKey.getAccountKey()).get(10,TimeUnit.SECONDS);
					assertTrue(manager.getAuthenticatedRouteKeys().contains(admittedKey.getAccountKey()));
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

	/** Failed publication releases attachments; network close needs no working store. */
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
				server.close();
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
