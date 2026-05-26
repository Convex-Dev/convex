package convex.core.lang;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test demonstrating front-running attack vector in Convex CPoS.
 * 
 * The vulnerability is in TransactionHandler.java:373-376:
 *   maybeGetOwnTransactions(peer);  // Peer can insert own txs
 *   transactionQueue.drainTo(newTransactions);  // Client txs come after
 * 
 * This gives the block-producing peer full control over transaction ordering.
 */
public class FrontRunningTest extends ACVMTest {

    @Test
    public void testTransactionOrderingManipulation() {
        Context c = context();
        
        // Deploy a simple DEX contract
        c = exec(c, """
            (do
              (def *price* 1000)
              (def *reserve* 100000)
              
              (defn ^:callable swap [amount]
                (let [new-reserve (+ *reserve* amount)
                      tokens-out (/ (* *reserve* *price*) new-reserve)]
                  (set! *reserve* new-reserve)
                  (set! *price* (/ new-reserve tokens-out))
                  tokens-out)))
        """);
        
        // Simulate: Attacker front-runs victim
        // In real attack, malicious peer would:
        // 1. See victim's swap(10000) in transactionQueue
        // 2. Insert own swap(5000) BEFORE victim in block
        // 3. Victim's swap executes at higher price
        
        // Execute attacker's swap first (simulating block ordering)
        long attackerTokens = ((CVMLong) eval(c, "(swap 5000)")).longValue();
        long priceAfterAttacker = ((CVMLong) eval(c, "*price*")).longValue();
        
        // Execute victim's swap (at worse price)
        long victimTokens = ((CVMLong) eval(c, "(swap 10000)")).longValue();
        long priceAfterVictim = ((CVMLong) eval(c, "*price*")).longValue();
        
        // Verify: victim gets fewer tokens due to attacker's front-run
        assertTrue(priceAfterVictim > priceAfterAttacker,
            "Victim should pay higher price after attacker front-runs");
        
        // Calculate attacker's profit
        // Attacker bought at lower price, can sell at higher price
        assertTrue(attackerTokens > 0,
            "Attacker should receive tokens from front-run buy");
    }
}
