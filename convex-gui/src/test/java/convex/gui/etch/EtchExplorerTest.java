package convex.gui.etch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.exceptions.StoreException;
import convex.etch.EtchStore;

/**
 * The Etch explorer closes the stores it opened, and no other. Needs no display: the
 * explorer is never shown.
 */
public class EtchExplorerTest {

	/** A lookup the cache cannot answer finds nothing in an open store, and fails in a closed one */
	private static boolean isOpen(EtchStore store) {
		try {
			store.refForHash(Hash.wrap(Blob.createRandom(new Random(),32).getBytes()));
			return true;
		} catch (StoreException e) {
			return false;
		}
	}

	@Test
	public void closesTheStoresItOpenedAndNoOther() throws Exception {
		// Closing the explorer or a store again does nothing
		try (EtchExplorer explorer=new EtchExplorer();
				EtchStore opened=EtchStore.createTemp();
				EtchStore openedNext=EtchStore.createTemp()) {
			EtchStore shownFirst=explorer.getStore();
			explorer.setStore(opened);
			assertTrue(isOpen(shownFirst),"the store it did not open was closed");
			explorer.setStore(openedNext);
			assertFalse(isOpen(opened),"a store it opened and replaced was left open");
			explorer.close();
			assertFalse(isOpen(openedNext),"the store it showed when closed was left open");
			assertTrue(isOpen(shownFirst),"the store it did not open was closed");
		}
	}
}
