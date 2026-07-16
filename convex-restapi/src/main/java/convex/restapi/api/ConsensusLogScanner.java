package convex.restapi.api;

import java.util.Objects;
import java.util.function.Consumer;

import convex.core.Result;
import convex.core.cpos.BlockResult;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.peer.FinalisedBlockScanner;

/**
 * Incrementally scans ordinary transaction logs from finalised peer state.
 *
 * <p>A scanner is single-consumer state: each accepted {@link Peer} advances its
 * block cursor up to that peer's executed state position. Intermediate peer
 * snapshots may be omitted because a later snapshot retains every intervening
 * {@link BlockResult}.</p>
 */
final class ConsensusLogScanner implements Consumer<Peer> {

	record LogEvent(long blockIndex, long transactionIndex, long logIndex, AVector<ACell> entry) {}

	private final Consumer<? super LogEvent> consumer;
	private final FinalisedBlockScanner blocks;

	ConsensusLogScanner(long nextBlock, Consumer<? super LogEvent> consumer) {
		this.consumer=Objects.requireNonNull(consumer,"Log consumer cannot be null");
		this.blocks=new FinalisedBlockScanner(nextBlock,this::scanBlock);
	}

	@Override
	public void accept(Peer peer) {
		blocks.accept(peer);
	}

	private void scanBlock(BlockResult blockResult, long blockIndex) {
		AVector<Result> results=blockResult.getResults();
		for (long transactionIndex=0;transactionIndex<results.count();transactionIndex++) {
			AVector<AVector<ACell>> log=results.get(transactionIndex).getLog();
			for (long logIndex=0;logIndex<log.count();logIndex++) {
				consumer.accept(new LogEvent(blockIndex,transactionIndex,logIndex,log.get(logIndex)));
			}
		}
	}

	long getNextBlock() {
		return blocks.getNextBlock();
	}
}
