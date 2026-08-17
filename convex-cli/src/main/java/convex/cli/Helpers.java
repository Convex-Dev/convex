package convex.cli;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import convex.core.crypto.PFXTools;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
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
	 * Applies the requested protocol version to a freshly created genesis state for
	 * a NEW network. Defaults to the latest supported version — a fresh network has
	 * no history to preserve, so it should not launch with known-fixed bugs. Pin a
	 * lower version with --protocol-version (e.g. 0 to match a network that has not
	 * yet upgraded).
	 *
	 * @param genesis Freshly created genesis state
	 * @param protocolVersion Requested protocol version, or null for latest
	 * @return Genesis state at the requested protocol version
	 */
	public static State applyGenesisProtocol(State genesis, Long protocolVersion) {
		long target = (protocolVersion == null) ? Migrations.MAX_VERSION : protocolVersion;
		if ((target < 0) || (target > Migrations.MAX_VERSION)) {
			throw new CLIError(ExitCodes.USAGE,
					"--protocol-version must be in range 0.." + Migrations.MAX_VERSION);
		}
		return Migrations.applyTo(genesis, target);
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

			if (value.indexOf(",") >= 0) {
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

	public static File createTempFile(String name, String ext) throws IOException {
		File temp=File.createTempFile(name,ext);
		temp.deleteOnExit();
		return temp;
	}
	
	public static File createTempKeystore(String name, char[] password) throws IOException, GeneralSecurityException {
		File temp=File.createTempFile(name,".pfx");
		temp.deleteOnExit();
		PFXTools.createStore(temp, password);
		return temp;
	}
	
	/**
	 * Gets list of ports from strings containing port ranges
	 * @param ports List of ports or port ranges
	 * @param count Number of ports to select
	 * @return Port list, or null if parsing failed
	 */
	public static int[] getPortList(String ports[], int count)  {
		try {
			Pattern rangePattern = Pattern.compile(("([0-9]+)\\s*-\\s*([0-9]*)"));
			List<String> portTextList = Helpers.splitArrayParameter(ports);
			List<Integer> portList = new ArrayList<Integer>();
			int countLeft = count;
			for (int index = 0; index < portTextList.size() && countLeft > 0; index ++) {
				String item = portTextList.get(index);
				Matcher matcher = rangePattern.matcher(item);
				if (matcher.matches()) {
					int portFrom = Integer.parseInt(matcher.group(1));
					// Open-ended range: allow up to `count` ports from the start of the range
					int portTo = portFrom + count - 1;
					if (!matcher.group(2).isEmpty()) {
						portTo = Integer.parseInt(matcher.group(2));
					}
					for ( int portIndex = portFrom; portIndex <= portTo && countLeft > 0; portIndex ++, --countLeft ) {
						portList.add(portIndex);
					}
				}
				else if (item.isBlank()) {
					// just skip
				} else {
					portList.add(Integer.parseInt(item));
					countLeft --;
				}
			}
			return portList.stream().mapToInt(Integer::intValue).toArray();
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String getConvexArt() {
		try {
			String art=Utils.readResourceAsString("/art/convex.logo");
			return art;
		} catch (IOException e) {
			return null;
		}
	}
}


