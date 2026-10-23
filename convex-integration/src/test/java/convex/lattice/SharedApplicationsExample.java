package convex.lattice;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.auth.did.DIDKeyAuthorizer;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.Shutdown;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSRegion;
import convex.etch.EtchStore;
import convex.node.LatticeNode;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.p2p.P2PLattice;
import convex.p2p.P2PUser;
import convex.social.Feed;
import convex.social.Social;

/**
 * Two domain applications on one host: a social feed and a file store, sharing P2P infrastructure.
 *
 * <p>Each application registers its domain component, stages its own region and publishes
 * when it chooses. Both use shared live P2P infrastructure. Incoming values go to the registered
 * components, which merge the regions they understand. A node wraps the host with
 * replication so that other nodes can pull the root.</p>
 *
 * <p>Run {@link #main} to watch the story unfold; {@link SharedApplicationsTest}
 * asserts each part of it.</p>
 */
public final class SharedApplicationsExample {
	private SharedApplicationsExample() {}

	/** Alice posts and Bob keeps files. Both have a session on every node. */
	public static final AKeyPair ALICE=AKeyPair.createSeeded(7301);
	public static final AKeyPair BOB=AKeyPair.createSeeded(7302);

	/** A node record for the P2P region. */
	static AHashMap<Keyword,ACell> nodeInfo(String name,long time) {
		return convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),
			Strings.create(name),Strings.create("example"),Vectors.empty(),time);
	}

	/** Social feeds, staged at {@code :social}. The P2P regions are shared live. */
	public static final class SocialApp extends AKeyedRegions {
		private final Social social;

		SocialApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair user,long time) {
			super(host);
			social=region(Social.KEY_SOCIAL,Social.connect(cursor().path(Social.KEY_SOCIAL),
				DIDKeyAuthorizer.CONVEX,(owner,value) -> user.signData(value),() -> CVMLong.create(time)).fork());
		}

		/** A user's feed in the staged region; edits stay private until {@link #sync()}. */
		public Feed feed(AKeyPair user) {
			return social.user(user.getAccountKey()).feed();
		}

		/** The same user's feed as the host currently holds it. */
		public Feed publishedFeed(AKeyPair user) {
			return Social.connect(this).user(user.getAccountKey()).feed();
		}

		public P2PUser p2p(AKeyPair user) {
			return P2PUser.create(this,user.getAccountKey(),(owner,value) -> user.signData(value));
		}
	}

	/**
	 * A node running both applications over one store and replicating with peers.
	 * The caller owns the store, so it can be reopened later; the node owns the rest.
	 */
	public static final class Node implements AutoCloseable {
		public final LatticeHost<Index<Keyword,ACell>> host;
		public final SocialApp social;
		public final DLFSApplication files;
		private final LatticeNode<Index<Keyword,ACell>> network;
		private final LatticePropagator propagation;
		private final AKeyPair nodeKey;

		/**
		 * Opens a node over a store, restoring whatever the store retained. Writes on
		 * this node carry {@code time} as their logical timestamp, which keeps the story
		 * deterministic; a real deployment uses the clock.
		 */
		public static Node open(EtchStore store,AKeyPair nodeKey,long time) throws Exception {
			return new Node(store,nodeKey,time);
		}

		private Node(EtchStore store,AKeyPair nodeKey,long time) throws Exception {
			this.nodeKey=nodeKey;
			host=LatticeHost.open(store);
			// Infrastructure is registered once; both domain applications use its live state.
			host.addApplication(LatticeApplication.live(P2PLattice.ROOT,host));
			social=host.addApplication(new SocialApp(host,ALICE,time));
			var region=DLFSRegion.connect(host.cursor().path(Keywords.FS),store,
				BOB::signData,() -> CVMLong.create(time));
			// Choose a private publication boundary explicitly; the application retains it.
			files=host.addApplication(new DLFSApplication(host,Keywords.FS,region.fork()));

			var config=NodeConfig.localNetwork();
			// Replication knows no lattice: it serves whatever the host publishes, from the host's store.
			network=new LatticeNode<>(host,config);
			network.setIdentity(DID.forKey(nodeKey.getAccountKey()),nodeKey);
			propagation=network.createPropagator();
			network.serveAllInbound(propagation).launch();
		}

		/** The root value this node's host currently holds. */
		public Index<Keyword,ACell> root() {
			return host.cursor().get();
		}

		/** Publishes both applications into the host, persists the root and serves it to peers. */
		public void publish() throws Exception {
			social.sync();
			files.sync();
			host.sync();
			awaitServed();
		}

		/** Pulls the peer's served root; the host hands it to both applications. */
		public void pullFrom(Node peer) throws Exception {
			try (Convex connection=ConvexRemote.connect(peer.network.getHostAddress())) {
				propagation.addPeer(peer.nodeKey.getAccountKey(),connection).get(10,TimeUnit.SECONDS);
				network.getServer().pull(propagation,connection).get(10,TimeUnit.SECONDS);
			}
		}

		/** Makes the published root durable. */
		public void flush() throws IOException {
			host.flush();
		}

		private void awaitServed() throws Exception {
			ACell expected=host.cursor().get();
			long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
			while (true) {
				var next=propagation.nextAnnounce();
				if (expected.equals(propagation.getLastAnnouncedValue())) return;
				next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
			}
		}

		@Override public void close() throws Exception {
			network.close();
		}
	}

	/** Alice posts and Bob files on one node; a second node pulls and reconciles its own pending edits. */
	public static void main(String... args) throws Exception {
		try {
			run(System.out);
		} finally {
			// Only the executable owns process-wide services such as Netty's shared acceptor.
			Shutdown.shutdownNow();
		}
	}

	/** The story behind {@link #main}, writing each step to {@code out}. */
	static void run(PrintStream out) throws Exception {
		try (var sourceStore=EtchStore.createTemp("shared-apps-source");
				var targetStore=EtchStore.createTemp("shared-apps-target");
				var source=Node.open(sourceStore,AKeyPair.generate(),100);
				var target=Node.open(targetStore,AKeyPair.generate(),200)) {
			Blob post=source.social.feed(ALICE).post("Hello from Alice");
			Files.writeString(source.files.drives(BOB.getAccountKey()).createDrive("home")
				.fileSystem().getPath("/hello.txt"),"Hello from Bob");
			source.social.p2p(ALICE).node().setNodeInfo(nodeInfo("alice's node",100));
			out.println("Before publish the source host holds only the live P2P region: "+source.root().keySet());
			source.publish();
			out.println("After publish it holds: "+source.root().keySet());

			Blob pending=target.social.feed(ALICE).post("Pending on the target");
			target.pullFrom(source);
			out.println("Target has Alice's post: "+(target.social.feed(ALICE).getPost(post)!=null));
			out.println("Target's pending post is still private: "+(target.social.publishedFeed(ALICE).getPost(pending)==null));
			out.println("Target has Bob's file: "+Files.readString(target.files.drives(BOB.getAccountKey())
				.drive("home").fileSystem().getPath("/hello.txt")));

			target.publish();
			out.println("After the target publishes, Alice's feed has "
				+target.social.publishedFeed(ALICE).count()+" posts");
		}
	}
}
