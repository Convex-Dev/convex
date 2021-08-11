package convex.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class CLIMainTest {

	@Test
	public void testMainGetPortList() {
		Main mainApp = new Main();
		String basicList[] = {"80", "90", "100", "101", "200"};
		int result[] = mainApp.getPortList(basicList, 4);
		assertArrayEquals(new int[]{80, 90, 100, 101}, result);

		String commaList[] = {"80,90,100,101,200"};
		result = mainApp.getPortList(commaList, 4);
		assertArrayEquals(new int[]{80, 90, 100, 101}, result);

		String closedRange[] = {"100-200"};
		result = mainApp.getPortList(closedRange, 4);
		assertArrayEquals(new int[]{100, 101, 102, 103}, result);

		String openRange[] = {"100-"};
		result = mainApp.getPortList(openRange, 6);
		assertArrayEquals(new int[]{100, 101, 102, 103, 104, 105}, result);

		String combinedClosedRange[] = {"80", "100-103", "200"};
		result = mainApp.getPortList(combinedClosedRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 200}, result);

		String combinedOpenRange[] = {"80", "100-", "200", "300"};
		result = mainApp.getPortList(combinedOpenRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 104}, result);

		String combinedCommaClosedRange[] = {"80,100-103,200"};
		result = mainApp.getPortList(combinedCommaClosedRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 200}, result);

		String combinedCommaOpenRange[] = {"80,100-,200,300"};
		result = mainApp.getPortList(combinedCommaOpenRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 104}, result);

		assertThrows(NumberFormatException.class, () -> {
			String badNumberValue[] = {"80,100+,200,300"};
			mainApp.getPortList(badNumberValue, 6);
		});
	}

}
