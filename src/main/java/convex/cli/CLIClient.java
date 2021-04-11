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
import convex.core.util.Utils;

public class CLIClient {
	
	static String optionTable=CLIUtils.buildTable(
			"-h, --help",           "Display help for this command.",
			"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
			"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
			"-p, --passphrase", "Specify a passphrase to protect key. If not specified, will be promtped.",
			"-port PORT",      "Connect to peer on a specified port for transaction. Defaults to the port specified for the peer server.",
			"-s, --server",   "Specifies the peer server to connect to.",
			"-a, --address ADDRESS","Use the specified user account address. Overrides configured Address if any."
			);
	
	static void buildTransactHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);
		
		sb.append("Usage: convex [OPTIONS] "+cmd+" '<code>' ... \n");
		sb.append('\n');
		sb.append("Where:");
		sb.append("  <code>  = A valid Convex Lisp expression");
		sb.append('\n');
		sb.append("Executes a transaction on the Convex network, submitted via a Convex Peer server.");
		sb.append('\n');
		sb.append("To execute a transaction sucessfully:\n");
		sb.append(" A) The Peer Server must be accessible and accepting client transactions.\n");
		sb.append(" B) The transaction must be signed using the correct private key for the Account.\n");
		sb.append('\n');		
		sb.append("Options:\n");
		sb.append(optionTable);
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
		sb.append(optionTable);
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
			Result result=convex.querySync(exp);
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
	
	/**
	 * Create a Convex client connection using the given configuration properties
	 * @param config
	 * @return Convex client connection instance
	 */
	public static Convex connect(Properties config) {
		String server=config.getProperty("server");
		if (server==null) server = "convex.world";
		
		Integer port=null;
		String ps=config.getProperty("port");
		if (ps!=null) try {
			port=Utils.toInt(ps);
		} catch (Throwable t) {
			System.out.println("Bad port specified: "+ps);
			return null;
		}
		if (port==null) port=43579;
		
		InetSocketAddress host=new InetSocketAddress(server, port);
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
