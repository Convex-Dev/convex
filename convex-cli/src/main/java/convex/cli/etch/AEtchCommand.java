package convex.cli.etch;

import convex.cli.ACommand;
import convex.cli.Main;
import convex.cli.mixins.EtchConfigMixin;
import convex.cli.mixins.EtchConfigMixin.KeyContext;
import convex.cli.mixins.EtchMixin;
import convex.cli.mixins.KeyStoreMixin;
import convex.core.util.Cleanup;
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
		KeyContext destination=destinationKeys;
		KeyContext source=sourceKeys;
		destinationKeys=null;
		sourceKeys=null;
		Cleanup cleanup=new Cleanup();
		if (destination!=null) cleanup.run(destination::close);
		if (source!=null) cleanup.run(source::close);
		cleanup.finish();
	}

	/** Closes the store, then the key contexts, which wipe their key material even if the store's close fails. */
	protected void closeStoreAndKeys(EtchStore store) {
		Cleanup cleanup=new Cleanup();
		cleanup.run(store::close);
		cleanup.run(this::closeKeyContexts);
		cleanup.finish();
	}

}
