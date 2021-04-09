package convex.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Init;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.core.transactions.Invoke;

public class CLIClient {
	static void buildTransactHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);
		
		sb.append("Usage: convex [OPTIONS] "+cmd+" '<code>' ... \n");
		sb.append('\n');
		sb.append("Where:");
		sb.append("  <code>  = A valid Convex Lisp expression");
		sb.append('\n');		
		sb.append("Options:\n");
		sb.append(CLIUtils.buildTable(
				"-h, --help",           "Display help for the command '"+cmd+"'.",
				"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
				"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
				"-p, --passphrase", "Specify a passphrase to protect key. If not specified, will be promtped."));
	}

	public static int runTransact(List<String> commands, Properties config) {
		if (commands.size()<=1) {
			return Help.runHelp(commands);
		}
		
		String cmd=commands.get(1);
		Convex convex=connect(config);
		if (convex==null) {
			System.out.println("Aborting transaction");
			return 1;
		}
		
		try {
			System.out.println("Executing transaction: "+cmd);
			ACell exp=Reader.read(cmd);
			Result result=convex.transactSync(Invoke.create(convex.getAddress(), 0, exp));
			System.out.println("Result received:");
			System.out.println(result);
		} catch (IOException e) {
			System.out.println("Transaction Error: "+e.getMessage());
			// TODO Auto-generated catch block
			e.printStackTrace();
			return 1;
		}  catch (TimeoutException e) {
			System.out.println("Transaction timeout");
		}
		
		return 0;
	}
	
	static void buildQueryHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);
		
		sb.append("Usage: convex [OPTIONS] "+cmd+" '<code>' ... \n");
		sb.append('\n');
		sb.append("Where:");
		sb.append("  <code>  = A valid Convex Lisp expression");
		sb.append('\n');		
		sb.append("Options:\n");
		sb.append(CLIUtils.buildTable(
				"-h, --help",           "Display help for the command '"+cmd+"'.",
				"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
				"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
				"-p, --passphrase", "Specify a passphrase to protect key. If not specified, will be promtped."));
	}

	public static int runQuery(List<String> commands, Properties config) {
		if (commands.size()<=1) {
			return Help.runHelp(commands);
		}

		String cmd=commands.get(1);
		Convex convex=connect(config);
		if (convex==null) {
			System.out.println("Aborting query");
			return 1;
		}
		
		try {
			System.out.println("Executing query: "+cmd);
			ACell exp=Reader.read(cmd);
			Result result=convex.transactSync(Invoke.create(convex.getAddress(), 0, exp));
			System.out.println("Result received:");
			System.out.println(result);
		} catch (IOException e) {
			System.out.println("Query Error: "+e.getMessage());
			// TODO Auto-generated catch block
			e.printStackTrace();
			return 1;
		}  catch (TimeoutException e) {
			System.out.println("Query timeout");
		}
		
		return 0;
	}
	
	public static Convex connect(Properties config) {
		String server=config.getProperty("server");
		if (server==null) server = "convex.world";
		InetSocketAddress host=new InetSocketAddress(server, 43579);
		System.out.println("Connecting to peer: "+host);
		try {
			Convex convex=Convex.connect(host, Init.HERO, Init.HERO_KP);
			return convex;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Failed to connect to peer at: "+host);
			return null;
		}
	}

}
