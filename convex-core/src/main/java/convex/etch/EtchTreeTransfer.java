package convex.etch;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;

/** Child-first copying shared by live GC and independent checkpoints. */
final class EtchTreeTransfer {

	interface Access {
		Ref<ACell> read(Hash hash) throws IOException;
		boolean contains(Hash hash) throws IOException;
		void write(Ref<ACell> ref) throws IOException;
		default void check() throws IOException { checkInterrupted(); }
	}

	static void checkInterrupted() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Etch tree transfer interrupted");
	}

	static boolean emptyRoot(Hash hash) {
		return Hash.UNSET_HASH.equals(hash) || Hash.NULL_HASH.equals(hash) || Hash.EMPTY_HASH.equals(hash);
	}

	static void transfer(Hash root, Access access) throws IOException {
		access.check();
		if (emptyRoot(root)) return;
		ArrayList<Frame> stack=new ArrayList<>();
		stack.add(new Frame(root));
		while (!stack.isEmpty()) {
			access.check();
			Frame frame=stack.getLast();
			if (frame.ref==null) {
				if (access.contains(frame.hash)) {
					stack.removeLast();
					continue;
				}
				frame.ref=access.read(frame.hash);
				Cells.visitBranchRefs(frame.ref.getValue(),br->stack.add(new Frame(br.getHash())));
			} else {
				access.write(frame.ref);
				stack.removeLast();
			}
		}
	}

	private static final class Frame {
		final Hash hash;
		Ref<ACell> ref;
		Frame(Hash hash) { this.hash=hash; }
	}
}
