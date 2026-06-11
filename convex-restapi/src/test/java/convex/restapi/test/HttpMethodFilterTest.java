package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/**
 * Tests for rejection of malformed HTTP method tokens.
 *
 * <p>Javalin 7 resolves methods via HandlerType.findOrCreate, which throws
 * for any token containing characters outside A-Z. The HttpMethodFilter
 * must convert these to a clean 501 rather than a 500 with a logged
 * exception, while well-formed unknown methods fall through to routing.</p>
 */
public class HttpMethodFilterTest extends ARESTTest {

	private static final HttpClient client = HttpClient.newHttpClient();

	private static int statusOf(String method, String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		return resp.statusCode();
	}

	@Test
	public void testMalformedMethodsReturn501() throws Exception {
		assertEquals(501, statusOf("M-SEARCH", HOST_PATH + "/"));
		assertEquals(501, statusOf("VERSION-CONTROL", API_PATH + "/status"));
		assertEquals(501, statusOf("propfind", HOST_PATH + "/"));
	}

	@Test
	public void testUnknownMethodOnMissingURLReturns404() throws Exception {
		assertEquals(404, statusOf("FOOBAR", HOST_PATH + "/no-such-url"));
	}

	@Test
	public void testValidMethodsUnaffected() throws Exception {
		assertEquals(200, statusOf("GET", API_PATH + "/status"));
	}

	@Test
	public void testMethodValidation() {
		assertTrue(convex.restapi.handler.HttpMethodFilter.isValidMethod("GET"));
		assertTrue(convex.restapi.handler.HttpMethodFilter.isValidMethod("PROPFIND"));
		assertFalse(convex.restapi.handler.HttpMethodFilter.isValidMethod("M-SEARCH"));
		assertFalse(convex.restapi.handler.HttpMethodFilter.isValidMethod("get"));
		assertFalse(convex.restapi.handler.HttpMethodFilter.isValidMethod(""));
		assertFalse(convex.restapi.handler.HttpMethodFilter.isValidMethod(null));
		assertFalse(convex.restapi.handler.HttpMethodFilter.isValidMethod("A".repeat(33)));
	}
}
