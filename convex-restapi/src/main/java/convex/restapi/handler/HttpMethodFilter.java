package convex.restapi.handler;

import java.io.IOException;
import java.util.EnumSet;

import org.eclipse.jetty.ee10.servlet.FilterHolder;

import io.javalin.config.JavalinConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter rejecting requests whose HTTP method token cannot be
 * represented by Javalin.
 *
 * <p>Javalin resolves request methods via {@code HandlerType.findOrCreate},
 * which throws for any name containing characters outside A-Z. Without this
 * guard, such requests (e.g. M-SEARCH probes or lowercase method tokens
 * from scanners) produce a 500 response and a logged exception per request
 * on a public endpoint. With it they get a clean 400 Bad Request before
 * reaching Javalin. Well-formed but unknown methods (e.g. FOOBAR) pass
 * through to normal routing and 404 if nothing matches.</p>
 */
public class HttpMethodFilter implements Filter {

	private static final int MAX_METHOD_LENGTH = 32;

	/**
	 * Installs this filter on a Javalin server configuration.
	 *
	 * @param config Javalin configuration being built
	 */
	public static void install(JavalinConfig config) {
		config.jetty.modifyServletContextHandler(handler ->
			handler.addFilter(new FilterHolder(new HttpMethodFilter()), "/*",
					EnumSet.of(DispatcherType.REQUEST)));
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (request instanceof HttpServletRequest req && !isValidMethod(req.getMethod())) {
			((HttpServletResponse) response).setStatus(400);
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * Checks whether a method token is one Javalin can route (uppercase A-Z only).
	 *
	 * @param method HTTP method token from the request line
	 * @return true if Javalin can represent this method
	 */
	public static boolean isValidMethod(String method) {
		int n = (method == null) ? 0 : method.length();
		if ((n == 0) || (n > MAX_METHOD_LENGTH)) return false;
		for (int i = 0; i < n; i++) {
			char c = method.charAt(i);
			if ((c < 'A') || (c > 'Z')) return false;
		}
		return true;
	}
}
