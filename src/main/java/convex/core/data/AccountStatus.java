package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Core;
import convex.core.lang.IFn;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.util.Utils;

/**
 * Class representing the current on-chain status of an account.
 * 
 * Accounts may be User accounts or Actor accounts.
 * 
 * "People said I should accept the world. Bullshit! I don't accept the world."
 * - Richard Stallman
 */
public class AccountStatus extends ACell {
	private final long sequence;
	private final Amount balance;
	private final AHashMap<Symbol, Syntax> environment;
	private final ABlobMap<Address, Object> holdings;

	private AccountStatus(long sequence, Amount balance,
			AHashMap<Symbol, Syntax> environment, ABlobMap<Address, Object> holdings) {
		this.sequence = sequence;
		this.balance = balance;
		this.environment = environment;
		this.holdings=holdings;
	}

	/**
	 * Create a regular account.
	 * 
	 * @param sequence
	 * @param balance
	 * @return New AccountStatus
	 */
	public static AccountStatus create(long sequence, Amount balance) {
		return new AccountStatus(sequence, balance, null,null);
	}

	/**
	 * Create a governance account.
	 * 
	 * @param sequence
	 * @param balance
	 * @return New governance AccountStatus
	 */
	public static AccountStatus createGovernance(long balance) {
		Amount amount = Amount.create(balance);
		return new AccountStatus(0, amount, null,null);
	}

	public static AccountStatus createActor(Amount balance,
			AHashMap<Symbol, Syntax> environment) {
		return new AccountStatus(Constants.ACTOR_SEQUENCE, balance, environment,null);
	}

	public static AccountStatus create(Amount balance) {
		return create(0, balance);
	}

	public static AccountStatus create() {
		return create(0, Amount.ZERO);
	}

	/**
	 * Gets the sequence number for this Account. The sequence number is the number
	 * of transactions executed by this account to date. It will be zero for new
	 * Accounts.
	 * 
	 * The next transaction executed must have a nonce equal to this value plus one.
	 * 
	 * @return The sequence number for this Account.
	 */
	public long getSequence() {
		return sequence;
	}

	public Amount getBalance() {
		return balance;
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.ACCOUNT_STATUS);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = Format.writeVLCLong(b, sequence);
		b = Format.write(b, balance);
		b = Format.write(b, environment);
		b = Format.write(b, holdings);
		return b;
	}

	public static AccountStatus read(ByteBuffer data) throws BadFormatException {
		long sequence = Format.readVLCLong(data);
		Amount balance = Format.read(data);
		AHashMap<Symbol, Syntax> environment = Format.read(data);
		ABlobMap<Address,Object> holdings = Format.read(data);
		return new AccountStatus(sequence, balance, environment,holdings);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	public boolean isActor() {
		return sequence<0;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#accountstatus {");
		sb.append(":balance " + Utils.ednString(balance));
		sb.append(',');
		sb.append(":seq " + Utils.ednString(sequence));
		sb.append('}');
	}

	/**
	 * Gets the exported function for a given symbol in an Actor
	 * 
	 * Returns null if not found (either this account is not an Actor, or it
	 * does not have the specified exported symbol).
	 * 
	 * @param <R>
	 * @param sym
	 * @return The function specified in Actor, or null if not
	 *         found/exported.
	 * @throws BadStateException
	 */
	public <R> IFn<R> getActorFunction(Symbol sym) {
		ASet<Symbol> exports = getExports();
		if (exports==null) return null;
		if (!exports.contains(sym)) return null;

		// get function from environment. Anything not a function results in null
		Syntax functionSyn = environment.get(sym);
		IFn<R> fn = RT.function(functionSyn.getValue());
		return fn;
	}

	public AHashMap<Symbol, Syntax> getEnvironment() {
		// default to standard environment
		// needed to avoid circularity in static initialisation?
		if (environment == null) return Core.ENVIRONMENT;
		return environment;
	}

	/**
	 * Checks if this account has enough balance for a transaction consuming the
	 * specified amount.
	 * 
	 * @param amt minimum amount that must be present in the specified balance
	 */
	public boolean hasBalance(long amt) {
		if (amt < 0) return false;
		if (amt > balance.getValue()) return false;
		return true;
	}

	public AccountStatus withBalance(Amount newBalance) {
		return new AccountStatus(sequence, newBalance, environment,holdings);
	}

	public AccountStatus withBalance(long newBalance) {
		return withBalance(Amount.create(newBalance));
	}

	public AccountStatus withEnvironment(AHashMap<Symbol, Syntax> newEnvironment) {
		// Core environment stored as null for efficiency
		if (newEnvironment==Core.ENVIRONMENT) newEnvironment=null;
		
		if (environment==newEnvironment) return this;
		return new AccountStatus(sequence, balance, newEnvironment,holdings);
	}

	/**
	 * Updates this account with a new sequence number.
	 * 
	 * @param newSequence
	 * @return Updated account, or null if the sequence number was wrong
	 */
	public AccountStatus updateSequence(long newSequence) {
		// SECURITY: shouldn't ever call updateSequence on a Actor address!
		if (isActor()) throw new Error("Trying to update Actor sequence number!");

		long expected = sequence + 1;
		if (expected != newSequence) {
			return null;
		}
		// NOTE: we can always assume actorArgs is null from security checks above
		return new AccountStatus(newSequence, balance, environment,holdings);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		balance.validate();
		if (environment != null) environment.validateCell();
	}

	/**
	 * Gets the value in the Account's environment for the given symbol.
	 * 
	 * @param <R>
	 * @param sym
	 * @return The value from the environment, or null if not found
	 */
	public <R> R getEnvironmentValue(Symbol symbol) {
		if (environment == null) return null;
		Syntax syntax = environment.get(symbol);
		if (syntax == null) return null;
		return syntax.getValue();
	}

	/**
	 * Gets the holdings for this account. Will always be a non-null map.
	 * @return Holdings map for this account
	 */
	public ABlobMap<Address, Object> getHoldings() {
		ABlobMap<Address, Object> result=holdings;
		if (result==null) return BlobMaps.empty();
		return result;
	}
	
	public Object getHolding(Address addr) {
		return holdings.get(addr);
	}
	
	public AccountStatus withHolding(Address addr,Object value) {
		ABlobMap<Address, Object> newHoldings=getHoldings();
		if (value==null) {
			newHoldings=newHoldings.dissoc(addr);
		} else if (newHoldings==null) {
			newHoldings=BlobMaps.of(addr,value);
		} else {
			newHoldings=newHoldings.assoc(addr, value);
		}
		return withHoldings(newHoldings);
	}

	private AccountStatus withHoldings(ABlobMap<Address, Object> newHoldings) {
		if (newHoldings.isEmpty()) newHoldings=null;
		if (holdings==newHoldings) return this;
		return new AccountStatus(sequence, balance, environment,newHoldings);
	}

	/**
	 * Gets *exports* from account
	 * 
	 * Returns null if the account has no *exports*. This might be for any of the following reasons:
	 * <ul>
	 * <li>The accounts is not an actor</li>
	 * <li>The account does not define the *exports* symbol</li>
	 * </ul>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public ASet<Symbol> getExports() {
		if (!isActor()) return null;
		
		// get *exports* from Actor environment, bail out if doesn't exist
		Syntax exportSyn = environment.get(Symbols.STAR_EXPORTS);
		if (exportSyn == null) return null;

		// examine *exports* value, bail out if not a set
		Object s = exportSyn.getValue();
		if (!(s instanceof ASet)) return null;

		ASet<Symbol> exports = (ASet<Symbol>) s;
		return exports;
	}
}
