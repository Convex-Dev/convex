package convex.cli.etch;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.EtchConfigMixin;
import convex.cli.mixins.EtchConfigMixin.KeyContext;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

public abstract class AEtchCommand extends ACommand {
	
	@ParentCommand
	protected Etch etchParent;

	@Mixin
    protected EtchMixin etchMixin;

	@Mixin
	protected EtchConfigMixin etchConfigMixin;

	@Mixin
	protected KeyStoreMixin keyStoreMixin;

	private KeyContext sourceKeys;
	private KeyContext destinationKeys;
	
	@Override
	public Main cli() {
		return etchParent.cli();
	}
	
	public EtchStore store() {
		try {
			return etchMixin.getEtchStore(sourceConfig());
		} catch (RuntimeException | Error e) {
			closeKeyContexts();
			throw e;
		}
	}

	protected EtchConfig sourceConfig() {
		return etchConfigMixin.sourceConfig(sourceKeys());
	}

	protected KeyContext sourceKeys() {
		if (sourceKeys==null) sourceKeys=etchConfigMixin.sourceContext(keyStoreMixin);
		return sourceKeys;
	}

	protected KeyContext destinationKeys() {
		if (destinationKeys==null) {
			destinationKeys=etchConfigMixin.destinationContext(keyStoreMixin);
		}
		return destinationKeys;
	}

	protected EtchConfig destinationConfig(EtchConfig source, boolean preserveSource) {
		KeyContext destination=destinationKeys();
		if (!etchConfigMixin.hasDestinationKey()) {
			destination.inheritResolvedKey(sourceKeys());
		}
		return etchConfigMixin.destinationConfig(source,destination,preserveSource);
	}

	protected void closeKeyContexts() {
		if (destinationKeys!=null) destinationKeys.close();
		if (sourceKeys!=null) sourceKeys.close();
		destinationKeys=null;
		sourceKeys=null;
	}

}
