package convex.etch;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.function.Consumer;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;

/** Private uncached decoding context: never touches a live handle's cells or refs. */
final class EtchReadView extends AStore {
	final Etch[] files;
	private final Runnable release;
	private final CVMEncoder encoder=new CVMEncoder(this);
	private boolean closed;

	EtchReadView(Etch[] files, Runnable release) {
		this.files=files;
		this.release=release;
	}

	<T extends ACell> Ref<T> read(Hash hash) throws IOException {
		if (closed) throw new ClosedChannelException();
		Ref<T> found=null;
		for (Etch file:files) {
			Ref<T> ref=file.read(hash,this);
			if (ref!=null && (found==null || ref.getStatus()>found.getStatus())) found=ref;
		}
		return found;
	}

	@Override public <T extends ACell> Ref<T> refForHash(Hash hash) {
		try { return read(hash); }
		catch (IOException e) { throw new StoreException("Cannot read retained Etch file",e); }
	}
	@Override public AEncoder<ACell> getEncoder() { return encoder; }
	@Override public <T extends ACell> Ref<T> checkCache(Hash hash) { return null; }
	@Override public String shortName() { return "Etch read view"; }
	@Override public Hash getRootHash() { throw new UnsupportedOperationException("Read view has no live root"); }
	@Override public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status, Consumer<Ref<ACell>> novelty) { throw readOnly(); }
	@Override public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> novelty) { throw readOnly(); }
	@Override public <T extends ACell> Ref<T> setRootData(T data) { throw readOnly(); }
	@Override public void flush() { throw readOnly(); }
	private UnsupportedOperationException readOnly() { return new UnsupportedOperationException("Read-only Etch view"); }
	@Override public void close() {
		if (closed) return;
		closed=true;
		if (release!=null) release.run();
	}
}
