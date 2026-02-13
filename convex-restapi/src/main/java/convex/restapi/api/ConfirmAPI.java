package convex.restapi.api;

import static j2html.TagCreator.button;
import static j2html.TagCreator.form;
import static j2html.TagCreator.p;
import static j2html.TagCreator.strong;
import static j2html.TagCreator.text;

import convex.restapi.RESTServer;
import convex.restapi.auth.ConfirmationService;
import convex.restapi.web.AWebSite;
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
public class ConfirmAPI extends AWebSite {

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
			ctx.status(400);
			returnPage(ctx, "Missing Token",
				p("No confirmation token provided."));
			return;
		}

		ConfirmationService confirmSvc = restServer.getConfirmationService();
		ConfirmationService.Confirmation c = confirmSvc.getConfirmation(token);
		if (c == null) {
			ctx.status(404);
			returnPage(ctx, "Invalid or Expired",
				p("This confirmation link is invalid or has expired."));
			return;
		}

		if (c.approved()) {
			returnPage(ctx, "Already Approved",
				p("This operation has already been approved."));
			return;
		}

		returnPage(ctx, "Confirm Operation",
			p(strong("Tool: "), text(c.toolName())),
			p(strong("Identity: "), text(c.identity().toString())),
			p(c.description()),
			form(
				button("Confirm").withType("submit")
			).withMethod("POST").withAction("/confirm?token=" + token));
	}

	/**
	 * POST /confirm?token=ct_... — approves the confirmation.
	 */
	private void handleConfirmPost(Context ctx) {
		String token = ctx.queryParam("token");
		if (token == null || token.isEmpty()) {
			ctx.status(400);
			returnPage(ctx, "Missing Token",
				p("No confirmation token provided."));
			return;
		}

		ConfirmationService confirmSvc = restServer.getConfirmationService();
		boolean approved = confirmSvc.approveConfirmation(token);
		if (approved) {
			returnPage(ctx, "Approved",
				p("You may close this window. The agent will complete the operation."));
		} else {
			ctx.status(404);
			returnPage(ctx, "Invalid or Expired",
				p("This confirmation link is invalid or has expired."));
		}
	}
}
