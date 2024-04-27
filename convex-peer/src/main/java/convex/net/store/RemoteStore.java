package convex.net.store;

import java.io.IOException;
import java.util.function.Consumer;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.store.ACachedStore;

/**
 * A Store implementation working over a Convex binary connection
 */
public class RemoteStore extends ACachedStore {
	
	protected Convex convex;
	
	public RemoteStore(Convex convex) {
		this.convex=convex;
	}

	public RemoteStore create(Convex connection) {
		return new RemoteStore(convex);
	}

	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Hash getRootHash() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <T extends ACell> Ref<T> setRootData(T data) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() {
		convex.close();
	}

	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String shortName() {
		// TODO Auto-generated method stub
		return "Remote Store: "+convex.getHostAddress();
	}

}
