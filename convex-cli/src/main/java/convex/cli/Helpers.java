package convex.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import convex.core.crypto.PFXTools;
import convex.core.util.Utils;

/**
 *
 * Helpers
 *
 *	Helper functions for the CLI classes.
 *
*/
public class Helpers {

	/**
	 * Expand a path string with a '~'. The tilde is expanded to the users home path.
	 *
	 * @param path Path string to expand.
	 *
	 * @return Expanded string if a tilde is present.
	 *
	 */
	public static String expandTilde(String path) {
		if (path==null) return null;
		
		String userHome=System.getProperty("user.home");
		String separator=File.separator;
		String regex = (separator.equals("\\")) ? "\\\\" : "/";
		userHome=userHome.replaceAll(regex, "/");
		return path.replaceFirst("^~", userHome);
	}



	/**
	 * Split a parameter list by ','. 
	 * Handles internal separators (sublists in strings)
	 * Trims resulting Strings of whitespace
	 * @param parameterValues Array of parameter values
	 * @return List of trimmed Strings
	 */
	public static List<String> splitArrayParameter(String... parameterValues) {
		List<String> result = new ArrayList<>(parameterValues.length);
		for (int index = 0; index < parameterValues.length; index ++) {
			String value = parameterValues[index];
			
			if (value.indexOf(",") > 0) {
				String[] items  = value.split(",");
				for (int itemIndex = 0; itemIndex < items.length; itemIndex ++ ) {
					String newValue = items[itemIndex].trim();
					if (newValue.length() > 0) {
						result.add(newValue);
					}
				}
			} else {
				result.add(value.trim());
			}
		}
		return result;
	}

	public static File createTempFile(String name, String ext) {
		try {
			File temp=File.createTempFile(name,ext);
			temp.deleteOnExit();
			return temp;
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
		
	}
	
	public static File createTempKeystore(String name, char[] password) {
		try {
			File temp=File.createTempFile(name,".pfx");
			PFXTools.createStore(temp, password);
			return temp;
		} catch (IOException|GeneralSecurityException e) {
			throw Utils.sneakyThrow(e);
		}
		
	}
	
	public static int[] getPortList(String ports[], int count) throws NumberFormatException {
		Pattern rangePattern = Pattern.compile(("([0-9]+)\\s*-\\s*([0-9]*)"));
		List<String> portTextList = Helpers.splitArrayParameter(ports);
		List<Integer> portList = new ArrayList<Integer>();
		int countLeft = count;
		for (int index = 0; index < portTextList.size() && countLeft > 0; index ++) {
			String item = portTextList.get(index);
			Matcher matcher = rangePattern.matcher(item);
			if (matcher.matches()) {
				int portFrom = Integer.parseInt(matcher.group(1));
				int portTo = portFrom  + count + 1;
				if (!matcher.group(2).isEmpty()) {
					portTo = Integer.parseInt(matcher.group(2));
				}
				for ( int portIndex = portFrom; portIndex <= portTo && countLeft > 0; portIndex ++, --countLeft ) {
					portList.add(portIndex);
				}
			}
			else if (item.strip().length() == 0) {
			}
			else {
				portList.add(Integer.parseInt(item));
				countLeft --;
			}
		}
		return portList.stream().mapToInt(Integer::intValue).toArray();
	}
}


