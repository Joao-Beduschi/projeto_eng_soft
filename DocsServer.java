import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts the Spring Boot cloud-storage backend for the root-level Java client.
 * Run from the project root with: java -cp backend/target/cli DocsServer
 */
public final class DocsServer {
    private DocsServer() {
    }

    public static void main(String[] args) {
        try {
            runBackend();
        } catch (Exception exception) {
            System.err.println("Could not start the cloud-storage backend: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void runBackend() throws IOException, InterruptedException {
        Path projectRoot = findProjectRoot();
        Path backendDirectory = projectRoot.resolve("backend");
        Map<String, String> environment = new HashMap<>(System.getenv());
        loadEnvironmentFile(projectRoot.resolve(".env"), environment);

        String port = environment.getOrDefault("SERVER_PORT", "8080");
        String apiUrl = environment.getOrDefault("CLOUD_STORAGE_API_URL", "http://localhost:" + port);
        if (isBackendAvailable(apiUrl)) {
            System.out.println("Cloud-storage backend is already available at " + apiUrl);
            return;
        }
        if (environment.getOrDefault("DB_PASSWORD", "").isBlank()) {
            throw new IllegalStateException("DB_PASSWORD is missing. Configure it in the project .env file.");
        }

        Path localMaven = backendDirectory.resolve(".tools/apache-maven-3.10.0/bin/mvn");
        List<String> command = new ArrayList<>();
        command.add(Files.isExecutable(localMaven) ? localMaven.toString() : "mvn");
        command.add("-Dmaven.repo.local=.local/m2");
        command.add("spring-boot:run");

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(backendDirectory.toFile())
                .inheritIO();
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        Thread shutdownHook = new Thread(() -> {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }, "stop-cloud-storage-backend");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("Starting cloud-storage backend at " + apiUrl + " (Ctrl+C to stop).");
        int exitCode = process.waitFor();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down.
        }
        if (exitCode != 0) {
            throw new IllegalStateException("Maven exited with status " + exitCode + ".");
        }
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("DocsClient.java"))
                    && Files.isRegularFile(candidate.resolve("backend/pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Run DocsServer from the project root or one of its subdirectories.");
    }

    private static boolean isBackendAvailable(String apiUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(apiUrl) + "/api/auth/csrf"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void loadEnvironmentFile(Path file, Map<String, String> environment) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            environment.putIfAbsent(key, value);
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
