package convex.core.exceptions;

import convex.core.data.SignedData;

@SuppressWarnings("serial")
public class BadSignatureException extends ValidationException {

	private SignedData<?> sig;

	public BadSignatureException(String message, SignedData<?> sig) {
		super(message);
		this.sig = sig;
	}

	@SuppressWarnings("unchecked")
	public <T> SignedData<T> getSignature() {
		return (SignedData<T>) sig;
	}

}
