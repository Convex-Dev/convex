package convex.cli.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.CVMLong;

public class OutputTest {

	public static final long TEST_LONG_VALUE = 123456789L;
	public static final String TEST_STRING_VALUE = "TEST_VALUE";
	public static final long TEST_ERROR_CODE = 98989898L;

	@Test
	public void testSetField() {

		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		ACell cellValue = Address.create(TEST_LONG_VALUE);
		Output output = new Output();
		output.setField("Test-Long", TEST_LONG_VALUE);
		output.setField("Test-Cell", cellValue);
		output.setField("Test-String", TEST_STRING_VALUE);
		output.writeToStream(printWriter);
		String outputResult = outputWriter.toString();
		String fullText = String.format("Test-Long: %d\nTest-Cell: #%d\nTest-String: %s\n", TEST_LONG_VALUE, TEST_LONG_VALUE, TEST_STRING_VALUE);
		assertEquals(fullText, outputResult.replaceAll("\r\n", "\n"));
	}

	@Test
	public void testSetResult() {
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		ACell cellValue = Address.create(TEST_LONG_VALUE);

		Output output = new Output();
		Result result = Result.create(CVMLong.create(1), cellValue, null);
		output.setResult(result);
		output.writeToStream(printWriter);
		String outputResult = outputWriter.toString();
		String fullText = String.format("Result: #%d\nData type: Address\n", TEST_LONG_VALUE);
		assertEquals(fullText, outputResult.replaceAll("\r\n", "\n"));
	}

	@Test
	public void testSetResultError() {
		StringWriter outputWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(outputWriter);
		ACell cellValue = Address.create(TEST_LONG_VALUE);
		ACell errorCode = CVMLong.create(TEST_ERROR_CODE);
		Output output = new Output();
		Result result = Result.create(CVMLong.create(1), cellValue, errorCode);
		output.setResult(result);
		output.writeToStream(printWriter);
		String outputResult = outputWriter.toString();
		String fullText = String.format("Result: #%d\nError code: %d\n", TEST_LONG_VALUE, TEST_ERROR_CODE);
		assertEquals(fullText, outputResult.replaceAll("\r\n", "\n"));
	}


}
