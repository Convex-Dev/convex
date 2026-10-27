package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.lattice.ALattice;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SignedLattice;
import convex.node.LatticeFilter;
import convex.node.LatticeListener;
import convex.node.LatticePropagator;
import convex.node.LatticePropagatorConfig;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import convex.node.RelayHost;
import convex.p2p.P2PApplication;
import convex.p2p.P2PLattice;
import convex.p2p.P2PNodeRecord;
import convex.p2p.P2PUser;
import convex.social.Social;
import convex.social.SocialPost;
import convex.social.SocialUser;

/**
 * Two compound applications, Social + P2P and DLFS + P2P, share one host.
 * Uses explicit region assembly and propagation groups: application subscription
 * registration and automatic interest lifecycle are not implemented yet.
 */
public class SharedApplicationInternalsTest {

	private static final AKeyPair SOCIAL_KEY=AKeyPair.createSeeded(7301);
	private static final AKeyPair FILES_KEY=AKeyPair.createSeeded(7302);
	private static final AKeyPair COMMON_KEY=AKeyPair.createSeeded(7303);
	private static final AKeyPair SOCIAL_TRANSPORT=AKeyPair.createSeeded(7304);
	private static final AKeyPair FILES_TRANSPORT=AKeyPair.createSeeded(7305);
	private static final Keyword SOCIAL_FLAG=Keyword.intern("social-enabled");
	private static final Keyword FILES_FLAG=Keyword.intern("files-enabled");

	private static final KeyedLattice SOCIAL_ROOT=P2PLattice.ROOT
		.addLattice(Social.KEY_SOCIAL,Social.SOCIAL_LATTICE);
	private static final KeyedLattice FILES_ROOT=P2PLattice.ROOT
		.addLattice(Keywords.FS,DLFSRegion.LATTICE);
	private static final KeyedLattice HOST_ROOT=SOCIAL_ROOT
		.addLattice(Keywords.FS,DLFSRegion.LATTICE);
	/** The application's owner rule decides for every owner: keys and did:key DIDs, each signed by its own key. */
	private static final LatticeContext VERIFY=LatticeContext.EMPTY.withOwnerVerifier(
		(owner,signer) -> owner.equals(signer) || owner.equals(DID.forKey(signer)));

	/** A live view of the whole host root, as a root application holds it. */
	private static LatticeApplication<Index<Keyword,ACell>> view(LatticeHost<Index<Keyword,ACell>> host) {
		return LatticeApplication.live(HOST_ROOT,host);
	}

	/** Applications choose working-state policy; ordinary domain views need no fork. */
	private static final class WorkingApplication extends LatticeApplication<Index<Keyword,ACell>> {
		CVMLong timestamp=CVMLong.create(100);
		final SignedComponent<Index<Keyword,ACell>> socialState;
		final SignedComponent<AHashMap<AString,AVector<ACell>>> fileState;
		final SignedComponent<ACell> nodeState;
		final SocialUser social;
		final DLFSDrives files;
		final P2PNodeRecord node;

		WorkingApplication(LatticeHost<Index<Keyword,ACell>> host,boolean socialApp,Runnable signing) {
			super(HOST_ROOT,host);
			setContext(VERIFY);
			if (socialApp) {
				var policy=context(SOCIAL_KEY,signing);
				socialState=owned(host,policy,Social.KEY_SOCIAL,DID.forKey(SOCIAL_KEY.getAccountKey()));
				social=Social.connect(cursor().path(Social.KEY_SOCIAL),convex.auth.did.DIDKeyAuthorizer.CONVEX,
					(owner,value) -> policy.signAs(owner,value),() -> timestamp).user(SOCIAL_KEY.getAccountKey()).withCursor(socialState.cursor());
				files=null;
				fileState=null;
			} else {
				fileState=owned(host,context(FILES_KEY,signing),Keywords.FS,FILES_KEY.getAccountKey());
				files=convex.dlfs.DLFSDrives.wrap(fileState.cursor(),() -> CVMLong.create(100));
				social=null;
				socialState=null;
			}
			AccountKey nodeOwner=(socialApp?COMMON_KEY:FILES_KEY).getAccountKey();
			nodeState=owned(host,context(socialApp?SOCIAL_KEY:FILES_KEY,signing),Keywords.P2P,Keywords.NODES,nodeOwner);
			node=P2PUser.create(this,nodeOwner).node().withCursor(nodeState.cursor());
		}

		@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
			return cursor().updateAndGet(own -> getLattice().merge(getContext(),own,other));
		}

		/**
		 * Each owned subtree publishes through its own fork of the host, beside this
		 * application's staged root. Its fork reconciles only that subtree, so the
		 * root's merge never has to ask which subtrees this application touched.
		 */
		@SuppressWarnings("unchecked")
		private <T extends ACell> SignedComponent<T> owned(LatticeHost<Index<Keyword,ACell>> host,
				LatticeContext context,ACell... path) {
			ALattice<?> lattice=getLattice();
			for (ACell key:path) lattice=lattice.path(key);
			var rule=(SignedLattice<T>)lattice;
			// This legacy application's resolver supplies both the public owner rule
			// and an explicit signer; the draft itself does not look up a context.
			return new SignedComponent<>(rule.withOwner(rule.getOwner(),context::verifyOwner),
				LatticeComponent.<SignedData<T>>resolve(host.cursor(),getLattice(),path),
				value -> context.signAs(rule.getOwner(),value));
		}

		@Override protected void syncChildren() {
			if (socialState!=null) socialState.sync();
			if (fileState!=null) fileState.sync();
			nodeState.sync();
		}
	}

	@Test
	public void testIndependentSocialApplicationsPublishBothOwnersPosts() {
		var host=LatticeHost.create(HOST_ROOT.zero(),new MemoryStore());
		AtomicInteger signatures=new AtomicInteger();
		var first=new WorkingApplication(host,true,signatures::incrementAndGet);
		var second=new WorkingApplication(host,true,signatures::incrementAndGet);
		// Distinct post keys are application policy; both applications author the
		// same signed owner subtree through separate working states.
		second.timestamp=CVMLong.create(200);
		// The supplied clock reads this application's timestamp dynamically.
		Blob a=first.social.feed().post("First application");
		Blob b=second.social.feed().post("Second application");
		first.sync();
		second.sync();
		var published=Social.connect(view(host)).user(SOCIAL_KEY.getAccountKey()).feed();
		assertNotNull(published.getPost(a));
		assertNotNull(published.getPost(b));
		assertEquals(2,published.count());
		assertEquals(2,signatures.get(),"The first envelope, then one combined signature");
	}

	@Test
	public void testWorkingApplicationsPublishSocialFilesAndSharedP2PRegion() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(HOST_ROOT.zero(),store);
		AtomicInteger signatures=new AtomicInteger();
		var socialApp=new WorkingApplication(host,true,signatures::incrementAndGet);
		var filesApp=new WorkingApplication(host,false,signatures::incrementAndGet);
		Blob post=socialApp.social.feed().post("Private application working state");
		socialApp.social.follows().follow(FILES_KEY.getAccountKey());
		var drive=filesApp.files.createDrive("home");
		Files.writeString(drive.fileSystem().getPath("/hello.txt"),"working file");
		Files.writeString(drive.fileSystem().getPath("/another.txt"),"batched file");
		var info=convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),
			Strings.create("peer"),Strings.create("test"),Vectors.empty(),100);
		socialApp.node.setNodeInfo(info);
		filesApp.node.setNodeInfo(info);
		assertEquals(HOST_ROOT.zero(),host.cursor().get());
		assertNotNull(socialApp.social.withCursor(socialApp.socialState.cursor()).feed().getPost(post),
			"Lightweight views share the owner's unsigned working state");
		assertEquals(0,signatures.get(),"Domain edits must not sign before application sync");
		assertEquals(HOST_ROOT.zero(),socialApp.cursor().get());
		socialApp.sync();
		assertEquals(2,signatures.get(),"One signature per changed owner subtree");
		assertNull(host.cursor().get().get(Keywords.FS));
		filesApp.sync();
		assertNotNull(Social.connect(view(host)).user(SOCIAL_KEY.getAccountKey()).feed().getPost(post));
		assertEquals("working file",Files.readString(DLFSRegion.connect(view(host).resolve(Keywords.FS),host.store())
			.drives(FILES_KEY.getAccountKey()).drive("home").fileSystem().getPath("/hello.txt")));
		assertEquals(info,P2PUser.create(view(host),COMMON_KEY.getAccountKey()).node().getNodeInfo());
		assertEquals(info,P2PUser.create(view(host),FILES_KEY.getAccountKey()).node().getNodeInfo());
		assertEquals(4,signatures.get(),"Root publication needs no additional signatures");
		socialApp.sync();
		filesApp.sync();
		assertEquals(4,signatures.get(),"Unchanged checkpoints do not sign");
		assertNull(socialApp.getContext().getSigningKey());
		assertNull(store.getRootData());
		host.sync();
		assertEquals(host.cursor().get(),store.getRootData());
	}

	/** Each application has its own user key and deliberately shares one node owner. */
	private static LatticeContext context(AKeyPair primary) {
		return context(primary,() -> {});
	}

	/** Test hook pauses signing before an atomic write can install its candidate. */
	private static LatticeContext context(AKeyPair primary,Runnable beforeSign) {
		return new LatticeContext() {
			@Override public AKeyPair getSigningKey() { return primary; }
			@Override public CVMLong currentTimestamp() { return CVMLong.create(100); }
			@Override public <T extends ACell> SignedData<T> sign(AccountKey key,T value) {
				beforeSign.run();
				if (COMMON_KEY.getAccountKey().equals(key)) return COMMON_KEY.signData(value);
				return super.sign(key,value);
			}
		}.withOwnerVerifier(VERIFY.getOwnerVerifier());
	}

	/** Domain composition over a root cursor, also usable as an isolated transaction view. */
	private static final class SocialWithP2P extends LatticeApplication<Index<Keyword,ACell>>
			implements TransactionalComponent<Index<Keyword,ACell>,SocialWithP2P> {
		private final LatticeContext policy;
		final Social social;
		SocialWithP2P(LatticeHost<Index<Keyword,ACell>> host) {
			this(host.cursor(),context(SOCIAL_KEY));
		}
		SocialWithP2P(AForkableCursor<Index<Keyword,ACell>> cursor,LatticeContext policy) {
			super(SOCIAL_ROOT,cursor);
			this.policy=policy;
			social=Social.connect(cursor.path(Social.KEY_SOCIAL),convex.auth.did.DIDKeyAuthorizer.CONVEX,
				(owner,value) -> policy.signAs(owner,value),policy::currentTimestamp);
		}
		@Override public SocialWithP2P withCursor(AForkableCursor<Index<Keyword,ACell>> cursor) {
			return new SocialWithP2P(cursor,policy);
		}
		P2PUser p2p(AccountKey owner) { return P2PUser.create(this,owner,(key,value) -> policy.signAs(key,value)); }
	}

	private static final class FilesWithP2P extends DLFSApplication
			implements TransactionalComponent<Index<Keyword,ACell>,FilesWithP2P> {
		private final LatticeContext policy;
		FilesWithP2P(LatticeHost<Index<Keyword,ACell>> host) {
			this(host.cursor(),host.store(),context(FILES_KEY));
		}
		FilesWithP2P(AForkableCursor<Index<Keyword,ACell>> cursor,AStore store,LatticeContext context) {
			super(cursor,store,FILES_KEY::signData,() -> CVMLong.create(100),30_000);
			policy=context;
			for (Keyword key:new Keyword[]{Keywords.P2P,Keywords.ID,P2PLattice.KEY_KAD}) {
				region(key,LatticeComponent.live(P2PLattice.ROOT.path(key),cursor.path(key),context));
			}
		}
		@Override public FilesWithP2P withCursor(AForkableCursor<Index<Keyword,ACell>> cursor) {
			return new FilesWithP2P(cursor,store(),policy);
		}
		P2PUser p2p(AccountKey owner) { return P2PUser.create(this,owner,(key,value) -> policy.signAs(key,value)); }
	}

	/** Explicit allow-list projection; a selective lattice merge is not projection. */
	/** A propagation group announcing one projection of the host root. */
	private static LatticePropagator group(AStore store,LatticeFilter<Index<Keyword,ACell>> projection) {
		LatticePropagator group=new LatticePropagator(store,LatticePropagatorConfig.create());
		group.setPublicationFilter(projection);
		return group;
	}

	private static Index<Keyword,ACell> project(Index<Keyword,ACell> root,Keyword domain) {
		Index<Keyword,ACell> result=Index.none();
		for (Keyword key:new Keyword[]{Keywords.P2P,Keywords.ID,P2PLattice.KEY_KAD,domain}) {
			ACell value=root.get(key);
			if (value!=null) result=result.assoc(key,value);
		}
		return result;
	}

	private static final class Fixture implements AutoCloseable {
		final MemoryStore store=new MemoryStore();
		final MemoryStore socialStore=new MemoryStore();
		final MemoryStore filesStore=new MemoryStore();
		final NodeServer<Index<Keyword,ACell>> node;
		final SocialWithP2P social;
		final FilesWithP2P files;
		final LatticePropagator socialGroup=group(socialStore,root -> project(root,Social.KEY_SOCIAL));
		final LatticePropagator filesGroup=group(filesStore,root -> project(root,Keywords.FS));
		Fixture() { this(false); }
		Fixture(boolean coordinated) {
			var cursor=coordinated?Cursors.transactionalRoot(HOST_ROOT.zero()):Cursors.root(HOST_ROOT.zero());
			var host=new LatticeHost<>(cursor,store);
			social=new SocialWithP2P(host);
			files=new FilesWithP2P(host);
			host.addApplication(social);
			host.addApplication(files);
			node=new NodeServer<>(host,NodeConfig.port(-1));
			socialGroup.setTransportKeyPair(SOCIAL_TRANSPORT);
			filesGroup.setTransportKeyPair(FILES_TRANSPORT);
			node.addPropagator(socialGroup);
			node.addPropagator(filesGroup);
		}
		@Override public void close() throws Exception {
			node.close();
			filesStore.close();
			socialStore.close();
			store.close();
		}
	}

	private record Data(Blob post,DLFSDrive drive) {}

	private static Data populate(Fixture f) throws Exception {
		Blob post=f.social.social.user(SOCIAL_KEY.getAccountKey()).feed().post("Shared host, social data");
		DLFSDrive drive=f.files.drives(FILES_KEY.getAccountKey()).createDrive("home");
		Files.writeString(drive.fileSystem().getPath("/hello.txt"),"Shared host, private file");
		f.social.p2p(SOCIAL_KEY.getAccountKey()).identity()
			.setIdentity(Strings.create("social user"),Vectors.empty(),100);
		f.files.p2p(FILES_KEY.getAccountKey()).identity()
			.setIdentity(Strings.create("files user"),Vectors.empty(),100);
		f.social.p2p(COMMON_KEY.getAccountKey()).node().setNodeInfo(convex.lattice.p2p.P2PLattice.createNodeInfo(
			Vectors.empty(),Strings.create("shared node"),Strings.create("test"),Vectors.empty(),100));
		return new Data(post,drive);
	}

	@Test public void testTypedTransactionsOnOrdinaryHost() throws Exception {
		testTypedTransactionsAcrossSharedApplicationRegions(false);
	}

	@Test public void testTypedTransactionsOnTransactionalHost() throws Exception {
		testTypedTransactionsAcrossSharedApplicationRegions(true);
	}

	private void testTypedTransactionsAcrossSharedApplicationRegions(boolean coordinated) throws Exception {
		try (Fixture f=new Fixture(coordinated)) {
			var root=f.node.getHost().cursor();
			assertEquals(coordinated,root.supportsExclusiveTransactions());
			assertEquals(coordinated,f.social.social.user(SOCIAL_KEY.getAccountKey()).cursor().supportsExclusiveTransactions());
			var before=root.get();
			f.social.transact(app -> {
				var user=app.social.user(SOCIAL_KEY.getAccountKey());
				user.feed().post("Atomic post and follow");
				user.follows().follow(FILES_KEY.getAccountKey());
				app.p2p(COMMON_KEY.getAccountKey()).node().setNodeInfo(convex.lattice.p2p.P2PLattice.createNodeInfo(
					Vectors.empty(),Strings.create("transactional node"),Strings.create("test"),Vectors.empty(),100));
				assertSame(before,root.get());
			});
			var user=f.social.social.user(SOCIAL_KEY.getAccountKey());
			assertEquals(1,user.feed().count());
			assertTrue(user.follows().isFollowing(FILES_KEY.getAccountKey()));
			assertNotNull(f.social.p2p(COMMON_KEY.getAccountKey()).node().getNodeInfo());
			var socialState=root.get().get(Social.KEY_SOCIAL);
			f.files.transactAsync(app -> {
				var drive=app.drives(FILES_KEY.getAccountKey()).createDrive("transaction");
				try {
					Files.writeString(drive.fileSystem().getPath("/one.txt"),"one");
					Files.writeString(drive.fileSystem().getPath("/two.txt"),"two");
				} catch (IOException e) { throw new UncheckedIOException(e); }
				app.p2p(FILES_KEY.getAccountKey()).identity().setIdentity(
					Strings.create("files transaction"),Vectors.empty(),100);
				assertNull(f.files.drives(FILES_KEY.getAccountKey()).drive("transaction"));
			}).get(10,TimeUnit.SECONDS);
			var drive=f.files.drives(FILES_KEY.getAccountKey()).drive("transaction");
			assertEquals("one",Files.readString(drive.fileSystem().getPath("/one.txt")));
			assertEquals("two",Files.readString(drive.fileSystem().getPath("/two.txt")));
			assertSame(socialState,root.get().get(Social.KEY_SOCIAL));
			var committed=root.get();
			assertThrows(IllegalArgumentException.class,() -> f.files.transact(app -> {
				app.drives(FILES_KEY.getAccountKey()).deleteDrive("transaction");
				throw new IllegalArgumentException("abort");
			}));
			assertSame(committed,root.get());
		}
	}

	@Test
	public void testAtomicDomainUpdatesWhileSocialPostIsInFlight() throws Exception {
		try (Fixture f=new Fixture()) {
			f.node.launch();
			DLFSDrive drive=f.files.drives(FILES_KEY.getAccountKey()).createDrive("home");
			var observer=new SocialWithP2P(f.node.getHost());
			var otherFeed=observer.social.user(SOCIAL_KEY.getAccountKey()).feed();
			CountDownLatch signing=new CountDownLatch(1);
			CountDownLatch resume=new CountDownLatch(1);
			AtomicInteger signatures=new AtomicInteger();
			var delayedPolicy=context(SOCIAL_KEY,() -> {
				if (signatures.incrementAndGet()==1) {
					signing.countDown();
					try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
			});
			var feed=Social.connect(f.social.cursor().path(Social.KEY_SOCIAL),convex.auth.did.DIDKeyAuthorizer.CONVEX,
				(owner,value) -> delayedPolicy.signAs(owner,value),delayedPolicy::currentTimestamp).user(SOCIAL_KEY.getAccountKey()).feed();
			var beforePost=f.node.getCursor().get();
			try (var executor=Executors.newSingleThreadExecutor()) {
				var posting=executor.submit(() -> feed.post("First post, delayed before installation"));
				Blob second;
				try {
					assertTrue(signing.await(10,TimeUnit.SECONDS));
					assertSame(beforePost,f.node.getCursor().get());
					assertEquals(0,otherFeed.count());
					second=otherFeed.post("Second post, installed first");

					// Register a new peer in lattice state; opening a connection is a separate effect.
					var peer=f.files.p2p(COMMON_KEY.getAccountKey()).node();
					assertNull(observer.p2p(COMMON_KEY.getAccountKey()).node().getNodeInfo());
					var info=convex.lattice.p2p.P2PLattice.createNodeInfo(Vectors.empty(),
						Strings.create("new peer"),Strings.create("test"),Vectors.empty(),100);
					peer.setNodeInfo(info);
					assertEquals(info,observer.p2p(COMMON_KEY.getAccountKey()).node().getNodeInfo());
					assertSigned(f,COMMON_KEY.getAccountKey(),info,
						Keywords.P2P,Keywords.NODES,COMMON_KEY.getAccountKey());

					// Several channel writes become one visible file update at fork.sync().
					try (DLFSDrive writing=drive.fork()) {
						var beforeFile=f.node.getCursor().get();
						var file=writing.fileSystem().getPath("/atomic.txt");
						try (var channel=Files.newByteChannel(file,
							StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
							channel.write(ByteBuffer.wrap("complete ".getBytes(StandardCharsets.UTF_8)));
							assertFalse(Files.exists(drive.fileSystem().getPath("/atomic.txt")));
							channel.write(ByteBuffer.wrap("file".getBytes(StandardCharsets.UTF_8)));
						}
						assertSame(beforeFile,f.node.getCursor().get(),"Staged file writes must not change live state");
						// A sibling file written after the fork must survive its merge-back.
						Files.writeString(drive.fileSystem().getPath("/sibling.txt"),"concurrent file");
						writing.sync();
					}
					assertEquals("complete file",Files.readString(drive.fileSystem().getPath("/atomic.txt")));
					assertEquals("concurrent file",Files.readString(drive.fileSystem().getPath("/sibling.txt")));
				} finally { resume.countDown(); }
				Blob first=posting.get(10,TimeUnit.SECONDS);
				assertTrue(signatures.get()>=2,"The post must retry and sign the feed containing the concurrent post");
				assertNotEquals(first,second);
				assertEquals(2,otherFeed.count());
				assertEquals(Strings.create("First post, delayed before installation"),otherFeed.getPost(first).get(SocialPost.TEXT));
				assertEquals(Strings.create("Second post, installed first"),otherFeed.getPost(second).get(SocialPost.TEXT));
			}
			assertNotNull(observer.p2p(COMMON_KEY.getAccountKey()).node().getNodeInfo());
			assertEquals("complete file",Files.readString(drive.fileSystem().getPath("/atomic.txt")));
			assertEquals("concurrent file",Files.readString(drive.fileSystem().getPath("/sibling.txt")));
			assertSigned(f,SOCIAL_KEY.getAccountKey(),f.social.social.user(SOCIAL_KEY.getAccountKey()).cursor().get(),
				Social.KEY_SOCIAL,DID.forKey(SOCIAL_KEY.getAccountKey()));
			assertSigned(f,FILES_KEY.getAccountKey(),f.files.drives(FILES_KEY.getAccountKey()).cursor().get(),
				Keywords.FS,FILES_KEY.getAccountKey());
			f.files.sync();
			f.node.getHost().sync();
			assertEquals(f.node.getCursor().get(),f.store.getRootData());
		}
	}

	@Test
	public void testConcurrentLiveFileWritesAndTruncation() throws Exception {
		for (boolean truncate:new boolean[]{false,true}) {
			try (Fixture f=new Fixture()) {
				DLFSDrive setup=f.files.drives(FILES_KEY.getAccountKey()).createDrive("home");
				Files.writeString(setup.fileSystem().getPath("/same.txt"),"0000");
				var other=new FilesWithP2P(f.node.getHost());
				DLFSDrive second=other.drives(FILES_KEY.getAccountKey()).drive("home");
				CountDownLatch signing=new CountDownLatch(1);
				CountDownLatch resume=new CountDownLatch(1);
				AtomicInteger attempts=new AtomicInteger();
				var signingFiles=DLFSRegion.connect(f.files.dlfs().cursor(),f.store,value -> {
					if (attempts.incrementAndGet()==1) {
						signing.countDown();
						try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
						catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
					}
					return FILES_KEY.signData(value);
				},() -> CVMLong.create(100));
				DLFSDrive first=signingFiles.drives(FILES_KEY.getAccountKey()).drive("home");
			try (var executor=Executors.newSingleThreadExecutor();
						var a=Files.newByteChannel(first.fileSystem().getPath("/same.txt"),StandardOpenOption.WRITE);
						var b=Files.newByteChannel(second.fileSystem().getPath("/same.txt"),StandardOpenOption.WRITE)) {
					var writing=executor.submit(() -> {
						if (truncate) a.truncate(3);
						else a.write(ByteBuffer.wrap(new byte[]{'A'}));
						return null;
					});
					try {
						assertTrue(signing.await(10,TimeUnit.SECONDS));
						b.position(truncate?0:3);
						b.write(ByteBuffer.wrap(new byte[]{'B'}));
					} finally { resume.countDown(); }
					writing.get(10,TimeUnit.SECONDS);
				}
				assertTrue(attempts.get()>=2,"The stale file update must retry against the latest bytes");
				assertEquals(truncate?"B00":"A00B",Files.readString(second.fileSystem().getPath("/same.txt")));
				assertSigned(f,FILES_KEY.getAccountKey(),other.drives(FILES_KEY.getAccountKey()).cursor().get(),
					Keywords.FS,FILES_KEY.getAccountKey());
			}
		}
	}

	@Test
	public void testConcurrentFileCreationDoesNotReplaceTheWinner() throws Exception {
		try (Fixture f=new Fixture()) {
			DLFSDrive setup=f.files.drives(FILES_KEY.getAccountKey()).createDrive("home");
			var other=new FilesWithP2P(f.node.getHost());
			DLFSDrive second=other.drives(FILES_KEY.getAccountKey()).drive("home");
			CountDownLatch signing=new CountDownLatch(1);
			CountDownLatch resume=new CountDownLatch(1);
			AtomicInteger attempts=new AtomicInteger();
			var signingFiles=DLFSRegion.connect(f.files.dlfs().cursor(),f.store,value -> {
				if (attempts.incrementAndGet()==1) {
					signing.countDown();
					try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
				return FILES_KEY.signData(value);
			},() -> CVMLong.create(100));
			DLFSDrive first=signingFiles.drives(FILES_KEY.getAccountKey()).drive("home");
			try (var executor=Executors.newSingleThreadExecutor()) {
				var creating=executor.submit(() -> Files.createFile(first.fileSystem().getPath("/new.txt")));
				try {
					assertTrue(signing.await(10,TimeUnit.SECONDS));
					Files.writeString(second.fileSystem().getPath("/new.txt"),"winner",
						StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
				} finally { resume.countDown(); }
				ExecutionException failure=assertThrows(ExecutionException.class,() -> creating.get(10,TimeUnit.SECONDS));
				assertInstanceOf(FileAlreadyExistsException.class,failure.getCause());
			}
			assertEquals("winner",Files.readString(first.fileSystem().getPath("/new.txt")));
		}
	}

	@Test
	public void testSocialBatchAndAbandonedFileWork() throws Exception {
		try (Fixture f=new Fixture()) {
			var user=f.social.social.user(SOCIAL_KEY.getAccountKey());
			var batch=user.fork();
			var before=f.node.getCursor().get();
			Blob post=batch.feed().post("Post and follow in one signed update");
			batch.follows().follow(FILES_KEY.getAccountKey());
			assertSame(before,f.node.getCursor().get());
			assertNull(user.feed().getPost(post));
			assertFalse(user.follows().isFollowing(FILES_KEY.getAccountKey()));

			DLFSDrive drive=f.files.drives(FILES_KEY.getAccountKey()).createDrive("home");
			var filesBefore=f.node.getCursor().get().get(Keywords.FS);
			batch.sync();
			assertEquals(batch.cursor().get(),user.cursor().get());
			assertNotNull(user.feed().getPost(post));
			assertTrue(user.follows().isFollowing(FILES_KEY.getAccountKey()));
			assertSame(filesBefore,f.node.getCursor().get().get(Keywords.FS));
			assertSigned(f,SOCIAL_KEY.getAccountKey(),user.cursor().get(),
				Social.KEY_SOCIAL,DID.forKey(SOCIAL_KEY.getAccountKey()));

			var beforeAbandon=f.node.getCursor().get();
			try (DLFSDrive abandoned=drive.fork()) {
				Files.writeString(abandoned.fileSystem().getPath("/abandoned.txt"),"unfinished work");
				assertTrue(Files.exists(abandoned.fileSystem().getPath("/abandoned.txt")));
			}
			assertSame(beforeAbandon,f.node.getCursor().get());
			assertFalse(Files.exists(drive.fileSystem().getPath("/abandoned.txt")));
		}
	}

	private static void assertSigned(Fixture f,AccountKey owner,ACell value,ACell... path) {
		SignedData<ACell> signed=f.node.getCursor().<SignedData<ACell>>path(path).get();
		assertNotNull(signed);
		assertEquals(owner,signed.getAccountKey());
		assertTrue(signed.checkSignature());
		assertEquals(value,signed.getValue());
	}

	@Test
	public void testConcurrentUpdatesToCommonSignedRegion() throws Exception {
		try (Fixture f=new Fixture()) {
			f.node.launch();
			Data data=populate(f);
			var socialNode=f.social.p2p(COMMON_KEY.getAccountKey()).node();
			var filesNode=f.files.p2p(COMMON_KEY.getAccountKey()).node();
			assertNotSame(socialNode,filesNode);
			assertSame(socialNode.getNodeInfo(),filesNode.getNodeInfo());
			assertSame(SOCIAL_ROOT.path(Keywords.P2P),FILES_ROOT.path(Keywords.P2P));

			CountDownLatch reading=new CountDownLatch(1);
			CountDownLatch resume=new CountDownLatch(1);
			AtomicInteger attempts=new AtomicInteger();
			try (var executor=Executors.newSingleThreadExecutor()) {
				var writing=executor.submit(() -> socialNode.cursor().updateAndGet(value -> {
					if (attempts.incrementAndGet()==1) {
						reading.countDown();
						try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
						catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
					}
					return withFlag(value,SOCIAL_FLAG);
				}));
				try {
					assertTrue(reading.await(10,TimeUnit.SECONDS));
					filesNode.cursor().updateAndGet(value -> withFlag(value,FILES_FLAG));
				} finally { resume.countDown(); }
				writing.get(10,TimeUnit.SECONDS);
			}
			assertTrue(attempts.get()>=2,"The signed update must retry against the other application's write");
			assertEquals(CVMBool.TRUE,socialNode.getNodeInfo().get(SOCIAL_FLAG));
			assertEquals(CVMBool.TRUE,filesNode.getNodeInfo().get(FILES_FLAG));
			SignedData<?> signed=f.node.getCursor().<SignedData<ACell>>path(
				Keywords.P2P,Keywords.NODES,COMMON_KEY.getAccountKey()).get();
			assertTrue(signed.checkSignature());
			assertEquals(COMMON_KEY.getAccountKey(),signed.getAccountKey());

			// The shared node signer does not grant access to the other application's user key.
			var before=f.node.getCursor().get();
			assertThrows(IllegalStateException.class,() -> f.files.p2p(SOCIAL_KEY.getAccountKey()).identity()
				.setIdentity(Strings.create("forged"),Vectors.empty(),200));
			assertSame(before,f.node.getCursor().get());
			assertNotNull(f.social.social.user(SOCIAL_KEY.getAccountKey()).feed().getPost(data.post()));
			assertEquals("Shared host, private file",Files.readString(data.drive().fileSystem().getPath("/hello.txt")));
			f.files.sync();
			f.node.getHost().sync();
			assertEquals(f.node.getCursor().get(),f.store.getRootData());
		}
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<Keyword,ACell> withFlag(ACell value,Keyword flag) {
		return ((AHashMap<Keyword,ACell>)value).assoc(flag,CVMBool.TRUE);
	}

	private static LatticeListener listener(LatticePropagator group) {
		LatticeListener listener=new LatticeListener(NodeConfig.localNetwork());
		listener.registerPropagator(group);
		listener.setSelector(connection -> group);
		return listener;
	}

	@SuppressWarnings("unchecked")
	private static Index<Keyword,ACell> awaitView(LatticePropagator group,
			Predicate<Index<Keyword,ACell>> ready) throws Exception {
		return (Index<Keyword,ACell>)group.whenAnnounced(
			value -> ready.test((Index<Keyword,ACell>)value)).get(10,TimeUnit.SECONDS);
	}

	@Test
	public void testSeparatePeerViewsAndRemovalOfOnePropagationGroup() throws Exception {
		try (Fixture f=new Fixture();
				MemoryStore remoteStore=new MemoryStore();
				MemoryStore reverseStore=new MemoryStore();
				NodeServer<Index<Keyword,ACell>> remote=new NodeServer<>(
					RelayHost.open(HOST_ROOT,remoteStore,VERIFY),NodeConfig.port(-1));
				LatticeListener socialListener=listener(f.socialGroup);
				LatticeListener filesListener=listener(f.filesGroup)) {
			LatticePropagator receiver=new LatticePropagator(remoteStore,LatticePropagatorConfig.create());
			receiver.setTransportKeyPair(AKeyPair.createSeeded(7306));
			remote.addPropagator(receiver);
			f.node.launch();
			remote.launch();
			socialListener.launch();
			filesListener.launch();
			Data data=populate(f);
			f.social.sync();
			f.node.getHost().sync();
			var socialView=awaitView(f.socialGroup,v -> v.get(Social.KEY_SOCIAL)!=null);
			var filesView=awaitView(f.filesGroup,v -> v.get(Keywords.FS)!=null);
			assertNull(socialView.get(Keywords.FS));
			assertNull(filesView.get(Social.KEY_SOCIAL));
			assertEquals(socialView.get(Keywords.P2P),filesView.get(Keywords.P2P));
			assertEquals(socialView.get(Keywords.ID),filesView.get(Keywords.ID));
			// Independent receiver sees the partial roots in reverse order, with the same result.
			// It takes only the social owners it wants, so it pins the social user first.
			var reverse=P2PApplication.connect(HOST_ROOT,LatticeHost.create(HOST_ROOT.zero(),reverseStore));
			reverse.setContext(VERIFY);
			reverse.pinSocial(DID.forKey(SOCIAL_KEY.getAccountKey()));
			reverse.merge(filesView);
			reverse.merge(socialView);
			assertEquals(f.node.getCursor().get(),reverse.cursor().get());
			assertSame(reverse.cursor().get(),reverse.merge(socialView),"Repeated shared-region delivery is idempotent");

			try (Convex socialPeer=ConvexRemote.connect(socialListener.getHostAddress());
					Convex filesPeer=ConvexRemote.connect(filesListener.getHostAddress())) {
				receiver.addPeer(SOCIAL_TRANSPORT.getAccountKey(),socialPeer).get(10,TimeUnit.SECONDS);
				receiver.addPeer(FILES_TRANSPORT.getAccountKey(),filesPeer).get(10,TimeUnit.SECONDS);
				remote.pull(receiver,socialPeer).get(10,TimeUnit.SECONDS);
				assertNotNull(remote.getCursor().get().get(Social.KEY_SOCIAL));
				assertNull(remote.getCursor().get().get(Keywords.FS),"Social route must not disclose file state");
				remote.pull(receiver,filesPeer).get(10,TimeUnit.SECONDS);
				assertEquals(f.node.getCursor().get(),remote.getCursor().get(),"Overlapping P2P values merge once into the root");
				SocialWithP2P remoteSocial=new SocialWithP2P(remote.getHost());
				FilesWithP2P remoteFiles=new FilesWithP2P(remote.getHost());
				assertNotNull(remoteSocial.social.user(SOCIAL_KEY.getAccountKey()).feed().getPost(data.post()));
				assertEquals("Shared host, private file",Files.readString(remoteFiles.drives(FILES_KEY.getAccountKey())
					.drive("home").fileSystem().getPath("/hello.txt")));

				// Explicit group removal is the existing lifecycle API, not application.close().
				socialListener.close();
				assertTrue(f.node.removePropagator(f.socialGroup));
				assertFalse(f.socialGroup.isRunning());
				assertTrue(f.node.isRunning());
				assertTrue(f.filesGroup.isRunning());
				var retainedSocial=f.node.getCursor().get().get(Social.KEY_SOCIAL);
				f.files.p2p(COMMON_KEY.getAccountKey()).identity()
					.setIdentity(Strings.create("files still running"),Vectors.empty(),200);
				Files.writeString(data.drive().fileSystem().getPath("/later.txt"),"Still available");
				f.files.sync();
				f.node.getHost().sync();
				var expected=project(f.node.getCursor().get(),Keywords.FS);
				awaitView(f.filesGroup,expected::equals);
				remote.pull(receiver,filesPeer).get(10,TimeUnit.SECONDS);
				assertEquals("Still available",Files.readString(remoteFiles.drives(FILES_KEY.getAccountKey())
					.drive("home").fileSystem().getPath("/later.txt")));
				assertEquals(Strings.create("files still running"),remoteSocial.p2p(COMMON_KEY.getAccountKey())
					.identity().getIdentity().get(P2PLattice.ID_NAME));
				assertEquals(retainedSocial,f.node.getCursor().get().get(Social.KEY_SOCIAL));
				assertEquals(retainedSocial,remote.getCursor().get().get(Social.KEY_SOCIAL));
				assertFalse(f.filesGroup.getStatus().hasFailures());
			}
		}
	}
}
