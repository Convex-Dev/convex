package convex.db.psql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.CertUtils;
import convex.core.crypto.TLS;
import convex.db.ConvexDB;
import convex.db.lattice.RowLWW;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * TLS on the PostgreSQL wire server (#756, CAD055), using the PostgreSQL JDBC
 * driver as the client and a self-signed service identity for "localhost".
 */
public class PgTLSTest {

	private static final int SSL_REQUEST = 80877103;
	private static final int GSSENC_REQUEST = 80877104;

	/** pgjdbc's sslmode=require without certificate checks */
	private static final String NON_VALIDATING = "org.postgresql.ssl.NonValidatingFactory";

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);

	private static SSLContext serverContext;
	private static Path rootCertificate;

	private ConvexDB cdb;
	private String dbName;
	private PgServer server;

	@BeforeAll
	public static void createIdentity(@TempDir Path dir) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair key = generator.generateKeyPair();
		X509Certificate certificate = CertUtils.selfSign(key, "CN=localhost");
		serverContext = TLS.serverContext(key.getPrivate(), certificate);
		rootCertificate = dir.resolve("root.crt");
		Files.write(rootCertificate, certificate.getEncoded());
	}

	@BeforeEach
	public void setUp() {
		dbName = "pgtls_" + DB_COUNTER.getAndIncrement();
		cdb = ConvexDB.create(RowLWW::merge);
		cdb.database(dbName);
		cdb.register(dbName);
	}

	@AfterEach
	public void tearDown() {
		if (server != null) server.stop();
		cdb.unregister(dbName);
	}

	private void start(SSLContext context, boolean allowPlaintext) throws InterruptedException {
		server = PgServer.builder()
			.port(0)
			.database(dbName)
			.sslContext(context)
			.allowPlaintext(allowPlaintext)
			.build();
		server.start();
	}

	private Connection connect(String sslmode, String... options) throws SQLException {
		Properties props = new Properties();
		props.setProperty("user", "testuser");
		props.setProperty("sslmode", sslmode);
		for (int i = 0; i < options.length; i += 2) {
			props.setProperty(options[i], options[i + 1]);
		}
		return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.getPort() + "/" + dbName, props);
	}

	private static void assertQueries(Connection conn) throws SQLException {
		try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt(1));
		}
	}

	@Test
	public void testRequireConnectsOverTLS() throws Exception {
		start(serverContext, false);
		try (Connection conn = connect("require", "sslfactory", NON_VALIDATING)) {
			assertQueries(conn);
		}
	}

	@Test
	public void testVerifyFullChecksServiceIdentity() throws Exception {
		start(serverContext, false);
		try (Connection conn = connect("verify-full", "sslrootcert", rootCertificate.toString())) {
			assertQueries(conn);
		}
	}

	@Test
	public void testPlaintextRefusedWhenTLSOffered() throws Exception {
		start(serverContext, false);
		SQLException e = assertThrows(SQLException.class, () -> connect("disable"));
		assertEquals("28000", e.getSQLState());
	}

	@Test
	public void testPlaintextAdmittedWhenAllowed() throws Exception {
		start(serverContext, true);
		try (Connection conn = connect("disable")) {
			assertQueries(conn);
		}
		try (Connection conn = connect("require", "sslfactory", NON_VALIDATING)) {
			assertQueries(conn);
		}
	}

	@Test
	public void testWithoutTLS() throws Exception {
		start(null, false);
		try (Connection conn = connect("prefer")) {
			assertQueries(conn);
		}
		assertThrows(SQLException.class, () -> connect("require", "sslfactory", NON_VALIDATING));
	}

	/** Bytes received with an SSLRequest arrived unprotected, so the connection closes before TLS. */
	@Test
	public void testDataAfterSSLRequestRefused() {
		AtomicInteger connections = new AtomicInteger();
		EmbeddedChannel channel = new EmbeddedChannel(new PgMessageDecoder(true),
			new PgProtocolHandler(() -> {
				connections.incrementAndGet();
				return null;
			}, null, serverContext, true));
		ByteBuf bytes = Unpooled.buffer();
		bytes.writeInt(8).writeInt(SSL_REQUEST);
		writeStartup(bytes);
		channel.writeInbound(bytes);

		assertFalse(channel.isOpen(), "Connection should close");
		assertNull(channel.readOutbound(), "No reply should be sent");
		assertEquals(0, connections.get(), "Injected startup must not be processed");
	}

	/** Without GSSAPI encryption, GSSENCRequest is declined and the client continues in plaintext. */
	@Test
	public void testGSSENCRequestDeclined() {
		EmbeddedChannel channel = new EmbeddedChannel(new PgMessageDecoder(),
			new PgProtocolHandler(() -> null, null));
		ByteBuf request = Unpooled.buffer();
		request.writeInt(8).writeInt(GSSENC_REQUEST);
		channel.writeInbound(request);
		ByteBuf reply = channel.readOutbound();
		assertEquals('N', reply.readByte());
		reply.release();

		ByteBuf startup = Unpooled.buffer();
		writeStartup(startup);
		channel.writeInbound(startup);
		ByteBuf auth = channel.readOutbound();
		assertEquals('R', auth.readByte());
		auth.readInt();
		assertEquals(0, auth.readInt(), "AuthenticationOk");
		auth.release();
		channel.finishAndReleaseAll();
	}

	/** Writes a protocol 3.0 startup message for user "testuser". */
	private static void writeStartup(ByteBuf buf) {
		byte[] params = "user\0testuser\0\0".getBytes(StandardCharsets.UTF_8);
		buf.writeInt(8 + params.length).writeInt(3 << 16).writeBytes(params);
	}
}
