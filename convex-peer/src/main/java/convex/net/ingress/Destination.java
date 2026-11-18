package convex.net.ingress;

import java.util.function.Predicate;

import convex.core.message.Message;

/**
 * Where a received message goes once it is decoded and classified: a handler that
 * can take it now or tell when it may have room. As a {@link Predicate}, it is the
 * retry a delivery function returns for a message it could not place, and a caller
 * that may wait offers through {@link #test(Message)}.
 */
public interface Destination extends Predicate<Message> {

	/**
	 * Offers a message without waiting.
	 *
	 * @param message Message to place
	 * @return true if this destination took the message
	 */
	boolean tryOffer(Message message);

	/**
	 * Runs an action once, after this destination may have room: at once if it has
	 * room now, otherwise after a message leaves it. The action runs on the thread
	 * that made the room, and must not block.
	 *
	 * @param action Action to run once
	 */
	void whenCapacity(Runnable action);

	/**
	 * Offers a message, waiting a bounded time for room.
	 *
	 * @param message Message to place
	 * @return true if this destination took the message
	 */
	@Override
	boolean test(Message message);
}
