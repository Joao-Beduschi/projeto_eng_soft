# Cloud Storage Backend

Spring Boot HTTP API for private file storage. PostgreSQL stores users, folders, file metadata, status and checksums. File contents are streamed to a private local directory using server-generated UUID keys; client filenames are metadata only.

The root-level `DocsServer.java` and `DocsClient.java` are integrated with this backend. `DocsServer` launches the Spring Boot application; `DocsClient` is an interactive Java HTTP client for account login/registration and file/folder operations. The Java client relies on the backend for authentication, authorization, SQL metadata, and file storage; it does not maintain a separate TCP storage protocol.

## Assumptions recorded for this implementation

- Java 21, Spring Boot 4.1.2, PostgreSQL 15 or newer, JDBC with prepared statements, and Flyway migrations.
- New accounts can register themselves. Passwords are stored using Spring Security's adaptive PBKDF2 encoder.
- Authentication uses server-side HTTP sessions in application memory. Cookies are HttpOnly and SameSite=Lax by default; enable HTTPS and set `SESSION_COOKIE_SECURE=true` outside local development. Cross-site frontends may need `SESSION_COOKIE_SAME_SITE=none`, which requires secure cookies over HTTPS.
- State-changing requests require a CSRF token obtained from `GET /api/auth/csrf` and sent as `X-CSRF-TOKEN`. Fetch it again after logout/session renewal.
- Files are stored on persistent local disk outside the application resources/static directory. Default root is `../storage` relative to the backend working directory.
- Maximum file size is 100 MiB by default. Set `MAX_FILE_SIZE_BYTES`, `MAX_FILE_SIZE`, and `MAX_REQUEST_SIZE` together when changing the limit. Uploads also reserve against currently available disk space before streaming and handle disk-full errors without returning success.
- Any file content type is accepted. Downloads are attachments with `nosniff`; MIME type is guessed from the display name, is informational, and is never used to choose a filesystem path.
- Duplicate names are allowed within a folder. Each upload gets a separate ID.
- Deleting a nonempty folder returns `409 Conflict`. Move operations reject direct and indirect cycles.
- CORS is limited to `http://localhost:3000` and `http://localhost:5173` by default; set `CORS_ALLOWED_ORIGINS` to comma-separated exact frontend origins.
- Sharing, public links, previews, versions, quotas and password recovery are out of scope.

## Run locally

Requirements: JDK 21, Maven 3.6.3+, Docker Compose, and a persistent filesystem with room for uploaded files.

```bash
export POSTGRES_PASSWORD='choose-a-local-development-password'
docker compose -f backend/compose.yaml up -d --wait
cd backend
export DB_PASSWORD="$POSTGRES_PASSWORD"
mvn spring-boot:run
```

Flyway applies `src/main/resources/db/migration/V1__initial_schema.sql` at startup. Set `STORAGE_ROOT` to choose a different private data directory. Do not place it under a public/static web directory or commit its contents.

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

Create an account or sign in, then upload/download files, create and navigate folders, rename or move items, delete items, and search the current folder. Files can also be uploaded by dropping them into the workspace. New accounts require a valid email and a password between 12 and 128 characters. The browser interface uses the same 100 MiB per-file limit as the API.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/cloud_storage` | PostgreSQL JDBC URL |
| `DB_USER` | `cloud_storage` | Database user |
| `DB_PASSWORD` | required | Database password; keep it outside source control |
| `STORAGE_ROOT` | `../storage` | Private persistent file directory |
| `MAX_FILE_SIZE_BYTES` | `104857600` | Application-level file limit (100 MiB) |
| `MAX_FILE_SIZE` | `100MB` | Servlet multipart per-file limit |
| `MAX_REQUEST_SIZE` | `101MB` | Servlet multipart total request limit |
| `SESSION_COOKIE_SECURE` | `false` | Set `true` when HTTPS is enabled |
| `SESSION_COOKIE_SAME_SITE` | `lax` | Use `none` for cross-site browser deployments with secure cookies |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Exact allowed frontend origins |

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

The tests start a disposable PostgreSQL container and use a temporary storage directory. They cover hashed-password authentication, case-insensitive duplicate accounts, CSRF/session behavior, binary round-trips, owner isolation, interrupted uploads, unsafe names, folder cycles/deletion policy, storage reinitialization readability, and concurrent same-name uploads.

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
| GET | `/api/files/{id}/download` | Stream original bytes as an attachment |
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

## Operational notes

- SQL transactions use JDBC bind parameters. Composite foreign keys enforce that files and nested folders share their owner's identity.
- Uploads write to a temporary file, enforce the byte limit while streaming, calculate SHA-256, force file contents, atomically move into place, then mark SQL metadata `ready`.
- Failed uploads remove pending rows/temporary objects. Startup reconciliation retries stale uploads/deletions and removes unreferenced UUID objects.
- Deletion first changes metadata to `deleting`; failed disk cleanup is retried during startup reconciliation.
- Folder moves serialize per owner and check descendants recursively to reject cycles.
- No HTTPS proxy, rate limiting, account recovery, persistent session store, quota, or cloud object store is configured. Do not expose the service to the public internet without deployment hardening.
