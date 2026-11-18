package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.json.JSONReader;
import convex.core.lang.RT;

/**
 * Tests for the `convex mcp` stdio MCP server against an ephemeral local
 * instance. Sessions are driven by piping newline-delimited JSON-RPC into
 * stdin and parsing the response lines from stdout. Each session builds a
 * genesis state, so the requests that change nothing share one session.
 */
@ResourceLock("java.lang.System.in")
public class McpTest {

	static final String INIT="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
			+"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";

	/** Responses to the shared session, run once under the class's stdin lock */
	private static List<ACell> shared;

	@BeforeAll
	static void runSharedSession() {
		shared=session(INIT
				+request(2,"tools/list")
				+toolCall(3,"query","{\"source\":\"(+ 1 2)\"}")
				+toolCall(4,"query","{\"source\":\"(fail :FOO :bad)\"}")
				+toolCall(5,"getBalance","{\"address\":\"#12\"}")
				+toolCall(6,"resolveCNS","{\"name\":\"convex.asset\"}")
				+toolCall(7,"status","{}")
				+toolCall(8,"nonsense","{}")
				+"this is not json\n"
				+request(9,"prompts/list"));
	}

	/**
	 * Runs an MCP session and returns the parsed response lines
	 */
	static List<ACell> session(String input, String... extraArgs) {
		String[] args=new String[extraArgs.length+1];
		args[0]="mcp";
		System.arraycopy(extraArgs,0,args,1,extraArgs.length);
		CLTester tester=CLTester.runWithInput(input,args);
		tester.assertExitCode(ExitCodes.SUCCESS);
		List<ACell> responses=new ArrayList<>();
		for (String line: tester.getOutput().split("\\R")) {
			if (line.isBlank()) continue;
			responses.add(JSONReader.read(line));
		}
		return responses;
	}

	/**
	 * Finds the response to the request with the given id
	 */
	static ACell response(List<ACell> responses, long id) {
		for (ACell r: responses) {
			if (CVMLong.create(id).equals(path(r,"id"))) return r;
		}
		return fail("No response with id "+id+" in "+responses);
	}

	/**
	 * Navigates nested maps by string key
	 */
	static ACell path(ACell m, String... keys) {
		ACell current=m;
		for (String k: keys) {
			assertNotNull(current,()->"Missing intermediate value navigating "+String.join("/",keys)+" in "+m);
			current=((AMap<?, ?>)current).get(Strings.create(k));
		}
		return current;
	}

	static String request(int id, String method) {
		return "{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\""+method+"\"}\n";
	}

	static String toolCall(int id, String tool, String argsJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\"tools/call\","
				+"\"params\":{\"name\":\""+tool+"\",\"arguments\":"+argsJson+"}}\n";
	}

	private static List<String> toolNames(ACell toolsListResponse) {
		AVector<?> tools=(AVector<?>)path(toolsListResponse,"result","tools");
		List<String> names=new ArrayList<>();
		for (ACell t: tools) names.add(RT.ensureString(((AMap<?, ?>)t).get(Strings.create("name"))).toString());
		return names;
	}

	@Test
	public void testInitializeAndToolsList() {
		// One line per request, the bad JSON line included; the notification produces none
		assertEquals(10,shared.size());

		ACell init=response(shared,1);
		assertNotNull(path(init,"result","protocolVersion"));
		assertEquals(Strings.create("convex-cli"),path(init,"result","serverInfo","name"));

		List<String> names=toolNames(response(shared,2));
		assertTrue(names.containsAll(List.of("query","transact","getBalance","resolveCNS","status")),
				()->"Missing expected tools in: "+names);
	}

	@Test
	public void testQueryTool() {
		ACell result=response(shared,3);
		assertEquals(CVMBool.FALSE,path(result,"result","isError"));
		assertEquals(CVMLong.create(3),path(result,"result","structuredContent","value"));
	}

	/** A transaction's state change is seen by a later query in the same session */
	@Test
	public void testTransactStatePersists() {
		List<ACell> rs=session(INIT
				+toolCall(2,"transact","{\"source\":\"(def x 7)\"}")
				+toolCall(3,"query","{\"source\":\"x\"}"));
		assertEquals(CVMLong.create(7),path(response(rs,2),"result","structuredContent","value"));
		assertEquals(CVMLong.create(7),path(response(rs,3),"result","structuredContent","value"));
	}

	@Test
	public void testQueryErrorIsToolError() {
		ACell result=response(shared,4);
		assertEquals(CVMBool.TRUE,path(result,"result","isError"));
		assertNotNull(path(result,"result","structuredContent","errorCode"));
	}

	@Test
	public void testGetBalance() {
		// Genesis account #12 is funded on the ephemeral local instance
		CVMLong balance=(CVMLong)path(response(shared,5),"result","structuredContent","balance");
		assertNotNull(balance);
		assertTrue(balance.longValue()>0);
	}

	@Test
	public void testResolveCNS() {
		ACell result=response(shared,6);
		assertEquals(CVMBool.FALSE,path(result,"result","isError"));
		assertNotNull(path(result,"result","structuredContent","value"));
	}

	@Test
	public void testStatus() {
		ACell sc=path(response(shared,7),"result","structuredContent");
		assertEquals(CVMBool.TRUE,path(sc,"signing"));
		AString target=RT.ensureString(path(sc,"target"));
		assertTrue(target.toString().contains("local"),()->"Unexpected target: "+target);
	}

	@Test
	public void testReadOnlyModeOmitsTransact() {
		List<ACell> rs=session(INIT+request(2,"tools/list"),"--query");
		assertFalse(toolNames(response(rs,2)).contains("transact"));
	}

	@Test
	public void testUnknownToolIsProtocolError() {
		ACell result=response(shared,8);
		assertNotNull(path(result,"error"));
		assertNull(path(result,"result"));
	}

	@Test
	public void testBadJsonLineIsParseError() {
		// A line that is not JSON has no id to answer, so its reply is found by its code
		for (ACell r: shared) {
			ACell error=path(r,"error");
			if ((error!=null)&&CVMLong.create(-32700).equals(path(error,"code"))) return;
		}
		fail("No parse error response in "+shared);
	}

	@Test
	public void testPromptAvailable() {
		AVector<?> prompts=(AVector<?>)path(response(shared,9),"result","prompts");
		assertTrue(prompts.count()>0);
	}
}
