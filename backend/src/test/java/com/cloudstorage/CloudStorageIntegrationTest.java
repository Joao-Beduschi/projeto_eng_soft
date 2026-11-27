package com.cloudstorage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudstorage.controller.mvc.HomeController;
import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.dto.FileVersionHistoryResponse;
import com.cloudstorage.dto.FileVersionResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FileRecord;
import com.cloudstorage.model.FileVersionRecord;
import com.cloudstorage.model.UserAccount;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.repository.FileRepository;
import com.cloudstorage.repository.FileVersionRepository;
import com.cloudstorage.repository.FolderRepository;
import com.cloudstorage.repository.UserRepository;
import com.cloudstorage.service.AuthService;
import com.cloudstorage.service.FileService;
import com.cloudstorage.service.FileReconciliationService;
import com.cloudstorage.service.FileVersionService;
import com.cloudstorage.service.FolderService;
import com.cloudstorage.service.StorageService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
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
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
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
    @Autowired private FileVersionService fileVersionService;
    @Autowired private FolderService folderService;
    @Autowired private FileRepository fileRepository;
    @Autowired private FileVersionRepository versionRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FileReconciliationService reconciliation;
    @Autowired private StorageService storageService;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private WebApplicationContext webContext;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void clearTestData() throws IOException {
        jdbc.update("UPDATE files SET status = 'deleting', current_version_id = NULL");
        jdbc.update("DELETE FROM file_versions");
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
    void fileHistoryKeepsImmutableBytesAndRestoreCreatesANewVersion() throws Exception {
        UserAccount alice = user("version-owner@example.com");
        byte[] v1Bytes = new byte[] {0, 1, 2, (byte) 0xff, 4};
        byte[] v2Bytes = new byte[] {5, 6, 7, 8};
        byte[] v3Bytes = new byte[] {9, 10, 0, 11, 12, 13};
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "history.bin", "application/octet-stream", v1Bytes));

        FileVersionHistoryResponse v1History = fileVersionService.list(file.id(), alice.id());
        assertEquals(1, v1History.currentVersionNumber());
        assertEquals(1, v1History.versions().size());
        assertTrue(v1History.versions().getFirst().current());
        assertEquals(sha256(v1Bytes), v1History.versions().getFirst().sha256());

        FileVersionResponse v2 = fileVersionService.upload(file.id(), alice.id(), v1History.currentEtag(),
                new MockMultipartFile("file", "history.bin", "application/octet-stream", v2Bytes), "second revision");
        FileVersionHistoryResponse v2History = fileVersionService.list(file.id(), alice.id());
        FileVersionResponse v3 = fileVersionService.upload(file.id(), alice.id(), v2History.currentEtag(),
                new MockMultipartFile("file", "history.bin", "application/octet-stream", v3Bytes), "third revision");

        assertEquals(2, v2.versionNumber());
        assertEquals(sha256(v2Bytes), v2.sha256());
        assertEquals(3, v3.versionNumber());
        assertEquals(sha256(v3Bytes), v3.sha256());
        assertNotEquals(v2.id(), v3.id());
        assertEquals(3, fileVersionService.list(file.id(), alice.id()).versions().size());
        assertArrayEquals(v1Bytes, downloadVersion(file.id(), 1, alice.id()));
        assertArrayEquals(v2Bytes, downloadVersion(file.id(), 2, alice.id()));
        assertArrayEquals(v3Bytes, downloadVersion(file.id(), 3, alice.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE file_versions SET change_message = 'rewritten'
                WHERE file_id = ? AND version_number = 1
                """, file.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                DELETE FROM file_versions WHERE file_id = ? AND version_number = 1
                """, file.id()));
        assertEquals(3, fileVersionService.list(file.id(), alice.id()).versions().size());

        FileVersionResponse v4 = fileVersionService.restore(file.id(), 1, alice.id(),
                fileVersionService.list(file.id(), alice.id()).currentEtag(), null);
        FileVersionHistoryResponse restoredHistory = fileVersionService.list(file.id(), alice.id());
        assertEquals(4, v4.versionNumber());
        assertEquals(sha256(v1Bytes), v4.sha256());
        assertEquals(4, restoredHistory.currentVersionNumber());
        assertEquals(4, restoredHistory.versions().size());
        assertEquals(v4.id(), restoredHistory.versions().getLast().id());
        assertTrue(restoredHistory.versions().getLast().current());
        assertArrayEquals(v1Bytes, downloadVersion(file.id(), 1, alice.id()));
        assertArrayEquals(v1Bytes, downloadVersion(file.id(), 4, alice.id()));
    }

    @Test
    void versionUpdatesRejectStaleTagsAndConcurrentReservations() throws Exception {
        UserAccount alice = user("version-concurrency@example.com");
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "concurrent.bin", "application/octet-stream", new byte[] {1}));
        String firstTag = fileVersionService.list(file.id(), alice.id()).currentEtag();
        FileVersionResponse second = fileVersionService.upload(file.id(), alice.id(), firstTag,
                new MockMultipartFile("file", "concurrent.bin", "application/octet-stream", new byte[] {2}), null);

        ApiException stale = assertThrows(ApiException.class, () -> fileVersionService.upload(file.id(), alice.id(),
                firstTag, new MockMultipartFile("file", "concurrent.bin", "application/octet-stream",
                        new byte[] {3}), null));
        assertEquals(412, stale.status().value());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        BlockingMultipartFile blockedUpload = new BlockingMultipartFile(new byte[] {4, 5, 6});
        try {
            Future<FileVersionResponse> firstConcurrent = executor.submit(() -> fileVersionService.upload(
                    file.id(), alice.id(), fileVersionService.list(file.id(), alice.id()).currentEtag(),
                    blockedUpload, "reserved first"));
            assertTrue(blockedUpload.readStarted.await(5, TimeUnit.SECONDS));

            ApiException duplicate = assertThrows(ApiException.class, () -> fileVersionService.upload(
                    file.id(), alice.id(), fileVersionService.list(file.id(), alice.id()).currentEtag(),
                    new MockMultipartFile("file", "concurrent.bin", "application/octet-stream", new byte[] {7}),
                    "duplicate while first upload is pending"));
            assertEquals(409, duplicate.status().value());
            blockedUpload.releaseRead.countDown();
            assertEquals(3, firstConcurrent.get(10, TimeUnit.SECONDS).versionNumber());
        } finally {
            blockedUpload.releaseRead.countDown();
            executor.shutdownNow();
        }

        FileVersionHistoryResponse history = fileVersionService.list(file.id(), alice.id());
        assertEquals(3, history.currentVersionNumber());
        assertEquals(3, history.versions().size());
        assertEquals(sha256(new byte[] {2}), second.sha256());
    }

    @Test
    void failedRevisionUploadLeavesCurrentHistoryAndObjectsIntact() throws Exception {
        UserAccount alice = user("version-failure@example.com");
        byte[] original = new byte[] {1, 2, 3, 4};
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "stable.bin", "application/octet-stream", original));
        String etag = fileVersionService.list(file.id(), alice.id()).currentEtag();

        ApiException failure = assertThrows(ApiException.class, () -> fileVersionService.upload(file.id(), alice.id(),
                etag, new TruncatedMultipartFile(), "incomplete replacement"));

        assertEquals(400, failure.status().value());
        FileVersionHistoryResponse history = fileVersionService.list(file.id(), alice.id());
        assertEquals(1, history.currentVersionNumber());
        assertEquals(1, history.versions().size());
        assertArrayEquals(original, downloadVersion(file.id(), 1, alice.id()));
        assertEquals(1, storageKeys().size());
    }

    @Test
    void wholeFileDeletionRemovesItsHistoryThroughTheExistingLifecycle() throws Exception {
        UserAccount alice = user("version-delete@example.com");
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "delete.bin", "application/octet-stream", new byte[] {1}));
        String currentEtag = fileVersionService.list(file.id(), alice.id()).currentEtag();
        fileVersionService.upload(file.id(), alice.id(), currentEtag,
                new MockMultipartFile("file", "delete.bin", "application/octet-stream", new byte[] {2}), null);
        assertEquals(2, storageKeys().size());

        fileService.delete(file.id(), alice.id());

        assertTrue(fileService.list(alice.id(), null).isEmpty());
        assertEquals(0, storageKeys().size());
        assertEquals(404, assertThrows(ApiException.class,
                () -> fileVersionService.list(file.id(), alice.id())).status().value());
    }

    @Test
    void versionHistoryAndDownloadsAreOwnerScopedAndRequireAuthentication() throws Exception {
        UserAccount alice = user("history-private@example.com");
        UserAccount bob = user("history-other@example.com");
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "private.bin", "application/octet-stream", new byte[] {1, 2}));

        assertEquals(404, assertThrows(ApiException.class,
                () -> fileVersionService.list(file.id(), bob.id())).status().value());
        assertEquals(404, assertThrows(ApiException.class,
                () -> fileVersionService.openDownload(file.id(), 1, bob.id())).status().value());
        assertEquals(404, assertThrows(ApiException.class,
                () -> fileVersionService.restore(file.id(), 1, bob.id(),
                        FileVersionService.etag(file.id(), 1), null)).status().value());

        HttpClient anonymous = HttpClient.newHttpClient();
        HttpResponse<String> response = send(anonymous,
                request("/api/files/" + file.id() + "/versions", "GET", null, null));
        assertEquals(401, response.statusCode());
    }

    @Test
    void versionHttpEndpointsUseIfMatchAndReturnHistoricalBytes() throws Exception {
        UserAccount alice = user("version-api@example.com");
        byte[] original = new byte[] {1, 2, 3};
        byte[] replacement = new byte[] {4, 5, 0, 6};
        FileResponse file = fileService.upload(alice.id(), null,
                new MockMultipartFile("file", "api.bin", "application/octet-stream", original));
        AuthenticatedHttpClient authenticated = loginClient(alice.email(), "a sufficiently long test password");

        HttpResponse<String> listed = send(authenticated.client(), request(
                "/api/files/" + file.id() + "/versions", "GET", null, null));
        assertEquals(200, listed.statusCode());
        String etag = listed.headers().firstValue("ETag").orElseThrow();
        assertTrue(listed.body().contains("\"currentVersionNumber\":1"));

        HttpResponse<String> uploaded = authenticated.client().send(
                multipartRequest("/api/files/" + file.id() + "/versions", etag,
                        authenticated.csrfToken(), replacement), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode());
        assertEquals(FileVersionService.etag(file.id(), 2), uploaded.headers().firstValue("ETag").orElseThrow());

        HttpResponse<byte[]> oldBytes = authenticated.client().send(HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/files/" + file.id() + "/versions/1/download"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, oldBytes.statusCode());
        assertArrayEquals(original, oldBytes.body());

        HttpRequest restore = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/files/" + file.id()
                        + "/versions/1/restore"))
                .header("X-CSRF-TOKEN", authenticated.csrfToken())
                .header("If-Match", FileVersionService.etag(file.id(), 2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"changeMessage\":\"restore v1\"}"))
                .build();
        HttpResponse<String> restored = authenticated.client().send(restore, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, restored.statusCode());
        assertTrue(restored.body().contains("\"versionNumber\":3"));
        assertEquals(FileVersionService.etag(file.id(), 3), restored.headers().firstValue("ETag").orElseThrow());
        HttpResponse<byte[]> restoredBytes = authenticated.client().send(HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/files/" + file.id() + "/versions/3/download"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, restoredBytes.statusCode());
        assertArrayEquals(original, restoredBytes.body());
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

    @Test
    void homeResolvesThymeleafAndRendersOwnerDataBeforeJavaScriptRuns() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        String anonymous = mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(handler().handlerType(HomeController.class))
                .andExpect(model().attributeDoesNotExist("currentUser"))
                .andExpect(view().name("index")).andReturn().getResponse().getContentAsString();
        assertTrue(anonymous.contains("action=\"/login\""));
        assertFalse(anonymous.contains("id=\"app-view\""));

        UserAccount alice = user("mvc@example.com");
        UserAccount bob = user("other-mvc@example.com");
        FolderResponse folder = folderService.create(alice.id(), null, "Design folder");
        FolderResponse child = folderService.create(alice.id(), folder.id(), "Nested folder");
        fileService.upload(bob.id(), null, new MockMultipartFile("file", "private-bob.bin", null, new byte[]{8}));
        fileService.upload(alice.id(), null, new MockMultipartFile("file", "owner-root.bin", null, new byte[]{1}));
        byte[] bytes = {1, 2, 3};
        FileResponse file = fileService.upload(alice.id(), folder.id(),
                new MockMultipartFile("file", "<img onerror=alert(1)>.png", "image/png", bytes));

        UserPrincipal principal = new UserPrincipal(alice.id(), alice.email(), alice.passwordHash());
        var owner = SecurityMockMvcRequestPostProcessors.user(principal);
        String root = mvc.perform(get("/").with(owner)).andExpect(status().isOk())
                .andExpect(model().attribute("currentUser", authService.accountDetails(alice.id())))
                .andExpect(view().name("index")).andReturn().getResponse().getContentAsString();
        assertTrue(root.contains("mvc@example.com"));
        assertTrue(root.contains("owner-root.bin"));
        assertTrue(root.contains("/folders/" + folder.id()));
        assertFalse(root.contains("private-bob.bin"));
        assertFalse(root.contains(alice.passwordHash()));

        String nested = mvc.perform(get("/folders/{id}", folder.id()).with(owner))
                .andExpect(status().isOk()).andExpect(view().name("index"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(nested.contains("Design folder"));
        assertTrue(nested.contains("Nested folder"));
        assertTrue(nested.contains("/folders/" + child.id()));
        assertTrue(nested.contains("/api/files/" + file.id() + "/download"));
        assertTrue(nested.contains("&lt;img onerror=alert(1)&gt;.png"));
        assertFalse(nested.contains("<img onerror=alert(1)>"));
        assertEquals(sha256(bytes), fileService.get(file.id(), alice.id()).sha256());
        assertArrayEquals(bytes, download(file.id(), alice.id()));

        mvc.perform(get("/files")).andExpect(status().isUnauthorized());
        mvc.perform(get("/folders/{id}", folder.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/folders/{id}", folder.id()).with(SecurityMockMvcRequestPostProcessors
                .user(new UserPrincipal(bob.id(), bob.email(), bob.passwordHash()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void renderedAuthFormsRegisterLoginAndLogoutWithSessionCsrf() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String credentials = "email=browser%40example.com&password="
                + URLEncoder.encode("a sufficiently long test password", StandardCharsets.UTF_8);
        var registration = send(client, request("/register", "GET", null, null));
        assertEquals(200, registration.statusCode());
        assertTrue(registration.body().contains("action=\"/register\""));
        assertEquals(403, send(client, formRequest("/register", credentials)).statusCode());
        assertEquals(403, send(client, formRequest("/register", credentials + "&_csrf=invalid")).statusCode());
        String token = formToken(registration.body());
        var bad = send(client, formRequest("/register", "email=invalid&password=short&_csrf=" + token));
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("Check the email address"));
        var registered = send(client, formRequest("/register", credentials + "&_csrf=" + token));
        assertEquals(302, registered.statusCode());
        assertTrue(registered.headers().firstValue("Location").orElseThrow().endsWith("/files"));

        var workspace = send(client, request("/files", "GET", null, null));
        assertEquals(200, workspace.statusCode());
        assertTrue(workspace.body().contains("browser@example.com"));
        assertTrue(workspace.body().contains("Your account is ready."));
        assertEquals(403, send(client, formRequest("/logout", "_csrf=" + token)).statusCode());
        assertEquals(403, send(client, formRequest("/logout", "")).statusCode());
        assertEquals(302, send(client, formRequest("/logout", "_csrf=" + formToken(workspace.body()))).statusCode());
        assertEquals(401, send(client, request("/files", "GET", null, null)).statusCode());

        var login = send(client, request("/login", "GET", null, null));
        assertEquals(200, login.statusCode());
        assertTrue(login.body().contains("action=\"/login\""));
        assertEquals(401, send(client, formRequest("/login",
                "email=browser%40example.com&password=wrong&_csrf=" + formToken(login.body()))).statusCode());
        assertEquals(302, send(client, formRequest("/login", credentials + "&_csrf=" + formToken(login.body())))
                .statusCode());
        assertEquals(200, send(client, request("/api/users/me", "GET", null, null)).statusCode());
    }

    private HttpRequest formRequest(String path, String body) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String formToken(String html) {
        Matcher matcher = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "Thymeleaf POST form should include a CSRF input");
        return matcher.group(1);
    }

    @Test
    void folderContentsAndPatchKeepOwnershipAndRootMoveSemantics() throws Exception {
        UserAccount alice = user("owner@example.com");
        UserAccount bob = user("other@example.com");
        FolderResponse parent = folderService.create(alice.id(), null, "parent");
        FolderResponse child = folderService.create(alice.id(), parent.id(), "child");
        FileResponse file = fileService.upload(alice.id(), parent.id(),
                new MockMultipartFile("file", "a.bin", "application/octet-stream", new byte[]{1, 2}));
        var owner = loginClient(alice.email(), "a sufficiently long test password");
        var other = loginClient(bob.email(), "a sufficiently long test password");
        String contents = "/api/folders/" + parent.id() + "/contents";
        HttpResponse<String> listing = send(owner.client(), request(contents, "GET", null, null));
        assertEquals(200, listing.statusCode());
        assertTrue(listing.body().contains(child.id().toString()));
        assertTrue(listing.body().contains(file.id().toString()));
        assertEquals(404, send(other.client(), request(contents, "GET", null, null)).statusCode());

        String path = "/api/files/" + file.id();
        assertEquals(400, send(owner.client(), request(path, "PATCH", owner.csrfToken(), "{\"ownerId\":null}")).statusCode());
        assertEquals(200, send(owner.client(), request(path, "PATCH", owner.csrfToken(), "{\"name\":\"b.bin\"}")).statusCode());
        assertEquals(parent.id(), fileService.get(file.id(), alice.id()).folderId());
        assertEquals(200, send(owner.client(), request(path, "PATCH", owner.csrfToken(), "{\"folderId\":null}")).statusCode());
        assertTrue(fileService.list(alice.id(), null).stream().anyMatch(row -> row.id().equals(file.id())));
    }

    @Test
    void jpaPreservesTimestampsRelationshipsAndOwnerScopedWrites() {
        UserAccount owner = user("persistence@example.com");
        UserAccount other = user("other-persistence@example.com");
        assertEquals(owner, userRepository.findAccount(owner.id()).orElseThrow());
        assertEquals(owner.createdAt(), jdbc.queryForObject("select created_at from users where id = ?",
                OffsetDateTime.class, owner.id()));

        FolderResponse root = folderService.create(owner.id(), null, "Root");
        FolderResponse child = folderService.create(owner.id(), root.id(), "Child");
        assertEquals(List.of(root), folderService.list(owner.id(), null));
        assertEquals(List.of(child), folderService.list(owner.id(), root.id()));
        assertEquals(root.id(), folderRepository.findOwned(child.id(), owner.id()).orElseThrow().parentId());
        assertThrows(DataIntegrityViolationException.class,
                () -> folderRepository.create(UUID.randomUUID(), other.id(), root.id(), "Cross owner"));

        FileResponse created = fileService.upload(owner.id(), null,
                new MockMultipartFile("file", "mapping.bin", null, new byte[]{1, 2, 3}));
        assertEquals(List.of(created), fileService.list(owner.id(), null));
        FileRecord ready = fileRepository.findReadyOwned(created.id(), owner.id()).orElseThrow();
        assertEquals("ready", ready.status());
        assertEquals(created.createdAt(), ready.createdAt());
        assertEquals(ready.currentVersionId(),
                versionRepository.findCurrentReadyOwned(ready.id(), owner.id()).orElseThrow().id());
        assertEquals(0, fileRepository.renameReady(ready.id(), other.id(), "Unauthorized"));
        assertEquals(0, fileRepository.moveReady(ready.id(), other.id(), null));
        assertTrue(fileRepository.markDeleting(ready.id(), other.id()).isEmpty());
        assertEquals(0, folderRepository.rename(root.id(), other.id(), "Unauthorized"));
        assertEquals(0, folderRepository.move(root.id(), other.id(), null));
        assertEquals(0, folderRepository.deleteIfEmpty(child.id(), other.id()));

        FileResponse moved = fileService.patch(ready.id(), owner.id(), true, "moved.bin", true, child.id());
        assertEquals("moved.bin", moved.name());
        assertEquals(created.createdAt(), moved.createdAt());
        assertFalse(moved.updatedAt().isBefore(created.updatedAt()));
        assertTrue(fileService.list(owner.id(), null).isEmpty());
        assertEquals(List.of(moved), fileService.list(owner.id(), child.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> fileRepository.moveReady(ready.id(), owner.id(),
                        folderService.create(other.id(), null, "Other root").id()));
        assertEquals(child.id(), fileService.get(ready.id(), owner.id()).folderId());

        ApiException cycle = assertThrows(ApiException.class,
                () -> folderService.patch(root.id(), owner.id(), true, "Do not keep", true, child.id()));
        assertEquals("folder_cycle", cycle.code());
        assertEquals(root, folderService.get(root.id(), owner.id()));

        FolderResponse renamed = folderService.patch(child.id(), owner.id(), true, "Renamed", true, null);
        assertEquals("Renamed", renamed.name());
        assertEquals(child.createdAt(), renamed.createdAt());
        assertNull(renamed.parentId());
        assertEquals(409, assertThrows(ApiException.class, () -> folderService.delete(child.id(), owner.id()))
                .status().value());
    }

    @Test
    void startupReconciliationRemovesInterruptedRowsAndPreservesReadyFiles() throws Exception {
        UserAccount owner = user("reconcile@example.com");
        byte[] bytes = {1, 3, 5};
        FileResponse keep = fileService.upload(owner.id(), null,
                new MockMultipartFile("file", "keep.bin", null, bytes));
        FileResponse removed = fileService.upload(owner.id(), null,
                new MockMultipartFile("file", "deleting.bin", null, bytes));
        FileRecord deleting = fileRepository.markDeleting(removed.id(), owner.id()).orElseThrow();
        assertEquals("deleting", deleting.status());
        assertNull(deleting.currentVersionId());

        UUID abandonedId = UUID.randomUUID();
        UUID abandonedKey = UUID.randomUUID();
        fileRepository.createPending(new FileRecord(abandonedId, owner.id(), null, "abandoned.bin",
                abandonedKey, 0, "application/octet-stream", null, "pending", null, null, null));
        jdbc.update("update files set created_at = now() - interval '2 hours' where id = ?", abandonedId);
        UUID pendingRevision = UUID.randomUUID();
        UUID pendingKey = UUID.randomUUID();
        versionRepository.createPending(new FileVersionRecord(pendingRevision, keep.id(), 2, owner.id(),
                null, pendingKey, 0, null, null, "pending"));
        jdbc.update("update file_versions set created_at = now() - interval '2 hours' where id = ?", pendingRevision);
        Files.write(STORAGE_ROOT.resolve(abandonedKey.toString()), bytes);
        Files.write(STORAGE_ROOT.resolve(pendingKey.toString()), bytes);

        assertEquals(2, fileRepository.findNonReady().size());
        assertEquals(List.of(keep), fileService.list(owner.id(), null));
        reconciliation.run(null);

        assertTrue(fileRepository.findNonReady().isEmpty());
        assertFalse(versionRepository.existsById(pendingRevision));
        assertEquals(1, storageKeys().size());
        assertEquals(List.of(keep), fileService.list(owner.id(), null));
        assertEquals(sha256(bytes), fileService.get(keep.id(), owner.id()).sha256());
        assertArrayEquals(bytes, download(keep.id(), owner.id()));
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

    private byte[] downloadVersion(UUID fileId, int versionNumber, UUID ownerId) throws IOException {
        FileVersionService.VersionDownload download = fileVersionService.openDownload(fileId, versionNumber, ownerId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        fileVersionService.writeDownload(download, output);
        return output.toByteArray();
    }

    private java.util.Set<UUID> storageKeys() throws IOException {
        return storageService.listStoredKeys();
    }

    private HttpRequest request(String path, String method, String csrfToken, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path));
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

    private AuthenticatedHttpClient loginClient(String email, String password) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> csrf = send(client, request("/api/auth/csrf", "GET", null, null));
        String token = csrfToken(csrf.body());
        HttpResponse<String> login = send(client, request("/api/auth/login", "POST", token,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, login.statusCode());
        HttpResponse<String> authenticatedCsrf = send(client, request("/api/auth/csrf", "GET", null, null));
        return new AuthenticatedHttpClient(client, csrfToken(authenticatedCsrf.body()));
    }

    private String csrfToken(String body) {
        Matcher matcher = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private HttpRequest multipartRequest(String path, String etag, String csrfToken, byte[] fileContent) {
        String boundary = "CloudStorageBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"revision.bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(fileContent);
            body.write(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"changeMessage\"\r\n\r\nAPI test\r\n"
                    + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("If-Match", etag)
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private String baseUrl() {
        int port = environment.getProperty("local.server.port", Integer.class);
        return "http://localhost:" + port;
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

    private static final class BlockingMultipartFile implements MultipartFile {
        private final byte[] content;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        private BlockingMultipartFile(byte[] content) {
            this.content = content;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "concurrent.bin"; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() {
            return new FilterInputStream(new ByteArrayInputStream(content)) {
                private boolean delayed;

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (!delayed) {
                        delayed = true;
                        readStarted.countDown();
                        try {
                            if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting for competing revision request");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for competing revision request", exception);
                        }
                    }
                    return super.read(bytes, offset, length);
                }
            };
        }
        @Override public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }

    private record AuthenticatedHttpClient(HttpClient client, String csrfToken) {
    }
}
