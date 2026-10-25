package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.Function;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;

/** An unsigned view over signed storage, with an explicit authoring function. */
public class SignedCursor<V extends ACell> extends AUpdateCursor<V,SignedData<V>> {
	private final Function<V,SignedData<V>> signer;

	private SignedCursor(ACursor<SignedData<V>> base,
			Function<V,SignedData<V>> signer) {
		super(base);
		this.signer=Objects.requireNonNull(signer);
	}

	/** The caller binds authorisation and signing policy before constructing this view. */
	public static <V extends ACell> SignedCursor<V> create(ACursor<SignedData<V>> base,
			Function<V,SignedData<V>> signer) {
		return new SignedCursor<>(base,signer);
	}

	/** Convenience authoring function for a single explicit key. */
	public static <V extends ACell> SignedCursor<V> create(ACursor<SignedData<V>> base, AKeyPair keyPair) {
		Objects.requireNonNull(keyPair);
		return create(base,keyPair::signData);
	}

	@Override protected V view(SignedData<V> stored) {
		return (stored==null)?null:stored.getValue();
	}

	@Override protected SignedData<V> prepareWrite(V value) {
		SignedData<V> signed=signer.apply(value);
		if (signed==null) throw new IllegalStateException("No authorised signer available");
		return signed;
	}
}
