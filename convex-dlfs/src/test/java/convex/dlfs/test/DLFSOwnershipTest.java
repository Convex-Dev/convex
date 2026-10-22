package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFSRegion;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.Cursors;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLPath;
import convex.lattice.fs.impl.DLFSLocal;

/** The ownership boundary is independent of context, filesystem views and publication. */
public class DLFSOwnershipTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(831);
	private static final AKeyPair BOB=AKeyPair.createSeeded(832);
	private static final Supplier<CVMLong> CLOCK=() -> CVMLong.create(100);

	private static DLFSRegion author() {
		return DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,ALICE::signData,CLOCK);
	}

	@Test void relayReusesEnvelopesButOnlyAnAuthorCanCombineDivergentFiles() throws Exception {
		var left=author();
		Files.writeString(left.drives(ALICE.getAccountKey()).createDrive("home")
			.fileSystem().getPath("/left"),"left");
		var right=author();
		Files.writeString(right.drives(ALICE.getAccountKey()).createDrive("home")
			.fileSystem().getPath("/right"),"right");
		var relay=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null);
		relay.merge(left.cursor().get());
		var before=relay.cursor().get();
		// Even an inherited signing context does not confer capability on this relay.
		relay.setContext(LatticeContext.create(null,ALICE,(owner,key) -> true));
		relay.merge(right.cursor().get());
		assertSame(before,relay.cursor().get());
		assertThrows(IllegalStateException.class,() -> relay.drives(ALICE.getAccountKey()).createDrive("no"));

		left.merge(right.cursor().get());
		var combined=left.cursor().get().get(ALICE.getAccountKey());
		assertTrue(combined.checkSignature());
		relay.mergeAt(combined,ALICE.getAccountKey());
		assertSame(combined,relay.cursor().get().get(ALICE.getAccountKey()));
		var fs=relay.drives(ALICE.getAccountKey()).drive("home").fileSystem();
		assertEquals("left",Files.readString(fs.getPath("/left")));
		assertEquals("right",Files.readString(fs.getPath("/right")));
	}

	@Test void customOwnerRuleControlsExistingViewsAndIncomingEnvelopes() throws Exception {
		var owner=Strings.create("team-files");
		var key=new AtomicReference<>(ALICE);
		var region=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,
			(identity,signer) -> owner.equals(identity) && key.get().getAccountKey().equals(signer),
			(identity,value) -> key.get().signData(value),CLOCK,30);
		var drive=region.drives(owner).createDrive("home");
		var fs=drive.fileSystem();
		Files.writeString(fs.getPath("/before"),"Alice");
		var old=region.cursor().get().get(owner);
		assertEquals(ALICE.getAccountKey(),old.getAccountKey());
		assertThrows(IllegalStateException.class,() -> region.drives(Strings.create("unknown")).createDrive("no"));

		// Authority may rotate without rebuilding views; both callbacks see the same local rule.
		key.set(BOB);
		Files.writeString(fs.getPath("/after"),"Bob");
		var current=region.cursor().get();
		assertEquals(BOB.getAccountKey(),current.get(owner).getAccountKey());
		assertTrue(current.get(owner).checkSignature());
		region.mergeAt(old,owner);
		assertSame(current,region.cursor().get());
		for (boolean whole:new boolean[]{false,true}) {
			var receiver=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,
				(identity,signer) -> owner.equals(identity) && BOB.getAccountKey().equals(signer),
				null,CLOCK,30);
			if (whole) receiver.merge(current);
			else receiver.mergeAt(current.get(owner),owner);
			assertEquals(current,receiver.cursor().get());
		}
		var relay=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null);
		relay.merge(current);
		assertTrue(relay.cursor().get().isEmpty(),"indirect ownership needs an explicit verifier");
	}

	@Test void failedAuthoringDuringForkSyncChangesNeitherSnapshot() throws Exception {
		var fail=new AtomicBoolean();
		var region=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,value -> {
			if (fail.get()) throw new IllegalStateException("signer unavailable");
			return ALICE.signData(value);
		},CLOCK);
		region.drives(ALICE.getAccountKey()).createDrive("home");
		var fork=region.fork();
		Files.writeString(region.drives(ALICE.getAccountKey()).drive("home").fileSystem().getPath("/live"),"live");
		Files.writeString(fork.drives(ALICE.getAccountKey()).drive("home").fileSystem().getPath("/draft"),"draft");
		var live=region.cursor().get();
		var draft=fork.cursor().get();
		fail.set(true);
		assertThrows(IllegalStateException.class,fork::sync);
		assertSame(live,region.cursor().get());
		assertSame(draft,fork.cursor().get());
		fail.set(false);
		fork.sync();
		var fs=region.drives(ALICE.getAccountKey()).drive("home").fileSystem();
		assertEquals("live",Files.readString(fs.getPath("/live")));
		assertEquals("draft",Files.readString(fs.getPath("/draft")));
	}

	@Test void clockReachesDrivesFilesForksAndSnapshots() throws Exception {
		var time=new AtomicReference<>(CVMLong.create(100));
		var region=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,ALICE::signData,time::get);
		var drive=region.drives(ALICE.getAccountKey()).createDrive("home");
		assertEquals(time.get(),DLFSNode.getUTime(drive.cursor().get()));
		var fork=drive.fork();
		var snapshot=((DLFSLocal)drive.fileSystem()).clone();
		time.set(CVMLong.create(200));
		for (var fs:new DLFSLocal[]{(DLFSLocal)drive.fileSystem(),(DLFSLocal)fork.fileSystem(),snapshot}) {
			var path=fs.getPath("/file");
			Files.writeString(path,"clock");
			assertEquals(time.get(),DLFSNode.getUTime(fs.getNode((DLPath)path)));
		}
	}

	@Test void futureTimestampAdmissionSurvivesContextRemoval() throws Exception {
		var signatures=new AtomicInteger();
		var region=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,
			(owner,key) -> key.equals(owner),
			(owner,value) -> { signatures.incrementAndGet(); return ALICE.signData(value); },CLOCK,30);
		var local=region.drives(ALICE.getAccountKey()).createDrive("home").fileSystem();
		Files.writeString(local.getPath("/file"),"current");
		var future=DLFSRegion.connect(Cursors.root(DLFSRegion.LATTICE.zero()),null,
			ALICE::signData,() -> CVMLong.create(131));
		Files.writeString(future.drives(ALICE.getAccountKey()).createDrive("home")
			.fileSystem().getPath("/file"),"future");
		var before=region.cursor().get();
		int signed=signatures.get();
		region.merge(future.cursor().get());
		assertSame(before,region.cursor().get());
		assertEquals(signed,signatures.get(),"rejected input does not invoke the signer");
		assertEquals("current",Files.readString(local.getPath("/file")));
	}
}
