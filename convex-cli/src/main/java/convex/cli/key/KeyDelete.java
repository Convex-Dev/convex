package convex.cli.key;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Enumeration;
import java.util.HashSet;

import convex.cli.CLIError;
import convex.cli.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name="delete",
	mixinStandardHelpOptions=false,
	description="Delete key(s) from the keystore. Use with caution")
public class KeyDelete extends AKeyCommand {

	static final String WILD="+";
	
	@Parameters(
			paramLabel="keys", 
			description="Key(s) to delete. Should be a hex prefix of a specific key, or trailing '"+WILD+"' as a wildcard")
	private String[] keys;
	
	protected void deleteEntry(String alias) throws KeyStoreException {
		storeMixin.getKeystore().deleteEntry(alias);
		inform("Deleting Key: "+alias);
	}
	
	@Override
	public void execute() {
		
		if ((keys==null)||(keys.length==0)) {
			showUsage();
			return;
		}
		
		KeyStore keyStore = storeMixin.ensureKeyStore();
		if (keyStore==null) throw new CLIError("Keystore does not exist. Specify a valid --keystore or use `convex key gen` to create one.");
		
		HashSet<String> toDelete=new HashSet<>();
		
		for (String argKey : keys) {
			String key=argKey.trim();
			if (key.startsWith("0x")) key=key.substring(2);
			if (key.length()==0) throw new CLIError(ExitCodes.DATAERR,"Empty key specified?");
			
			// If a trailing wildcard was used, strip it and set wildcard flag
			boolean wild=key.endsWith(WILD);
			if (wild) {
				key=key.substring(0,key.length()-WILD.length());
			}
			
			inform(3,"Looking for keys to delete like: "+key);
			
			String found=null;
			Enumeration<String> aliases;
			try {
				aliases = keyStore.aliases();
				while (aliases.hasMoreElements()) {
					String alias = aliases.nextElement();
					if (alias.startsWith(key)) {
						if (wild) {
							toDelete.add(alias);
						} else {
							if (found!=null) {
								throw new CLIError("Duplicate keys found for prefix: "+argKey);
							}
							found=alias;
						}
					}
				}
			} catch (KeyStoreException e) {
				throw new CLIError("Unexpected error reading keystore",e);
			}
			
			// check if we can remove specific key
			if (found!=null) {
				toDelete.add(found);
			}
		}
		
		if (toDelete.isEmpty()) {
			informWarning("No matching keys found");
		} else {
			for (String s: toDelete) {
				try {
					deleteEntry(s);
				} catch (KeyStoreException e) {
					throw new CLIError("Unable to remove key: "+s,e);
				}
			}
		}
		storeMixin.saveKeyStore();
	}


}
