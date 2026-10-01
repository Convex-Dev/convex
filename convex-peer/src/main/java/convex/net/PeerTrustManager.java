package convex.net;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.exceptions.BadFormatException;

/** Trusts a TLS server certificate issued directly by one expected Convex peer. */
final class PeerTrustManager implements X509TrustManager {

	private final PublicKey peerKey;

	PeerTrustManager(AccountKey expectedPeer) throws BadFormatException {
		peerKey=AKeyPair.publicKeyFromBytes(expectedPeer.getBytes());
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		if (chain==null || chain.length==0 || chain[0]==null || authType==null || authType.isEmpty()) {
			throw new CertificateException("Missing TLS server certificate or authentication type");
		}
		X509Certificate leaf=chain[0];
		boolean[] usage=leaf.getKeyUsage();
		if (usage!=null && (usage.length==0 || !usage[0])) {
			throw new CertificateException("TLS certificate does not permit digital signatures");
		}
		List<String> purposes=leaf.getExtendedKeyUsage();
		if (purposes!=null && !purposes.contains("1.3.6.1.5.5.7.3.1") && !purposes.contains("2.5.29.37.0")) {
			throw new CertificateException("TLS certificate does not permit server authentication");
		}
		try {
			// The issuer name is descriptive; the preconfigured peer key is the authority.
			TrustAnchor anchor=new TrustAnchor(leaf.getIssuerX500Principal(),peerKey,null);
			PKIXParameters parameters=new PKIXParameters(Set.of(anchor));
			// Peer-key trust has no CA revocation service. Expiry and signature are checked.
			parameters.setRevocationEnabled(false);
			var path=CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf));
			CertPathValidator.getInstance("PKIX").validate(path,parameters);
		} catch (GeneralSecurityException e) {
			throw new CertificateException("TLS certificate is not valid for the expected peer key",e);
		}
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		throw new CertificateException("Peer trust manager is for outbound TLS connections");
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return new X509Certificate[0];
	}
}
