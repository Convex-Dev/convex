package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.SignedComponent;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/** A notebook deliberately batches unsigned edits before publishing through its application. */
class ApplicationPublicationTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7320);
	private static final Keyword NOTEBOOK=Keyword.intern("notebook");
	private static final SignedLattice<ASet<AString>> CONTENTS=
		OwnerKeyLattice.create(SetLattice.<AString>create()).signingFor(ALICE.getAccountKey());
	private static final KeyedLattice LATTICE=KeyedLattice.create(NOTEBOOK,CONTENTS);

	/** Application policy coordinates a notebook; it does not fork the entire host. */
	static final class NotebookApp {
		private final ACursor<SignedData<ASet<AString>>> destination;
		private final Notebook notebook;

		NotebookApp(LatticeHost<Index<Keyword,ACell>> host,AKeyPair owner) {
			destination=ALatticeComponent.resolve(host.cursor(),LATTICE,NOTEBOOK);
			notebook=new Notebook(destination,owner);
		}

		Notebook notebook() { return notebook; }
		SignedData<ASet<AString>> publishedNotebook() { return destination.get(); }
		void sync() { notebook.sync(); }
	}

	/** This component explicitly owns private working state and defers signing until sync. */
	static final class Notebook extends SignedComponent<ASet<AString>> {
		Notebook(ACursor<SignedData<ASet<AString>>> destination,AKeyPair owner) {
			super(CONTENTS,destination);
			setContext(LatticeContext.EMPTY.withSigningKey(owner));
		}
		Notes notes() { return new Notes(cursor()); }
	}

	/** Ordinary views share notebook state and have no publication lifecycle of their own. */
	static final class Notes extends ALatticeComponent<ASet<AString>> {
		Notes(AForkableCursor<ASet<AString>> cursor) { super(cursor); }
		void add(String text) {
			cursor.updateAndGet(notes -> (notes==null?Sets.<AString>empty():notes).include(Strings.create(text)));
		}
		ASet<AString> all() {
			var notes=cursor.get();
			return notes==null?Sets.empty():notes;
		}
	}

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
		assertNull(host.getContext().getSigningKey());
	}

	@Test void applicationSyncPublishesItsNotebook() {
		var host=LatticeHost.create();
		var app=new NotebookApp(host,ALICE);
		app.notebook().notes().add("Publish in one call");
		assertNull(app.publishedNotebook());
		app.sync();
		assertEquals(Sets.of("Publish in one call"),app.publishedNotebook().getValue());
		assertEquals(app.publishedNotebook(),host.cursor().get().get(NOTEBOOK));
	}

	@Test void independentNotebooksReconcileAtTheSigningBoundary() {
		var host=LatticeHost.create();
		var first=new NotebookApp(host,ALICE);
		var second=new NotebookApp(host,ALICE);
		first.notebook().notes().add("From first workspace");
		second.notebook().notes().add("From second workspace");
		first.sync();
		second.sync();
		SignedData<ASet<AString>> published=RT.getIn(host.cursor().get(),NOTEBOOK);
		assertEquals(Sets.of("From first workspace","From second workspace"),published.getValue());
		assertTrue(published.checkSignature());
		assertNull(host.getContext().getSigningKey());
	}
}
