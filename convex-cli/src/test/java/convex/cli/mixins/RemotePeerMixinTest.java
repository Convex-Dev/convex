package convex.cli.mixins;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.cli.CLIError;
import picocli.CommandLine;

public class RemotePeerMixinTest {

	private RemotePeerMixin parse(String... args) {
		RemotePeerMixin mixin=new RemotePeerMixin();
		CommandLine command=new CommandLine(mixin);
		// Keep these parser tests independent of the operator's environment.
		var spec=command.getCommandSpec();
		for (String name:new String[] {"--host","--port"}) {
			var option=spec.findOption(name);
			spec.remove(option);
			spec.addOption(option.toBuilder().defaultValue(null).build());
		}
		command.parseArgs(args);
		return mixin;
	}

	@Test
	public void preservesTLSForConnectionsAndSync() {
		RemotePeerMixin mixin=parse("--host","tls://localhost:18889");
		assertEquals(URI.create("tls://localhost:18889"),mixin.getEndpoint());
		assertEquals(mixin.getEndpoint(),mixin.getSpecifiedEndpoint());
		assertEquals(18889,mixin.getSocketAddress().getPort());
		assertEquals(URI.create("tls://localhost:19000"),
			parse("--host","tls://localhost:18889","--port","19000").getEndpoint());
	}

	@Test
	public void unqualifiedAddressesKeepTCPDefaults() {
		RemotePeerMixin mixin=parse("--host","localhost");
		assertEquals("tcp",mixin.getEndpoint().getScheme());
		assertEquals(18888,mixin.getSocketAddress().getPort());
		assertEquals(19000,parse("--host","localhost:19000").getSocketAddress().getPort());
	}

	@Test
	public void disabledAndInvalidEndpoints() {
		RemotePeerMixin disabled=parse("--host","none");
		assertNull(disabled.getSpecifiedEndpoint());
		assertThrows(CLIError.class,disabled::getEndpoint);
		assertThrows(CLIError.class,() -> parse("--host","localhost","--port","-1").getEndpoint());
		assertThrows(CLIError.class,() -> parse("--host","localhost","--port","65536").getEndpoint());
		assertThrows(CLIError.class,() -> parse("--host","tls://").getEndpoint());
	}
}
