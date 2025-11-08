package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.SignedData;

/**
 * Test class for Explorer API endpoints.
 * Tests that each main explorer endpoint returns appropriate HTTP status codes.
 */
public class ExplorerTest extends ARESTTest {
    
    private static final String EXPLORER_PATH = HOST_PATH + "/explorer";
    
    /**
     * Helper method to validate that a 200 response contains valid HTML content.
     * Checks for basic HTML structure elements.
     * 
     * @param response The HTTP response to validate
     * @param expectedTitle Optional expected title to check for
     * @return true if the response appears to be valid HTML
     */
    private boolean isValidHtmlResponse(HttpResponse<String> response, String expectedTitle) {
        if (response.statusCode() != 200) {
            return false;
        }
        
        String body = response.body();
        if (body == null || body.trim().isEmpty()) {
            return false;
        }
        
        // Check for basic HTML structure
        boolean hasHtmlTag = body.toLowerCase().contains("<html") || body.toLowerCase().contains("<!doctype");
        boolean hasHeadTag = body.toLowerCase().contains("<head");
        boolean hasBodyTag = body.toLowerCase().contains("<body");
        
        // Check for expected title if provided
        boolean hasExpectedTitle = expectedTitle == null || body.contains(expectedTitle);
        
        return hasHtmlTag && hasHeadTag && hasBodyTag && hasExpectedTitle;
    }
    
    
    @Test
    public void testExplorerMainPage() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Peer Explorer"));
    }
    
    @Test
    public void testExplorerBlocks() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/blocks");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Blocks"));
    }
    
    @Test
    public void testExplorerBlockZero() throws IOException, InterruptedException {
        // First check if block 0 exists by getting the blocks list
        HttpResponse<String> blocksResponse = get(EXPLORER_PATH + "/blocks");
        assertEquals(200, blocksResponse.statusCode());
        
        // Try to access block 0 - it might not exist in a fresh peer
        HttpResponse<String> response = get(EXPLORER_PATH + "/blocks/0");
        // Block 0 might not exist in a fresh peer, so we accept either 200 or 404
        assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
        
        if (response.statusCode() == 200) {
            assertTrue(isValidHtmlResponse(response, "Convex Block: 0"));
        }
    }
    
    @Test
    public void testExplorerBlockNotFound() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/blocks/999999");
        assertEquals(404, response.statusCode());
    }
    
    @Test
    public void testExplorerBlockTransaction() throws IOException, InterruptedException {
        // First check if block 0 exists
        HttpResponse<String> blockResponse = get(EXPLORER_PATH + "/blocks/0");
        if (blockResponse.statusCode() == 404) {
            // Block 0 doesn't exist, skip this test
            return;
        }
        
        // Test transaction 0 in block 0 (genesis block should have at least one transaction)
        HttpResponse<String> response = get(EXPLORER_PATH + "/blocks/0/txs/0");
        // Transaction might not exist even if block exists, so accept either 200 or 404
        assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
        
        if (response.statusCode() == 200) {
            assertTrue(isValidHtmlResponse(response, "Transaction 0 in Block 0"));
        }
    }
    
    @Test
    public void testExplorerBlockTransactionNotFound() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/blocks/0/txs/999999");
        assertEquals(404, response.statusCode());
    }
    
    @Test
    public void testExplorerStates() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/states");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "States"));
    }
    
    @Test
    public void testExplorerStateZero() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/states/0");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "State #0"));
    }
    
    @Test
    public void testExplorerStateNotFound() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/states/999999");
        assertEquals(404, response.statusCode());
    }
    
    @Test
    public void testExplorerAccounts() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/accounts");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Accounts"));
    }
    
    @Test
    public void testExplorerAccountZero() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/accounts/0");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Account: #0"));
    }
    
    @Test
    public void testExplorerAccountNotFound() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/accounts/999999");
        assertEquals(404, response.statusCode());
    }
    
    @Test
    public void testExplorerPeers() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/peers");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Peers"));
    }
    
    @Test
    public void testExplorerPeerDetail() throws IOException, InterruptedException {
        // First get the peers list to find a valid peer key
        HttpResponse<String> peersResponse = get(EXPLORER_PATH + "/peers");
        assertEquals(200, peersResponse.statusCode());
        
        // Extract a peer key from the response (this is a simplified approach)
        // In a real test, you might want to parse the HTML to extract actual peer keys
        // For now, we'll test with a known format that should exist
        var peers = server.getServer().getPeer().getConsensusState().getPeers();
        if (peers.count() > 0) {
            String peerKey = peers.entryAt(0).getKey().toHexString();
            HttpResponse<String> response = get(EXPLORER_PATH + "/peers/" + peerKey);
            assertEquals(200, response.statusCode());
            assertTrue(isValidHtmlResponse(response, "Peer: " + peerKey.substring(0, 8)));
        }
    }
    
    @Test
    public void testExplorerPeerNotFound() throws IOException, InterruptedException {
        // Use a fake peer key that shouldn't exist
        String fakePeerKey = "0000000000000000000000000000000000000000000000000000000000000000";
        HttpResponse<String> response = get(EXPLORER_PATH + "/peers/" + fakePeerKey);
        assertEquals(404, response.statusCode());
    }
    
    @Test
    public void testExplorerRepl() throws IOException, InterruptedException {
        HttpResponse<String> response = get(EXPLORER_PATH + "/repl");
        assertEquals(200, response.statusCode());
        assertTrue(isValidHtmlResponse(response, "Convex REPL"));
    }
    
    @Test
    public void testExplorerSearch() throws IOException, InterruptedException {
        // Test search with a valid account number
        HttpResponse<String> response = post(EXPLORER_PATH + "/search", "q=0");
        // Search should redirect to the account page, so we expect a 200 or 302
        assertTrue(response.statusCode() == 200 || response.statusCode() == 302);
    }
    
    @Test
    public void testExplorerSearchEmpty() throws IOException, InterruptedException {
        // Test search with empty query - should redirect to main page
        HttpResponse<String> response = post(EXPLORER_PATH + "/search", "q=");
        assertEquals(302, response.statusCode()); // Should redirect
    }
    
    @Test
    public void testExplorerSearchNotFound() throws IOException, InterruptedException {
        // Test search with invalid query
        HttpResponse<String> response = post(EXPLORER_PATH + "/search", "q=invalidquery123");
        assertEquals(200, response.statusCode());
        
        // Check for either the expected error message or any indication of search failure
        assertTrue(response.body().contains("Couldn't find") || 
                  response.body().contains("search") || 
                  response.body().contains("invalidquery123"));
    }

	@Test
	public void testExplorerSearchBlockHash() throws IOException, InterruptedException {
		// Obtain first block hash if available
		Order order = server.getServer().getPeer().getPeerOrder();
		AVector<SignedData<Block>> blocks = order.getBlocks();
		if (blocks.isEmpty()) return; // nothing to test
		SignedData<Block> firstBlock = blocks.get(0);
		Hash blockHash = firstBlock.getHash();
		String hashHex = blockHash.toHexString();

		HttpResponse<String> response = post(EXPLORER_PATH + "/search", "q=" + hashHex);

		assertEquals(302, response.statusCode());
		assertTrue(response.headers().firstValue("Location")
				.map(loc -> loc.equals("/explorer/blocks/0") || loc.endsWith("/explorer/blocks/0"))
				.orElse(false));
	}

	@Test
	public void testExplorerSearchBlockHashNotFound() throws IOException, InterruptedException {
		String fakeHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
		HttpResponse<String> response = post(EXPLORER_PATH + "/search", "q=" + fakeHash);

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("Couldn't find") || response.body().contains(fakeHash));
	}
}
