package convex.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoggerTest {

	public static void main(String[] args) {
		// code to load properties from resources
		try {
			InputStream configFile = LoggerTest.class.getResourceAsStream("/logging.properties");
			LogManager.getLogManager().readConfiguration(configFile);
		} catch (IOException e) {
			e.printStackTrace();
		}

		Logger logger = Logger.getLogger(LoggerTest.class.getName());
		logger.info("Hmmm....");
		logger.warning("Danger!!");
	}
}
