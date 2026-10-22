package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSRegion;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.LatticeHost;

/** Component boundaries, independent of transport or disk-store lifecycle. */
public class DLFSApplicationTest {
	private static final AKeyPair OWNER=AKeyPair.createSeeded(821);
	private static final AKeyPair OTHER=AKeyPair.createSeeded(822);
	private static final Keyword UNRELATED=Keyword.intern("unrelated");

	private static DLFSApplication files(LatticeHost<Index<Keyword,ACell>> host) {
		return host.addApplication(new DLFSApplication(host,OWNER::signData,() -> CVMLong.create(100),
			DLFSLattice.DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW));
	}

	@Test void emptyApplicationAndUnknownDeliveriesDoNotChangeTheRoot() {
		var host=LatticeHost.create();
		var before=host.cursor().get();
		var app=files(host);
		assertSame(before,host.cursor().get(),"construction is read-only");
		app.sync();
		host.merge(Index.of(UNRELATED,CVMLong.ONE));
		host.mergeAt(CVMLong.TWO,UNRELATED);
		assertSame(before,host.cursor().get());
	}

	@Test void rootRegionAndOwnerDeliveryEnforceTheSameSignedBoundary() throws Exception {
		var source=files(LatticeHost.create());
		Files.writeString(source.drives(OWNER.getAccountKey()).createDrive("home")
			.fileSystem().getPath("/hello"),"hello");
		var region=source.dlfs().cursor().get();
		var signed=region.get(OWNER.getAccountKey());
		var forged=SignedData.create(OWNER.getAccountKey(),signed.getSignature(),Ref.get(Maps.empty()));

		for (int depth=0;depth<=2;depth++) {
			var host=LatticeHost.create();
			var app=files(host);
			host.cursor().updateAndGet(root -> root.assoc(UNRELATED,CVMLong.ONE));
			if (depth==0) host.merge(source.cursor().get().assoc(UNRELATED,CVMLong.TWO));
			else if (depth==1) host.mergeAt(region,Keywords.FS);
			else host.mergeAt(signed,Keywords.FS,OWNER.getAccountKey());
			assertEquals(region,app.dlfs().cursor().get());
			var before=host.cursor().get();

			// Replays, wrong signers, corrupt signatures and unsigned interiors are inert.
			host.mergeAt(signed,Keywords.FS,OWNER.getAccountKey());
			host.mergeAt(Maps.of(OTHER.getAccountKey(),signed),Keywords.FS);
			host.mergeAt(signed,Keywords.FS,OTHER.getAccountKey());
			host.mergeAt(forged,Keywords.FS,OWNER.getAccountKey());
			host.mergeAt(Maps.of(OWNER.getAccountKey(),forged),Keywords.FS);
			host.mergeAt(CVMLong.ONE,Keywords.FS);
			host.mergeAt(CVMLong.ONE,Keywords.FS,OWNER.getAccountKey());
			host.mergeAt(Maps.empty(),Keywords.FS,OWNER.getAccountKey(),Keywords.VALUE);
			assertSame(before,host.cursor().get());
			assertEquals(CVMLong.ONE,host.cursor().get().get(UNRELATED));
		}
	}

	@Test void sharedForkReconcilesIncomingFilesWithoutPublishingPendingEdits() throws Exception {
		var host=LatticeHost.create();
		var region=DLFSRegion.connect(host.cursor().path(Keywords.FS),null,OWNER::signData,() -> CVMLong.create(100));
		var staged=region.fork();
		var app=host.addApplication(new DLFSApplication(host,Keywords.FS,staged));
		var shared=host.addApplication(new DLFSApplication(host,Keywords.FS,staged));
		assertSame(staged,app.dlfs());
		assertSame(staged,shared.dlfs());
		var local=app.drives(OWNER.getAccountKey()).createDrive("home").fileSystem();
		Files.writeString(local.getPath("/local"),"local");

		var remote=files(LatticeHost.create());
		Files.writeString(remote.drives(OWNER.getAccountKey()).createDrive("home")
			.fileSystem().getPath("/remote"),"remote");
		host.merge(remote.cursor().get());
		assertEquals("remote",Files.readString(local.getPath("/remote")));
		assertEquals("local",Files.readString(local.getPath("/local")));
		assertNull(host.cursor().get().get(Keywords.FS),"delivery must not publish the shared fork");

		host.cursor().updateAndGet(root -> root.assoc(UNRELATED,CVMLong.ONE));
		shared.sync();
		assertEquals(staged.cursor().get(),host.cursor().get().get(Keywords.FS));
		assertTrue(staged.cursor().get().get(OWNER.getAccountKey()).checkSignature());
		Files.writeString(local.getPath("/next"),"next");
		app.sync();
		assertEquals("next",Files.readString(region.drives(OWNER.getAccountKey())
			.drive("home").fileSystem().getPath("/next")));
		assertEquals(CVMLong.ONE,host.cursor().get().get(UNRELATED));
	}

	@Test void nestedRegionsCreateOnlyTheirOwnPathAndPreserveSiblingValues() throws Exception {
		var projects=Keyword.intern("projects");
		var documents=Keyword.intern("documents");
		var host=LatticeHost.<Index<Keyword,ACell>>create(null,new MemoryStore());
		var app=host.addApplication(new DLFSApplication(host,OWNER::signData,() -> CVMLong.create(100),
			DLFSLattice.DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW,projects,documents));
		assertNull(host.cursor().get());
		var drive=app.drives(OWNER.getAccountKey()).createDrive("home");
		assertEquals(List.of(projects,documents,OWNER.getAccountKey(),Strings.create("home")),
			drive.location().path());
		host.cursor().<Index<Keyword,ACell>>path(projects)
			.updateAndGet(value -> value.assoc(UNRELATED,CVMLong.ONE));
		var source=files(LatticeHost.create());
		source.drives(OWNER.getAccountKey()).createDrive("remote");
		host.mergeAt(source.dlfs().cursor().get(),projects,documents);
		assertNotNull(app.drives(OWNER.getAccountKey()).drive("remote"));
		assertEquals(CVMLong.ONE,host.cursor().<Index<Keyword,ACell>>path(projects).get().get(UNRELATED));
	}
}
