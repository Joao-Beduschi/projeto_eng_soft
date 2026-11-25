# Cloud Storage Backend

Spring Boot HTTP API for private file storage. PostgreSQL stores users, folders, file metadata, status and checksums. File contents are streamed to a private local directory using server-generated UUID keys; client filenames are metadata only.

The root-level `DocsServer.java` and `DocsClient.java` are integrated with this backend. `DocsServer` launches the Spring Boot application; `DocsClient` is an interactive Java HTTP client for account login/registration and file/folder operations. The Java client relies on the backend for authentication, authorization, SQL metadata, and file storage; it does not maintain a separate TCP storage protocol.

## Assumptions recorded for this implementation

- Java 21, Spring Boot 4.1.2, PostgreSQL 15 or newer, JDBC with prepared statements, and Flyway migrations.
- New accounts can register themselves. Passwords are stored using Spring Security's adaptive PBKDF2 encoder.
- Authentication uses server-side HTTP sessions in application memory. Cookies are HttpOnly and SameSite=Lax by default; enable HTTPS and set `SESSION_COOKIE_SECURE=true` outside local development. Cross-site frontends may need `SESSION_COOKIE_SAME_SITE=none`, which requires secure cookies over HTTPS.
- Spring is configured to honor forwarded request headers from a reverse proxy. This makes it proxy-aware; it does not install or start Cloudflare Tunnel. `cloudflared` is a separate process/service on the server.
- State-changing requests require a CSRF token obtained from `GET /api/auth/csrf` and sent as `X-CSRF-TOKEN`. Fetch it again after logout/session renewal.
- Files are stored on persistent local disk outside the application resources/static directory. Default root is `../storage` relative to the backend working directory.
- Maximum file size is 100 MiB by default. Set `MAX_FILE_SIZE_BYTES`, `MAX_FILE_SIZE`, and `MAX_REQUEST_SIZE` together when changing the limit. Uploads also reserve against currently available disk space before streaming and handle disk-full errors without returning success.
- Any file content type is accepted. Downloads are attachments with `nosniff`; MIME type is guessed from the display name, is informational, and is never used to choose a filesystem path.
- Duplicate names are allowed within a folder. Each upload gets a separate ID.
- Deleting a nonempty folder returns `409 Conflict`. Move operations reject direct and indirect cycles.
- The browser UI and API use the same origin, so the normal browser interface does not need CORS. For a separately hosted frontend, set `CORS_ALLOWED_ORIGINS` to comma-separated exact origins; wildcard credentialed CORS is not enabled.
- File revision history is supported for uploaded files. Sharing, public links, previews, quotas and password recovery are out of scope.

## Run locally

Requirements: JDK 21, Maven 3.6.3+, Docker Compose, and a persistent filesystem with room for uploaded files.

```bash
export POSTGRES_PASSWORD='choose-a-local-development-password'
docker compose -f backend/compose.yaml up -d --wait
cd backend
export DB_PASSWORD="$POSTGRES_PASSWORD"
mvn spring-boot:run
```

Flyway applies the migrations in `src/main/resources/db/migration/` at startup. `V2__file_versions.sql` backfills every existing file as version 1 and keeps its existing storage key and bytes. Set `STORAGE_ROOT` to choose a different private data directory. Do not place it under a public/static web directory or commit its contents.

### Use the root Java entrypoints

Start the Compose database using the commands above. From the project root, compile and launch the Java server wrapper:

```bash
javac -encoding UTF-8 -d backend/target/cli DocsServer.java DocsClient.java
java -cp backend/target/cli DocsServer
```

`DocsServer` loads the ignored root `.env` file and runs Maven from `backend/` (using the project-local Maven installation when present). If the API is already answering on its configured port, it reports that and exits. In a second terminal, start the client:

```bash
java -cp backend/target/cli DocsClient
```

The client keeps the session cookie and CSRF token, prompts to log in or create an account, and then provides file upload/list/download/rename/move/delete and folder create/list/contents/rename/move/delete operations. Pass an API origin as the first argument to use a different host, for example `java -cp backend/target/cli DocsClient http://192.168.1.15:8080`.

### Use the browser interface

The Spring Boot application serves the web interface and API from the same origin, so browser sessions and CSRF protection work without a separate frontend server. Start PostgreSQL and the backend using the commands under [Run locally](#run-locally), or launch the root `DocsServer` entrypoint as described above. Then open:

```text
http://localhost:8080/
```

Create an account or sign in, then upload/download files, create and navigate folders, rename or move items, delete items, and search the current folder. Files can also be uploaded by dropping them into the workspace. New accounts require a syntactically valid email address and a password between 12 and 128 characters. The email check validates address format; it does not confirm that the mailbox exists. The browser interface uses the same 100 MiB per-file limit as the API.

### Publish the web interface with Cloudflare Tunnel

The merged application configuration is compatible with a reverse proxy: `server.forward-headers-strategy=framework` tells Spring to interpret forwarded host/protocol information from the proxy. It does **not** connect to Cloudflare by itself. The Cloudflare Tunnel connector, `cloudflared`, must run separately on the Ubuntu server and forward traffic to the local backend.

```text
User browser --HTTPS--> Cloudflare --encrypted tunnel--> cloudflared
                                                   --> http://localhost:8080
                                                   --> Spring Boot UI and API
```

The current `backend/compose.yaml` starts PostgreSQL only; the backend still runs on the host through Maven/`DocsServer`. Start the backend and confirm `http://localhost:8080/` works before configuring a tunnel. The following commands run on the same server as the backend.

#### Temporary development URL

Install `cloudflared` using [Cloudflare's Linux installation instructions](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/), then run:

```bash
cloudflared tunnel --url http://localhost:8080
```

Cloudflare prints a temporary `https://<random>.trycloudflare.com` URL. It works only while this command is running, and the URL changes when a new Quick Tunnel is started. Anyone with the URL can reach the application unless you add an access restriction. Quick Tunnels are for testing and development, not a permanent deployment. [Cloudflare Quick Tunnel documentation](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

#### Persistent hostname

For regular use, have a domain managed by Cloudflare and create a **named/remote-managed tunnel** in the Cloudflare dashboard. Create a Cloudflare Access self-hosted application and an allow policy for the intended people **before** publishing the hostname. A tunnel route without Access can make the app reachable to anyone on the internet; Access adds a Cloudflare identity check before requests reach the app. If available in the tunnel settings, enable **Protect with Access** so `cloudflared` also validates the Access token at the origin. [Cloudflare's tunnel setup guide](https://developers.cloudflare.com/tunnel/get-started/) and [self-hosted application Access guide](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/) describe these steps.

1. In Cloudflare, create a tunnel and choose the Linux installation command shown for the Ubuntu server. Keep the tunnel token private; do not put it in Git or the project's `.env` file.
2. Add a published application route for a hostname such as `files.example.com`, with service URL `http://localhost:8080`.
3. Install the tunnel as a `cloudflared` system service using the dashboard-provided command. Check the tunnel status in the Cloudflare dashboard.
4. Set `SESSION_COOKIE_SECURE=true` in the server's environment/ignored `.env`. The user's browser sees HTTPS at Cloudflare even though `cloudflared` connects to the local Spring server over HTTP.
5. For the current host-run backend, set `SERVER_ADDRESS=127.0.0.1` so the Spring port is reachable locally by `cloudflared` without listening on every server network interface. If the app is later placed in Docker, bind the **host-published** app port to `127.0.0.1`; the app inside its container must still listen on its container interface.
6. Keep PostgreSQL off the public interfaces. The current Compose file publishes its port; for a host-run backend, bind it to loopback, for example `127.0.0.1:${POSTGRES_PORT:-5432}:5432`, or use an internal-only Docker network when the backend is containerized.
7. Open the HTTPS hostname, pass the Cloudflare Access check, then sign in to the application's own account.

Cloudflare Access and the application's login are separate checks. The application currently permits self-registration, so users who pass the Cloudflare policy can still create an app account. Cloudflare Tunnel transports requests; it does not replace the application's owner checks, login, or CSRF protection.

Because the UI and API share the tunneled hostname, browser requests are same-origin and do not need a Cloudflare hostname in `CORS_ALLOWED_ORIGINS`. If the frontend is hosted at a different origin, configure that exact `https://...` origin; do not use `https://*.trycloudflare.com` or another wildcard with credentials.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `SERVER_ADDRESS` | all interfaces | Host-run listener address; set to `127.0.0.1` when `cloudflared` runs on the same host |
| `DB_URL` | `jdbc:postgresql://localhost:5432/cloud_storage` | PostgreSQL JDBC URL |
| `DB_USER` | `cloud_storage` | Database user |
| `DB_PASSWORD` | required | Database password; keep it outside source control |
| `STORAGE_ROOT` | `../storage` | Private persistent file directory |
| `MAX_FILE_SIZE_BYTES` | `104857600` | Application-level file limit (100 MiB) |
| `MAX_FILE_SIZE` | `100MB` | Servlet multipart per-file limit |
| `MAX_REQUEST_SIZE` | `101MB` | Servlet multipart total request limit |
| `SESSION_COOKIE_SECURE` | `false` | Set `true` when the public browser connection uses HTTPS, including Cloudflare Tunnel |
| `SESSION_COOKIE_SAME_SITE` | `lax` | Use `none` for cross-site browser deployments with secure cookies |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Exact allowed origins for separate frontends; the same-origin UI does not need CORS |

If PostgreSQL uses custom database/user settings in Compose, set matching `DB_URL` and `DB_USER` values for the backend. Set the three upload limits together.

To package the backend:

```bash
mvn package
java -jar target/cloud-storage-backend-1.0.0-SNAPSHOT.jar
```

Run integration tests with Docker available:

```bash
mvn test
```

The tests start disposable PostgreSQL containers and use a temporary storage directory. They cover hashed-password authentication, case-insensitive duplicate accounts, CSRF/session behavior, binary round-trips, owner isolation, interrupted uploads, unsafe names, folder cycles/deletion policy, storage reinitialization readability, concurrent same-name uploads, legacy-file migration backfill, file revision history and restore, stale and simultaneous edits, failed revision cleanup, and authorized HTTP routes.

### P0 end-to-end validation (2026-11-25)

The packaged application was also run locally with a separate disposable PostgreSQL container and a fresh temporary storage root. The automated suite passed **8 tests, 0 failures**. Headless Chrome exercised the browser registration/login and file upload flow; the Java `DocsClient` registered a second user and exercised login, upload, listing, and download.

- The browser user uploaded and retrieved a 262,144-byte binary file. Its SHA-256 was `8f2144a274ead978258dbbcc0b62c5b0ee7335eb2bf729fbb5ca4026af0f0a14` before and after download.
- The Java user uploaded and retrieved a 131,071-byte binary file. Its SHA-256 was `e76c8ea2cd8b92b8413ed9583c99d85a076bd582e32d81d20078621644caf526` before and after download.
- After restarting the application process with the same test database and storage directory, both users logged in again and downloaded their files with matching checksums. User B's file list contained only B's file, and B's attempt to download user A's file returned HTTP 404.
- A multipart request cut off after 32 KiB of its declared 5,000,000-byte body left no visible metadata, stored object, or temporary object. A `../../...` upload filename returned HTTP 400, and an outside-storage sentinel file stayed unchanged.

The Java download request now accepts `*/*` because downloads may have different MIME types and API errors are JSON. This lets the client receive the correct 404 response for an inaccessible file instead of a content-negotiation HTTP 500. These checks used isolated temporary data, which was removed afterward; they did not exercise the remote Ubuntu host, Cloudflare Tunnel/Access, public HTTPS, or backup/restore.

### P2 file revision history verification (2026-11-25)

`backend/.tools/apache-maven-3.10.0/bin/mvn -f backend/pom.xml -B -ntp test` completed successfully: **15 tests, 0 failures, 0 errors, 0 skipped**. The test run used disposable PostgreSQL 16 containers and a temporary file-storage directory. It exercised V1-to-V2 migration and backfill, v1 → v2 → v3, SHA-256 values, byte-for-byte historical downloads, database rejection of revision edits/deletes, restoring v1 as v4, stale ETag rejection, a concurrent upload reservation conflict, cleanup after a truncated revision, whole-file deletion through the existing lifecycle, other-user and anonymous access denial, and authenticated HTTP upload/restore/download routes.

The P2 checks ran as automated API and migration tests. They did not exercise version history through the browser UI or against the remote Ubuntu/Cloudflare deployment.

## Authentication and CSRF flow

1. `GET /api/auth/csrf`; preserve the returned session cookie and token.
2. Send `X-CSRF-TOKEN: <token>` and the session cookie on `POST /api/auth/register` or `POST /api/auth/login`.
3. After login, fetch `/api/auth/csrf` again; login rotates the token. Keep the session cookie for protected calls.
4. `POST /api/auth/logout` requires the current session and CSRF token, then invalidates the session. Fetch a new token if the client logs in again or continues after logout.

Use `credentials: "include"` in browser fetch calls when the frontend is cross-origin. CORS origins must be exact and must not use `*` with credentials.

## HTTP API

All routes below, except registration, login, and CSRF retrieval, require an authenticated session. Mutating routes require CSRF.

| Method | Route | Behavior |
|---|---|---|
| GET | `/api/auth/csrf` | Return CSRF header name and token |
| POST | `/api/auth/register` | Create account (`email`, `password`) |
| POST | `/api/auth/login` | Authenticate and create session |
| POST | `/api/auth/logout` | Invalidate session |
| GET | `/api/users/me` | Return current account |
| POST | `/api/files` | Multipart upload (`file`, optional `folderId`) |
| GET | `/api/files?folderId=...` | List files in folder; omit folderId for root |
| GET | `/api/files/{id}` | Get file metadata |
| GET | `/api/files/{id}/download` | Stream the current revision as an attachment |
| GET | `/api/files/{id}/versions` | List ready revisions and return the current strong ETag |
| POST | `/api/files/{id}/versions` | Upload a revision (`If-Match`, multipart `file`, optional `changeMessage`) |
| GET | `/api/files/{id}/versions/{versionNumber}` | Get one revision's metadata |
| GET | `/api/files/{id}/versions/{versionNumber}/download` | Stream the exact historical revision bytes |
| POST | `/api/files/{id}/versions/{versionNumber}/restore` | Restore a revision by creating a new current revision (`If-Match`) |
| PATCH | `/api/files/{id}` | Rename (`name`) and/or move (`folderId`; `null` means root) |
| DELETE | `/api/files/{id}` | Delete metadata and stored bytes |
| POST | `/api/folders` | Create folder (`name`, optional `parentId`) |
| GET | `/api/folders?parentId=...` | List children; omit parentId for root |
| GET | `/api/folders/{id}` | Get folder metadata |
| GET | `/api/folders/{id}/contents` | Get folder, child folders and files |
| PATCH | `/api/folders/{id}` | Rename (`name`) and/or move (`parentId`; `null` means root) |
| DELETE | `/api/folders/{id}` | Delete an empty folder |

Registration body:

```json
{"email":"user@example.com","password":"a password with at least 12 characters"}
```

Registration requires a valid email address and a password between 12 and 128 characters. The Java client checks these limits before sending the request.

Successful registration returns `201 Created` with safe account metadata:

```json
{"id":"4ea79cc7-abaf-443c-97b7-7215ddc13df7","email":"user@example.com","createdAt":"2026-11-12T14:00:00Z"}
```

File upload returns `201 Created` with `id`, `name`, `sizeBytes`, `mimeType`, `sha256`, `folderId`, `createdAt`, and `updatedAt`. File and folder PATCH requests accept only the documented fields; `folderId: null` or `parentId: null` moves the resource to the root. Errors return a stable object such as:

```json
{"timestamp":"2026-11-12T14:00:00Z","status":404,"code":"file_not_found","message":"The requested file was not found.","path":"/api/files/4ea79cc7-abaf-443c-97b7-7215ddc13df7"}
```

Example upload:

```bash
curl -b cookies.txt -c cookies.txt \
  -H 'X-CSRF-TOKEN: <token>' \
  -F 'file=@/path/to/document.pdf' \
  http://localhost:8080/api/files
```

Success responses use JSON metadata; errors use `{ "timestamp", "status", "code", "message", "path" }`. Files owned by another user are reported as `404` to avoid revealing their existence. `pending` and `deleting` files are never listed or downloaded.

### File revision history

A file ID is the stable identity of the logical file. Every successful upload or restore appends an immutable, numbered revision with its own UUID storage key, uploader account, creation timestamp, byte length, SHA-256 and optional change message. The existing `files.storage_key`, size, MIME type and checksum columns describe the current revision for compatibility with the existing file API. Previous revision keys and bytes are not reused or overwritten.

`GET /api/files/{id}/versions` returns oldest-first history. It also returns the current ETag in the JSON body and the HTTP `ETag` header. Each item contains `id`, `fileId`, `versionNumber`, `uploaderUserId`, `createdAt` (UTC), `sizeBytes`, `sha256`, `changeMessage`, and `current`.

To upload a new revision, send the ETag from the latest history response as the exact, quoted `If-Match` value. The request is multipart; `changeMessage` is an optional form field (maximum 1000 characters, no control characters). The authenticated session supplies `uploaderUserId`; clients cannot choose it.

```bash
curl -b cookies.txt -c cookies.txt -H 'X-CSRF-TOKEN: <token>' -H 'If-Match: "4ea79cc7-abaf-443c-97b7-7215ddc13df7-v2"' -F 'file=@/path/to/replacement.pdf' -F 'changeMessage=Updated figures' http://localhost:8080/api/files/4ea79cc7-abaf-443c-97b7-7215ddc13df7/versions
```

The `201 Created` response contains the new revision metadata and its ETag. A missing `If-Match` returns `428 Precondition Required`; malformed tags return `400`; a tag for an older current revision returns `412 Precondition Failed`. A simultaneous request that reserves the same next revision number while another upload is in progress returns `409 Conflict`. Clients should fetch history again after either response and retry only after reviewing the new current ETag.

`GET /api/files/{id}/versions/{versionNumber}` returns metadata; adding `/download` streams that exact version as an attachment. `POST /api/files/{id}/versions/{versionNumber}/restore` accepts an optional JSON body such as `{"changeMessage":"Restore before cleanup"}` and requires the current `If-Match`. Restore copies the selected old bytes to a fresh storage key and creates the next version number; it does not move the current pointer backward. Missing or other-user file IDs and versions return `404`. Every version endpoint uses the same authenticated owner check as the existing file endpoints, and POST requests require CSRF.

`DELETE /api/files/{id}` retains the existing whole-file deletion behavior: it removes the logical file and all of its stored revisions. A successful revision upload or restore does not remove any earlier revision.

#### Migration and rollback

Flyway V2 adds `file_versions`, a nullable `files.current_version_id`, a composite foreign key that ensures the current revision belongs to the same file, and a check that ready files have a current revision. A database trigger rejects updates to ready history rows and their individual deletion; the existing whole-file deletion lifecycle can remove them after the logical file is marked `deleting`. The migration backfills pre-existing rows as version 1, attributes them to the existing owner, copies their original timestamp, size, checksum and status, and points ready files to the inserted version. It reuses `files.storage_key`; it neither moves nor rewrites any stored bytes. The backfill `INSERT` uses `WHERE NOT EXISTS` so that data-copy step can be safely retried; Flyway still applies the complete schema migration once.

Flyway does not automatically roll back applied SQL migrations. For an application rollback, stop the service and restore a matching database and storage backup. If intentionally removing V2 metadata, first stop the service and preserve the storage objects referenced by every `file_versions.storage_key` outside the active storage root; otherwise the old application's startup reconciliation can treat later revision objects as unreferenced and remove them. With a database backup available, the schema additions can be removed in this order: drop `fk_files_current_version` and `chk_files_ready_has_current_version`, drop `files.current_version_id`, then drop `file_versions`. The original V1 file metadata and its original storage object remain unchanged by that SQL rollback. Do not use this rollback path after creating revisions unless their bytes and metadata are backed up.

## Operational notes

- SQL transactions use JDBC bind parameters. Composite foreign keys enforce that files and nested folders share their owner's identity.
- Uploads write to a temporary file, enforce the byte limit while streaming, calculate SHA-256, force file contents, atomically move into place, then mark SQL metadata `ready`. Revisions reserve a pending history row and a new UUID object key, then mark the revision ready and advance the file's current pointer only if the expected current revision still matches.
- Failed uploads remove pending rows/temporary objects. Startup reconciliation retries stale uploads/deletions and removes unreferenced UUID objects.
- Deletion first changes metadata to `deleting`; failed disk cleanup is retried during startup reconciliation.
- Folder moves serialize per owner and check descendants recursively to reject cycles.
- `server.forward-headers-strategy=framework` enables forwarded-header handling, but this repository does not configure or run `cloudflared`, create a Cloudflare Access policy, or automatically validate Access tokens. Do not publish a public hostname until Cloudflare Access is configured. Rate limiting, account recovery, persistent session storage, quotas, and cloud object storage are also not configured.
