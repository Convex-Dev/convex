package convex.social;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;

/**
 * A single entry in a merged timeline view.
 *
 * @param author The account key of the post author
 * @param postKey The 8-byte timestamp key in the author's feed
 * @param timestamp The extracted timestamp (millis since epoch)
 * @param post The post content
 */
public record TimelineEntry(
	AccountKey author,
	Blob postKey,
	long timestamp,
	AHashMap<Keyword, ACell> post
) implements Comparable<TimelineEntry> {

	@Override
	public int compareTo(TimelineEntry other) {
		return Long.compare(this.timestamp, other.timestamp);
	}
}
