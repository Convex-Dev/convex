package convex.lattice;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.auth.did.DIDKeyAuthorizer;
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
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSRegion;
import convex.etch.EtchStore;
import convex.lattice.generic.KeyedLattice;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.p2p.P2PLattice;
import convex.p2p.P2PUser;
import convex.social.Feed;
import convex.social.Social;

/**
 * Two applications on one host: a social feed and a file store.
 *
 * <p>Each application declares its own lattice, stages its own region and publishes
 * when it chooses. Both share the P2P regions live, and both receive incoming values
 * from the host, each merging the regions it understands. A node wraps the host with
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

	/** Each application declares the regions it understands: the shared P2P regions plus its own. */
	static final KeyedLattice SOCIAL_ROOT=P2PLattice.ROOT
		.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
	static final KeyedLattice FILES_ROOT=P2PLattice.ROOT
		.addLattice(Keywords.FS,DLFSRegion.LATTICE);

	/** Signs as {@code user} at a fixed logical time, verifying owners by the standard did:key rule. */
	static LatticeContext policy(AKeyPair user,long time) {
		return LatticeContext.create(CVMLong.create(time),user,DIDKeyAuthorizer.CONVEX::verifiesOwner);
	}

	/** A node record for the P2P region. */
	static AHashMap<Keyword,ACell> nodeInfo(String name,long time) {
		return convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),
			Strings.create(name),Strings.create("example"),Vectors.empty(),time);
	}

	/** Social feeds, staged at {@code :social}. The P2P regions are shared live. */
	public static final class SocialApp extends ALatticeApplication<Index<Keyword,ACell>> {
		private final Social social;

		SocialApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair user,long time) {
			super(SOCIAL_ROOT,host.cursor());
			setContext(policy(user,time));
			social=Social.connect(this).fork();
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
			return P2PUser.create(this,user.getAccountKey());
		}

		@Override protected void beforeSync() {
			social.sync();
		}
	}

	/** Files, staged at {@code :fs}. The P2P regions are shared live. */
	public static final class FilesApp extends DLFSApplication<Index<Keyword,ACell>> {
		private final DLFSRegion staged;

		FilesApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair user,long time) {
			super(FILES_ROOT,host.cursor(),host.store(),Keywords.FS);
			setContext(policy(user,time));
			staged=dlfs().fork();
		}

		/** Drives come from the staged region; edits stay private until {@link #sync()}. */
		@Override protected DLFSRegion region() {
			return staged;
		}

		public P2PUser p2p(AKeyPair user) {
			return P2PUser.create(this,user.getAccountKey());
		}

		@Override protected void beforeSync() {
			staged.sync();
		}
	}

	/**
	 * A node running both applications over one store and replicating with peers.
	 * The caller owns the store, so it can be reopened later; the node owns the rest.
	 */
	public static final class Node implements AutoCloseable {
		public final LatticeHost<Index<Keyword,ACell>> host;
		public final SocialApp social;
		public final FilesApp files;
		private final NodeServer<Index<Keyword,ACell>> server;
		private final LatticePropagator propagation;
		private final LatticeListener listener;
		private final EtchStore servingStore;
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
			social=new SocialApp(host,ALICE,time);
			files=new FilesApp(host,BOB,time);
			// The host hands every incoming value to both applications. Each merges the
			// regions its lattice declares into the shared root and leaves the rest alone.
			host.configureApplications(List.of(social,files));

			var config=NodeConfig.localNetwork();
			server=new NodeServer<>(host,config);
			// Replication knows no lattice: it serves whatever the host publishes.
			servingStore=EtchStore.createTemp("shared-apps-serving");
			propagation=new LatticePropagator(servingStore);
			propagation.setTransportKeyPair(nodeKey);
			server.addPropagator(propagation);
			listener=new LatticeListener(config);
			listener.registerPropagator(propagation);
			listener.setSelector(connection -> propagation);
			server.launch();
			listener.launch();
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
			try (Convex connection=ConvexRemote.connect(peer.listener.getHostAddress())) {
				propagation.addPeer(peer.nodeKey.getAccountKey(),connection).get(10,TimeUnit.SECONDS);
				server.pull(propagation,connection).get(10,TimeUnit.SECONDS);
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
			try {
				listener.close();
			} finally {
				try { server.close(); }
				finally { servingStore.close(); }
			}
		}
	}

	/** Alice posts and Bob files on one node; a second node pulls and reconciles its own pending edits. */
	public static void main(String... args) throws Exception {
		run(System.out);
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
			out.println("Target has Alice's post: "+(target.social.publishedFeed(ALICE).getPost(post)!=null));
			out.println("Target's pending post is still private: "+(target.social.publishedFeed(ALICE).getPost(pending)==null));
			out.println("Target has Bob's file: "+Files.readString(target.files.dlfs()
				.drives(BOB.getAccountKey()).drive("home").fileSystem().getPath("/hello.txt")));

			target.publish();
			out.println("After the target publishes, Alice's feed has "
				+target.social.publishedFeed(ALICE).count()+" posts");
		}
	}
}
