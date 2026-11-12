package com.cloudstorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FileRecord;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.model.UserPrincipal;
import com.cloudstorage.repository.FileRepository;
import com.cloudstorage.service.AuthService;
import com.cloudstorage.service.FileService;
import com.cloudstorage.service.FolderService;
import com.cloudstorage.service.StorageService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CloudStorageIntegrationTest {
    private static final Path STORAGE_ROOT = createTemporaryStorageRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cloud_storage_test")
            .withUsername("cloud_storage_test")
            .withPassword("integration-test-password");

    @Autowired private AuthService authService;
    @Autowired private FileService fileService;
    @Autowired private FolderService folderService;
    @Autowired private FileRepository fileRepository;
    @Autowired private StorageService storageService;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void clearTestData() throws IOException {
        jdbc.update("DELETE FROM files");
        jdbc.update("DELETE FROM folders");
        jdbc.update("DELETE FROM users");
        for (UUID key : storageService.listStoredKeys()) {
            storageService.delete(key);
        }
    }

    @Test
    void passwordsAreHashedAndAuthenticationIsCaseInsensitive() {
        UserAccount user = authService.register("Alice@example.com", "correct horse battery staple");

        assertNotEquals("correct horse battery staple", user.passwordHash());
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        "ALICE@EXAMPLE.COM", "correct horse battery staple"));
        assertTrue(authentication.getPrincipal() instanceof UserPrincipal);
        assertEquals(user.id(), ((UserPrincipal) authentication.getPrincipal()).id());
        assertThrows(BadCredentialsException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice@example.com", "wrong password")));
        assertThrows(ApiException.class,
                () -> authService.register("ALICE@example.com", "another correct password"));
    }

    @Test
    void sessionApiRequiresCsrfAndLogoutInvalidatesTheSession() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        HttpResponse<String> csrfResponse = send(client, request("/api/auth/csrf", "GET", null, null));
        assertEquals(200, csrfResponse.statusCode());
        Matcher tokenMatcher = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"")
                .matcher(csrfResponse.body());
        assertTrue(tokenMatcher.find());
        String csrfToken = tokenMatcher.group(1);

        HttpResponse<String> missingCsrf = send(client, request("/api/auth/register", "POST", null,
                "{\"email\":\"web@example.com\",\"password\":\"a sufficiently long password\"}"));
        assertEquals(403, missingCsrf.statusCode());

        HttpResponse<String> registration = send(client, request("/api/auth/register", "POST", csrfToken,
                "{\"email\":\"web@example.com\",\"password\":\"a sufficiently long password\"}"));
        assertEquals(201, registration.statusCode());
        assertEquals(401, send(client, request("/api/files", "GET", null, null)).statusCode());

        HttpResponse<String> login = send(client, request("/api/auth/login", "POST", csrfToken,
                "{\"email\":\"web@example.com\",\"password\":\"a sufficiently long password\"}"));
        assertEquals(200, login.statusCode());
        HttpResponse<String> authenticatedCsrf = send(client, request("/api/auth/csrf", "GET", null, null));
        Matcher authenticatedTokenMatcher = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"")
                .matcher(authenticatedCsrf.body());
        assertTrue(authenticatedTokenMatcher.find());
        String authenticatedToken = authenticatedTokenMatcher.group(1);
        assertEquals(200, send(client, request("/api/users/me", "GET", null, null)).statusCode());

        HttpResponse<String> logout = send(client, request("/api/auth/logout", "POST", authenticatedToken, ""));
        assertEquals(204, logout.statusCode());
        assertEquals(401, send(client, request("/api/users/me", "GET", null, null)).statusCode());
    }

    @Test
    void uploadPreservesBinaryBytesAndAnotherAccountCannotReadThem() throws Exception {
        UserAccount alice = user("alice@example.com");
        UserAccount bob = user("bob@example.com");
        byte[] original = new byte[] {0, 1, 2, (byte) 0xff, '\n', '\r', 0, (byte) 0x80};

        FileResponse uploaded = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "report.bin", "application/octet-stream", original));

        assertEquals("report.bin", uploaded.name());
        assertEquals(original.length, uploaded.sizeBytes());
        assertEquals(sha256(original), uploaded.sha256());
        assertArrayEquals(original, download(uploaded.id(), alice.id()));
        assertEquals(List.of(uploaded), fileService.list(alice.id(), null));
        assertTrue(fileService.list(bob.id(), null).isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> fileService.get(uploaded.id(), bob.id()))
                .status().value());
    }

    @Test
    void completedBytesRemainReadableAfterStorageServiceReinitialization() throws Exception {
        UserAccount alice = user("alice@example.com");
        byte[] original = new byte[4096];
        for (int index = 0; index < original.length; index++) {
            original[index] = (byte) (index * 31);
        }
        FileResponse uploaded = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "payload.dat", "application/octet-stream", original));
        FileRecord record = fileRepository.findReadyOwned(uploaded.id(), alice.id()).orElseThrow();

        StorageService restartedStorage = new StorageService(STORAGE_ROOT.toString());
        try (InputStream input = restartedStorage.retrieve(record.storageKey())) {
            assertArrayEquals(original, input.readAllBytes());
        }
    }

    @Test
    void incompleteUploadIsNotListedAndLeavesNoStoredObject() throws IOException {
        UserAccount alice = user("alice@example.com");

        ApiException failure = assertThrows(ApiException.class,
                () -> fileService.upload(alice.id(), null, new TruncatedMultipartFile()));

        assertEquals(400, failure.status().value());
        assertTrue(fileService.list(alice.id(), null).isEmpty());
        assertTrue(storageKeys().isEmpty());
    }

    @Test
    void filenamesCannotContainPathsAndRootFoldersAreOwnerScoped() {
        UserAccount alice = user("alice@example.com");
        UserAccount bob = user("bob@example.com");

        assertThrows(ApiException.class, () -> fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "../../server/config.properties", "text/plain", new byte[] {1})));

        FolderResponse aliceFolder = folderService.create(alice.id(), null, "private");
        assertThrows(ApiException.class, () -> fileService.upload(bob.id(), aliceFolder.id(),
                new MockMultipartFile("file", "secret.txt", "text/plain", new byte[] {1})));
        assertTrue(folderService.list(bob.id(), null).isEmpty());
    }

    @Test
    void folderMovesRejectCyclesAndNonemptyDeletion() {
        UserAccount alice = user("alice@example.com");
        FolderResponse parent = folderService.create(alice.id(), null, "parent");
        FolderResponse child = folderService.create(alice.id(), parent.id(), "child");

        ApiException cycle = assertThrows(ApiException.class,
                () -> folderService.patch(parent.id(), alice.id(), false, null, true, child.id()));
        assertEquals(409, cycle.status().value());

        ApiException nonempty = assertThrows(ApiException.class,
                () -> folderService.delete(parent.id(), alice.id()));
        assertEquals(409, nonempty.status().value());

        folderService.patch(child.id(), alice.id(), false, null, true, null);
        folderService.delete(parent.id(), alice.id());
        assertFalse(folderService.list(alice.id(), null).contains(parent));
    }

    @Test
    void sameNamedConcurrentUploadsReceiveDifferentIdsAndKeepTheirBytes() throws Exception {
        UserAccount alice = user("alice@example.com");
        byte[] firstBytes = new byte[] {1, 2, 3};
        byte[] secondBytes = new byte[] {4, 5, 6, 7};
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FileResponse> first = executor.submit(() -> fileService.upload(alice.id(), null,
                    new MockMultipartFile("file", "same-name.bin", "application/octet-stream", firstBytes)));
            Future<FileResponse> second = executor.submit(() -> fileService.upload(alice.id(), null,
                    new MockMultipartFile("file", "same-name.bin", "application/octet-stream", secondBytes)));
            FileResponse firstUploaded = first.get();
            FileResponse secondUploaded = second.get();

            assertNotEquals(firstUploaded.id(), secondUploaded.id());
            assertArrayEquals(firstBytes, download(firstUploaded.id(), alice.id()));
            assertArrayEquals(secondBytes, download(secondUploaded.id(), alice.id()));
        } finally {
            executor.shutdownNow();
        }
    }

    private UserAccount user(String email) {
        return authService.register(email, "a sufficiently long test password");
    }

    private byte[] download(UUID fileId, UUID ownerId) throws IOException {
        FileService.DownloadFile download = fileService.openDownload(fileId, ownerId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        fileService.writeDownload(download, output);
        return output.toByteArray();
    }

    private java.util.Set<UUID> storageKeys() throws IOException {
        return storageService.listStoredKeys();
    }

    private HttpRequest request(String path, String method, String csrfToken, String body) {
        int port = environment.getProperty("local.server.port", Integer.class);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path createTemporaryStorageRoot() {
        try {
            return Files.createTempDirectory("cloud-storage-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class TruncatedMultipartFile implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "truncated.bin"; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return 10; }
        @Override public byte[] getBytes() { return new byte[] {1, 2, 3}; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBytes()); }
        @Override public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), getBytes());
        }
    }
}
