package convex.social;

import java.util.Objects;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.impl.LongBlob;

/**
 * A timeline position ordered by feed-key bytes, then author DID.
 * Timelines traverse this order in descending order.
 *
 * <p>A full position resumes strictly below one entry. Omitting the author
 * places a bound below every author at that key. A key prefix places it below
 * every key beginning with that prefix, deliberately skipping that group.</p>
 *
 * @param postKey Full post key or an exclusive key-prefix bound
 * @param author Author DID, or null for a key-only bound
 */
public record TimelinePosition(Blob postKey,AString author) implements Comparable<TimelinePosition> {

	public TimelinePosition {
		Objects.requireNonNull(postKey);
	}

	/** An exclusive bound below every entry with this key or key prefix. */
	public TimelinePosition(Blob prefix) { this(prefix,null); }

	/** An exclusive bound below all posts at this timestamp, including legacy keys. */
	public static TimelinePosition atTimestamp(long timestamp) {
		return new TimelinePosition(LongBlob.create(timestamp).toFlatBlob());
	}

	@Override public int compareTo(TimelinePosition other) {
		int order=postKey.compareTo(other.postKey);
		if (order!=0) return order;
		if (author==null) return other.author==null?0:-1;
		return other.author==null?1:author.compareTo(other.author);
	}
}
