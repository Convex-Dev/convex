package convex.cli.mixins;

import picocli.CommandLine.Mixin;

public class ClientKeyMixin {

	@Mixin 
	public KeyMixin keyMixin;
	
	@Mixin
	public KeyStoreMixin storeMixin;
}
