package convex.core.crypto;


import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;
import java.util.Date;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import convex.core.crypto.bc.BCProvider;


public class CertUtils {

	
	// See: https://stackoverflow.com/questions/29852290/self-signed-x509-certificate-with-bouncy-castle-in-java
	
	public static X509Certificate selfSign(KeyPair keyPair, String subjectDN) throws OperatorCreationException, CertificateException, IOException
	{
	    BouncyCastleProvider bcProvider = BCProvider.BC;

	    long now = System.currentTimeMillis();
	    Date startDate = new Date(now);

	    // <-- Using the current timestamp as the certificate serial number
	    X500Name dnName = new X500Name(subjectDN);
	    BigInteger certSerialNumber = new BigInteger(Long.toString(now));

	    Calendar calendar = Calendar.getInstance();
	    calendar.setTime(startDate);
	    calendar.add(Calendar.YEAR, 1); // <-- 1 Yr validity

	    Date endDate = calendar.getTime();

	    String signatureAlgorithm = "SHA256WithRSA"; // <-- Use appropriate signature algorithm based on your keyPair algorithm.

	    ContentSigner contentSigner = new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());

	    JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());

	    // Extensions --------------------------

	    // Basic Constraints
	    BasicConstraints basicConstraints = new BasicConstraints(true); // <-- true for CA, false for EndEntity

	    certBuilder.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, basicConstraints); // Basic Constraints is usually marked as critical.

	    // -------------------------------------

	    return new JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner));
	}
	
	public static KeyPair generateRSAKeyPair() throws GeneralSecurityException {
		KeyPairGenerator  kpGen = KeyPairGenerator.getInstance("RSA", "BC");
		kpGen.initialize(new RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4));
		return kpGen.generateKeyPair();
	}
	
	public static void createCertificateFiles(String subjectDN, Path path) throws GeneralSecurityException, IOException {
		KeyPair kp=generateRSAKeyPair();
		X509Certificate cert;
		try {
			cert = selfSign(kp,subjectDN);
		} catch (OperatorCreationException e) {
			throw new GeneralSecurityException("Failed to self sign certificate",e);
		}
		
		Path keyPath=path.resolve("private.pem");
		Path certPath=path.resolve("certificate.pem");
		
		writePemFile(kp.getPrivate().getEncoded(), "PRIVATE KEY", keyPath);
		writePemFile(cert.getEncoded(), "CERTIFICATE", certPath);
	}
	
	private static void writePemFile(byte[] content, String type, Path file)
		      throws IOException {
		try (PemWriter writer = new PemWriter(new FileWriter(file.toFile()))) {
			PemObject obj=new PemObject(type,content);
			writer.writeObject(obj);
		}
	}
	
	public static void main(String... args) throws OperatorCreationException, GeneralSecurityException, IOException {
		Providers.init();
		createCertificateFiles("CN=localhost, O=o, L=L, ST=il, C=c",Path.of("."));
	}
	
}
