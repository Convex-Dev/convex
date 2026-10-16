package convex.lattice;

import static convex.lattice.SharedApplicationsExample.ALICE;
import static convex.lattice.SharedApplicationsExample.BOB;
import static convex.lattice.SharedApplicationsExample.nodeInfo;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.lattice.SharedApplicationsExample.Node;
import convex.social.Social;

/** What an embedder can rely on; {@link SharedApplicationsExample} assembles the nodes. */
public class SharedApplicationsTest {
	private static final AKeyPair SOURCE_NODE=AKeyPair.createSeeded(7304);
	private static final AKeyPair TARGET_NODE=AKeyPair.createSeeded(7305);
	private static final String TEXT="A lattice-backed file.\n".repeat(500); // Large enough to replicate as a separate blob.

	@Test void theRunnableExampleTellsItsStory() throws Exception {
		var output=new ByteArrayOutputStream();
		SharedApplicationsExample.run(new PrintStream(output,true,StandardCharsets.UTF_8));
		String story=output.toString(StandardCharsets.UTF_8);
		assertTrue(story.contains("Target has Alice's post: true"),story);
		assertTrue(story.contains("Target's pending post is still private: true"),story);
		assertTrue(story.contains("Target has Bob's file: Hello from Bob"),story);
		assertTrue(story.contains("Alice's feed has 2 posts"),story);
	}

	@Test void alicesPostAndBobsFileReachTheOtherNode() throws Exception {
		try (var sourceStore=EtchStore.createTemp("shared-apps-source");
				var targetStore=EtchStore.createTemp("shared-apps-target");
				var source=Node.open(sourceStore,SOURCE_NODE,100);
				var target=Node.open(targetStore,TARGET_NODE,200)) {
			Blob post=source.social.feed(ALICE).post("Hello from Social + P2P");
			Files.writeString(source.files.drives(BOB.getAccountKey()).createDrive("home")
				.fileSystem().getPath("/hello.txt"),TEXT);
			source.social.p2p(ALICE).node().setNodeInfo(nodeInfo("social node",100));
			assertNull(source.root().get(Social.KEY_SOCIAL),"staged edits are not in the host until published");
			source.publish();

			target.pullFrom(source);
			// One incoming root, two receivers: each application admitted its own region.
			assertEquals(source.root(),target.root());
			assertNotNull(target.social.publishedFeed(ALICE).getPost(post));
			assertEquals(TEXT,Files.readString(target.files.dlfs().drives(BOB.getAccountKey())
				.drive("home").fileSystem().getPath("/hello.txt")));
			assertNotNull(target.social.p2p(ALICE).node().getNodeInfo());
		}
	}

	@Test void pendingEditsStayPrivateUntilTheirOwnerPublishes() throws Exception {
		try (var sourceStore=EtchStore.createTemp("shared-apps-source");
				var targetStore=EtchStore.createTemp("shared-apps-target");
				var source=Node.open(sourceStore,SOURCE_NODE,100);
				var target=Node.open(targetStore,TARGET_NODE,200)) {
			Blob remotePost=source.social.feed(ALICE).post("Published elsewhere");
			Files.writeString(source.files.drives(BOB.getAccountKey()).createDrive("home")
				.fileSystem().getPath("/hello.txt"),TEXT);
			source.publish();

			// Local edits on the target, not yet published.
			var feed=target.social.feed(ALICE);
			Blob localPost=feed.post("Not published yet");
			var localDrive=target.files.drives(BOB.getAccountKey()).createDrive("home");
			Files.writeString(localDrive.fileSystem().getPath("/local.txt"),"A pending local file");

			target.pullFrom(source);
			var published=target.social.publishedFeed(ALICE);
			assertNotNull(published.getPost(remotePost),"the incoming post is in the host");
			assertNull(published.getPost(localPost),"the pending post is not");
			assertNull(feed.getPost(remotePost),"the staged feed has not reconciled yet");
			assertFalse(Files.exists(localDrive.fileSystem().getPath("/hello.txt")),"nor has the staged drive");

			// Each owner reconciles at its own boundary. Publishing one region leaves the other alone.
			ACell receivedFiles=target.root().get(Keywords.FS);
			target.social.sync();
			assertEquals(2,feed.count(),"the staged feed now holds both posts");
			assertNotNull(published.getPost(localPost));
			assertEquals(receivedFiles,target.root().get(Keywords.FS));
			target.files.sync();
			assertEquals(TEXT,Files.readString(localDrive.fileSystem().getPath("/hello.txt")));
			assertEquals("A pending local file",Files.readString(localDrive.fileSystem().getPath("/local.txt")));

			// The reconciled regions are signed by their owners, not by the host or node.
			SignedData<?> signedSocial=RT.getIn(target.root(),Social.KEY_SOCIAL,DID.forKey(ALICE.getAccountKey()));
			SignedData<?> signedFiles=RT.getIn(target.root(),Keywords.FS,BOB.getAccountKey());
			assertTrue(signedSocial.checkSignature());
			assertTrue(signedFiles.checkSignature());
			assertNull(target.host.getContext().getSigningKey());
		}
	}

	@Test void applicationsShareP2PLiveAndNeverDisturbEachOthersRegions() throws Exception {
		try (var store=EtchStore.createTemp("shared-apps-single");
				var node=Node.open(store,SOURCE_NODE,100)) {
			node.social.feed(ALICE).post("A post");
			Files.writeString(node.files.drives(BOB.getAccountKey()).createDrive("home")
				.fileSystem().getPath("/hello.txt"),"A file");
			node.files.p2p(BOB).node().setNodeInfo(nodeInfo("files node",100));
			assertNotNull(node.social.p2p(BOB).node().getNodeInfo(),"the P2P region is live and shared");
			assertNull(node.root().get(Social.KEY_SOCIAL));
			assertNull(node.root().get(Keywords.FS));

			node.social.sync();
			ACell social=node.root().get(Social.KEY_SOCIAL);
			assertNotNull(social);
			assertNull(node.root().get(Keywords.FS),"publishing the social region publishes nothing else");
			node.files.sync();
			assertEquals(social,node.root().get(Social.KEY_SOCIAL),"publishing files leaves social untouched");
			assertNotNull(node.root().get(Keywords.FS));
			node.social.sync();
			assertNotNull(node.root().get(Keywords.FS),"and vice versa");
		}
	}

	@Test void everythingSurvivesReopeningTheStore() throws Exception {
		File file;
		Blob post;
		try (var store=EtchStore.createTemp("shared-apps-reopen");
				var node=Node.open(store,SOURCE_NODE,100)) {
			file=store.getFile();
			post=node.social.feed(ALICE).post("Durable");
			Files.writeString(node.files.drives(BOB.getAccountKey()).createDrive("home")
				.fileSystem().getPath("/hello.txt"),TEXT);
			node.social.p2p(ALICE).node().setNodeInfo(nodeInfo("durable node",100));
			node.publish();
			node.flush();
		}
		try (var store=EtchStore.create(file);
				var node=Node.open(store,SOURCE_NODE,300)) {
			assertNotNull(node.social.feed(ALICE).getPost(post));
			assertEquals(TEXT,Files.readString(node.files.drives(BOB.getAccountKey())
				.drive("home").fileSystem().getPath("/hello.txt")));
			assertNotNull(node.files.p2p(ALICE).node().getNodeInfo());
		}
	}
}
