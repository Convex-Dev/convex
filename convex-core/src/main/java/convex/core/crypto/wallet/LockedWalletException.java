package convex.core.crypto.wallet;

import convex.core.exceptions.BaseException;

@SuppressWarnings("serial")
public class LockedWalletException extends BaseException {

	public LockedWalletException(String message) {
		super(message);
	}
	public LockedWalletException(String message, Throwable cause) {
		super(message, cause);
	}

}
