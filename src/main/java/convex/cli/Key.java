package convex.cli;

import java.io.File;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import convex.core.crypto.PFXTools;

public class Key {

	static void buildKeyHelp(StringBuilder sb, List<String> commands) {
		String cmd=commands.get(0);

		sb.append("Usage: convex [OPTIONS] "+cmd+" COMMAND ... \n");
		sb.append('\n');
		sb.append("Commands:\n");
		sb.append(CLIUtils.buildTable(
				"gen",     "Generate a new private key pair.",
				"list",    "List available key pairs."));
		sb.append('\n');
		sb.append("Options:\n");
		sb.append(CLIUtils.buildTable(
				"-h, --help",           "Display help for the command '"+cmd+"'.",
				"-k, --keystore FILE",  "Use the specified keystore. Defaults to the keystore specified in config file, or '~/.convex/keystore otherwise'",
				"-c, --config FILE",    "Use the specified config file. Defaults to '~/.convex/config'",
				"-p, --passphrase", "Specify a passphrase to protect key. If not specified, will be promtped."));
	}

	static int runKey(List<String> commands, Properties config) {
		if (commands.size()<=1) {
			return Help.runHelp(commands);
		}

		String cmd=commands.get(1);
		if ("gen".equals(cmd)) {
			return runKeyGen(config);
		} else {
			System.out.println("Unrecognised key command: "+cmd);
			return 1;
		}
	}

	private static int runKeyGen(Properties config) {
		String keyPath=CLIUtils.expandTilde(config.getProperty("keystore"));
		String passPhrase=config.getProperty("passphrase");
		File keyFile = new File(keyPath);
		for ( Provider provider : Security.getProviders()) {
            System.out.println(provider.getName());
		}
		if (!keyFile.exists()) {
            if (passPhrase == null) {
                System.out.print("Enter in your pass phrase: ");
                Scanner scanner = new Scanner(System.in);
                passPhrase=scanner.nextLine();
            }
			System.out.println("Creating key store: "+keyFile);
			try {
				PFXTools.createStore(keyFile, passPhrase);
			} catch (Throwable e) {
				// TODO Auto-generated catch block
				System.out.println("Unable to create keystore");
				e.printStackTrace();
				return 1;
			}
		}
		return 0;
	}

}
