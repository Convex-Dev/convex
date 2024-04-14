package convex.store;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.InitTest;
import convex.core.store.AStore;
import convex.core.store.Stores;
import etch.EtchStore;

public class EtchInitTest {

	@Test public void testInitState() throws InvalidDataException {
		AStore temp=Stores.current();
		try {
			Stores.setCurrent(EtchStore.createTemp());

			// Use fresh State
			State s=InitTest.createState();
			Ref<State> sr=Cells.persist(s).getRef();

			Hash hash=sr.getHash();

			Ref<State> sr2=Ref.forHash(hash);
			State s2=sr2.getValue();
			s2.validate();
		} finally {
			Stores.setCurrent(temp);
		}
	}
}
