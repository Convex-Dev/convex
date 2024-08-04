package convex.cli.mixins;

import picocli.CommandLine.Mixin;

public class ClientMixin extends AMixin {

	@Mixin 
	ClientKeyMixin clientKeyMixin;
	
	@Mixin
	RemotePeerMixin peerMixin;
	
	
}
