package convex.peer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.message.Message;
import convex.core.message.MessageType;

/**
 * Accumulates the novelty announced for one consensus update and shapes it into the
 * ordered messages that carry it. Novelty arrives in announcement order, children
 * before parents. When it all fits alongside the root in one message the update is a
 * single BELIEF delta with the root as its top cell. Otherwise it is DATA messages of
 * at most the message limit each, in announcement order, followed by the root alone,
 * so that on a receiver's ordered queue every cell is staged before the root that
 * needs it. A Block of any size is carried this way.
 *
 * <p>Materialisation is bounded by bytes, never by cell count: once the budget is
 * spent, further novelty is counted but not carried. It is still announced and still
 * in the store, so a receiver that finds it missing pulls it by hash. What to carry
 * eagerly is a peer's decision, which is why this lives here and not in the data
 * layer.</p>
 */
final class UpdateAccumulator implements Consumer<Ref<ACell>> {

	/** Allowance per cell for its length prefix, its reference from the DATA vector and vector nodes. */
	private static final long CELL_OVERHEAD = Format.MAX_VLQ_LONG_LENGTH + Ref.INDIRECT_ENCODING_LENGTH + 8;

	private final int messageLimit;
	private final long byteBudget;
	private final Hash rootHash;

	private final ArrayList<Message> dataMessages = new ArrayList<>();
	private ArrayList<ACell> batch = new ArrayList<>();
	private long batchEstimate = 0;
	private long materialised = 0;
	private long omitted = 0;

	/**
	 * @param messageLimit Maximum encoded length of any message produced
	 * @param byteBudget Maximum encoded bytes to materialise for this update
	 * @param rootHash Hash of the update's root, which travels as the final top cell and never as data
	 */
	UpdateAccumulator(int messageLimit, long byteBudget, Hash rootHash) {
		if (messageLimit < 1 || messageLimit > CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Message limit must be between 1 and "
				+ CPoSConstants.MAX_MESSAGE_LENGTH + ": " + messageLimit);
		}
		if (byteBudget < 1) throw new IllegalArgumentException("Byte budget must be positive");
		this.messageLimit = messageLimit;
		this.byteBudget = byteBudget;
		this.rootHash = rootHash;
	}

	@Override
	public void accept(Ref<ACell> ref) {
		add(ref.getValue());
	}

	/**
	 * Adds one announced cell. Embedded cells travel inside their parents and the root
	 * travels as the final top cell, so neither is carried as data.
	 *
	 * @param cell Newly announced cell
	 */
	void add(ACell cell) {
		if (cell == null || cell.isEmbedded()) return;
		if (rootHash != null && rootHash.equals(cell.getHash())) return;
		long estimate = cell.getEncodingLength() + CELL_OVERHEAD;
		if (omitted > 0 || estimate > messageLimit || materialised + batchEstimate + estimate > byteBudget) {
			omitted++; // left in the store for receivers to pull
			return;
		}
		if (!batch.isEmpty() && batchEstimate + estimate > messageLimit) sealBatch();
		batch.add(cell);
		batchEstimate += estimate;
	}

	private void sealBatch() {
		if (batch.isEmpty()) return;
		List<Message> messages = Message.createDataMessages(batch, messageLimit);
		for (Message m : messages) materialised += m.getMessageData().count();
		dataMessages.addAll(messages);
		batch = new ArrayList<>();
		batchEstimate = 0;
	}

	/**
	 * Shapes the accumulated novelty into the update's messages, ending with the root.
	 *
	 * @param root The update's root cell, the top cell of the final BELIEF message
	 * @return Messages to offer in order, never empty
	 */
	List<Message> toMessages(ACell root) {
		if (dataMessages.isEmpty()) {
			ArrayList<ACell> cells = new ArrayList<>(batch.size() + 1);
			cells.addAll(batch);
			cells.add(root);
			if (Format.getDeltaEncodingLength(cells) <= messageLimit) {
				return List.of(Message.create(MessageType.BELIEF, root, Format.encodeDelta(cells, messageLimit)));
			}
		}
		sealBatch();
		ArrayList<Message> messages = new ArrayList<>(dataMessages.size() + 1);
		messages.addAll(dataMessages);
		messages.add(Message.create(MessageType.BELIEF, root, root.getEncoding()));
		return messages;
	}

	/** Cells announced but not carried because they did not fit the budget or a message. */
	long getOmittedCount() {
		return omitted;
	}
}
