package convex.gui;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/** Keeps the shared GUI peer fixture alive for the test plan, then closes it. */
public final class GUIFixtureExtension implements BeforeAllCallback {
	private static final Namespace NAMESPACE=Namespace.create(GUIFixtureExtension.class);

	@Override
	public void beforeAll(ExtensionContext context) {
		context.getRoot().getStore(NAMESPACE).computeIfAbsent(
				GUIFixtureExtension.class,k->new Fixture(),Fixture.class);
	}

	private static final class Fixture implements AutoCloseable {
		@Override
		public void close() {
			GUITest.closeFixture();
		}
	}
}
