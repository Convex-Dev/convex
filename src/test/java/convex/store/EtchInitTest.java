package convex.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import etch.EtchStore;

public class EtchInitTest {

	@Test public void testInitState() throws InvalidDataException {
		AStore temp=Stores.current();
		try {
			Stores.setCurrent(EtchStore.createTemp());
			
			State s=Init.createState();
			Ref<State> sr=ACell.createPersisted(s);
			
			Hash hash=sr.getHash();
			
			Ref<State> sr2=Ref.forHash(hash);
			State s2=sr2.getValue();
			s2.validate();			
		} finally {
			Stores.setCurrent(temp);
		}
	}
}
