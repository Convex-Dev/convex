package convex.cli;

import java.util.ArrayList;

import convex.core.util.Text;

public class CLIUtils {
	private static String TAB="   ";

	public static String buildTable(String... ss) {
		int n=ss.length/2;
		ArrayList<String> keys=new ArrayList<>();
		ArrayList<String> vals=new ArrayList<>();
		for (int i=0; i<n; i++) {
			keys.add(ss[i*2]);
			vals.add(ss[i*2+1]);
		}

		int maxKey=0;
		for (int i=0; i<n;i++) {
			int keyLen=keys.get(i).length();
			if (keyLen>maxKey) maxKey=keyLen;
		}

		StringBuilder sb=new StringBuilder();
		for (int i=0; i<n;i++) {
			String key=Text.rightPad(keys.get(i), maxKey);
			String val=vals.get(i);
			sb.append(TAB+key+TAB+val+"\n");
		}

		return sb.toString();
	}
	public static String expandTilde(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
	}
}
