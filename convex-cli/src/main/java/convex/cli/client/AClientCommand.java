package convex.cli.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.cli.ATopCommand;
import convex.cli.CLIError;
import convex.cli.Constants;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Symbols;
import picocli.CommandLine.Option;

public abstract class AClientCommand extends ATopCommand {
	@Option(names={"--timeout"},
			description="Timeout in miliseconds.")
	protected long timeout = Constants.DEFAULT_TIMEOUT_MILLIS;

	@Option(names={"-a", "--address"},
			defaultValue="${env:CONVEX_ADDRESS}",
			description = "Account address to use. Default: ${DEFAULT-VALUE}")
	protected String addressValue = null;
	
	@Option(names={"--port"},
			defaultValue="${env:CONVEX_PORT}",
			description="Port number to connect to a peer.")
	private Integer port;

	@Option(names={"--host"},
		defaultValue=Constants.HOSTNAME_PEER,
		description="Hostname to connect to a peer. Default: ${DEFAULT-VALUE}")
	private String hostname;

	
	public Address getUserAddress() {
		Address result= Address.parse(addressValue);	
		return result;
	}
	
	protected boolean ensureAddress(Convex convex) {
		Address a = convex.getAddress();
		if (a!=null) return true;
		if (cli().isInteractive()) {
			String s=System.console().readLine("Enter origin address: ");
			a=Address.parse(s);
		}
		if (a!=null) {
			convex.setAddress(a);
			return true;
		}
		return false;
	}
	
	protected boolean ensureKeyPair(Convex convex) {
		AKeyPair keyPair = convex.getKeyPair();
		if (keyPair!=null) return true;

		Address address=convex.getAddress();
		
		// Try to identify the required keypair for the Address
		Result ar;
		try {
			ar = convex.query(Symbols.STAR_KEY).get(1000,TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			ar=null;
		}
		
		String pk=null;
		if (ar==null) {
			// we couldn't query the *key*, so prompt the user
		} else if (!ar.isError()) {
			// Attempt to use query result as public key
			ACell v=ar.getValue();
			if (v instanceof ABlob) {
				pk=((ABlob)v).toHexString();
			}
		}
		
		if (pk==null) {
			pk=cli().prompt("Enter public key: ");
		}
		

		keyPair=mainParent.loadKeyFromStore(pk);
		if (keyPair==null) {
			// We didn't find required keypair
			throw new CLIError("Can't find keypair with public key "+pk+" for Address "+address);
		}
		convex.setKeyPair(keyPair);
		return true;
	}

	protected convex.api.Convex connect() throws IOException,TimeoutException {
		Address a=addressValue==null?null:Address.parse(addressValue);
		
		if (port==null) port=convex.core.Constants.DEFAULT_PEER_PORT;
		if (hostname==null) hostname=convex.cli.Constants.HOSTNAME_PEER;
		try {
			InetSocketAddress sa=new InetSocketAddress(hostname,port);
			Convex c;
			c=Convex.connect(sa);
			
			if (a!=null) {
				c.setAddress(a);
			}
			
			return c;
		} catch (ConnectException ce) {
			throw new CLIError("Cannot connect to: "+hostname+" on port "+port);
		} catch (Exception e) {
			throw e;
		}
	}
}
