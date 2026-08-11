package convex.restapi.test;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/** Closes the shared REST integration fixture after the complete test plan. */
public final class RESTFixtureExtension implements BeforeAllCallback {
	private static final Namespace NAMESPACE=Namespace.create(RESTFixtureExtension.class);

	@Override
	public void beforeAll(ExtensionContext context) {
		context.getRoot().getStore(NAMESPACE).computeIfAbsent(
				RESTFixtureExtension.class,k->new Fixture(),Fixture.class);
	}

	private static final class Fixture implements AutoCloseable {
		@Override
		public void close() {
			ARESTTest.closeFixture();
		}
	}
}
