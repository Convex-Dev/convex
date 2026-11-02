package convex.auth.did;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/**
 * The result of resolving a DID, as the {@code resolve} function of W3C DID Resolution
 * returns it: resolution metadata, the DID document and document metadata. Each is a
 * map keyed by its JSON property names.
 *
 * <p>A failed resolution has no document and an {@code error} in its resolution
 * metadata: an RFC 9457 problem object whose {@code type} is one of the DID Resolution
 * error URLs, with an optional {@code detail}. A deactivated DID has no document and
 * {@code deactivated: true} in its document metadata.</p>
 *
 * @see <a href="https://www.w3.org/TR/did-resolution/">W3C DID Resolution</a>
 */
public final class DIDResolution {

	static final AString ERROR=Strings.intern("error");
	static final AString TYPE=Strings.intern("type");
	static final AString DETAIL=Strings.intern("detail");
	static final AString DEACTIVATED=Strings.intern("deactivated");

	/** The DID is not a conformant DID, or not one its method defines. */
	public static final AString INVALID_DID=Strings.intern("https://www.w3.org/ns/did#INVALID_DID");
	/** The document obtained is not a conformant DID document for the DID. */
	public static final AString INVALID_DID_DOCUMENT=Strings.intern("https://www.w3.org/ns/did#INVALID_DID_DOCUMENT");
	/** No document exists for the DID. */
	public static final AString NOT_FOUND=Strings.intern("https://www.w3.org/ns/did#NOT_FOUND");
	/** No resolver handles the DID's method. */
	public static final AString METHOD_NOT_SUPPORTED=Strings.intern("https://www.w3.org/ns/did#METHOD_NOT_SUPPORTED");
	/** Resolution failed for a reason other than the DID or its document. */
	public static final AString INTERNAL_ERROR=Strings.intern("https://www.w3.org/ns/did#INTERNAL_ERROR");

	private static final AMap<AString,ACell> NONE=Maps.empty();

	private final AMap<AString,ACell> resolutionMetadata;
	private final AMap<AString,ACell> document;
	private final AMap<AString,ACell> documentMetadata;

	private DIDResolution(AMap<AString,ACell> resolutionMetadata,AMap<AString,ACell> document,
			AMap<AString,ACell> documentMetadata) {
		this.resolutionMetadata=resolutionMetadata;
		this.document=document;
		this.documentMetadata=documentMetadata;
	}

	/**
	 * A successful resolution.
	 *
	 * @param document the DID document
	 * @param documentMetadata document metadata, or null for none
	 * @return resolution holding the document
	 */
	public static DIDResolution of(AMap<AString,ACell> document,AMap<AString,ACell> documentMetadata) {
		if (document==null) throw new IllegalArgumentException("A resolved DID needs a document");
		return new DIDResolution(NONE,document,(documentMetadata==null)?NONE:documentMetadata);
	}

	/**
	 * A deactivated DID: no document, and {@code deactivated: true} in the document metadata.
	 *
	 * @param documentMetadata other document metadata, or null for none
	 * @return resolution of a deactivated DID
	 */
	public static DIDResolution deactivated(AMap<AString,ACell> documentMetadata) {
		AMap<AString,ACell> metadata=(documentMetadata==null)?NONE:documentMetadata;
		return new DIDResolution(NONE,null,metadata.assoc(DEACTIVATED,CVMBool.TRUE));
	}

	/**
	 * A failed resolution.
	 *
	 * @param type one of the DID Resolution error URLs, such as {@link #NOT_FOUND}
	 * @param detail human-readable explanation, or null
	 * @return resolution carrying the error
	 */
	public static DIDResolution error(AString type,String detail) {
		if (type==null) throw new IllegalArgumentException("An error needs a type");
		AMap<AString,ACell> problem=NONE.assoc(TYPE,type);
		if (detail!=null) problem=problem.assoc(DETAIL,Strings.create(detail));
		return new DIDResolution(NONE.assoc(ERROR,problem),null,NONE);
	}

	/** @return the resolution metadata, never null */
	public AMap<AString,ACell> getResolutionMetadata() {
		return resolutionMetadata;
	}

	/** @return the DID document, or null if resolution failed or the DID is deactivated */
	public AMap<AString,ACell> getDocument() {
		return document;
	}

	/** @return the document metadata, never null */
	public AMap<AString,ACell> getDocumentMetadata() {
		return documentMetadata;
	}

	/** @return the error type URL, or null if resolution did not fail */
	public AString getErrorType() {
		AMap<AString,ACell> problem=RT.ensureMap(resolutionMetadata.get(ERROR));
		return (problem==null)?null:RT.ensureString(problem.get(TYPE));
	}

	/** @return the error detail, or null if there is none */
	public String getErrorDetail() {
		AMap<AString,ACell> problem=RT.ensureMap(resolutionMetadata.get(ERROR));
		AString detail=(problem==null)?null:RT.ensureString(problem.get(DETAIL));
		return (detail==null)?null:detail.toString();
	}

	/** @return true if the document metadata says the DID is deactivated */
	public boolean isDeactivated() {
		return CVMBool.TRUE.equals(documentMetadata.get(DEACTIVATED));
	}

	@Override
	public String toString() {
		AString error=getErrorType();
		if (error!=null) {
			String detail=getErrorDetail();
			return (detail==null)?error.toString():error+": "+detail;
		}
		return isDeactivated()?"deactivated":"resolved";
	}
}
