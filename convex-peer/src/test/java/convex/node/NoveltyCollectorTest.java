package convex.node;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Blobs;

public class NoveltyCollectorTest {

	@Test
	public void testRetainsBoundedTail() {
		NoveltyCollector collector=new NoveltyCollector(700,2);
		Blob first=Blobs.createRandom(300);
		Blob middle=Blobs.createRandom(300);
		Blob last=Blobs.createRandom(300);
		collector.accept(first.getRef());
		collector.accept(middle.getRef());
		collector.accept(last.getRef());

		List<ACell> retained=collector.getCells();
		assertTrue(retained.size()<=2);
		assertSame(last,retained.get(retained.size()-1));
		assertTrue(collector.getEstimatedBytes()<=700);
		assertTrue(collector.getOmittedCount()>0);
	}
}
