package convex.auth.did;

import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/**
 * The {@code did:key} method for Ed25519 keys: a DID that is its own public key, so it
 * resolves without network access.
 *
 * @see <a href="https://w3c-ccg.github.io/did-key-spec/">The did:key Method v0.9</a>
 */
public final class DIDKey {

	private static final String PREFIX="did:key:";
	private static final AString DID_KEY=Strings.intern(PREFIX);
	private static final AString DID_CONTEXT=Strings.intern("https://www.w3.org/ns/did/v1.1");
	private static final AString MULTIKEY=Strings.intern("Multikey");

	private DIDKey() {}

	/**
	 * Resolves a {@code did:key}. Only Ed25519 keys are supported; anything else gives
	 * {@link DIDResolution#INVALID_DID}.
	 *
	 * @param did canonical {@code did:key} DID
	 * @return resolution holding the document {@link #document} builds
	 */
	public static DIDResolution resolve(AString did) {
		if ((did==null) || !did.toString().startsWith(PREFIX)) {
			return DIDResolution.error(DIDResolution.INVALID_DID,"Not a did:key: "+did);
		}
		try {
			AccountKey key=Multikey.decodePublicKey(did.toString().substring(PREFIX.length()));
			return DIDResolution.of(document(key),null);
		} catch (IllegalArgumentException e) {
			return DIDResolution.error(DIDResolution.INVALID_DID,e.getMessage());
		}
	}

	/**
	 * The document an Ed25519 {@code did:key} resolves to: one {@code Multikey}
	 * verification method, identified by the encoded key, in the authentication,
	 * assertion, capability invocation and capability delegation relationships.
	 *
	 * @param key Ed25519 public key
	 * @return DID document
	 */
	public static AMap<AString,ACell> document(AccountKey key) {
		AString encoded=Multikey.encodePublicKey(key);
		AString did=DID_KEY.append(encoded);
		AString methodID=did.append("#").append(encoded);
		AMap<AString,ACell> method=Maps.of(
			DIDDocuments.ID,methodID,
			DIDDocuments.TYPE,MULTIKEY,
			DIDDocuments.CONTROLLER,did,
			DIDDocuments.PUBLIC_KEY_MULTIBASE,encoded);
		AVector<AString> reference=Vectors.of(methodID);
		return Maps.of(
			DIDDocuments.CONTEXT,Vectors.of(DID_CONTEXT),
			DIDDocuments.ID,did,
			DIDDocuments.VERIFICATION_METHOD,Vectors.of(method),
			DIDDocuments.AUTHENTICATION,reference,
			DIDDocuments.ASSERTION_METHOD,reference,
			DIDDocuments.CAPABILITY_INVOCATION,reference,
			DIDDocuments.CAPABILITY_DELEGATION,reference);
	}
}
