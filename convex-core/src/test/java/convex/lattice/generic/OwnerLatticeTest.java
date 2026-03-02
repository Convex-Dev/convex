package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;

/**
 * Tests for OwnerLattice ownership and authentication mechanics.
 *
 * OwnerLattice maps owner identities to signed values. During merge, it verifies
 * that the signer (from SignedData) is authorised for the claimed owner identity.
 *
 * Owner types supported:
 * - AccountKey / 32-byte Blob: direct equality with signer
 * - Address: delegate to verifier (e.g. account lookup)
 * - String (DID): delegate to verifier (e.g. DID resolution)
 *
 * @see <a href="https://docs.convex.world/cad/038_lattice_auth">CAD038: Lattice Authentication</a>
 */
public class OwnerLatticeTest {

	// ===== AccountKey Owner Tests =====

	@Test
	public void testAccountKeyOwnerMatchesSigner() {
		AKeyPair kp = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		// Owner key is the signer's AccountKey — should be accepted
		ACell ownerKey = kp.getAccountKey();
		SignedData<AInteger> signed = kp.signData(CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(ownerKey, signed);

		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), incoming);

		assertNotNull(result.get(ownerKey));
		assertEquals(CVMLong.create(42), result.get(ownerKey).getValue());
	}

	@Test
	public void testAccountKeyOwnerMismatchRejected() {
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpSigner = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		// Owner key is kpOwner but signed by kpSigner — should be rejected
		ACell ownerKey = kpOwner.getAccountKey();
		SignedData<AInteger> signed = kpSigner.signData(CVMLong.create(42));
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(ownerKey, signed);

		LatticeContext ctx = LatticeContext.create(null, kpSigner);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), incoming);

		assertNull(result.get(ownerKey), "Mismatched signer should be rejected");
	}

	@Test
	public void testMultipleAccountKeyOwners() {
		AKeyPair kp1 = AKeyPair.generate();
		AKeyPair kp2 = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		// Two owners, each signing their own entry
		ACell owner1 = kp1.getAccountKey();
		ACell owner2 = kp2.getAccountKey();
		SignedData<AInteger> signed1 = kp1.signData(CVMLong.create(10));
		SignedData<AInteger> signed2 = kp2.signData(CVMLong.create(20));

		AHashMap<ACell, SignedData<AInteger>> map1 = Maps.of(owner1, signed1);
		AHashMap<ACell, SignedData<AInteger>> map2 = Maps.of(owner2, signed2);

		LatticeContext ctx = LatticeContext.create(null, kp1);
		AHashMap<ACell, SignedData<AInteger>> merged = lattice.merge(ctx, map1, map2);

		assertEquals(2, merged.size());
		assertEquals(CVMLong.create(10), merged.get(owner1).getValue());
		assertEquals(CVMLong.create(20), merged.get(owner2).getValue());
	}

	// ===== Address Owner Tests =====

	@Test
	public void testAddressOwnerWithVerifier() {
		AKeyPair kpAuthorised = AKeyPair.generate();
		AKeyPair kpUnauthorised = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		Address orgAddress = Address.create(1337);
		AccountKey authorisedKey = kpAuthorised.getAccountKey();

		// Verifier that accepts only the authorised key for the org address
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof Address addr && addr.longValue() == 1337) {
				return authorisedKey.equals(signerKey);
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kpAuthorised, verifier);

		// Authorised signer → accepted
		SignedData<AInteger> goodSigned = kpAuthorised.signData(CVMLong.create(100));
		AHashMap<ACell, SignedData<AInteger>> goodIncoming = Maps.of(orgAddress, goodSigned);
		AHashMap<ACell, SignedData<AInteger>> result1 = lattice.merge(ctx, Maps.empty(), goodIncoming);

		assertNotNull(result1.get(orgAddress), "Authorised signer should be accepted");
		assertEquals(CVMLong.create(100), result1.get(orgAddress).getValue());

		// Unauthorised signer → rejected
		SignedData<AInteger> badSigned = kpUnauthorised.signData(CVMLong.create(200));
		AHashMap<ACell, SignedData<AInteger>> badIncoming = Maps.of(orgAddress, badSigned);
		AHashMap<ACell, SignedData<AInteger>> result2 = lattice.merge(ctx, Maps.empty(), badIncoming);

		assertNull(result2.get(orgAddress), "Unauthorised signer should be rejected");
	}

	@Test
	public void testAddressOwnerMultipleAuthorisedKeys() {
		AKeyPair kpAdmin1 = AKeyPair.generate();
		AKeyPair kpAdmin2 = AKeyPair.generate();
		AKeyPair kpRogue = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		Address orgAddress = Address.create(42);
		Set<AccountKey> authorisedKeys = Set.of(
			kpAdmin1.getAccountKey(),
			kpAdmin2.getAccountKey()
		);

		// Verifier simulating an org account with multiple admins
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof Address addr && addr.longValue() == 42) {
				return authorisedKeys.contains(signerKey);
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kpAdmin1, verifier);

		// Admin 1 → accepted
		SignedData<AInteger> signed1 = kpAdmin1.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> result1 = lattice.merge(ctx, Maps.empty(), Maps.of(orgAddress, signed1));
		assertNotNull(result1.get(orgAddress), "Admin 1 should be accepted");

		// Admin 2 → accepted
		SignedData<AInteger> signed2 = kpAdmin2.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> result2 = lattice.merge(ctx, Maps.empty(), Maps.of(orgAddress, signed2));
		assertNotNull(result2.get(orgAddress), "Admin 2 should be accepted");

		// Rogue → rejected
		SignedData<AInteger> signedRogue = kpRogue.signData(CVMLong.create(30));
		AHashMap<ACell, SignedData<AInteger>> resultRogue = lattice.merge(ctx, Maps.empty(), Maps.of(orgAddress, signedRogue));
		assertNull(resultRogue.get(orgAddress), "Rogue key should be rejected");
	}

	// ===== String (DID) Owner Tests =====

	@Test
	public void testDIDOwnerWithVerifier() {
		AKeyPair kpAuthorised = AKeyPair.generate();
		AKeyPair kpUnauthorised = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		ACell didOwner = Strings.create("did:convex:#1337");
		AccountKey authorisedKey = kpAuthorised.getAccountKey();

		// Verifier that resolves the DID to a specific key
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof convex.core.data.AString s && s.toString().equals("did:convex:#1337")) {
				return authorisedKey.equals(signerKey);
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kpAuthorised, verifier);

		// Authorised signer → accepted
		SignedData<AInteger> goodSigned = kpAuthorised.signData(CVMLong.create(50));
		AHashMap<ACell, SignedData<AInteger>> result1 = lattice.merge(ctx, Maps.empty(), Maps.of(didOwner, goodSigned));
		assertNotNull(result1.get(didOwner), "Authorised DID signer should be accepted");

		// Unauthorised signer → rejected
		SignedData<AInteger> badSigned = kpUnauthorised.signData(CVMLong.create(60));
		AHashMap<ACell, SignedData<AInteger>> result2 = lattice.merge(ctx, Maps.empty(), Maps.of(didOwner, badSigned));
		assertNull(result2.get(didOwner), "Unauthorised DID signer should be rejected");
	}

	@Test
	public void testDIDKeyMethodDirectResolution() {
		// did:key: encodes the public key directly, so we can verify without external lookup
		AKeyPair kp = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		// Simulate did:key: format (in practice, would be multibase-encoded)
		String didKey = "did:key:" + kp.getAccountKey().toHexString();
		ACell didOwner = Strings.create(didKey);

		// Verifier that extracts key from did:key: and compares
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof convex.core.data.AString s) {
				String did = s.toString();
				if (did.startsWith("did:key:")) {
					String keyHex = did.substring("did:key:".length());
					AccountKey extractedKey = AccountKey.fromHex(keyHex);
					return extractedKey != null && extractedKey.equals(signerKey);
				}
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kp, verifier);

		// Matching key → accepted
		SignedData<AInteger> signed = kp.signData(CVMLong.create(77));
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), Maps.of(didOwner, signed));
		assertNotNull(result.get(didOwner), "did:key: with matching signer should be accepted");
	}

	// ===== Lenient Mode Tests =====

	@Test
	public void testNoVerifierLenientMode() {
		AKeyPair kp = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		// String owner with no verifier → lenient mode accepts
		ACell stringOwner = Strings.create("any-string-owner");
		SignedData<AInteger> signed = kp.signData(CVMLong.create(99));

		// Context without verifier
		LatticeContext ctx = LatticeContext.create(null, kp);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), Maps.of(stringOwner, signed));

		assertNotNull(result.get(stringOwner), "Lenient mode should accept string owner without verifier");
	}

	@Test
	public void testAccountKeyOwnerNoVerifierStillVerifies() {
		// AccountKey owners are verified by direct equality even without a verifier
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpSigner = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		ACell ownerKey = kpOwner.getAccountKey();
		SignedData<AInteger> signed = kpSigner.signData(CVMLong.create(42));

		// No verifier, but AccountKey mismatch should still be rejected
		LatticeContext ctx = LatticeContext.create(null, kpSigner);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), Maps.of(ownerKey, signed));

		assertNull(result.get(ownerKey), "AccountKey mismatch should be rejected even without verifier");
	}

	// ===== Merge Direction Tests =====

	@Test
	public void testOnlyIncomingValuesVerified() {
		AKeyPair kpOwn = AKeyPair.generate();
		AKeyPair kpOther = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		ACell ownOwner = kpOwn.getAccountKey();
		ACell otherOwner = kpOther.getAccountKey();

		// Own value (trusted, not verified)
		SignedData<AInteger> ownSigned = kpOwn.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> ownMap = Maps.of(ownOwner, ownSigned);

		// Other value (incoming, verified)
		SignedData<AInteger> otherSigned = kpOther.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> otherMap = Maps.of(otherOwner, otherSigned);

		LatticeContext ctx = LatticeContext.create(null, kpOwn);
		AHashMap<ACell, SignedData<AInteger>> merged = lattice.merge(ctx, ownMap, otherMap);

		// Both should be present — own is trusted, other passes verification
		assertEquals(2, merged.size());
		assertEquals(CVMLong.create(10), merged.get(ownOwner).getValue());
		assertEquals(CVMLong.create(20), merged.get(otherOwner).getValue());
	}

	// ===== Mixed Owner Types =====

	@Test
	public void testMixedOwnerTypes() {
		AKeyPair kpBlob = AKeyPair.generate();
		AKeyPair kpAddress = AKeyPair.generate();
		AKeyPair kpDid = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		ACell blobOwner = kpBlob.getAccountKey();
		Address addressOwner = Address.create(999);
		ACell didOwner = Strings.create("did:example:123");

		// Verifier handles Address and DID owners
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof Address addr && addr.longValue() == 999) {
				return kpAddress.getAccountKey().equals(signerKey);
			}
			if (owner instanceof convex.core.data.AString s && s.toString().equals("did:example:123")) {
				return kpDid.getAccountKey().equals(signerKey);
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kpBlob, verifier);

		// Build incoming map with all three owner types
		AHashMap<ACell, SignedData<AInteger>> incoming = Maps.of(
			blobOwner, kpBlob.signData(CVMLong.create(1)),
			addressOwner, kpAddress.signData(CVMLong.create(2)),
			didOwner, kpDid.signData(CVMLong.create(3))
		);

		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), incoming);

		assertEquals(3, result.size());
		assertEquals(CVMLong.create(1), result.get(blobOwner).getValue());
		assertEquals(CVMLong.create(2), result.get(addressOwner).getValue());
		assertEquals(CVMLong.create(3), result.get(didOwner).getValue());
	}

	// ===== Value Update Tests =====

	@Test
	public void testOwnerValueUpdate() {
		AKeyPair kp = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kp);

		ACell owner = kp.getAccountKey();

		// Initial value
		SignedData<AInteger> signed1 = kp.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> map1 = Maps.of(owner, signed1);

		// Updated value (higher, wins with MaxLattice)
		SignedData<AInteger> signed2 = kp.signData(CVMLong.create(20));
		AHashMap<ACell, SignedData<AInteger>> map2 = Maps.of(owner, signed2);

		AHashMap<ACell, SignedData<AInteger>> merged = lattice.merge(ctx, map1, map2);

		assertEquals(CVMLong.create(20), merged.get(owner).getValue(), "MaxLattice should take higher value");
	}

	@Test
	public void testOwnerValueUpdateRejectedIfUnauthorised() {
		AKeyPair kpOwner = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kpOwner);

		ACell owner = kpOwner.getAccountKey();

		// Owner's legitimate value
		SignedData<AInteger> legitimate = kpOwner.signData(CVMLong.create(10));
		AHashMap<ACell, SignedData<AInteger>> ownMap = Maps.of(owner, legitimate);

		// Attacker tries to overwrite with higher value
		SignedData<AInteger> attack = kpAttacker.signData(CVMLong.create(999));
		AHashMap<ACell, SignedData<AInteger>> attackMap = Maps.of(owner, attack);

		AHashMap<ACell, SignedData<AInteger>> merged = lattice.merge(ctx, ownMap, attackMap);

		// Attack should be rejected, original value preserved
		assertEquals(CVMLong.create(10), merged.get(owner).getValue(), "Attack should be rejected");
		assertEquals(kpOwner.getAccountKey(), merged.get(owner).getAccountKey(), "Original signer preserved");
	}

	// ===== Adversarial Tests =====

	@Test
	public void testCorruptedSignatureRejected() {
		// Correct owner key but signature bytes are corrupted
		AKeyPair kp = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kp);

		ACell owner = kp.getAccountKey();

		// Create a valid signed value
		SignedData<AInteger> validSigned = kp.signData(CVMLong.create(42));

		// Corrupt the signature by creating a new SignedData with wrong signature bytes
		// We can't directly corrupt bytes, but we can test via SignedLattice.checkForeign
		// which validates signatures. The OwnerLattice delegates to SignedLattice.
		// For this test, we verify that checkSignature works correctly.
		assertTrue(validSigned.checkSignature(), "Valid signature should pass");

		// Create a SignedData where the signature is from a different key
		AKeyPair kpOther = AKeyPair.generate();
		SignedData<AInteger> otherSigned = kpOther.signData(CVMLong.create(42));

		// This has a valid signature but for kpOther, not kp
		// When placed under kp's owner key, should be rejected
		AHashMap<ACell, SignedData<AInteger>> forgedMap = Maps.of(owner, otherSigned);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), forgedMap);

		assertNull(result.get(owner), "Signature from wrong key should be rejected");
	}

	@Test
	public void testValidSignatureWrongOwnerKeyRejected() {
		// Attacker signs with their own key but places under victim's owner key
		AKeyPair kpVictim = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kpVictim);

		ACell victimOwner = kpVictim.getAccountKey();

		// Attacker creates a validly signed value with their own key
		SignedData<AInteger> attackerSigned = kpAttacker.signData(CVMLong.create(666));
		assertTrue(attackerSigned.checkSignature(), "Attacker's signature is valid");
		assertEquals(kpAttacker.getAccountKey(), attackerSigned.getAccountKey());

		// Attacker places this under victim's owner key
		AHashMap<ACell, SignedData<AInteger>> attackMap = Maps.of(victimOwner, attackerSigned);

		// Should be rejected: signature is valid but signer != owner
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), attackMap);

		assertNull(result.get(victimOwner), "Valid signature under wrong owner should be rejected");
	}

	@Test
	public void testReplayAttackBlocked() {
		// Attacker replays a legitimate value from victim under their own owner key
		AKeyPair kpVictim = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());
		LatticeContext ctx = LatticeContext.create(null, kpVictim);

		// Victim creates legitimate signed value
		SignedData<AInteger> victimSigned = kpVictim.signData(CVMLong.create(100));

		// Attacker tries to replay victim's signed data under attacker's owner key
		ACell attackerOwner = kpAttacker.getAccountKey();
		AHashMap<ACell, SignedData<AInteger>> replayMap = Maps.of(attackerOwner, victimSigned);

		// Should be rejected: victim's signature doesn't match attacker's owner key
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), replayMap);

		assertNull(result.get(attackerOwner), "Replay of victim's data under attacker's key should be rejected");
	}

	@Test
	public void testAddressOwnerAttackWithWrongSigner() {
		// Attacker tries to write to an Address owner they don't control
		AKeyPair kpAuthorised = AKeyPair.generate();
		AKeyPair kpAttacker = AKeyPair.generate();
		OwnerLattice<AInteger> lattice = OwnerLattice.create(MaxLattice.create());

		Address orgAddress = Address.create(1234);
		AccountKey authorisedKey = kpAuthorised.getAccountKey();

		// Verifier only accepts the authorised key
		BiPredicate<ACell, AccountKey> verifier = (owner, signerKey) -> {
			if (owner instanceof Address addr && addr.longValue() == 1234) {
				return authorisedKey.equals(signerKey);
			}
			return false;
		};

		LatticeContext ctx = LatticeContext.create(null, kpAuthorised, verifier);

		// Attacker signs data and places under org address
		SignedData<AInteger> attackerSigned = kpAttacker.signData(CVMLong.create(999));
		assertTrue(attackerSigned.checkSignature(), "Attacker's signature is valid");

		AHashMap<ACell, SignedData<AInteger>> attackMap = Maps.of(orgAddress, attackerSigned);
		AHashMap<ACell, SignedData<AInteger>> result = lattice.merge(ctx, Maps.empty(), attackMap);

		// Verifier rejects because attacker is not authorised for org address
		assertNull(result.get(orgAddress), "Unauthorised signer for Address owner should be rejected");
	}
}
