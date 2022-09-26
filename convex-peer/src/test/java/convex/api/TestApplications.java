package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestApplications {

	@Test 
	public void testProcess() throws Exception {
		Process p=Applications.launchApp(TestApplications.class);
		p.waitFor();
		assertEquals(0,p.exitValue());
	}
	
	@Test 
	public void testProcessWithArgs() throws Exception {
		Process p=Applications.launchApp(TestApplications.class,"foo","bar");
		p.waitFor();
		assertEquals(2,p.exitValue());
	}
	
	/**
	 * Test main class for launch
	 * @param args Command line arguments
	 */
	public static void main(String[] args) {
		System.exit(args.length);
	}
}
