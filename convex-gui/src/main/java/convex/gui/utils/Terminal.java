package convex.gui.utils;

import java.awt.GraphicsEnvironment;
import java.io.Console;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class Terminal {

	public static boolean checkIfTerminal() {
		if (GraphicsEnvironment.isHeadless()) return true;
		
		Console c=System.console();
		// If null, we have no terminal (Java up to 21)
		if (c==null) return false;
		
		// We have a console, but in Java 22+ we need to check if it is actually a terminal
		try {
			Method m=c.getClass().getMethod("isTerminal");
			return  (Boolean)m.invoke(c);
		} catch (NoSuchMethodException e) {
			return true;
		} catch (SecurityException | IllegalAccessException | InvocationTargetException e) {
			// Shouldn't happen?
			return false;
		} 
	}

}
