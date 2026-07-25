package convex.restapi.auth;

import java.net.InetAddress;
import java.util.Set;
import java.util.function.Supplier;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.peer.Server;
import convex.peer.auth.PeerAuth;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;

/**
 * Authorises security-sensitive venue administration requests.
 *
 * <p>Every request must carry a self-issued JWT targeted at the configured venue
 * DID. By default the actual signer must be either the venue's local operational
 * key or the registered Peer's controller account key in the current consensus
 * state. The latter is resolved from one immutable {@link Peer} snapshot for each
 * request, so controller rotation takes effect without caches or token tracking.</p>
 *
 * <p>Valid administrators may connect directly over HTTPS. Cleartext HTTP is
 * accepted only from the direct loopback interface. A TLS-terminating proxy may
 * report HTTPS with {@code X-Forwarded-Proto} only when its direct socket address
 * is loopback or explicitly trusted. Forwarded client-address headers are never
 * consulted.</p>
 */
public final class AdminAuthorizer {

	enum Decision { ALLOW, UNAUTHENTICATED, FORBIDDEN }

	private final Supplier<Peer> peerSupplier;
	private final PeerAuth tokenVerifier;
	private final Set<AString> configuredKeys;
	private final Set<String> trustedProxies;

	public AdminAuthorizer(Server server,AString venueDID,Set<AString> configuredKeys,
			Set<String> trustedProxies) {
		this(server::getPeer,server.getKeyPair(),venueDID,configuredKeys,trustedProxies);
	}

	AdminAuthorizer(Supplier<Peer> peerSupplier,AKeyPair venueKeyPair,AString venueDID,
			Set<AString> configuredKeys,Set<String> trustedProxies) {
		if (peerSupplier==null) throw new IllegalArgumentException("Peer supplier required");
		if (venueDID==null) throw new IllegalArgumentException("Venue DID required");
		this.peerSupplier=peerSupplier;
		this.tokenVerifier=new PeerAuth(venueKeyPair,venueDID);
		this.configuredKeys=configuredKeys;
		this.trustedProxies=(trustedProxies==null)?Set.of():Set.copyOf(trustedProxies);
	}

	/** Requires administrator authority or throws an HTTP 401/403 response. */
	public void require(Context ctx) {
		String remoteAddress=ctx.req().getRemoteAddr();
		Decision decision=check(bearerToken(ctx),remoteAddress,ctx.scheme(),
			ctx.header("X-Forwarded-Proto"),hasProxyHeaders(ctx));
		if (decision==Decision.UNAUTHENTICATED) {
			throw new UnauthorizedResponse("Valid venue administrator credentials required");
		}
		if (decision==Decision.FORBIDDEN) {
			throw new ForbiddenResponse("Venue administrator authority and secure transport required");
		}
	}

	Decision check(AString token,String remoteAddress,String scheme,String forwardedProto,
			boolean proxiedRequest) {
		AccountKey signer=tokenVerifier.verifySelfIssuedToken(token);
		if (signer==null) return Decision.UNAUTHENTICATED;
		if (!isAuthorised(signer)) return Decision.FORBIDDEN;
		if (!isTransportAllowed(remoteAddress,scheme,forwardedProto,proxiedRequest)) return Decision.FORBIDDEN;
		return Decision.ALLOW;
	}

	private boolean isAuthorised(AccountKey signer) {
		if (configuredKeys!=null) return configuredKeys.contains(DID.forKey(signer));
		return isDefaultAuthority(signer,peerSupplier.get());
	}

	/** Resolves the two default authorities entirely from one immutable Peer snapshot. */
	static boolean isDefaultAuthority(AccountKey signer,Peer peer) {
		if ((signer==null)||(peer==null)) return false;
		AccountKey peerKey=peer.getPeerKey();
		if (signer.equals(peerKey)) return true;

		State state=peer.getConsensusState();
		if (state==null) return false;
		PeerStatus status=state.getPeer(peerKey);
		if (status==null) return false;
		Address controller=status.getController();
		if (controller==null) return false;
		AccountStatus account=state.getAccount(controller);
		return (account!=null)&&signer.equals(account.getAccountKey());
	}

	boolean isTransportAllowed(String remoteAddress,String scheme,String forwardedProto,
			boolean proxiedRequest) {
		if ("https".equalsIgnoreCase(scheme)) return true;
		boolean loopback=isLoopback(remoteAddress);
		if (!proxiedRequest) return loopback;
		boolean trustedProxy=loopback||trustedProxies.contains(remoteAddress);
		return trustedProxy&&"https".equalsIgnoreCase(forwardedProto);
	}

	private static boolean isLoopback(String remoteAddress) {
		if ((remoteAddress==null)||remoteAddress.isBlank()) return false;
		try {
			return InetAddress.getByName(remoteAddress).isLoopbackAddress();
		} catch (Exception e) {
			return false;
		}
	}

	private static AString bearerToken(Context ctx) {
		String header=ctx.header("Authorization");
		if ((header==null)||!header.startsWith("Bearer ")) return null;
		String token=header.substring(7).trim();
		return token.isEmpty()?null:Strings.create(token);
	}

	/**
	 * Detects a request that has traversed a proxy. A loopback socket is only a
	 * direct-local HTTP exception when no forwarding metadata is present.
	 */
	private static boolean hasProxyHeaders(Context ctx) {
		return (ctx.header("Forwarded")!=null)||(ctx.header("X-Forwarded-For")!=null)
			||(ctx.header("X-Real-IP")!=null)||(ctx.header("X-Forwarded-Host")!=null)
			||(ctx.header("X-Forwarded-Port")!=null)||(ctx.header("X-Forwarded-Prefix")!=null)
			||(ctx.header("X-Forwarded-Proto")!=null);
	}
}
