package convex.peer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;

import convex.core.cpos.BlockResult;
import convex.core.cvm.Peer;

/**
 * Incrementally scans block results from finalised peer state.
 *
 * <p>Each accepted {@link Peer} advances the cursor to that peer's executed
 * state position. Intermediate peer snapshots may be omitted because a later
 * snapshot retains every intervening {@link BlockResult}.</p>
 */
public final class FinalisedBlockScanner implements Consumer<Peer> {

	private final ObjLongConsumer<? super BlockResult> consumer;
	private long nextBlock;

	/**
	 * Creates a scanner.
	 *
	 * @param nextBlock First block position to observe
	 * @param consumer Consumer called with each block result and its position
	 */
	public FinalisedBlockScanner(long nextBlock, ObjLongConsumer<? super BlockResult> consumer) {
		if (nextBlock<0) throw new IllegalArgumentException("Negative starting block: "+nextBlock);
		this.nextBlock=nextBlock;
		this.consumer=Objects.requireNonNull(consumer,"Block consumer cannot be null");
	}

	@Override
	public void accept(Peer peer) {
		Objects.requireNonNull(peer,"Peer cannot be null");
		long statePosition=peer.getStatePosition();
		if (statePosition<nextBlock) {
			throw new IllegalStateException("Consensus state position moved backwards from "+nextBlock+" to "+statePosition);
		}

		while (nextBlock<statePosition) {
			long blockIndex=nextBlock;
			BlockResult blockResult=peer.getBlockResult(blockIndex);
			if (blockResult==null) {
				throw new IllegalStateException("Block result unavailable at position "+blockIndex);
			}
			consumer.accept(blockResult,blockIndex);
			nextBlock++;
		}
	}

	/**
	 * Gets the next block position to be observed.
	 *
	 * @return Next block position
	 */
	public long getNextBlock() {
		return nextBlock;
	}
}
