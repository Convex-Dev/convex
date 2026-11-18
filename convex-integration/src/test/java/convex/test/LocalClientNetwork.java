package convex.test;

import java.net.InetSocketAddress;
import java.net.URI;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.store.MemoryStore;
import convex.java.ConvexJSON;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;

/** One in-memory peer and REST endpoint for all client tests in a JUnit run. */
public final class LocalClientNetwork implements AutoCloseable {
	private final MemoryStore store=new MemoryStore();
	private Server peer;
	private RESTServer rest;

	private LocalClientNetwork() {
		try {
			RESTConfig restConfig=RESTConfig.parse("{rest:{faucet:true}}");
			var config=restConfig.toLegacy();
			config.put(Keywords.KEYPAIR,AKeyPair.generate());
			config.put(Keywords.STORE,store);
			config.put(Keywords.PORT,0);
			config.put(Keywords.PROTOCOL_VERSION,1L);
			config.put(Keywords.AUTO_MANAGE,false);
			config.put(Keywords.OUTGOING_CONNECTIONS,0);
			peer=API.launchPeer(config);
			rest=RESTServer.create(peer,restConfig);
			rest.start(0);
		} catch (Exception e) {
			try { close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
			throw new IllegalStateException("Could not start local client network",e);
		}
	}

	public URI uri() {
		return URI.create("http://localhost:"+rest.getPort());
	}

	public InetSocketAddress binaryAddress() {
		return new InetSocketAddress("localhost",peer.getPort());
	}

	public AccountKey peerKey() {
		return peer.getKeyPair().getAccountKey();
	}

	public ConvexJSON jsonClient() {
		return ConvexJSON.connect(uri().toString());
	}

	/** Each caller owns its account and sequence, while funding uses the shared faucet. */
	public ConvexJSON fundedClient() {
		ConvexJSON client=jsonClient();
		client.useNewAccount(1_000_000);
		return client;
	}

	@Override
	public void close() {
		try {
			if (rest!=null) rest.close();
		} finally {
			try { if (peer!=null) peer.close(); }
			finally { store.close(); }
		}
	}

	/** The root store creates atomically and closes the fixture after all test classes have finished. */
	public static final class Extension implements ParameterResolver {
		@Override
		public boolean supportsParameter(ParameterContext parameter,ExtensionContext context) {
			return parameter.getParameter().getType()==LocalClientNetwork.class;
		}

		@Override
		public Object resolveParameter(ParameterContext parameter,ExtensionContext context) {
			return context.getRoot().getStore(ExtensionContext.Namespace.create(LocalClientNetwork.class))
					.computeIfAbsent(LocalClientNetwork.class,k->new LocalClientNetwork(),LocalClientNetwork.class);
		}
	}
}
