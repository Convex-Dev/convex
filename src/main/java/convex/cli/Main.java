package convex.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convex CLI implementation
 */
public class Main {
	/**
	 * Extracts config parameters and populates a config map. Removes handled parameters from arg list.
	 * @return
	 */
	public static Map<String,Object> parseConfig(List<String> args) {
		Map<String,Object> config=new HashMap<>();
		int i=0;
		while (i<args.size()) {
			String arg=args.get(i);
			if ("-h".equals(arg)||"--help".equals(arg)) {
				config.put("help",true);
				args.remove(i);
			} else {
				i++;
			}
		}
		return config;
	}
	
	public static void main(String[] args) {
		List<String> argList=List.of(args);
		Map<String,Object> config = parseConfig(argList);
		
		if (args.length==0 ||config.get("help")!=null) {
			runHelp(argList);
		} else {
			String cmd=argList.get(0);
			if ("key".equals(cmd)) {
				runKey(argList);
			} else {
				runUnknown(cmd);
			}
 			
		}
		
	}

	private static void runKey(List<String> argList) {
		int n=argList.size();
		if (n==1) {
			runHelp(argList);
			return;
		} 
		
		String cmd=argList.get(1);
		if ("gen".equals(cmd)) {
			
		}
	}

	static void runUnknown(String cmd) {
		System.out.println("Unrecognised command: "+cmd);
		System.out.println("Expected key, peer, transact, query");
		System.out.println("Use 'convex --help' for more information");
	}

	static void runHelp(List<String> argList) {
		StringBuilder sb= new StringBuilder();
		Help.buildHelp(sb,argList);
		System.out.println(sb.toString());
	}
}
