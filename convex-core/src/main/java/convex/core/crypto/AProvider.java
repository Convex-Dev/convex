package convex.core.crypto;

import java.security.Provider;

/**
 * Abstract base class for Custom Convex security providers
 */
@SuppressWarnings("serial")
public abstract class AProvider extends Provider {

	protected AProvider(String name, String versionStr, String info) {
		super(name, versionStr, info);
	}

}
