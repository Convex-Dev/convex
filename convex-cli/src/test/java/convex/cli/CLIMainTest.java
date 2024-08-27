package convex.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class CLIMainTest {

	@Test
	public void testMainGetPortList() {
		String basicList[] = {"80", "90", "100", "101", "200"};
		int result[] = Helpers.getPortList(basicList, 4);
		assertArrayEquals(new int[]{80, 90, 100, 101}, result);

		String commaList[] = {"80,90,100,101,200"};
		result = Helpers.getPortList(commaList, 4);
		assertArrayEquals(new int[]{80, 90, 100, 101}, result);

		String closedRange[] = {"100-200"};
		result = Helpers.getPortList(closedRange, 4);
		assertArrayEquals(new int[]{100, 101, 102, 103}, result);

		String openRange[] = {"100-"};
		result = Helpers.getPortList(openRange, 6);
		assertArrayEquals(new int[]{100, 101, 102, 103, 104, 105}, result);

		String combinedClosedRange[] = {"80", "100-103", "200"};
		result = Helpers.getPortList(combinedClosedRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 200}, result);

		String combinedOpenRange[] = {"80", "100-", "200", "300"};
		result = Helpers.getPortList(combinedOpenRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 104}, result);

		String combinedCommaClosedRange[] = {"80,100-103,200"};
		result = Helpers.getPortList(combinedCommaClosedRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 200}, result);

		String combinedCommaOpenRange[] = {"80,100-,200,300"};
		result = Helpers.getPortList(combinedCommaOpenRange, 6);
		assertArrayEquals(new int[]{80, 100, 101, 102, 103, 104}, result);

		String badNumberValue[] = {"80,100+,200,300"};
		assertNull(Helpers.getPortList(badNumberValue, 6));
	}

}
