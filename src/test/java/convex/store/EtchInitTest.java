package convex.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ACell;
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
			
			State s=Init.STATE;
			Ref<State> sr=Ref.createPersisted(s);
			
			Hash hash=sr.getHash();
			
			Ref<State> sr2=Ref.forHash(hash);
			State s2=sr2.getValue();
			s2.validate();
			
			Hash hTest=Hash.fromHex("9bbdc2efd5b93eef6b379820a5888e80f91401b18020459862b3270a0c781ba4");
			Ref<?> rTest=Ref.forHash(hTest);
			ACell c=rTest.getValue();
			assertNotNull(c);
			
		} finally {
			Stores.setCurrent(temp);
		}
	}
}
