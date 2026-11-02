package convex.auth.did;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Reads DID documents held as maps keyed by their JSON property names, as
 * {@link DIDResolution} carries them.
 *
 * @see <a href="https://www.w3.org/TR/did-1.1/">W3C DID 1.1</a>
 * @see <a href="https://www.w3.org/TR/cid-1.0/">W3C Controlled Identifiers 1.0</a>
 */
public final class DIDDocuments {

	public static final AString CONTEXT=Strings.intern("@context");
	public static final AString ID=Strings.intern("id");
	public static final AString TYPE=Strings.intern("type");
	public static final AString CONTROLLER=Strings.intern("controller");
	public static final AString VERIFICATION_METHOD=Strings.intern("verificationMethod");
	public static final AString AUTHENTICATION=Strings.intern("authentication");
	public static final AString ASSERTION_METHOD=Strings.intern("assertionMethod");
	public static final AString KEY_AGREEMENT=Strings.intern("keyAgreement");
	public static final AString CAPABILITY_INVOCATION=Strings.intern("capabilityInvocation");
	public static final AString CAPABILITY_DELEGATION=Strings.intern("capabilityDelegation");
	public static final AString PUBLIC_KEY_MULTIBASE=Strings.intern("publicKeyMultibase");
	public static final AString PUBLIC_KEY_JWK=Strings.intern("publicKeyJwk");
	public static final AString SERVICE=Strings.intern("service");
	public static final AString SERVICE_ENDPOINT=Strings.intern("serviceEndpoint");

	private static final AString[] RELATIONSHIPS={AUTHENTICATION,ASSERTION_METHOD,KEY_AGREEMENT,
		CAPABILITY_INVOCATION,CAPABILITY_DELEGATION};
	private static final Set<String> MULTIBASE_TYPES=Set.of("Multikey","Ed25519VerificationKey2020");
	private static final Set<String> JWK_TYPES=Set.of("JsonWebKey","JsonWebKey2020");
	private static final AString KTY=Strings.intern("kty");
	private static final AString CRV=Strings.intern("crv");
	private static final AString X=Strings.intern("x");
	private static final AString D=Strings.intern("d");
	private static final AString OKP=Strings.intern("OKP");
	private static final AString ED25519=Strings.intern("Ed25519");

	private DIDDocuments() {}

	/**
	 * Checks what the readers here rely on: the document has an {@code id}, and no two
	 * verification methods or services share an id. DID Core requires consumers to
	 * refuse duplicate service ids; duplicate verification method ids are refused
	 * because they make a key reference ambiguous.
	 *
	 * @param document DID document
	 * @return null if the document passes, otherwise what is wrong with it
	 */
	public static String problem(AMap<AString,ACell> document) {
		if (document==null) return "No document";
		AString did=RT.ensureString(document.get(ID));
		if (did==null) return "Document has no id";
		Set<String> methodIDs=new HashSet<>();
		AVector<ACell> methods=items(document.get(VERIFICATION_METHOD));
		for (long i=0; i<methods.count(); i++) {
			String duplicate=addID(methodIDs,methods.get(i),did);
			if (duplicate!=null) return "Duplicate verification method id "+duplicate;
		}
		for (AString relationship:RELATIONSHIPS) {
			AVector<ACell> entries=items(document.get(relationship));
			for (long i=0; i<entries.count(); i++) {
				ACell entry=entries.get(i);
				if (!(entry instanceof AMap)) continue;
				String duplicate=addID(methodIDs,entry,did);
				if (duplicate!=null) return "Duplicate verification method id "+duplicate;
			}
		}
		Set<String> serviceIDs=new HashSet<>();
		AVector<ACell> services=items(document.get(SERVICE));
		for (long i=0; i<services.count(); i++) {
			String duplicate=addID(serviceIDs,services.get(i),did);
			if (duplicate!=null) return "Duplicate service id "+duplicate;
		}
		return null;
	}

	/**
	 * The Ed25519 keys a document lists for authentication, the relationship
	 * Controlled Identifiers defines for challenge-response. A key counts if its
	 * verification method is embedded in {@code authentication} or referenced from it
	 * by an id in this document, its {@code controller} is the document's DID, and its
	 * type is {@code Multikey}, {@code Ed25519VerificationKey2020}, {@code JsonWebKey} or
	 * {@code JsonWebKey2020}. References to other documents are not followed, and other
	 * methods are ignored.
	 *
	 * @param document DID document that passes {@link #problem}
	 * @return keys in document order, without repeats; empty if none count
	 */
	public static AVector<AccountKey> authenticationKeys(AMap<AString,ACell> document) {
		AVector<AccountKey> keys=Vectors.empty();
		AString did=RT.ensureString(document.get(ID));
		if (did==null) return keys;
		Map<String,AMap<AString,ACell>> methods=new HashMap<>();
		AVector<ACell> declared=items(document.get(VERIFICATION_METHOD));
		for (long i=0; i<declared.count(); i++) {
			AMap<AString,ACell> method=RT.ensureMap(declared.get(i));
			String id=(method==null)?null:absolute(RT.ensureString(method.get(ID)),did);
			if (id!=null) methods.put(id,method);
		}
		AVector<ACell> entries=items(document.get(AUTHENTICATION));
		for (long i=0; i<entries.count(); i++) {
			ACell entry=entries.get(i);
			AMap<AString,ACell> method=RT.ensureMap(entry);
			if (method==null) {
				String reference=absolute(RT.ensureString(entry),did);
				if (reference==null || !reference.startsWith(did+"#")) continue;
				method=methods.get(reference);
			}
			if (method==null || !did.equals(method.get(CONTROLLER))) continue;
			AccountKey key=ed25519Key(method);
			if (key!=null && !keys.contains(key)) keys=keys.conj(key);
		}
		return keys;
	}

	/**
	 * The endpoints of a document's services of a given type, in document order. A
	 * service's {@code type} may be a string or a set of strings; its
	 * {@code serviceEndpoint} may be a URL, a map or a set of either, and maps are
	 * skipped.
	 *
	 * @param document DID document that passes {@link #problem}
	 * @param type service type to select
	 * @return endpoint URLs as written; empty if there are none
	 */
	public static AVector<AString> serviceEndpoints(AMap<AString,ACell> document,AString type) {
		AVector<AString> endpoints=Vectors.empty();
		AVector<ACell> services=items(document.get(SERVICE));
		for (long i=0; i<services.count(); i++) {
			AMap<AString,ACell> service=RT.ensureMap(services.get(i));
			if (service==null || !items(service.get(TYPE)).contains(type)) continue;
			AVector<ACell> values=items(service.get(SERVICE_ENDPOINT));
			for (long j=0; j<values.count(); j++) {
				AString endpoint=RT.ensureString(values.get(j));
				if (endpoint!=null) endpoints=endpoints.conj(endpoint);
			}
		}
		return endpoints;
	}

	/** The Ed25519 key of a verification method of a supported type, or null. */
	static AccountKey ed25519Key(AMap<AString,ACell> method) {
		AString type=RT.ensureString(method.get(TYPE));
		if (type==null) return null;
		try {
			if (MULTIBASE_TYPES.contains(type.toString())) {
				AString multibase=RT.ensureString(method.get(PUBLIC_KEY_MULTIBASE));
				return (multibase==null)?null:Multikey.decodePublicKey(multibase.toString());
			}
			if (JWK_TYPES.contains(type.toString())) {
				AMap<AString,ACell> jwk=RT.ensureMap(method.get(PUBLIC_KEY_JWK));
				// A public key JWK must not carry private key material
				if (jwk==null || jwk.containsKey(D)) return null;
				if (!OKP.equals(jwk.get(KTY)) || !ED25519.equals(jwk.get(CRV))) return null;
				AString x=RT.ensureString(jwk.get(X));
				if (x==null) return null;
				byte[] bytes=Base64.getUrlDecoder().decode(x.toString());
				return (bytes.length==AccountKey.LENGTH)?AccountKey.wrap(bytes):null;
			}
		} catch (IllegalArgumentException e) {
			// Malformed key material: the method does not count
		}
		return null;
	}

	/** A value that DID Core allows as one item or a set of items, as a vector. */
	private static AVector<ACell> items(ACell value) {
		if (value==null) return Vectors.empty();
		AVector<ACell> vector=RT.ensureVector(value);
		return (vector!=null)?vector:Vectors.of(value);
	}

	/** Adds an entry's absolute id to {@code ids}, returning the id if it was already there. */
	private static String addID(Set<String> ids,ACell entry,AString did) {
		AMap<AString,ACell> map=RT.ensureMap(entry);
		String id=(map==null)?null:absolute(RT.ensureString(map.get(ID)),did);
		if (id==null) return null;
		return ids.add(id)?null:id;
	}

	/** Resolves an id relative to the document, as DID Core allows for a fragment. */
	private static String absolute(AString id,AString did) {
		if (id==null) return null;
		String text=id.toString();
		return text.startsWith("#")?did+text:text;
	}
}
