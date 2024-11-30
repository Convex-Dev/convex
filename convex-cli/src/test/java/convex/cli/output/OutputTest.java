package convex.cli.output;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.cvm.Address;

public class OutputTest {

	public static final long TEST_LONG_VALUE = 123456789L;
	public static final String TEST_STRING_VALUE = "TEST_VALUE";
	public static final long TEST_ADDRESS_VLAUE = 98989898L;

	@Test
	public void testSetField() {

		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		ACell cellValue = Address.create(TEST_ADDRESS_VLAUE);
		TableOutput output = new TableOutput("Test-Long","Test-Cell","Test-String");
		output.addRow(TEST_LONG_VALUE,cellValue, TEST_STRING_VALUE);
		output.writeToStream(printWriter);
		String outputResult = outputWriter.toString();
		assertTrue(outputResult.contains("12345678"));
		assertTrue(outputResult.contains("TEST_VALUE"));
		assertTrue(outputResult.contains("#98989898"));
	}




}
