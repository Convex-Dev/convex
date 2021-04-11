package convex.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Convex CLI implementation
 */
public class Main {
	public static final String DEFAULT_CONFIG_FILE="~/.convex/config";
	
	/**
	 * Extracts config parameters and populates a config map. Removes handled parameters from arg list.
	 * @return
	 */
	public static Properties parseConfig(List<String> args) {
		Properties cmdConfig=new Properties();
		int i=0;
		while (i<args.size()) {
			String arg=args.get(i);
			if ("-h".equals(arg)||"--help".equals(arg)) {
				cmdConfig.put("help","true");
				args.remove(i);
			} else if ("-c".equals(arg)||"--config".equals(arg)) {
				cmdConfig.put("config",args.get(i+1));
				args.remove(i);
				args.remove(i);
			} else if ("-s".equals(arg)||"--server".equals(arg)) {
				cmdConfig.put("server",args.get(i+1));
				args.remove(i);
				args.remove(i);
			} else if ("-a".equals(arg)||"--address".equals(arg)) {
					cmdConfig.put("address",args.get(i+1));
					args.remove(i);
					args.remove(i);
			} else if ("--port".equals(arg)) {
				cmdConfig.put("port",args.get(i+1));
				args.remove(i);
				args.remove(i);
			} else if ("-k".equals(arg)||"--keystore".equals(arg)) {
				cmdConfig.put("keystore",args.get(i+1));
				args.remove(i);
				args.remove(i);
			} else {
				i++;
			}
		}
		
		// Merge into default properties
		Properties config=new Properties();
		
		// Config file name commed from command line first, then default
		String fname=cmdConfig.getProperty("config");
		if (fname==null) fname=DEFAULT_CONFIG_FILE;
		File f=new File(fname);
		
		if (f.exists()) {
			try(FileInputStream input = new FileInputStream(f)) {
				config.load(new FileInputStream(f));
				config.setProperty("config", fname); // Set if successfully loaded
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("Failed to read config file: "+f);
			} catch (IllegalArgumentException e) {
				System.out.println("Bad format in config file: "+f);
			}
 		}
		
		// copy config
		for (Map.Entry<Object,Object> e: cmdConfig.entrySet()) {
			config.put(e.getKey(),e.getValue());
		}
		
		return config;
	}
	
	public static void main(String... args) {
		List<String> argList=new ArrayList<>(List.of(args));
		Properties config = parseConfig(argList);
		
		int retVal=2;
		if (args.length==0 ||config.get("help")!=null) {
			retVal=Help.runHelp(argList);
		} else {
			String cmd=argList.get(0);
			if ("config".equals(cmd)) {
				retVal=runConfig(argList,config);
			} else if ("help".equals(cmd)) {
				argList.remove(0);
				retVal=Help.runHelp(argList);
			} else if ("key".equals(cmd)) {
				retVal=Key.runKey(argList,config);
			} else if ("peer".equals(cmd)) {
				retVal=Peer.runPeer(argList,config);
			} else if ("transact".equals(cmd)) {
				retVal=CLIClient.runTransact(argList,config);
			} else if ("query".equals(cmd)) {
				retVal=CLIClient.runQuery(argList,config);
			} else{
				retVal=runUnknown(cmd);
			}
 			
		}
		System.exit(retVal);
	}

	@SuppressWarnings("rawtypes")
	private static int runConfig(List<String> argList, Properties config) {
		// Print source config file. Note this works as a .propertioes file comment so output can be piped to a new properties file if desired.
		String configFile=config.getProperty("config");
		if (configFile!=null) {
			System.out.println("# Using configuration file: "+config.getProperty("config"));
		} else {
			System.out.println("# Note: Config file not found");
		}
		
		Set<Map.Entry<Object,Object>> configEntries=config.entrySet();
		if (configEntries.size()==0)  {
			System.out.println("# Note: No config entries set");
		} else {
			// Display individual entries
			for (Map.Entry e:configEntries) {
				System.out.println(e.getKey()+" = "+e.getValue());
			}
		}
		return 0;
	}

	static int runUnknown(String cmd) {
		System.out.println("Unrecognised command: "+cmd);
		System.out.println("Expected key, peer, transact, query, config, help");
		System.out.println("Use 'convex --help' for more information");
		return 1;
	}
}
