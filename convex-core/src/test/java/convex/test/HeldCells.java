package convex.test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.store.AStore;

/**
 * Checks of what a value keeps in memory. Saved data can be reclaimed only where it
 * is reached through soft references, so these inspect the references directly,
 * which says what the collector may reclaim without running it.
 */
public final class HeldCells {

	private HeldCells() {}

	/** Non-embedded cells below this one reached through strong references alone, loading nothing. */
	public static Set<ACell> strongCells(ACell top) {
		Set<ACell> strong=Collections.newSetFromMap(new IdentityHashMap<>());
		collectStrong(top,strong,Collections.newSetFromMap(new IdentityHashMap<>()));
		return strong;
	}

	private static void collectStrong(ACell cell,Set<ACell> strong,Set<ACell> seen) {
		if (cell==null || !seen.add(cell)) return;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (!ref.isDirect()) continue;
			ACell child=ref.getValue();
			if (child!=null && !ref.isEmbedded()) strong.add(child);
			collectStrong(child,strong,seen);
		}
	}

	/** Every non-embedded reference in the whole tree is soft, bound to this store, persisted and present there. */
	public static void assertHeldSoftly(AStore store,ACell top) {
		assertTrue(checkSoft(store,top,new HashSet<>())>0,"Nothing to check");
	}

	private static int checkSoft(AStore store,ACell cell,Set<Hash> seen) {
		int checked=0;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (!ref.isEmbedded()) {
				assertInstanceOf(RefSoft.class,ref,"Held strongly");
				assertSame(store,((RefSoft<ACell>)ref).getStore(),"Bound to another store");
				// A reference decoded from the store has no status of its own until read, so ask the store
				Ref<ACell> stored=store.refForHash(ref.getHash());
				assertNotNull(stored,"Not in the store");
				assertTrue(stored.getStatus()>=Ref.PERSISTED,"Not persisted");
				checked++;
				if (!seen.add(ref.getHash())) continue;
			}
			ACell child=ref.getValue();
			if (child!=null) checked+=checkSoft(store,child,seen);
		}
		return checked;
	}

	/** Whether this cell is the target or leads to it through strong references. */
	public static boolean leadsTo(ACell cell,ACell target) {
		if (target.equals(cell)) return true;
		for (int i=0;i<Cells.refCount(cell);i++) {
			Ref<ACell> ref=Cells.getRef(cell,i);
			if (ref.isDirect() && ref.getValue()!=null && leadsTo(ref.getValue(),target)) return true;
		}
		return false;
	}

	/** Nothing is held strongly but cells that lead to the target, which need not be strong itself. */
	public static void assertStrongOnlyOnTheWayTo(ACell held,ACell target) {
		for (ACell cell:strongCells(held)) {
			assertTrue(leadsTo(cell,target),"Strong but not on the way to the target: "+cell.getClass());
		}
	}

	/**
	 * The new content is held strongly, and nothing else is but the cells that lead
	 * to it and the cells it is made of.
	 */
	public static void assertOnlyStrongAround(ACell held,ACell content) {
		Set<ACell> strong=strongCells(held);
		assertTrue(strong.stream().anyMatch(content::equals),"The new content itself is strong");
		Set<ACell> within=strongCells(content);
		for (ACell cell:strong) {
			assertTrue(within.contains(cell) || leadsTo(cell,content),
				"Strong but neither on the way to the new content nor part of it: "+cell.getClass());
		}
	}
}
