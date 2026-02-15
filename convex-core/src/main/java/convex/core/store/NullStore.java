package convex.core.store;

import java.util.function.Consumer;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;

/**
 * A store that never persists anything. Ref lookups always return null,
 * so any RefSoft created against this store will throw MissingDataException
 * on resolution.
 *
 * Useful for decoding messages where only embedded values (e.g. a Result ID)
 * are needed and the decoded cell can be discarded.
 */
public class NullStore extends AStore {

	public static final NullStore INSTANCE = new NullStore();

	private final CVMEncoder encoder = new CVMEncoder(this);

	private NullStore() {}

	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
		return ref;
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
		return ref;
	}

	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		return null;
	}

	@Override
	public Hash getRootHash() {
		return null;
	}

	@Override
	public <T extends ACell> Ref<T> setRootData(T data) {
		return Ref.get(data);
	}

	@Override
	public void close() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		return (T) encoder.decode(encoding);
	}

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}

	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		return null;
	}

	@Override
	public String shortName() {
		return "null";
	}
}
