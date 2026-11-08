import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Signs the Maven Central bundle the release workflow built for a version and
 * publishes it, so Central receives exactly the files CI built and tested while
 * the signing key stays on the maintainer's machine.
 *
 * <p>Before signing, it checks the bundle's attestation shows it was built by the
 * release workflow from the commit the release tag names, and that every file in
 * it belongs to that version.
 *
 * <p>Needs {@code gh}, {@code gpg} with the signing key, and Central credentials
 * in {@code ~/.m2/settings.xml} (server id {@code central}).
 *
 * <p>Usage: {@code java scripts/PublishToCentral.java <version>}
 */
public class PublishToCentral {
	private static final String REPO = "Convex-Dev/convex";
	private static final String WORKFLOW = REPO + "/.github/workflows/release.yml";
	private static final String CENTRAL = "https://central.sonatype.com/api/v1/publisher";
	private static final Pattern CHECKSUM = Pattern.compile(".*\\.(md5|sha1|sha256|sha512)");
	private static final Pattern STATE = Pattern.compile("\"deploymentState\"\\s*:\\s*\"(\\w+)\"");
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
	private static final Duration PUBLISH_TIMEOUT = Duration.ofMinutes(30);

	private final String version;
	private final Path work;
	private final HttpClient http = HttpClient.newHttpClient();

	private PublishToCentral(String version, Path work) {
		this.version = version;
		this.work = work;
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1 || !args[0].matches("\\d+\\.\\d+\\.\\d+")) {
			System.err.println("usage: java scripts/PublishToCentral.java <version>");
			System.exit(2);
		}
		Path work = Files.createTempDirectory("convex-central");
		try {
			new PublishToCentral(args[0], work).publish();
		} catch (IllegalStateException e) {
			System.err.println("PublishToCentral: " + e.getMessage());
			System.exit(1);
		} finally {
			try (Stream<Path> files = Files.walk(work)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
		}
	}

	private void publish() throws Exception {
		Path bundle = fetchVerifiedBundle();
		System.out.println("Bundle verified against tag " + version + ". Signing.");
		Path signed = sign(bundle);
		String token = centralToken();
		String deployment = upload(signed, token);
		System.out.println("Uploaded as deployment " + deployment + ". Waiting for Central.");
		awaitPublished(deployment, token);
		System.out.println("Central has published " + version + ".");
	}

	/** Downloads the release's bundle and checks it was built from the tagged commit. */
	private Path fetchVerifiedBundle() throws Exception {
		String asset = "convex-" + version + "-central-bundle.zip";
		String commit = output("gh", "api", "repos/" + REPO + "/commits/refs/tags/" + version, "--jq", ".sha").trim();
		run("gh", "release", "download", version, "-R", REPO, "-p", asset, "-D", work.toString());
		Path bundle = work.resolve(asset);
		output("gh", "attestation", "verify", bundle.toString(), "-R", REPO,
				"--signer-workflow", WORKFLOW, "--source-digest", commit);
		return bundle;
	}

	/** Copies the bundle's files unchanged, adding a signature for each one that is not a checksum. */
	private Path sign(Path bundle) throws Exception {
		Pattern expected = Pattern.compile("world/convex/[^/]+/" + Pattern.quote(version) + "/[^/]+");
		Path signed = work.resolve("signed.zip");
		Path file = work.resolve("file");
		Path signature = work.resolve("file.asc");
		int count = 0;
		try (ZipFile in = new ZipFile(bundle.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(signed))) {
			for (ZipEntry entry : Collections.list(in.entries())) {
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				if (!expected.matcher(name).matches()) {
					throw new IllegalStateException("Unexpected file in the bundle: " + name + ". Nothing was published.");
				}
				byte[] data;
				try (InputStream stream = in.getInputStream(entry)) {
					data = stream.readAllBytes();
				}
				add(out, name, data);
				if (CHECKSUM.matcher(name).matches()) continue;
				Files.write(file, data);
				run("gpg", "--yes", "--armor", "--detach-sign", "--output", signature.toString(), file.toString());
				add(out, name + ".asc", Files.readAllBytes(signature));
				count++;
			}
		}
		if (count == 0) throw new IllegalStateException("The bundle has no files to sign. Nothing was published.");
		return signed;
	}

	private static void add(ZipOutputStream out, String name, byte[] data) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(data);
		out.closeEntry();
	}

	/** Central's bearer token, from the credentials the Maven plugin uses. */
	private static String centralToken() throws Exception {
		Path settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
		NodeList servers = DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(settings.toFile()).getElementsByTagName("server");
		for (int i = 0; i < servers.getLength(); i++) {
			Element server = (Element) servers.item(i);
			if (!"central".equals(text(server, "id"))) continue;
			String password = text(server, "password");
			if (password.startsWith("{")) {
				throw new IllegalStateException("The central password in settings.xml is encrypted, which this tool cannot read. Nothing was published.");
			}
			String credentials = text(server, "username") + ":" + password;
			return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		}
		throw new IllegalStateException("No server with id central in " + settings + ". Nothing was published.");
	}

	private static String text(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagName(tag);
		return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
	}

	private String upload(Path signed, String token) throws Exception {
		String boundary = "convex-" + UUID.randomUUID();
		String head = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n";
		String tail = "\r\n--" + boundary + "--\r\n";
		HttpRequest request = HttpRequest.newBuilder(URI.create(CENTRAL + "/upload?publishingType=AUTOMATIC&name=convex-" + version))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(BodyPublishers.concat(BodyPublishers.ofString(head), BodyPublishers.ofFile(signed), BodyPublishers.ofString(tail)))
				.build();
		HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
		if (response.statusCode() != 201) {
			throw new IllegalStateException("Central refused the upload (HTTP " + response.statusCode() + "): " + response.body());
		}
		return response.body().trim();
	}

	private void awaitPublished(String deployment, String token) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(CENTRAL + "/status?id=" + deployment))
				.header("Authorization", "Bearer " + token)
				.POST(BodyPublishers.noBody())
				.build();
		Instant deadline = Instant.now().plus(PUBLISH_TIMEOUT);
		String last = null;
		while (Instant.now().isBefore(deadline)) {
			HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
			Matcher state = STATE.matcher(response.body());
			if (response.statusCode() != 200 || !state.find()) {
				throw new IllegalStateException("Could not read the status of deployment " + deployment
						+ " (HTTP " + response.statusCode() + "). Check it at central.sonatype.com before running this again.");
			}
			String current = state.group(1);
			if (!current.equals(last)) System.out.println("  " + current);
			last = current;
			if (current.equals("PUBLISHED")) return;
			if (current.equals("FAILED")) {
				throw new IllegalStateException("Central rejected deployment " + deployment + ": " + response.body());
			}
			Thread.sleep(POLL_INTERVAL);
		}
		throw new IllegalStateException("Deployment " + deployment + " was still " + last
				+ " after " + PUBLISH_TIMEOUT.toMinutes() + " minutes. Check it at central.sonatype.com before running this again.");
	}

	private static void run(String... command) throws Exception {
		check(new ProcessBuilder(command).inheritIO().start(), command);
	}

	private static String output(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
		String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		check(process, command);
		return result;
	}

	private static void check(Process process, String... command) throws Exception {
		if (process.waitFor() != 0) {
			throw new IllegalStateException("Command failed: " + String.join(" ", command) + ". Nothing was published.");
		}
	}
}
