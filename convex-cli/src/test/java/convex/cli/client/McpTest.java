package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
 * stdin and parsing the response lines from stdout.
 */
public class McpTest {

	static final String INIT="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
			+"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";

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

	static String toolCall(int id, String tool, String argsJson) {
		return "{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\"tools/call\","
				+"\"params\":{\"name\":\""+tool+"\",\"arguments\":"+argsJson+"}}\n";
	}

	@Test
	public void testInitializeAndToolsList() {
		List<ACell> rs=session(INIT+"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n");
		// Notification produces no response line
		assertEquals(2,rs.size());

		assertNotNull(path(rs.get(0),"result","protocolVersion"));
		assertEquals(Strings.create("convex-cli"),path(rs.get(0),"result","serverInfo","name"));

		AVector<?> tools=(AVector<?>)path(rs.get(1),"result","tools");
		List<String> names=new ArrayList<>();
		for (ACell t: tools) names.add(RT.ensureString(((AMap<?, ?>)t).get(Strings.create("name"))).toString());
		assertTrue(names.containsAll(List.of("query","transact","getBalance","resolveCNS","status")),
				()->"Missing expected tools in: "+names);
	}

	@Test
	public void testQueryTool() {
		List<ACell> rs=session(INIT+toolCall(2,"query","{\"source\":\"(+ 1 2)\"}"));
		ACell result=rs.get(1);
		assertEquals(CVMBool.FALSE,path(result,"result","isError"));
		assertEquals(CVMLong.create(3),path(result,"result","structuredContent","value"));
	}

	@Test
	public void testTransactStatePersists() {
		List<ACell> rs=session(INIT
				+toolCall(2,"transact","{\"source\":\"(def x 7)\"}")
				+toolCall(3,"query","{\"source\":\"x\"}"));
		assertEquals(CVMLong.create(7),path(rs.get(1),"result","structuredContent","value"));
		assertEquals(CVMLong.create(7),path(rs.get(2),"result","structuredContent","value"));
	}

	@Test
	public void testQueryErrorIsToolError() {
		List<ACell> rs=session(INIT+toolCall(2,"query","{\"source\":\"(fail :FOO :bad)\"}"));
		ACell result=rs.get(1);
		assertEquals(CVMBool.TRUE,path(result,"result","isError"));
		assertNotNull(path(result,"result","structuredContent","errorCode"));
	}

	@Test
	public void testGetBalance() {
		// Genesis account #12 is funded on the ephemeral local instance
		List<ACell> rs=session(INIT+toolCall(2,"getBalance","{\"address\":\"#12\"}"));
		CVMLong balance=(CVMLong)path(rs.get(1),"result","structuredContent","balance");
		assertNotNull(balance);
		assertTrue(balance.longValue()>0);
	}

	@Test
	public void testResolveCNS() {
		List<ACell> rs=session(INIT+toolCall(2,"resolveCNS","{\"name\":\"convex.asset\"}"));
		assertEquals(CVMBool.FALSE,path(rs.get(1),"result","isError"));
		assertNotNull(path(rs.get(1),"result","structuredContent","value"));
	}

	@Test
	public void testStatus() {
		List<ACell> rs=session(INIT+toolCall(2,"status","{}"));
		ACell sc=path(rs.get(1),"result","structuredContent");
		assertEquals(CVMBool.TRUE,path(sc,"signing"));
		AString target=RT.ensureString(path(sc,"target"));
		assertTrue(target.toString().contains("local"),()->"Unexpected target: "+target);
	}

	@Test
	public void testReadOnlyModeOmitsTransact() {
		List<ACell> rs=session(INIT+"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n","--query");
		AVector<?> tools=(AVector<?>)path(rs.get(1),"result","tools");
		for (ACell t: tools) {
			assertFalse(RT.ensureString(((AMap<?, ?>)t).get(Strings.create("name"))).toString().equals("transact"));
		}
	}

	@Test
	public void testUnknownToolIsProtocolError() {
		List<ACell> rs=session(INIT+toolCall(2,"nonsense","{}"));
		assertNotNull(path(rs.get(1),"error"));
		assertNull(path(rs.get(1),"result"));
	}

	@Test
	public void testBadJsonLineIsParseError() {
		List<ACell> rs=session(INIT+"this is not json\n");
		assertEquals(CVMLong.create(-32700),path(rs.get(1),"error","code"));
	}

	@Test
	public void testPromptAvailable() {
		List<ACell> rs=session(INIT+"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}\n");
		AVector<?> prompts=(AVector<?>)path(rs.get(1),"result","prompts");
		assertTrue(prompts.count()>0);
	}
}
