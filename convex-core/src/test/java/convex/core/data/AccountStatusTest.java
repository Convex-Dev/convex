package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Symbols;
import convex.core.util.Utils;
import convex.test.Samples;

public class AccountStatusTest {

	@Test public void testEmpty() {
		AccountStatus as=AccountStatus.create();
		doAccountStatusTest(as);
	}
	
	@Test public void testFull() throws BadFormatException {
		AccountStatus as=AccountStatus.create(10, 1000, Samples.ACCOUNT_KEY);
		as=as.withMemory(10000);
		as=as.withHolding(Address.create(127), Symbols.FOO);
		as=as.withEnvironment(Maps.of(Symbols.FOO,CVMLong.ONE));
		as=as.withMetadata(Maps.of(Symbols.FOO,Maps.empty()));
		as=as.withController(Address.create(1546746));
		
		// Round trip through encoding
		as=Format.decodeMultiCell(as.getEncoding());
		
		assertEquals(10,as.getSequence());
		assertEquals(1000,as.getBalance());
		assertEquals(10000,as.getMemory());
		assertEquals(Symbols.FOO,as.getHoldings().get(Address.create(127)));
		assertEquals(CVMLong.ONE,as.getEnvironmentValue(Symbols.FOO));
		assertEquals(Maps.empty(),as.getMetadata().get(Symbols.FOO));
		assertEquals(Address.create(1546746),as.getController());
		
		doAccountStatusTest(as);
	}

	private void doAccountStatusTest(AccountStatus as)  {
		assertTrue(as.isCanonical());
		
		try {
			as.validateCell();
		} catch (InvalidDataException e) {
			throw Utils.sneakyThrow(e);
		}
		
		RecordTest.doRecordTests(as);
	}
}
