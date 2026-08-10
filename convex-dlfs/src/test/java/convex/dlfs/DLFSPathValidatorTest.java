package convex.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class DLFSPathValidatorTest {

	@Test
	public void testComponentLimitIsUtf8Bytes() {
		String ascii="a".repeat(DLFSPathValidator.MAX_COMPONENT_LENGTH);
		assertEquals(ascii,DLFSPathValidator.canonicalRelativePath(ascii));
		assertThrows(IllegalArgumentException.class,
				() -> DLFSPathValidator.canonicalRelativePath(ascii+"a"));

		String unicode="€".repeat(DLFSPathValidator.MAX_COMPONENT_LENGTH/3);
		assertEquals(unicode,DLFSPathValidator.canonicalRelativePath(unicode));
		assertThrows(IllegalArgumentException.class,
				() -> DLFSPathValidator.canonicalRelativePath(unicode+"€"));
	}
}
