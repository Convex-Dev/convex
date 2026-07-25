package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.cli.Main;
import convex.restapi.RESTConfig;

/** Verifies that CLI peer launch preserves nested REST runtime policy. */
class PeerConfigLoadingTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsAndAttachesTypedRestConfiguration() throws Exception {
		Path file=temporaryDirectory.resolve("peer-config.json5");
		Files.writeString(file,"{auth:{publicAccess:false},mcp:{enabled:false}}");

		Main main=new Main();
		var parseResult=main.commandLine.parseArgs("peer","--config",file.toString());
		Peer peer=(Peer) parseResult.subcommand().commandSpec().userObject();
		var launchConfig=peer.loadPeerConfig();
		RESTConfig config=(RESTConfig) launchConfig.get(RESTConfig.CONFIG);

		assertFalse(config.isPublicAccess());
		assertFalse(config.isMcpEnabled());
		assertSame(config,launchConfig.get(RESTConfig.CONFIG));
	}
}
