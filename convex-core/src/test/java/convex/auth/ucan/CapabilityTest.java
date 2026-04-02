package convex.auth.ucan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Tests for Capability — UCAN capability matching and construction.
 */
public class CapabilityTest {

	// ========== Factory methods ==========

	@Test
	public void testCreate() {
		AMap<AString, ACell> cap = Capability.create(
			Strings.create("w/data"), Capability.CRUD_READ);
		assertEquals(Strings.create("w/data"), cap.get(Capability.WITH));
		assertEquals(Capability.CRUD_READ, cap.get(Capability.CAN));
		assertNull(cap.get(Capability.NB));
	}

	@Test
	public void testCreateWithCaveats() {
		AMap<AString, ACell> nb = Maps.of(Strings.create("maxSize"), 1000);
		AMap<AString, ACell> cap = Capability.create(
			Strings.create("w/data"), Capability.CRUD_READ, nb);
		assertNotNull(cap.get(Capability.NB));
	}

	// ========== Ability matching (AString) ==========

	@Test
	public void testAbilityExactMatch() {
		assertTrue(Capability.abilityCovers(Capability.CRUD_READ, Capability.CRUD_READ));
		assertTrue(Capability.abilityCovers(Capability.CRUD_WRITE, Capability.CRUD_WRITE));
	}

	@Test
	public void testAbilityPrefixMatch() {
		assertTrue(Capability.abilityCovers(Capability.CRUD, Capability.CRUD_READ));
		assertTrue(Capability.abilityCovers(Capability.CRUD, Capability.CRUD_WRITE));
		assertTrue(Capability.abilityCovers(Capability.CRUD, Capability.CRUD_DELETE));
	}

	@Test
	public void testAbilityTopCoversAll() {
		assertTrue(Capability.abilityCovers(Capability.TOP, Capability.CRUD_READ));
		assertTrue(Capability.abilityCovers(Capability.TOP, Strings.create("invoke")));
		assertTrue(Capability.abilityCovers(Capability.TOP, Strings.create("anything")));
	}

	@Test
	public void testAbilityNotCovered() {
		assertFalse(Capability.abilityCovers(Capability.CRUD_READ, Capability.CRUD_WRITE));
		assertFalse(Capability.abilityCovers(Capability.CRUD_READ, Capability.CRUD));
		assertFalse(Capability.abilityCovers(Strings.create("agent/request"), Capability.CRUD_READ));
	}

	@Test
	public void testAbilityPrefixBoundary() {
		// "crud" should NOT cover "cruddy" (no / boundary)
		assertFalse(Capability.abilityCovers(Capability.CRUD, Strings.create("cruddy")));
		// "crud" covers "crud/read" (has / boundary)
		assertTrue(Capability.abilityCovers(Capability.CRUD, Strings.create("crud/read")));
	}

	@Test
	public void testAbilityNullGrant() {
		assertFalse(Capability.abilityCovers(null, Capability.CRUD_READ));
	}

	// ========== Resource matching (AString) ==========

	@Test
	public void testResourceExactMatch() {
		assertTrue(Capability.resourceCovers(
			Strings.create("w/decisions"), Strings.create("w/decisions")));
	}

	@Test
	public void testResourcePrefixMatch() {
		assertTrue(Capability.resourceCovers(
			Strings.create("w/decisions"), Strings.create("w/decisions/INV-123")));
	}

	@Test
	public void testResourceTrailingSlashCoversBase() {
		assertTrue(Capability.resourceCovers(
			Strings.create("w/records/"), Strings.create("w/records")));
	}

	@Test
	public void testResourceTrailingSlashCoversChildren() {
		assertTrue(Capability.resourceCovers(
			Strings.create("w/records/"), Strings.create("w/records/Acme")));
	}

	@Test
	public void testResourceNotCovered() {
		assertFalse(Capability.resourceCovers(
			Strings.create("w/decisions"), Strings.create("w/vendor-records/Acme")));
	}

	@Test
	public void testResourceNullGrantCoversAll() {
		assertTrue(Capability.resourceCovers(null, Strings.create("w/anything")));
	}

	@Test
	public void testResourceEmptyGrantCoversAll() {
		assertTrue(Capability.resourceCovers(Strings.create(""), Strings.create("w/anything")));
	}

	@Test
	public void testResourceNullRequestAllowed() {
		assertTrue(Capability.resourceCovers(Strings.create("w/data"), null));
	}

	// ========== Full covers (AString) ==========

	@Test
	public void testCoversAString() {
		assertTrue(Capability.covers(
			Strings.create("w/decisions"), Capability.CRUD_WRITE,
			Strings.create("w/decisions/INV-123"), Capability.CRUD_WRITE));
	}

	@Test
	public void testCoversDeniedResource() {
		assertFalse(Capability.covers(
			Strings.create("w/decisions"), Capability.CRUD_WRITE,
			Strings.create("w/vendor-records/Acme"), Capability.CRUD_WRITE));
	}

	@Test
	public void testCoversDeniedAbility() {
		assertFalse(Capability.covers(
			Strings.create("w/"), Capability.CRUD_READ,
			Strings.create("w/data"), Capability.CRUD_WRITE));
	}

	// ========== Full covers (AMap) ==========

	@Test
	public void testCoversMapGrant() {
		AMap<AString, ACell> cap = Capability.create(
			Strings.create("w/enrichments"), Capability.CRUD_WRITE);
		assertTrue(Capability.covers(cap,
			Strings.create("w/enrichments/INV-123"), Capability.CRUD_WRITE));
		assertFalse(Capability.covers(cap,
			Strings.create("w/decisions/INV-123"), Capability.CRUD_WRITE));
	}

	@Test
	public void testCoversMapGrantString() {
		AMap<AString, ACell> cap = Capability.create(
			Strings.create("w/enrichments"), Capability.CRUD_WRITE);
		assertTrue(Capability.covers(cap, "w/enrichments/INV-123", "crud/write"));
		assertFalse(Capability.covers(cap, "w/decisions/INV-123", "crud/write"));
	}

	// ========== String convenience overloads ==========

	@Test
	public void testCoversString() {
		assertTrue(Capability.covers("w/decisions", "crud/write",
			"w/decisions/INV-123", "crud/write"));
		assertFalse(Capability.covers("w/decisions", "crud/read",
			"w/decisions/INV-123", "crud/write"));
	}

	@Test
	public void testCoversStringWildcard() {
		assertTrue(Capability.covers("", "*", "w/anything", "crud/write"));
	}

	@Test
	public void testCoversStringNull() {
		assertTrue(Capability.covers((String) null, "*", "w/anything", "crud/read"));
	}

	// ========== DID URL resources ==========

	@Test
	public void testDIDURLResource() {
		AString grant = Strings.create("did:key:zAlice/w/");
		AString request = Strings.create("did:key:zAlice/w/notes");
		assertTrue(Capability.resourceCovers(grant, request));
	}

	@Test
	public void testDIDURLResourceDifferentUser() {
		AString grant = Strings.create("did:key:zAlice/w/");
		AString request = Strings.create("did:key:zBob/w/notes");
		assertFalse(Capability.resourceCovers(grant, request));
	}

	// ========== resourceURI ==========

	@Test
	public void testResourceURI() {
		AString uri = Capability.resourceURI("account", 42);
		assertEquals("convex:account:#42", uri.toString());
	}
}
