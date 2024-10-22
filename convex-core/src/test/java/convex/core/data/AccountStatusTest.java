package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.AccountStatus;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Core;
import convex.test.Samples;

public class AccountStatusTest {

	@Test public void testEmpty() throws InvalidDataException {
		AccountStatus as=AccountStatus.create();
		doAccountStatusTest(as);
	}
	
	@Test public void testEncodingRegression() throws BadFormatException {
		// Detected with fuzz tests
		Blob b=Blob.parse("0xc1036632b4");
		assertThrows(BadFormatException.class,()->Format.read(b));
	}
	
	@Test public void testBigSequence() throws BadFormatException, InvalidDataException {
		AccountStatus as=AccountStatus.create(80, 1000, Samples.ACCOUNT_KEY);
		doAccountStatusTest(as);
	}
	
	@Test public void testFull() throws BadFormatException, InvalidDataException {
		AccountStatus as=AccountStatus.create(10, 1000, Samples.ACCOUNT_KEY);
		as=as.withMemory(10000);
		as=as.withHolding(Address.create(127), Symbols.FOO);
		as=as.withEnvironment(Maps.of(Symbols.FOO,CVMLong.ONE));
		as=as.withMetadata(Maps.of(Symbols.FOO,Maps.empty()));
		as=as.withController(Address.create(1546746));
		as=as.withParent(Core.CORE_ADDRESS);
		
		// Round trip through encoding
		as=Format.decodeMultiCell(as.getEncoding());
		
		assertEquals(10,as.getSequence());
		assertEquals(1000,as.getBalance());
		assertEquals(10000,as.getMemory());
		assertEquals(Symbols.FOO,as.getHoldings().get(Address.create(127)));
		assertEquals(CVMLong.ONE,as.getEnvironmentValue(Symbols.FOO));
		assertEquals(Maps.empty(),as.getMetadata().get(Symbols.FOO));
		assertEquals(Address.create(1546746),as.getController());
		assertEquals(Core.CORE_ADDRESS,as.getParent());
		
		assertEquals(Core.CORE_ADDRESS,as.get(Keywords.PARENT));
		
		doAccountStatusTest(as);
	}

	private void doAccountStatusTest(AccountStatus as) throws InvalidDataException  {
		assertTrue(as.isCanonical());
		as.validateCell();
	
		RecordTest.doRecordTests(as);
	}
}
