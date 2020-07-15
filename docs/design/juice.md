# Juice

## Rationale

On-chain transactions must be controlled via cryptoeconomic methods to ensure that scarce on-chain compute capacity is utilised productively.

Without such controls, the Convex system would face significant problems from:
- Lack of incentives to write efficient code
- Cheap denial of service attacks on the network by wasting compute time
- Tragedy of the commons effects, where compute is treated as free but the cost falls on peer operators

## Overview of Solution

Every transaction submitted to Convex must be backed by a reserve of juice. If no juice is available from the account that submits the transaction, it will automatically fail with the JUICE exception.

During the execution of the transaction, juice is consumed depending on the natire of execution.

- A Transfer transaction pays a fixed juice cost
- An Invoke transaction pays juice for each on-chain operation executed, according to a pre-defined juice cost schedule.

Once the transaction completes, any remaining juice is refunded to the caller.


