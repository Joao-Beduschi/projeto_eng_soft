import java.io.BufferedReader;
import java.io.Console;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive Java client for the Spring Boot cloud-storage API.
 * Run with: java -cp backend/target/cli DocsClient
 */
public final class DocsClient {
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern CSRF_TOKEN =
            Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final String DEFAULT_API_URL = "http://localhost:8080";

    private final URI apiBase;
    private final HttpClient http;
    private final BufferedReader input;
    private final Console console;
    private String csrfToken;

    private DocsClient(String baseUrl) {
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.apiBase = URI.create(normalized);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.console = System.console();
    }

    public static void main(String[] args) {
        String baseUrl = args.length > 0
                ? args[0]
                : System.getenv().getOrDefault("CLOUD_STORAGE_API_URL", DEFAULT_API_URL);
        try {
            new DocsClient(baseUrl).run();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("Client stopped: " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid API URL: " + exception.getMessage());
        }
    }

    private void run() throws IOException, InterruptedException {
        System.out.println("Cloud storage backend: " + apiBase);
        while (true) {
            if (!authenticate()) {
                return;
            }
            if (fileMenu()) {
                return;
            }
        }
    }

    private boolean authenticate() throws IOException, InterruptedException {
        while (true) {
            System.out.println();
            System.out.println("1) Log in");
            System.out.println("2) Create account");
            System.out.println("3) Exit");
            String choice = prompt("Choose an option: ").strip();
            if (choice.equals("3")) {
                return false;
            }
            if (!choice.equals("1") && !choice.equals("2")) {
                System.out.println("Choose 1, 2, or 3.");
                continue;
            }

            String email = prompt("Email: ").strip();
            if (choice.equals("2") && !isValidEmailShape(email)) {
                System.out.println("Enter a valid email address to create an account.");
                continue;
            }
            char[] passwordCharacters = readPassword("Password: ");
            if (choice.equals("2")
                    && (passwordCharacters.length < 12 || passwordCharacters.length > 128)) {
                Arrays.fill(passwordCharacters, '\0');
                System.out.println("Account passwords must be 12 to 128 characters long.");
                continue;
            }
            String password = new String(passwordCharacters);
            Arrays.fill(passwordCharacters, '\0');

            csrfToken = fetchCsrfToken();
            if (choice.equals("2")) {
                HttpResponse<String> registration = sendJson(
                        "POST",
                        "/api/auth/register",
                        "{\"email\":\"" + jsonEscape(email)
                                + "\",\"password\":\"" + jsonEscape(password) + "\"}",
                        csrfToken);
                if (registration.statusCode() != 201) {
                    printFailure("Account creation", registration);
                    if (registration.statusCode() == 400) {
                        System.err.println("Check that the email is valid and the password is 12 to 128 characters.");
                    }
                    continue;
                }
                System.out.println("Account created.");
                csrfToken = fetchCsrfToken();
            }

            HttpResponse<String> login = sendJson(
                    "POST",
                    "/api/auth/login",
                    "{\"email\":\"" + jsonEscape(email)
                            + "\",\"password\":\"" + jsonEscape(password) + "\"}",
                    csrfToken);
            if (login.statusCode() != 200) {
                printFailure("Login", login);
                continue;
            }
            csrfToken = fetchCsrfToken();
            System.out.println("Logged in.");
            return true;
        }
    }

    private boolean fileMenu() throws IOException, InterruptedException {
        while (true) {
            System.out.println();
            System.out.println("1) Upload file");
            System.out.println("2) List files in a folder");
            System.out.println("3) Download file");
            System.out.println("4) Rename file");
            System.out.println("5) Move file");
            System.out.println("6) Delete file");
            System.out.println("7) Create folder");
            System.out.println("8) List folders");
            System.out.println("9) Show folder contents");
            System.out.println("10) Rename folder");
            System.out.println("11) Move folder");
            System.out.println("12) Delete folder");
            System.out.println("13) Log out");
            System.out.println("14) Exit");
            String choice = prompt("Choose an option: ").strip();
            switch (choice) {
                case "1" -> uploadFile();
                case "2" -> listFiles();
                case "3" -> downloadFile();
                case "4" -> renameFile();
                case "5" -> moveFile();
                case "6" -> deleteFile();
                case "7" -> createFolder();
                case "8" -> listFolders();
                case "9" -> showFolderContents();
                case "10" -> renameFolder();
                case "11" -> moveFolder();
                case "12" -> deleteFolder();
                case "13" -> {
                    logout();
                    return false;
                }
                case "14" -> {
                    logout();
                    return true;
                }
                default -> System.out.println("Choose a number from 1 to 14.");
            }
        }
    }

    private String fetchCsrfToken() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("/api/auth/csrf"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            printFailure("Fetching CSRF token", response);
            throw new IOException("The backend did not return a CSRF token.");
        }
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("The backend returned an unrecognized CSRF response.");
        }
        return matcher.group(1);
    }

    private HttpResponse<String> sendJson(String method, String path, String body, String csrf)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(apiBase.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (csrf != null) {
            request.header("X-CSRF-TOKEN", csrf);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return http.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void uploadFile() throws IOException, InterruptedException {
        String source = prompt("Path to the file: ");
        Path file = Path.of(source).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            System.out.println("That path is not a regular file.");
            return;
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            System.out.println("The backend upload limit is 100 MiB.");
            return;
        }
        String folderId = promptUuidOrRoot("Folder ID (blank for root): ");
        if (folderId == null) {
            return;
        }

        String originalName = file.getFileName().toString();
        String fallbackName = originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (fallbackName.isBlank()) {
            fallbackName = "upload";
        }
        String encodedName = URLEncoder.encode(originalName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String boundary = "----DocsClient" + UUID.randomUUID().toString().replace("-", "");
        String contentType = Files.probeContentType(file);
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fallbackName
                + "\"; filename*=UTF-8''" + encodedName + "\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String folderPart = folderId.isEmpty() ? "" : "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"folderId\"\r\n\r\n"
                + folderId;
        String suffix = folderPart + "\r\n--" + boundary + "--\r\n";
        HttpRequest.BodyPublisher multipart = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofByteArray(suffix.getBytes(StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("/api/files"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart)
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201) {
            printFailure("Upload", response);
            return;
        }
        System.out.println("Upload complete:");
        System.out.println(response.body());
    }

    private void listFiles() throws IOException, InterruptedException {
        String folderId = promptUuidOrRoot("Folder ID (blank for root): ");
        if (folderId == null) {
            return;
        }
        String path = folderId.isEmpty()
                ? "/api/files"
                : "/api/files?folderId=" + URLEncoder.encode(folderId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            printFailure("Listing files", response);
            return;
        }
        System.out.println("Files:");
        System.out.println(response.body());
    }

    private void downloadFile() throws IOException, InterruptedException {
        String id = prompt("File ID: ").strip();
        if (!isUuid(id)) {
            System.out.println("Enter a valid file UUID.");
            return;
        }
        Path destination = Path.of(prompt("Save to path: ")).toAbsolutePath().normalize();
        if (Files.exists(destination) && !confirm("That path exists. Overwrite it? (y/N): ")) {
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve("/api/files/" + id + "/download"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                printFailure("Download", response.statusCode(),
                        new String(body.readAllBytes(), StandardCharsets.UTF_8));
                return;
            }
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Downloaded to " + destination);
    }

    private void renameFile() throws IOException, InterruptedException {
        String id = prompt("File ID: ").strip();
        if (!isUuid(id)) {
            System.out.println("Enter a valid file UUID.");
            return;
        }
        String newName = prompt("New name: ");
        if (newName.isBlank()) {
            System.out.println("The new name cannot be blank.");
            return;
        }
        HttpResponse<String> response = sendJson(
                "PATCH",
                "/api/files/" + id,
                "{\"name\":\"" + jsonEscape(newName) + "\"}",
                csrfToken);
        if (response.statusCode() != 200) {
            printFailure("Rename", response);
            return;
        }
        System.out.println("File renamed:");
        System.out.println(response.body());
    }

    private void moveFile() throws IOException, InterruptedException {
        String id = promptRequiredUuid("File ID: ");
        if (id == null) {
            return;
        }
        String folderId = promptUuidOrRoot("Destination folder ID (blank for root): ");
        if (folderId == null) {
            return;
        }
        String folderJson = folderId.isEmpty() ? "null" : "\"" + folderId + "\"";
        HttpResponse<String> response = sendJson(
                "PATCH", "/api/files/" + id, "{\"folderId\":" + folderJson + "}", csrfToken);
        if (response.statusCode() != 200) {
            printFailure("Move file", response);
            return;
        }
        System.out.println("File moved:");
        System.out.println(response.body());
    }

    private void createFolder() throws IOException, InterruptedException {
        String name = prompt("Folder name: ");
        if (name.isBlank()) {
            System.out.println("The folder name cannot be blank.");
            return;
        }
        String parentId = promptUuidOrRoot("Parent folder ID (blank for root): ");
        if (parentId == null) {
            return;
        }
        String parentJson = parentId.isEmpty() ? "null" : "\"" + parentId + "\"";
        HttpResponse<String> response = sendJson(
                "POST", "/api/folders",
                "{\"name\":\"" + jsonEscape(name) + "\",\"parentId\":" + parentJson + "}",
                csrfToken);
        if (response.statusCode() != 201) {
            printFailure("Create folder", response);
            return;
        }
        System.out.println("Folder created:");
        System.out.println(response.body());
    }

    private void listFolders() throws IOException, InterruptedException {
        String parentId = promptUuidOrRoot("Parent folder ID (blank for root): ");
        if (parentId == null) {
            return;
        }
        String path = parentId.isEmpty()
                ? "/api/folders"
                : "/api/folders?parentId=" + URLEncoder.encode(parentId, StandardCharsets.UTF_8);
        HttpResponse<String> response = sendJson("GET", path, null, null);
        if (response.statusCode() != 200) {
            printFailure("List folders", response);
            return;
        }
        System.out.println("Folders:");
        System.out.println(response.body());
    }

    private void showFolderContents() throws IOException, InterruptedException {
        String id = promptRequiredUuid("Folder ID: ");
        if (id == null) {
            return;
        }
        HttpResponse<String> response = sendJson(
                "GET", "/api/folders/" + id + "/contents", null, null);
        if (response.statusCode() != 200) {
            printFailure("Folder contents", response);
            return;
        }
        System.out.println("Folder contents:");
        System.out.println(response.body());
    }

    private void renameFolder() throws IOException, InterruptedException {
        String id = promptRequiredUuid("Folder ID: ");
        if (id == null) {
            return;
        }
        String name = prompt("New folder name: ");
        if (name.isBlank()) {
            System.out.println("The folder name cannot be blank.");
            return;
        }
        HttpResponse<String> response = sendJson(
                "PATCH", "/api/folders/" + id,
                "{\"name\":\"" + jsonEscape(name) + "\"}", csrfToken);
        if (response.statusCode() != 200) {
            printFailure("Rename folder", response);
            return;
        }
        System.out.println("Folder renamed:");
        System.out.println(response.body());
    }

    private void moveFolder() throws IOException, InterruptedException {
        String id = promptRequiredUuid("Folder ID: ");
        if (id == null) {
            return;
        }
        String parentId = promptUuidOrRoot("New parent folder ID (blank for root): ");
        if (parentId == null) {
            return;
        }
        String parentJson = parentId.isEmpty() ? "null" : "\"" + parentId + "\"";
        HttpResponse<String> response = sendJson(
                "PATCH", "/api/folders/" + id,
                "{\"parentId\":" + parentJson + "}", csrfToken);
        if (response.statusCode() != 200) {
            printFailure("Move folder", response);
            return;
        }
        System.out.println("Folder moved:");
        System.out.println(response.body());
    }

    private void deleteFolder() throws IOException, InterruptedException {
        String id = promptRequiredUuid("Folder ID: ");
        if (id == null || !confirm("Delete this empty folder? (y/N): ")) {
            return;
        }
        HttpResponse<String> response = sendJson(
                "DELETE", "/api/folders/" + id, null, csrfToken);
        if (response.statusCode() != 204) {
            printFailure("Delete folder", response);
            return;
        }
        System.out.println("Folder deleted.");
    }

    private void deleteFile() throws IOException, InterruptedException {
        String id = prompt("File ID: ").strip();
        if (!isUuid(id)) {
            System.out.println("Enter a valid file UUID.");
            return;
        }
        if (!confirm("Delete this file permanently? (y/N): ")) {
            return;
        }
        HttpResponse<String> response = sendJson(
                "DELETE", "/api/files/" + id, null, csrfToken);
        if (response.statusCode() != 204) {
            printFailure("Delete", response);
            return;
        }
        System.out.println("File deleted.");
    }

    private void logout() throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson(
                "POST", "/api/auth/logout", null, csrfToken);
        if (response.statusCode() != 204) {
            printFailure("Logout", response);
            return;
        }
        System.out.println("Logged out.");
    }

    private String promptRequiredUuid(String question) throws IOException {
        while (true) {
            String value = prompt(question).strip();
            if (isUuid(value)) {
                return value;
            }
            System.out.println("Enter a valid UUID.");
        }
    }

    private String promptUuidOrRoot(String question) throws IOException {
        while (true) {
            String value = prompt(question).strip();
            if (value.isEmpty() || isUuid(value)) {
                return value;
            }
            System.out.println("Enter a valid UUID or leave it blank for the root folder.");
        }
    }

    private boolean confirm(String question) throws IOException {
        String answer = prompt(question).strip();
        return answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    private String prompt(String question) throws IOException {
        String result;
        if (console != null) {
            result = console.readLine("%s", question);
        } else {
            System.out.print(question);
            System.out.flush();
            result = input.readLine();
        }
        if (result == null) {
            throw new EOFException("Input ended.");
        }
        return result;
    }

    private char[] readPassword(String question) throws IOException {
        if (console != null) {
            char[] result = console.readPassword("%s", question);
            if (result == null) {
                throw new EOFException("Input ended.");
            }
            return result;
        }
        return prompt(question + "(input may be visible): ").toCharArray();
    }

    private static void printFailure(String action, HttpResponse<String> response) {
        printFailure(action, response.statusCode(), response.body());
    }

    private static void printFailure(String action, int status, String body) {
        System.err.println(action + " failed (HTTP " + status + ").");
        if (body != null && !body.isBlank()) {
            System.err.println(body);
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isValidEmailShape(String email) {
        if (email.isBlank() || email.length() > 320 || email.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int separator = email.indexOf('@');
        return separator > 0
                && separator < email.length() - 1
                && email.indexOf('@', separator + 1) < 0;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
