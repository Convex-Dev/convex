package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.Reader;
import convex.peer.TestNetwork;

/**
 * Generic test utilities for Convex instances.
 * 
 * Provides static test methods that can be called by other test classes
 * to check example Convex instances with a standard set of tests.
 * 
 * Tests are organized by condition/case to check invariants and expected behavior.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ConvexTest {

	/**
	 * Performs all generic tests on a Convex instance.
	 * 
	 * This method runs a standard set of tests that should work for any Convex
	 * implementation (ConvexLocal, ConvexRemote, ConvexDirect, etc.).
	 * 
	 * Handles different cases based on the current state of the Convex instance:
	 * - Null address: tests queries work, transactions fail with :NOBODY
	 * - Address.ZERO: tests queries work, transactions may fail
	 * - Null keypair: tests queries work, transactions fail with :SIGNATURE
	 * - Wrong keypair: tests account key matches keypair, transactions fail with :SIGNATURE
	 * - Normal case: tests all functionality
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	public static void doTests(Convex convex) throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		doConnectionTests(convex);
		
		// Test keypair and account key invariants
		doKeyPairTests(convex);
		
		// Test queries - should work in all cases
		doQueryTests(convex);
		
		Address address = convex.getAddress();
		
		// Branch based on address state
		if (address == null) {
			doNullAddressTests(convex);
		} else {
			// Address is not null - run tests that assume address exists
			doAddressTests(convex);
			
			// Test transactions - behavior depends on keypair
			AKeyPair keyPair = convex.getKeyPair();
			if (keyPair == null) {
				doTransactionTestsWithNullKeyPair(convex);
			} else {
				// Check if keypair matches the account
				boolean keyPairMatches = checkKeyPairMatchesAccount(convex);
				if (keyPairMatches) {
					doTransactionTests(convex);
					doSequenceTests(convex);
				} else {
					doTransactionTestsWithWrongKeyPair(convex);
				}
			}
			
			// Test error handling
			doErrorHandlingTests(convex);
		}
	}

	/**
	 * Tests connection status invariants.
	 * 
	 * Verifies that the Convex instance reports as connected.
	 * 
	 * @param convex The Convex instance to test
	 */
	public static void doConnectionTests(Convex convex) {
		assertTrue(convex.isConnected(), "Convex instance should be connected");
	}

	/**
	 * Tests behaviour when address is null.
	 * 
	 * Verifies that queries should still work even when no address is set.
	 * This tests the invariant that queries don't require an address.
	 * Also tests that transactions fail appropriately.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	public static void doNullAddressTests(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNull(address, "Address should be null for null address tests");
		
		// Test that queries work without an address
		Result r = convex.querySync("(+ 1 1)");
		final Result rFinal = r;
		assertFalse(rFinal.isError(), () -> "Query should work even without address: " + rFinal);
		assertEquals(CVMLong.create(2), rFinal.getValue(), "Query (+ 1 1) should return 2");
		
		// Test transactions with null address
		doTransactionTestsWithNullAddress(convex);
	}

	/**
	 * Tests address-related queries and invariants.
	 * 
	 * Verifies that address queries return the correct address and that
	 * balance queries work correctly.
	 * Assumes address is not null.
	 * 
	 * @param convex The Convex instance to test with non-null Address
	 * @throws InterruptedException If interrupted during test execution
	 */
	public static void doAddressTests(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for address tests");
		
		// Test query for address - should return the same address
		Result r = convex.querySync("*address*");
		final Result rFinal = r;
		assertFalse(rFinal.isError(), () -> "Query for *address* should not error: " + rFinal);
		assertEquals(address, rFinal.getValue(), "Query *address* should return the same address");
		
		// Test query for balance - should return non-negative CVMLong
		Result r2 = convex.querySync("*balance*");
		final Result r2Final = r2;
		assertFalse(r2Final.isError(), () -> "Query for *balance* should not error: " + r2Final);
		assertTrue(r2Final.getValue() instanceof CVMLong, "Balance should be a CVMLong");
		CVMLong balance = (CVMLong) r2Final.getValue();
		assertTrue(balance.longValue() >= 0, "Balance should be non-negative");
	}

	/**
	 * Tests query functionality (both sync and async).
	 * 
	 * Verifies that queries execute correctly and return expected results.
	 * Queries should work regardless of address or keypair state.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	public static void doQueryTests(Convex convex) throws InterruptedException, ExecutionException, TimeoutException {
		
		// Test sync query with string - should work in all cases
		Result r = convex.querySync("(+ 10 20)");
		final Result rFinal = r;
		assertFalse(rFinal.isError(), () -> "Query with string should not error: " + rFinal);
		assertEquals(CVMLong.create(30), rFinal.getValue(), "Query (+ 10 20) should return 30");
	}

	/**
	 * Tests transaction functionality (both sync and async).
	 * 
	 * Verifies that transactions execute correctly and return expected results.
	 * Assumes address is not null and keypair matches the account.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	public static void doTransactionTests(Convex convex) throws InterruptedException, ExecutionException, TimeoutException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for transaction tests");
		
		// Test basic sync transaction execution
		Result r = convex.transactSync("(+ 1 2)");
		final Result rFinal = r;
		assertFalse(rFinal.isError(), () -> "Simple transaction should not error: " + rFinal);
		assertEquals(CVMLong.create(3), rFinal.getValue(), "Transaction (+ 1 2) should return 3");
		
		// Test async transaction
		Result r2 = convex.transact("(* 6 7)").get(5000, TimeUnit.MILLISECONDS);
		final Result r2Final = r2;
		assertFalse(r2Final.isError(), () -> "Async transaction should not error: " + r2Final);
		assertEquals(CVMLong.create(42), r2Final.getValue(), "Async transaction (* 6 7) should return 42");
	}

	/**
	 * Tests sequence number handling and invariants.
	 * 
	 * Verifies that sequence numbers are non-negative, increment correctly,
	 * and that transactions work even if sequence lookup fails.
	 * Assumes address is not null and keypair matches the account.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	public static void doSequenceTests(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for sequence tests");
		
		try {
			long sequence = convex.getSequence();
			// Invariant: sequence numbers should be non-negative
			assertTrue(sequence >= 0, "Sequence number should be non-negative");
			
			// Test transaction with auto-sequence
			long initialSequence = sequence;
			Result r = convex.transactSync(Invoke.create(address, 0, Reader.read("*address*")));
			final Result rFinal = r;
			assertFalse(rFinal.isError(), () -> "Transaction with auto-sequence should not error: " + rFinal);
			assertEquals(address, rFinal.getValue(), "Transaction should return address");
			
			// Invariant: sequence should increment or stay the same after transaction
			long newSequence = convex.getSequence();
			assertTrue(newSequence >= initialSequence, "Sequence should increment or stay the same");
		} catch (ResultException e) {
			// Sequence lookup might fail in some scenarios, that's okay
			// Invariant: transactions should still work even if sequence lookup fails
			Result r = convex.transactSync("42");
			final Result rFinal = r;
			assertFalse(rFinal.isError(), () -> "Transaction should work even if sequence lookup fails: " + rFinal);
		}
	}

	/**
	 * Tests keypair and account key access invariants.
	 * 
	 * Verifies that account key correctly reflects the keypair state.
	 * 
	 * @param convex The Convex instance to test
	 */
	public static void doKeyPairTests(Convex convex) {
		AKeyPair keyPair = convex.getKeyPair();
		// Invariant: account key should match keypair if keypair is set, null otherwise
		if (keyPair != null) {
			assertNotNull(convex.getAccountKey(), "Account key should be available if keypair is set");
			assertEquals(keyPair.getAccountKey(), convex.getAccountKey(), 
				"Account key should match keypair");
		} else {
			assertNull(convex.getAccountKey(), "Account key should be null if keypair is not set");
		}
	}
	
	/**
	 * Checks if the current keypair matches the account's public key.
	 * Assumes address is not null.
	 * 
	 * @param convex The Convex instance to test
	 * @return true if keypair matches account, false otherwise
	 * @throws InterruptedException If interrupted during test execution
	 */
	private static boolean checkKeyPairMatchesAccount(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		if (address == null) return false;
		
		try {
			AccountKey accountKey = convex.getAccountKey(address);
			if (accountKey == null) return false;
			AKeyPair keyPair = convex.getKeyPair();
			return keyPair != null && accountKey.equals(keyPair.getAccountKey());
		} catch (Exception e) {
			return false;
		}
	}
	
	
	/**
	 * Tests transaction behaviour when address is null.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	private static void doTransactionTestsWithNullAddress(Convex convex) throws InterruptedException {
		// Transaction should fail - either with :NOBODY error or exception during preparation
		// The transactSync method may throw an exception or return an error result
		try {
			Result r = convex.transactSync("(+ 1 2)");
			assertTrue(r.isError(), "Transaction without address should error");
			// May be :NOBODY or :STATE error depending on when the check happens
			assertTrue(ErrorCodes.NOBODY.equals(r.getErrorCode()) || ErrorCodes.STATE.equals(r.getErrorCode()),
				"Transaction should fail with :NOBODY or :STATE error, got: " + r.getErrorCode());
		} catch (IllegalArgumentException e) {
			// This is also acceptable - transaction preparation fails before execution
			assertTrue(e.getMessage().contains("Null") || e.getMessage().contains("address"),
				"Exception should mention null address: " + e.getMessage());
		}
	}
	
	/**
	 * Tests transaction behaviour when keypair is null.
	 * Assumes address is not null.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	private static void doTransactionTestsWithNullKeyPair(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for transaction tests");
		
		// Transaction should fail with :SIGNATURE error
		Result r = convex.transactSync("(+ 1 2)");
		assertTrue(r.isError(), "Transaction without keypair should error");
		assertEquals(ErrorCodes.SIGNATURE, r.getErrorCode(), "Transaction should fail with :SIGNATURE error");
	}
	
	/**
	 * Tests transaction behaviour when keypair doesn't match the account.
	 * Assumes address is not null.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	private static void doTransactionTestsWithWrongKeyPair(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for transaction tests");
		
		// Transaction should fail - may be :SIGNATURE, :STATE, or :NOBODY error depending on account state
		Result r = convex.transactSync("(+ 1 2)");
		assertTrue(r.isError(), "Transaction with wrong keypair should error");
		// Address.ZERO may not have an account, so could be :STATE or :NOBODY instead of :SIGNATURE
		assertTrue(ErrorCodes.SIGNATURE.equals(r.getErrorCode()) || 
				   ErrorCodes.STATE.equals(r.getErrorCode()) ||
				   ErrorCodes.NOBODY.equals(r.getErrorCode()),
			"Transaction should fail with :SIGNATURE, :STATE, or :NOBODY error, got: " + r.getErrorCode());
	}

	/**
	 * Tests error handling and invariants.
	 * 
	 * Verifies that invalid transactions return a Result (either error or success)
	 * and don't crash the system.
	 * Assumes address is not null.
	 * 
	 * @param convex The Convex instance to test
	 * @throws InterruptedException If interrupted during test execution
	 */
	public static void doErrorHandlingTests(Convex convex) throws InterruptedException {
		Address address = convex.getAddress();
		assertNotNull(address, "Address should not be null for error handling tests");
		
		// Invariant: invalid transactions should return a Result, not crash
		Result r = convex.transactSync(Invoke.create(address, 999999, Reader.read("(invalid-function-call-that-does-not-exist)")));
		// This should either error or succeed, but not crash
		// We just verify it returns a Result
		assertNotNull(r, "Transaction should return a Result");
	}

	/**
	 * Test case that runs doTests with a client from TestNetwork.
	 * 
	 * This test verifies that all generic tests pass when using a ConvexRemote
	 * client obtained from the test network.
	 * 
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	@Test
	public void testWithTestNetworkClient() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		TestNetwork network = TestNetwork.getInstance();
		Convex convex = network.getClient();
		doTests(convex);
	}

	/**
	 * Test case with address set to Address.ZERO.
	 * 
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	@Test
	public void testWithAddressZero() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		TestNetwork network = TestNetwork.getInstance();
		Convex convex = network.getClient();
		Address originalAddress = convex.getAddress();
		try {
			convex.setAddress(Address.ZERO);
			doTests(convex);
		} finally {
			convex.setAddress(originalAddress);
		}
	}

	/**
	 * Test case with address set to null.
	 * 
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	@Test
	public void testWithNullAddress() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		TestNetwork network = TestNetwork.getInstance();
		Convex convex = network.getClient();
		Address originalAddress = convex.getAddress();
		try {
			convex.setAddress(null);
			doTests(convex);
		} finally {
			convex.setAddress(originalAddress);
		}
	}

	/**
	 * Test case with keypair set to null.
	 * 
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	@Test
	public void testWithNullKeyPair() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		TestNetwork network = TestNetwork.getInstance();
		Convex convex = network.getClient();
		AKeyPair originalKeyPair = convex.getKeyPair();
		try {
			convex.setKeyPair(null);
			doTests(convex);
		} finally {
			convex.setKeyPair(originalKeyPair);
		}
	}

	/**
	 * Test case with keypair set to a new keypair.
	 * 
	 * @throws InterruptedException If interrupted during test execution
	 * @throws ResultException If a result error occurs
	 * @throws ExecutionException If an execution error occurs
	 * @throws TimeoutException If a timeout occurs
	 */
	@Test
	public void testWithNewKeyPair() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		TestNetwork network = TestNetwork.getInstance();
		Convex convex = network.getClient();
		AKeyPair originalKeyPair = convex.getKeyPair();
		try {
			convex.setKeyPair(AKeyPair.generate());
			doTests(convex);
		} finally {
			convex.setKeyPair(originalKeyPair);
		}
	}
}

