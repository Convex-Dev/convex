package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.etch.EtchStore;
import convex.lattice.fs.DLFSLocation;
import convex.lattice.generic.KeyedLattice;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.p2p.P2PLattice;
import convex.p2p.P2PUser;
import convex.social.Social;

/** Social + P2P and files + P2P are separate applications on one durable, networked host. */
public class SharedApplicationsTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7301);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7302);
	private static final AKeyPair SOURCE_NODE=AKeyPair.createSeeded(7304);
	private static final AKeyPair TARGET_NODE=AKeyPair.createSeeded(7305);
	// Each application declares the regions it understands: the shared P2P regions plus its own.
	private static final KeyedLattice SOCIAL_ROOT=P2PLattice.ROOT
		.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
	private static final KeyedLattice FILES_ROOT=P2PLattice.ROOT
		.addLattice(Keywords.FS,DLFSRegion.LATTICE);
	// The propagator still needs one lattice covering everything the node serves.
	private static final KeyedLattice SERVED=SOCIAL_ROOT.addLattice(Keywords.FS,DLFSRegion.LATTICE);
	private static final LatticeContext VERIFY=LatticeContext.EMPTY.withOwnerVerifier(
		(owner,signer) -> owner.equals(DID.forKey(signer)));

	/** Live at the root for incoming values and P2P; the social region is staged. */
	static final class SocialApp extends ALatticeApplication<Index<Keyword,ACell>> {
		private final Social social;

		SocialApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair user,long timestamp) {
			super(SOCIAL_ROOT,host.cursor());
			setContext(VERIFY.withSigningKey(user).withTimestamp(CVMLong.create(timestamp)));
			social=Social.connect(this).fork();
		}
		Social social() { return social; }
		P2PUser p2p(AccountKey owner) { return P2PUser.create(this,owner); }
		/** Publishes the staged social region; the live root itself has nothing to publish. */
		@Override public Index<Keyword,ACell> sync() { social.sync(); return super.sync(); }
	}

	/** Live at the root for incoming values and P2P; the files region is staged. */
	static final class FilesApp extends ALatticeApplication<Index<Keyword,ACell>> {
		private final DLFSRegion live;
		private final DLFSRegion files;

		FilesApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair user,long timestamp) {
			super(FILES_ROOT,host.cursor());
			setContext(VERIFY.withSigningKey(user).withTimestamp(CVMLong.create(timestamp)));
			// The store is for large-file blob checkpoints, not root publication.
			live=DLFSRegion.connect(resolve(Keywords.FS),host.store(),DLFSLocation.at(cursor,Keywords.FS));
			live.setContext(getContext());
			files=live.fork();
		}
		DLFSRegion live() { return live; }
		DLFSDrives drives(AccountKey owner) { return files.drives(owner); }
		P2PUser p2p(AccountKey owner) { return P2PUser.create(this,owner); }
		@Override public Index<Keyword,ACell> sync() { files.sync(); return super.sync(); }
	}

	/** Assembly and resource ownership stay outside the domain applications. */
	static final class RunningApps implements AutoCloseable {
		final EtchStore store;
		final EtchStore servingStore;
		final LatticeHost<Index<Keyword,ACell>> host;
		final SocialApp social;
		final FilesApp files;
		final NodeServer<Index<Keyword,ACell>> node;
		final LatticePropagator propagation;
		final LatticeListener listener;

		RunningApps(AKeyPair transportKey,long timestamp) throws Exception {
			store=EtchStore.createTemp("shared-app-root");
			servingStore=EtchStore.createTemp("shared-app-serving");
			host=LatticeHost.open(store);
			host.setContext(VERIFY);
			social=new SocialApp(host,ALICE,timestamp);
			files=new FilesApp(host,BOB,timestamp);
			// The host hands every incoming value to both applications. Each merges the
			// regions its lattice declares into the shared root and leaves the rest alone.
			host.configureApplications(List.of(social,files));

			var config=NodeConfig.localNetwork(); // Ephemeral port; no background discovery required.
			node=new NodeServer<>(host,config);
			propagation=new LatticePropagator(servingStore,SERVED,value -> value);
			propagation.setMergeContext(VERIFY);
			propagation.setTransportKeyPair(transportKey);
			node.addPropagator(propagation);
			listener=new LatticeListener(config);
			listener.registerPropagator(propagation);
			listener.setSelector(connection -> propagation);
			node.launch();
			listener.launch();
		}

		@Override public void close() throws Exception {
			try {
				listener.close();
			} finally {
				try { node.close(); }
				finally {
					servingStore.close();
					store.close();
				}
			}
		}
	}

	@Test void domainEditsPropagateThroughApplicationsAndSurviveReopening() throws Exception {
		Blob remotePost;
		Blob remoteUpdate;
		Blob localPost;
		File retained;
		String text="A lattice-backed file.\n".repeat(500); // Exercise non-embedded blob acquisition.
		try (var source=new RunningApps(SOURCE_NODE,100);
				var target=new RunningApps(TARGET_NODE,200)) {
			retained=target.store.getFile();
			remotePost=source.social.social().user(ALICE.getAccountKey()).feed().post("Hello from Social + P2P");
			source.social.p2p(ALICE.getAccountKey()).node().setNodeInfo(
				convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),Strings.create("social node"),
					Strings.create("example"),Vectors.empty(),100));
			var drive=source.files.drives(BOB.getAccountKey()).createDrive("home");
			Files.writeString(drive.fileSystem().getPath("/hello.txt"),text);
			source.files.p2p(BOB.getAccountKey()).node().setNodeInfo(
				convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),Strings.create("files node"),
					Strings.create("example"),Vectors.empty(),100));
			assertNull(source.host.cursor().get().get(Social.KEY_SOCIAL));
			assertNull(source.host.cursor().get().get(Keywords.FS));
			assertNotNull(source.social.p2p(BOB.getAccountKey()).node().getNodeInfo(),"P2P is shared immediately");
			assertNotNull(source.files.p2p(ALICE.getAccountKey()).node().getNodeInfo());

			source.social.sync();
			ACell publishedSocial=source.host.cursor().get().get(Social.KEY_SOCIAL);
			assertNotNull(publishedSocial);
			assertNull(source.host.cursor().get().get(Keywords.FS));
			source.files.sync();
			assertEquals(publishedSocial,source.host.cursor().get().get(Social.KEY_SOCIAL),"Files cannot replace Social");
			source.host.sync();
			awaitPublished(source.propagation,source.host.cursor().get());

			// Pending local edits remain private while the root accepts a remote snapshot.
			var feed=target.social.social().user(ALICE.getAccountKey()).feed();
			localPost=feed.post("An unpublished local post");
			assertNotEquals(remotePost,localPost,"Independent writers deliberately use distinct post timestamps");
			var localDrive=target.files.drives(BOB.getAccountKey()).createDrive("home");
			var localFileSystem=localDrive.fileSystem();
			Files.writeString(localFileSystem.getPath("/local.txt"),"A pending local file");
			var liveFeed=Social.connect(target.social).user(ALICE.getAccountKey()).feed();
			var liveFiles=target.files.live();
			var livePeer=target.files.p2p(ALICE.getAccountKey()).node();
			assertNull(liveFeed.getPost(localPost));
			assertNull(livePeer.getNodeInfo());

			try (var peer=ConvexRemote.connect(source.listener.getHostAddress())) {
				target.propagation.addPeer(SOURCE_NODE.getAccountKey(),peer).get(10,TimeUnit.SECONDS);
				target.node.pull(target.propagation,peer).get(10,TimeUnit.SECONDS);
				// Each application admitted its own region from the one incoming root;
				// together they reproduce the source root without a composed receiver.
				assertNotNull(target.host.cursor().get().get(Social.KEY_SOCIAL),"the social application admitted :social");
				assertNotNull(target.host.cursor().get().get(Keywords.FS),"the files application admitted :fs");
				assertEquals(source.host.cursor().get(),target.host.cursor().get());

				// The next envelope contains the first post plus another: its signature is reusable.
				remoteUpdate=source.social.social().user(ALICE.getAccountKey()).feed().post("A second published post");
				source.social.sync();
				source.host.sync();
				awaitPublished(source.propagation,source.host.cursor().get());
				target.node.pull(target.propagation,peer).get(10,TimeUnit.SECONDS);
			}
			assertEquals(source.host.cursor().get(),target.host.cursor().get());
			assertNotNull(liveFeed.getPost(remotePost),"Existing live views observe the incoming root");
			assertNotNull(liveFeed.getPost(remoteUpdate),"A signer-free root can select the incoming signed envelope");
			assertNotNull(livePeer.getNodeInfo());
			assertEquals(text,Files.readString(liveFiles.drives(BOB.getAccountKey())
				.drive("home").fileSystem().getPath("/hello.txt")));
			assertNull(feed.getPost(remotePost),"Staged components choose when to reconcile");
			assertFalse(Files.exists(localFileSystem.getPath("/hello.txt")));
			assertNull(liveFeed.getPost(localPost));

			// Each owner reconciles and signs at its own boundary; neither overwrites siblings.
			ACell receivedFiles=target.host.cursor().get().get(Keywords.FS);
			target.social.sync();
			assertNotNull(feed.getPost(remotePost));
			assertNotNull(feed.getPost(remoteUpdate));
			assertEquals(3,feed.count());
			assertNotNull(liveFeed.getPost(localPost));
			assertEquals(receivedFiles,target.host.cursor().get().get(Keywords.FS));
			ACell combinedSocial=target.host.cursor().get().get(Social.KEY_SOCIAL);
			target.files.sync();
			assertEquals(combinedSocial,target.host.cursor().get().get(Social.KEY_SOCIAL));
			assertEquals(text,Files.readString(localFileSystem.getPath("/hello.txt")));
			assertEquals("A pending local file",Files.readString(localFileSystem.getPath("/local.txt")));
			SignedData<?> signedSocial=RT.getIn(target.host.cursor().get(),Social.KEY_SOCIAL,DID.forKey(ALICE.getAccountKey()));
			SignedData<?> signedFiles=RT.getIn(target.host.cursor().get(),Keywords.FS,BOB.getAccountKey());
			assertTrue(signedSocial.checkSignature());
			assertTrue(signedFiles.checkSignature());
			assertNull(target.host.getContext().getSigningKey());
			target.host.sync();
			target.host.flush();
			assertEquals(target.host.cursor().get(),target.store.getRootData());
		}

		try (var store=EtchStore.create(retained)) {
			var host=LatticeHost.open(store);
			var social=new SocialApp(host,ALICE,300);
			var files=new FilesApp(host,BOB,300);
			assertNotNull(social.social().user(ALICE.getAccountKey()).feed().getPost(remotePost));
			assertNotNull(social.social().user(ALICE.getAccountKey()).feed().getPost(remoteUpdate));
			assertNotNull(social.social().user(ALICE.getAccountKey()).feed().getPost(localPost));
			var fileSystem=files.drives(BOB.getAccountKey()).drive("home").fileSystem();
			assertEquals(text,Files.readString(fileSystem.getPath("/hello.txt")));
			assertEquals("A pending local file",Files.readString(fileSystem.getPath("/local.txt")));
			assertNotNull(social.p2p(BOB.getAccountKey()).node().getNodeInfo());
			assertNotNull(files.p2p(ALICE.getAccountKey()).node().getNodeInfo());
		}
	}

	/** Await a materialised serving view, including any earlier queued announcement. */
	private static void awaitPublished(LatticePropagator group,ACell expected) throws Exception {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while (true) {
			var next=group.nextAnnounce();
			if (expected.equals(group.getLastAnnouncedValue())) return;
			next.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
		}
	}
}
