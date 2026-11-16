package convex.test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures what code under test logs through {@link System.Logger}.
 */
public class LogCapture {

	/**
	 * A captured log record
	 * @param level Name of the java.util.logging level, e.g. "SEVERE"
	 * @param message Message as logged
	 * @param thrown Throwable logged with the message, or null
	 */
	public record Entry(String level, String message, Throwable thrown) {}

	/**
	 * Runs an action and returns what it logged to the named logger on this thread.
	 * Those records are captured instead of written; other threads and loggers log as
	 * before. A test using this holds a ResourceLock on "java.util.logging:" followed by
	 * the logger name.
	 *
	 * @param loggerName Name of the logger, usually the logging class's name
	 * @param action Action to run
	 * @return Records logged on this thread, in order
	 * @throws ReflectiveOperationException If java.util.logging cannot be reached
	 */
	public static List<Entry> capture(String loggerName, Runnable action) throws ReflectiveOperationException {
		// System.Logger uses JUL in the JDK. Access it reflectively so tests patched
		// into convex.core don't require a production dependency on java.logging,
		// or Maven/Eclipse-specific --add-reads launch options.
		Class<?> loggerClass=Class.forName("java.util.logging.Logger");
		Class<?> filterClass=Class.forName("java.util.logging.Filter");
		Class<?> recordClass=Class.forName("java.util.logging.LogRecord");
		Object logger=loggerClass.getMethod("getLogger",String.class).invoke(null,loggerName);
		Object previous=loggerClass.getMethod("getFilter").invoke(logger);
		Thread owner=Thread.currentThread();
		ArrayList<Object> records=new ArrayList<>();
		Object filter=Proxy.newProxyInstance(filterClass.getClassLoader(),new Class<?>[] {filterClass},(proxy,method,args)->{
			if (Thread.currentThread()==owner) {
				records.add(args[0]);
				return false;
			}
			return (previous==null)||((Boolean)method.invoke(previous,args));
		});
		loggerClass.getMethod("setFilter",filterClass).invoke(logger,filter);
		try {
			action.run();
		} finally {
			loggerClass.getMethod("setFilter",filterClass).invoke(logger,previous);
		}
		ArrayList<Entry> entries=new ArrayList<>();
		for (Object r:records) {
			String level=recordClass.getMethod("getLevel").invoke(r).toString();
			String message=(String)recordClass.getMethod("getMessage").invoke(r);
			Throwable thrown=(Throwable)recordClass.getMethod("getThrown").invoke(r);
			entries.add(new Entry(level,message,thrown));
		}
		return entries;
	}
}
