package convex.restapi.api;

import convex.restapi.RESTServer;
import convex.restapi.auth.ConfirmationService;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * HTTP endpoints for the elevated operation confirmation flow.
 *
 * Elevated signing service operations (import, export, delete, change
 * passphrase) require user confirmation via web browser. When an agent
 * calls an elevated MCP tool, the tool returns a {@code confirmUrl}
 * pointing to this API. The user visits the URL, reviews the action,
 * and clicks "Confirm". The agent then retries the tool call with the
 * {@code confirmToken}.
 *
 * @see ConfirmationService
 */
public class ConfirmAPI extends ABaseAPI {

	public ConfirmAPI(RESTServer restServer) {
		super(restServer);
	}

	@Override
	public void addRoutes(Javalin app) {
		app.get("/confirm", this::handleConfirmGet);
		app.post("/confirm", this::handleConfirmPost);
	}

	/**
	 * GET /confirm?token=ct_... — renders confirmation page showing action details.
	 */
	private void handleConfirmGet(Context ctx) {
		String token = ctx.queryParam("token");
		if (token == null || token.isEmpty()) {
			ctx.status(400).contentType("text/html").result(
				page("Missing Token", "<p>No confirmation token provided.</p>"));
			return;
		}

		ConfirmationService confirmSvc = restServer.getConfirmationService();
		ConfirmationService.Confirmation c = confirmSvc.getConfirmation(token);
		if (c == null) {
			ctx.status(404).contentType("text/html").result(
				page("Invalid or Expired", "<p>This confirmation link is invalid or has expired.</p>"));
			return;
		}

		if (c.approved()) {
			ctx.contentType("text/html").result(
				page("Already Approved", "<p>This operation has already been approved.</p>"));
			return;
		}

		ctx.contentType("text/html").result(page("Confirm Operation",
			"<p><strong>Tool:</strong> " + esc(c.toolName()) + "</p>"
			+ "<p><strong>Identity:</strong> " + esc(c.identity().toString()) + "</p>"
			+ "<p>" + esc(c.description()) + "</p>"
			+ "<form method='POST' action='/confirm?token=" + esc(token) + "'>"
			+ "<button type='submit'>Confirm</button>"
			+ "</form>"));
	}

	/**
	 * POST /confirm?token=ct_... — approves the confirmation.
	 */
	private void handleConfirmPost(Context ctx) {
		String token = ctx.queryParam("token");
		if (token == null || token.isEmpty()) {
			ctx.status(400).contentType("text/html").result(
				page("Missing Token", "<p>No confirmation token provided.</p>"));
			return;
		}

		ConfirmationService confirmSvc = restServer.getConfirmationService();
		boolean approved = confirmSvc.approveConfirmation(token);
		if (approved) {
			ctx.contentType("text/html").result(
				page("Approved", "<p>You may close this window. The agent will complete the operation.</p>"));
		} else {
			ctx.status(404).contentType("text/html").result(
				page("Invalid or Expired", "<p>This confirmation link is invalid or has expired.</p>"));
		}
	}

	private static String page(String title, String body) {
		return "<html><head><title>" + esc(title) + "</title></head><body>"
			+ "<h2>" + esc(title) + "</h2>" + body + "</body></html>";
	}

	private static String esc(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("<", "&lt;")
				.replace(">", "&gt;").replace("\"", "&quot;");
	}
}
