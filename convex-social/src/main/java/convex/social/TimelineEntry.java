package convex.social;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Keyword;

/**
 * A single entry in a merged timeline view.
 *
 * @param author The canonical DID of the post author
 * @param postKey The timestamp-prefixed key in the author's feed
 * @param timestamp The extracted timestamp (millis since epoch)
 * @param post The post content
 */
public record TimelineEntry(
	AString author,
	Blob postKey,
	long timestamp,
	AHashMap<Keyword, ACell> post
) implements Comparable<TimelineEntry> {

	/** Full continuation position, independent of mutable post content. */
	public TimelinePosition position() { return new TimelinePosition(postKey,author); }

	@Override
	public int compareTo(TimelineEntry other) {
		return position().compareTo(other.position());
	}
}
