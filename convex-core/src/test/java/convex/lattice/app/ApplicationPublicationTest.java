package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.SignedComponent;
import convex.lattice.SingleRegion;
import convex.lattice.SetComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;

/**
 * A notebook with an explicit unsigned draft and deferred signing.
 * Its host stores {@code {:notebook #Signed #{note-texts}}}; each notebook binds its
 * verification rule to the supplied owner. A notebook uses set union, so notes are unique values
 * with no ordering, replacement or deletion semantics. Local operations add text;
 * unfamiliar incoming members survive editing and publication unchanged.
 *
 * <p>{@link Notes} handles share one notebook's private unsigned working set.
 * Their edits neither sign nor reach the host. {@link Notebook#sync()} reconciles
 * that set with the current signed destination and signs the result. Independent
 * notebooks at the same host path have separate drafts and combine them when they
 * publish. {@link NotebookApp} delegates application sync to its notebook.</p>
 *
 * <p>This contrasts with {@link FavouritesExample}, where each changed local edit
 * is immediately signed into a staged owner region. Here the signed notebook's
 * destination is already a host path, so notebook sync reaches the host directly.
 * Host persistence remains a separate step. The tests use only in-memory hosts.</p>
 */
class ApplicationPublicationTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7320);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7321);
	private static final Keyword NOTEBOOK=Keyword.intern("notebook");
	private static final OwnerKeyLattice<ASet<ACell>> CONTENTS=OwnerKeyLattice.create(SetLattice.create());

	/** Routes complete envelopes at {@code :notebook} and publishes only that component. */
	static final class NotebookApp extends SingleRegion<Notebook> {
		NotebookApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair owner) {
			super(host,NOTEBOOK,new Notebook(host.cursor().path(NOTEBOOK),owner));
		}

		Notebook notebook() { return component(); }
		/** Reads the host's published envelope, not the notebook's pending unsigned edits. */
		SignedData<ASet<ACell>> publishedNotebook() { return RT.getIn(cursor().get(),NOTEBOOK); }
	}

	/**
	 * Owns the draft and signing capability. The owner-bound contents rule verifies
	 * incoming envelopes before their payloads enter the unsigned draft. A union
	 * assembled from local and remote notes needs a new signature only when published.
	 */
	static final class Notebook extends SignedComponent<ASet<ACell>> {
		Notebook(AForkableCursor<SignedData<ASet<ACell>>> destination,AKeyPair owner) {
			super(CONTENTS.signingFor(owner.getAccountKey()),destination,owner::signData);
		}
		Notes notes() { return new Notes(cursor()); }
	}

	/** Ordinary views share notebook state and have no publication lifecycle of their own. */
	static final class Notes extends ALatticeComponent<ASet<ACell>> {
		private final SetComponent<ACell> entries;
		Notes(AForkableCursor<ASet<ACell>> cursor) {
			super(cursor);
			entries=new SetComponent<>(cursor);
		}
		void add(String text) { entries.add(Strings.create(text)); }
		ASet<ACell> all() { return entries.get(); }
	}

	/** A text authoring API does not turn foreign set members into a string schema. */
	@Test void unfamiliarNotesSurviveLocalEditingAndPublication() {
		var host=LatticeHost.create();
		var app=new NotebookApp(host,ALICE);
		host.addApplication(app);
		var foreign=Sets.<ACell>of(42,"remote");
		host.mergeAt(ALICE.signData(foreign),NOTEBOOK);
		var notes=app.notebook().notes();
		for (ACell note:notes.all()) assertNotNull(note);
		notes.add("local");
		app.sync();
		assertEquals(foreign.include(Strings.create("local")),app.publishedNotebook().getValue());
		assertTrue(app.publishedNotebook().checkSignature());
	}

	/** The constructor's owner controls both publication and acceptance of incoming notes. */
	@Test void eachNotebookBindsItsSuppliedOwner() {
		for (var owner:List.of(ALICE,BOB)) {
			var host=LatticeHost.create();
			var app=new NotebookApp(host,owner);
			host.addApplication(app);
			var other=owner==ALICE?BOB:ALICE;
			app.notebook().notes().add("Local");
			host.mergeAt(other.signData(Sets.of("Unauthorised")),NOTEBOOK);
			host.mergeAt(owner.signData(Sets.of("Authorised")),NOTEBOOK);
			assertEquals(Sets.of("Local","Authorised"),app.notebook().notes().all());
			app.sync();
			assertEquals(owner.getAccountKey(),app.publishedNotebook().getAccountKey());
			assertTrue(app.publishedNotebook().checkSignature());
			assertEquals(Sets.of("Local","Authorised"),app.publishedNotebook().getValue());
		}
	}

	/** A lightweight view's sync cannot implicitly publish its enclosing signed draft. */
	@Test void eachPublicationStopsAtItsSuppliedDestination() {
		var host=LatticeHost.create();
		var app=new NotebookApp(host,ALICE);
		var notebook=app.notebook();
		var notes=notebook.notes();
		notes.add("First note");
		notebook.notes().add("Second note");
		assertEquals(Sets.of("First note","Second note"),notes.all());
		notes.sync(); // Lightweight views already share their enclosing notebook state.
		assertNull(app.publishedNotebook());
		assertTrue(host.cursor().get().isEmpty());

		notebook.sync(); // One signed envelope reaches its host path; persistence is separate.
		assertEquals(notes.all(),app.publishedNotebook().getValue());
		assertTrue(app.publishedNotebook().checkSignature());
		assertEquals(app.publishedNotebook(),host.cursor().get().get(NOTEBOOK));
	}

	/** Application sync is the convenient entry point for the registered child's publication. */
	@Test void applicationSyncPublishesItsNotebook() {
		var host=LatticeHost.create();
		var app=new NotebookApp(host,ALICE);
		app.notebook().notes().add("Publish in one call");
		assertNull(app.publishedNotebook());
		app.sync();
		assertEquals(Sets.of("Publish in one call"),app.publishedNotebook().getValue());
		assertEquals(app.publishedNotebook(),host.cursor().get().get(NOTEBOOK));
	}

	/** Both drafts start before either publishes; the second publication must retain the first one's notes. */
	@Test void independentNotebooksReconcileAtTheSigningBoundary() {
		var host=LatticeHost.create();
		var first=new NotebookApp(host,ALICE);
		var second=new NotebookApp(host,ALICE);
		first.notebook().notes().add("From first workspace");
		second.notebook().notes().add("From second workspace");
		first.sync();
		// Reconciliation and re-signing belong to the notebook, so the host needs no key.
		second.sync();
		SignedData<ASet<ACell>> published=RT.getIn(host.cursor().get(),NOTEBOOK);
		assertEquals(Sets.of("From first workspace","From second workspace"),published.getValue());
		assertTrue(published.checkSignature());
	}

	/** Incoming delivery contributes verified notes to the draft without forcing a publication. */
	@Test void aHostedNotebookAcceptsSignedDeliveryWithoutPublishingItsDraft() {
		var host=LatticeHost.create();
		var app=new NotebookApp(host,ALICE);
		host.addApplication(app);
		app.notebook().notes().add("Local draft");
		// Root routing and direct region delivery must enter the same signed boundary.
		host.merge(Index.of(NOTEBOOK,ALICE.signData(Sets.of("Received in a root"))));
		host.mergeAt(ALICE.signData(Sets.of("Received at a path")),NOTEBOOK);
		assertNull(app.publishedNotebook());
		assertEquals(Sets.of("Local draft","Received in a root","Received at a path"),app.notebook().notes().all());
		app.sync();
		assertEquals(app.notebook().notes().all(),app.publishedNotebook().getValue());
		assertTrue(app.publishedNotebook().checkSignature());
	}
}
