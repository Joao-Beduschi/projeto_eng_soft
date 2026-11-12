# Cloud Storage Application — Mandatory Backend Requirements (Java + SQL)

> **Purpose:** Implementation-ready requirements and development guide for the backend of a multi-user cloud storage web application (Google Drive–style MVP). Intended for a developer or coding CLI to read as a single source of truth.
>
> **Recommended stack:** Java + Spring Boot, PostgreSQL, Spring Data JPA **or** JDBC, server-side sessions, persistent local filesystem storage, Flyway, JUnit. These are implementation recommendations, not requirements imposed by the cloud-storage concept itself.
>
> **Scope note:** The basic functional core needs users, authentication, upload/download/list/delete, persistent file bytes, metadata, per-user authorization, validation, and error handling. Folders, rename, and move are **required for the Google Drive–style MVP described here**, but not for the smallest possible storage API. File sharing, preview, version history, cloud synchronization, mobile apps, and real-time collaboration are out of scope unless explicitly added.

## 1. Project goals and boundaries

The backend must:

1. Authenticate users and securely handle credentials and sessions.
2. Accept file uploads and save their bytes durably.
3. Maintain SQL metadata identifying each file, its owner, and its folder.
4. Let authenticated users list, download, rename/move, and delete files they own.
5. Let users organize files into nested folders (Google Drive–style MVP).
6. Enforce authorization in the backend on **every** protected action.
7. Prevent invalid uploads, unsafe filesystem paths, corrupted files, and inconsistent state.
8. Expose an HTTP API for a web frontend and return predictable error responses.
9. Preserve uploaded data across application restarts.
10. Include automated tests for security, data integrity, and concurrent/failure cases.

**Do not put file contents in SQL by default.** PostgreSQL holds user records, folder relationships, and file metadata; a persistent filesystem (or later an object store) holds file contents.

**Explicit out of scope:** paid plans, email verification, 2FA, sharing/permissions beyond ownership, expiring links, file previews, file versions, trash/restore, resumable uploads, malware scanning, desktop/mobile synchronization, and real-time collaboration. These can be introduced later.

### 1.1 Requirement priority conventions

- **P0 — Required core:** needed for a secure, functional multi-user file service.
- **P1 — Required for this Drive-like MVP:** folder hierarchy, rename, and move.
- **P2 — Recommended:** search, explicit per-user quotas, display used space, advanced observability.
- **P3 — Optional:** sharing, versions, previews, resumable uploads, cloud sync.

A university course specification may impose additional requirements. When it does, the course document takes precedence over these general recommendations.

## 2. System architecture

```text
Browser / Frontend
       |
       | HTTPS + HTTP API (JSON and multipart file uploads)
       v
Java Backend (Spring Boot recommended)
  |-- Controllers: HTTP request/response handling
  |-- Security: sessions, authentication, authorization
  |-- Services: business rules and workflows
  |-- Repositories: SQL persistence
  |-- StorageService: persistent file-byte I/O
       |                         |
       v                         v
PostgreSQL                     Persistent Storage
(users, folders, files)        (files addressed by opaque storage keys)
```

### 2.1 Responsibilities

| Component | Mandatory responsibility |
|---|---|
| HTTP server/API | Handle requests, validate basic inputs, return structured responses |
| Authentication | Register/login/logout or support administrator-created accounts |
| Authorization | Derive authenticated user identity; check file/folder ownership |
| User service/repository | Store accounts and look up users |
| File service/repository | Coordinate uploads, downloads, metadata, deletion, rename, move |
| Folder service/repository | Create/list/rename/move/delete folder structures |
| Storage service | Write, read, and delete file bytes without trusting original names as paths |
| PostgreSQL | Persist metadata, relationships, constraints, and indexes |
| Error handling | Translate failures into appropriate HTTP statuses and safe messages |
| Tests | Demonstrate secure behavior and persistent, intact files |

A REST API is recommended for a separate frontend/backend design, but REST is not intrinsically mandatory. A frontend framework is also not intrinsically mandatory.

## 3. Database: tables and relationships

### 3.1 Core tables

| Table | Role | Priority |
|---|---|---|
| `users` | Account IDs, unique emails, hashed passwords | P0 |
| `files` | File IDs, ownership, folder, metadata, opaque storage key, lifecycle state | P0 |
| `folders` | Nested folder hierarchy and ownership | P1 |
| `sessions` | Persistent sessions, **only if** using database-backed sessions | Implementation-dependent |
| `file_permissions` | User-to-file sharing/access rights | P3, not in MVP |
| `file_versions` | Historical versions | P3, not in MVP |
| `audit_logs` | Security-sensitive activity logging | Recommended for production |
| `password_reset_tokens` | Password recovery | Only if feature implemented |

Relationships:

```text
users 1 --- N files
users 1 --- N folders
folders 1 --- N files       (file.folder_id may be NULL for root)
folders 1 --- N folders     (folder.parent_id may be NULL for root)
```

Every file has **exactly one owner**. Every folder has **exactly one owner**. A folder and any children/files must belong to the same owner. Files/folders at the root use a `NULL` folder or parent ID.

### 3.2 PostgreSQL schema (PostgreSQL 15+)

Use a migration such as `src/main/resources/db/migration/V1__initial_schema.sql`:

```sql
-- UUID generation through gen_random_uuid() is available in recent PostgreSQL.
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX users_email_unique
    ON users (LOWER(email));

CREATE TABLE folders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    parent_id UUID,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (id, owner_id),
    FOREIGN KEY (parent_id, owner_id)
        REFERENCES folders(id, owner_id),
    CHECK (LENGTH(TRIM(name)) > 0),
    CHECK (parent_id IS DISTINCT FROM id)
);

CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    folder_id UUID,
    name VARCHAR(255) NOT NULL,
    storage_key UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (folder_id, owner_id)
        REFERENCES folders(id, owner_id),
    CHECK (LENGTH(TRIM(name)) > 0),
    CHECK (size_bytes >= 0),
    CHECK (status IN ('pending', 'ready', 'deleting'))
);

CREATE INDEX idx_files_owner_folder
    ON files(owner_id, folder_id);

CREATE INDEX idx_folders_owner_parent
    ON folders(owner_id, parent_id);

CREATE INDEX idx_files_owner_name
    ON files(owner_id, name);
```

#### Column meaning

- `users.id`: stable account identity; `users.email`: unique under case-insensitive comparison; `users.password_hash`: **never plaintext**.
- `folders.owner_id`: owner; `folders.parent_id`: nullable parent folder, enabling nesting.
- `files.owner_id`: owner; `files.folder_id`: nullable containing folder.
- `files.name`: display/original name; **never use directly as a filesystem path**.
- `files.storage_key`: internal opaque identifier used to resolve actual stored bytes.
- `files.size_bytes`: file length; `files.mime_type`: informational MIME type (do not blindly trust what clients claim).
- `files.status`: `pending` during upload, `ready` after durable storage, `deleting` during removal.
- `created_at` / `updated_at`: timestamps; Java application updates `updated_at` explicitly on metadata mutations.

#### Important schema/application constraints

1. `FOREIGN KEY (folder_id, owner_id)` and `(parent_id, owner_id)` enforce **same-owner** folder relationships.
2. `CHECK (parent_id IS DISTINCT FROM id)` prevents a folder parenting itself, but **not indirect cycles** (A -> B -> A). Detect ancestry cycles on folder moves in a transaction; use suitable locking/concurrency protection.
3. Use unique constraints/indexes for folder/file names *within one directory* only if the product decides duplicate names are prohibited. SQL handling of `NULL` means root-level uniqueness may require `NULLS NOT DISTINCT` (PostgreSQL 15+) or partial indexes.
4. Decide account/folder deletion policy explicitly. Avoid casually adding `ON DELETE CASCADE` when physical file bytes require cleanup.
5. Check database rows affected by `UPDATE` and `DELETE` to detect missing or unauthorized resources.
6. Use migrations (Flyway recommended) rather than manually changing schema between environments.
7. `size_bytes >= 0` permits a zero-byte file, which is valid.
8. A SQL transaction alone **cannot** atomically commit changes to the database and local filesystem. See file lifecycle below.

### 3.3 Essential SQL operations

**Find user for login (hash comparison happens in Java):**

```sql
SELECT id, password_hash
FROM users
WHERE LOWER(email) = LOWER(?);
```

**List ready files from root (`folder_id` parameter NULL) or a chosen folder:**

```sql
SELECT id, name, size_bytes, mime_type, created_at
FROM files
WHERE owner_id = ?
  AND folder_id IS NOT DISTINCT FROM ?
  AND status = 'ready'
ORDER BY name;
```

**Check ownership and look up bytes for download:**

```sql
SELECT storage_key, name, mime_type, size_bytes
FROM files
WHERE id = ? AND owner_id = ? AND status = 'ready';
```

**Rename file owned by authenticated user:**

```sql
UPDATE files
SET name = ?, updated_at = NOW()
WHERE id = ? AND owner_id = ? AND status = 'ready';
```

**Move file to folder:** first ensure the target folder belongs to the user (or `NULL` for root), then update the file subject to the same owner. For strong concurrency guarantees, validate and update in a transaction, or use a single conditional update.

**List folders inside parent:**

```sql
SELECT id, name, created_at
FROM folders
WHERE owner_id = ?
  AND parent_id IS NOT DISTINCT FROM ?
ORDER BY name;
```

**Important:** Every SQL statement must use parameterized queries/prepared statements (or a properly configured ORM), **never string-concatenate untrusted inputs** into SQL.

## 4. Functional requirements

### 4.1 Authentication and user management

| ID | Requirement | Priority |
|---|---|---|
| AUTH-01 | Register user or provision accounts by administrator | P0 |
| AUTH-02 | Login with verified credentials | P0 |
| AUTH-03 | Logout and invalidate active session | P0 |
| AUTH-04 | Authenticate each protected API request | P0 |
| AUTH-05 | Store only password hashes (e.g., Argon2id) | P0 |
| AUTH-06 | Enforce unique email and validate inputs | P0 |
| AUTH-07 | Change password | P2 |
| AUTH-08 | Recover password | Production/optional feature |
| AUTH-09 | Delete account | Product/legal requirements |
| AUTH-10 | Email verification, 2FA | P3 |

Login flow: look up account by email -> verify password hash in Java -> create secure session -> send authenticated response. Do **not** do plaintext comparisons in SQL. For a browser frontend, server-side sessions with secure, `HttpOnly`, appropriately configured `SameSite` cookies are a straightforward choice. Use CSRF defenses for state-changing requests if using cookie-based authentication. Configure `Secure` cookies over HTTPS. Sessions may be stored in server memory for local development or in a dedicated store/database for multi-instance deployment.

### 4.2 File management

| ID | Requirement | Priority |
|---|---|---|
| FILE-01 | Upload file | P0 |
| FILE-02 | Download file | P0 |
| FILE-03 | List files belonging to authenticated user | P0 |
| FILE-04 | Delete file and associated stored bytes | P0 |
| FILE-05 | Retrieve file metadata | P0 |
| FILE-06 | Preserve files across server restart | P0 |
| FILE-07 | Ensure downloaded bytes match uploaded bytes | P0 |
| FILE-08 | Handle incomplete/interrupted uploads safely | P0 |
| FILE-09 | Rename file | P1 |
| FILE-10 | Move file among folders/root | P1 |
| FILE-11 | Search file by name | P2 |
| FILE-12 | Track used storage and enforce capacity | P0 for safe global/file limits; P2 for explicit per-user quotas |
| FILE-13 | Multiple uploads, ZIP download, preview, version history, recycle bin | P3 |

**File metadata example:**

```json
{
  "id": "4ea79cc7-abaf-443c-97b7-7215ddc13df7",
  "name": "document.pdf",
  "sizeBytes": 1024000,
  "mimeType": "application/pdf",
  "ownerId": "487c66b7-5a34-433b-9edc-6c68620508ea",
  "folderId": null,
  "createdAt": "2026-11-12T14:00:00Z",
  "updatedAt": "2026-11-12T14:00:00Z"
}
```

`storage_key` is internal and generally should not be exposed to clients. `ownerId` may also be omitted from client-facing DTOs if not needed.

### 4.3 Folder management (Drive-like MVP)

| ID | Requirement | Priority |
|---|---|---|
| FOLDER-01 | Create folder | P1 |
| FOLDER-02 | List files and child folders | P1 |
| FOLDER-03 | Navigate nested folders | P1 |
| FOLDER-04 | Rename folder | P1 |
| FOLDER-05 | Delete folder with documented child-content policy | P1 |
| FOLDER-06 | Move folder and reject hierarchy cycles | P1/P2 |
| FOLDER-07 | Search folders | P2 |

Example tree:

```text
root/
├── University/
│   ├── Documents/
│   │   ├── assignment.pdf
│   │   └── report.docx
│   └── Projects/
│       └── project.zip
└── Personal/
    └── photo.jpg
```

Decide what happens if the user deletes a nonempty folder: **reject** with conflict until empty (simplest MVP), or recursively delete with consistent storage cleanup. Do not silently drop SQL rows while leaving physical file bytes orphaned.

### 4.4 Sharing (out of scope)

If later implemented, add `file_permissions` or a similar access model, authenticated invitations or secure share links, permission scopes (read/edit), and revocation. **Authorization is mandatory even without sharing.**

## 5. REST HTTP API contract (recommended)

Use JSON for metadata and multipart/form-data for uploads. These routes are a suggested contract, not an externally mandated standard.

| Method | Route | Purpose | Priority |
|---|---|---|---|
| POST | `/api/auth/register` | Register account | P0 if self-registration |
| POST | `/api/auth/login` | Login | P0 |
| POST | `/api/auth/logout` | Logout | P0 |
| GET | `/api/users/me` | Identify current user | P2 |
| POST | `/api/files` | Upload file | P0 |
| GET | `/api/files` | List accessible files (e.g. optional `folderId`) | P0 |
| GET | `/api/files/{id}` | File metadata | P0 |
| GET | `/api/files/{id}/download` | Download file bytes | P0 |
| DELETE | `/api/files/{id}` | Delete file | P0 |
| PATCH | `/api/files/{id}` | Rename or move file | P1 |
| POST | `/api/folders` | Create folder | P1 |
| GET | `/api/folders/{id}` | Folder metadata | P1 |
| GET | `/api/folders/{id}/contents` | Files and child folders | P1 |
| PATCH | `/api/folders/{id}` | Rename/move folder | P1 |
| DELETE | `/api/folders/{id}` | Delete folder according to policy | P1 |
| GET | `/api/search?q=...` | Search name metadata | P2 |

Suggested HTTP response behavior:

- `200 OK` for successful data retrieval and updates, or `204 No Content` for successful no-body deletes.
- `201 Created` for new users, files, or folders (upload can also be asynchronous if defined accordingly).
- `400 Bad Request` for malformed inputs; `401 Unauthorized` for missing/invalid authentication.
- `403 Forbidden` for known access denial, or `404 Not Found` to avoid revealing existence of other users' resources; choose a consistent policy.
- `404 Not Found` for absent resources; `409 Conflict` for duplicate or invalid folder operations; `413 Content Too Large` for upload limit exceeded.
- `429 Too Many Requests` when rate limiting is enabled; `500/503` for server/storage failures without leaking details.

Important rules:

- Derive `currentUserId` from authenticated server-side session/security context, not request body/query parameters.
- Never return another user's metadata or bytes.
- Do not allow users to supply a trusted storage key.
- Validate folder ownership before creating or moving a file into it.
- Stream file data instead of loading arbitrarily large files into server memory.
- Use suitable `Content-Disposition` and content type headers for downloads.
- Ensure CORS is explicitly configured if frontend/backend use different origins; do not allow arbitrary credentialed origins.

## 6. Persistent file storage and lifecycle

### 6.1 Storage strategy

For a small university application, use a durable directory separate from public/static files:

```text
project/
├── backend/
│   ├── src/
│   └── pom.xml
└── storage/
    ├── 2b2aa11e-0f3d-4e31-b089-7e52d6785f11
    ├── 4a23013b-9201-4271-aa9a-1e09e5e3e011
    └── 7dc310dc-8e61-4d5b-8df8-699a5e4b1c71
```

Key properties:

- Storage root configured outside the publicly served web directory.
- Persist the storage root via host disk or Docker volume; do not rely on a disposable container filesystem.
- Use an internally generated opaque `storage_key`, not the original filename, as physical file identity.
- Resolve and validate paths against the configured storage root; ensure no path traversal or symlink escape.
- Treat content type as untrusted until validated as needed; never execute uploads as code.
- Apply size limits before and during streaming; handle disk-full conditions gracefully.

### 6.2 Upload workflow

```text
POST /api/files (authenticated)
  1. Authenticate user.
  2. Validate request, file size/type policy, and filename.
  3. Validate destination folder is owned by user (or root).
  4. Create pending metadata in SQL with generated storage key.
  5. Stream bytes to a temporary file in the storage area.
  6. Flush/close and atomically rename or otherwise finalize stored bytes.
  7. Mark metadata status = 'ready' in SQL.
  8. Return success and file metadata.
On failure: clean temporary/partial bytes and resolve pending SQL state.
```

Do not show or serve `pending` files as completed. Make cleanup **idempotent** and add restart/reconciliation logic for stalled pending uploads or orphaned stored files. Crash windows exist between filesystem and SQL updates.

Optional (recommended) integrity enhancement: calculate SHA-256 while writing and verify before serving or periodically; add a checksum column in a later migration if you implement this.

### 6.3 Download workflow

```text
GET /api/files/{id}/download
  1. Authenticate user.
  2. Find 'ready' metadata restricted to owner_id = current user.
  3. Resolve storage_key under private storage root.
  4. Stream bytes to response.
  5. Return safe HTTP response headers.
```

If the stored object is missing even though SQL says `ready`, log an internal consistency error and return an appropriate server error; do not disclose internal paths.

### 6.4 Deletion workflow

```text
DELETE /api/files/{id}
  1. Authenticate and authorize.
  2. Mark metadata as 'deleting' (or otherwise create a durable deletion task).
  3. Delete stored bytes safely; treat already-missing bytes as idempotent cleanup.
  4. Remove metadata once cleanup succeeds.
  5. Recover/retry interrupted deletion after restart.
```

For the simplest MVP, this can be implemented synchronously, but its retry/consistency behavior must still be specified. A SQL transaction **does not** automatically undo a filesystem deletion.

### 6.5 Concurrency and folder integrity

- Two simultaneous uploads must not overwrite each other: use distinct storage keys and temporary names.
- Rename/move/delete operations must check both ownership and current state.
- Folder moves must prevent direct or indirect cycles, including under simultaneous requests.
- Define what happens if a file is being downloaded while deleted (e.g., an already-open stream may complete).
- Updates that depend on multiple SQL reads/writes should use transactions and appropriate locking or single-statement conditions.

## 7. Java implementation plan

Recommended directory organization:

```text
backend/
├── src/main/java/com/cloudstorage/
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── FileController.java
│   │   └── FolderController.java
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── FileService.java
│   │   ├── FolderService.java
│   │   └── StorageService.java
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── FileRepository.java
│   │   └── FolderRepository.java
│   ├── model/
│   │   ├── User.java
│   │   ├── FileMetadata.java
│   │   └── Folder.java
│   ├── security/
│   │   ├── SecurityConfig.java
│   │   └── AuthenticationFilter.java
│   └── CloudStorageApplication.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/V1__initial_schema.sql
└── pom.xml
```

Class names and packages are design suggestions, not Java language requirements.

### 7.1 Controllers

Parse requests, validate basic data, call services, build HTTP responses. Do not embed SQL or filesystem manipulation in controllers.

**Illustrative Spring controller signature, not full production code:**

```java
@PostMapping("/api/files")
public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
    // Obtain authenticated user identity from the security context.
    // Pass identity and file to FileService; handle errors centrally.
    return fileService.upload(file);
}
```

Do not copy this snippet into a working project without implementing session context, validation, authorization, and the service method contract.

### 7.2 Services

- `AuthService`: registration, hash verification, session creation/invalidation.
- `FileService`: upload/download/list/delete/rename/move and cross-system consistency.
- `FolderService`: ownership, nested hierarchy, cycle prevention, deletion policy.
- `StorageService`: durable opaque-key byte storage and retrieval.

### 7.3 Repositories

- `UserRepository`: insert/find by ID/email.
- `FileRepository`: insert/select/update/delete metadata scoped by owner.
- `FolderRepository`: insert/select/update/delete hierarchy scoped by owner.

Possible Spring Data JPA repository example:

```java
public interface FileRepository extends JpaRepository<FileMetadata, UUID> {
    List<FileMetadata> findByOwnerId(UUID ownerId);
}
```

JDBC with prepared SQL statements is an equally valid approach. Choose JPA **or** JDBC based on the assignment and team skill; do not implement duplicate persistence strategies without reason.

### 7.4 Storage abstraction

```java
public interface StorageService {
    void store(UUID storageKey, InputStream data);
    InputStream retrieve(UUID storageKey);
    void delete(UUID storageKey);
}
```

Adjust exception declarations, stream ownership, temporary writes, and lifecycle semantics in the real implementation. The abstraction lets you migrate from local disk to S3-compatible storage later.

### 7.5 Suggested technology choices

| Concern | Suggested choice |
|---|---|
| Language/runtime | Java (supported LTS release) |
| HTTP framework | Spring Boot |
| Database | PostgreSQL |
| Persistence | Spring Data JPA or JDBC |
| Authentication | Secure server-side sessions; Spring Security if using Spring |
| Password hash | Argon2id using a trusted library |
| Migration | Flyway |
| File bytes | Persistent local directory first |
| Testing | JUnit + integration testing; HTTP client/Postman/Bruno |
| Operations | Docker and persistent volume, optional in local development |

If the university explicitly requires Java sockets, do not assume the team may switch to Spring Boot. Adapt HTTP/network implementation to that constraint while retaining the same logical components.

## 8. Backend security requirements

| ID | Control | Priority |
|---|---|---|
| SEC-01 | Hash passwords with a modern adaptive algorithm, never plaintext | P0 |
| SEC-02 | Protect login/session creation and logout | P0 |
| SEC-03 | Authenticate all protected requests | P0 |
| SEC-04 | Authorize access to each file and folder by server-side user identity | P0 |
| SEC-05 | Use parameterized SQL / ORM bind parameters | P0 |
| SEC-06 | Validate file names and uploads; cap file size | P0 |
| SEC-07 | Prevent path traversal, symlink escapes, and unsafe file execution | P0 |
| SEC-08 | Keep bytes out of publicly accessible static directories | P0 |
| SEC-09 | Return safe errors without leaking internal paths/secrets | P0 |
| SEC-10 | Protect session cookies, CSRF (when applicable), and cross-origin access | P0 |
| SEC-11 | Use HTTPS for any non-isolated deployment | P0 |
| SEC-12 | Handle disk exhaustion, concurrent mutations, and partial uploads | P0 |
| SEC-13 | Avoid exposing private metadata in logs and API responses | P0 |
| SEC-14 | Rate-limit sensitive operations (especially login and upload) | Production |
| SEC-15 | Encrypt at rest, malware scanning, audit logs, security monitoring | Production/context-based |
| SEC-16 | Update vulnerable dependencies and manage secrets outside source control | Production |

**Authorization example:** `GET /api/files/<someone-elses-file-id>/download` must not succeed just because the requester is logged in. Every query/service operation must check owner or future granted permissions.

**Input rules:** Validate constraints in Java and SQL; use a safe representation of original names, disallow ambiguous path separators/control characters, and enforce a maximum name length.

**Do not trust frontend validation:** all security restrictions must be enforced server-side.

## 9. Non-functional and operational requirements

| Area | Requirement / suggested acceptance criterion |
|---|---|
| Persistence | Completed uploads survive restart and are retrievable |
| Integrity | Downloaded bytes equal uploaded bytes; compare SHA-256 in tests |
| Multi-user isolation | No user sees or modifies another user's private files/folders |
| Concurrency | Simultaneous uploads do not overwrite/corrupt data |
| Failure handling | Failed uploads and interrupted deletion leave recoverable state |
| Performance | Define load-specific measurable response targets; not universal |
| Availability | Define operating period or uptime objective appropriate to deployment |
| Scalability | Keep storage access behind an abstraction to allow future changes |
| Compatibility | Document supported frontend browsers and HTTP/API behavior |
| Maintainability | Separate controller/service/repository/storage code; version schema |
| Observability | Log errors/health without sensitive data; track storage failures |
| Backup/recovery | Production deployment must define backups, retention, and restore tests |
| Accessibility | Primarily frontend requirement; define target if applicable |

**Example targets only (not mandatory standards):** maximum file size 100 MB, 20 concurrent users, 50 GB storage capacity, and metadata request p95 under 500 ms at specified test load. Set the actual limits from course requirements and available hardware.

**Deployment/privacy considerations:** production environments processing personal data may have legal obligations (e.g., LGPD in Brazil), including transparency, security, retention, and data-subject rights. Confirm actual applicability before treating any specific compliance feature as mandatory.

## 10. Acceptance tests (required)

| Test ID | Scenario | Expected result |
|---|---|---|
| TEST-01 | Register user | User exists in database, hash stored, plaintext absent |
| TEST-02 | Register same email with different case | Duplicate rejected according to email policy |
| TEST-03 | Login with correct password | Valid authenticated session created |
| TEST-04 | Login with incorrect password | Rejected without revealing account details |
| TEST-05 | Logout then access protected route | Request rejected |
| TEST-06 | Upload PDF | Bytes written, ready metadata created |
| TEST-07 | Restart backend | Previously uploaded ready file still downloads |
| TEST-08 | Download uploaded file | Same content; original/downloaded SHA-256 matches |
| TEST-09 | List files as user A | Only A's files returned |
| TEST-10 | User B guesses A's file ID | Download/rename/delete denied (or not found) |
| TEST-11 | Upload above max size | HTTP 413 or documented error; no ready file |
| TEST-12 | Upload filename `../../server/config.properties` | Cannot escape storage root or overwrite server files |
| TEST-13 | Interrupt upload/crash | No partial object exposed as ready; cleanup works |
| TEST-14 | Delete file | File inaccessible; physical bytes eventually removed |
| TEST-15 | Request nonexistent file ID | Correct error, no internal path disclosure |
| TEST-16 | Create nested folders | Parent/child relationships persisted correctly |
| TEST-17 | Upload into other user's folder | Rejected |
| TEST-18 | Move folder under its descendant | Rejected (no cycle) |
| TEST-19 | Delete nonempty folder | Documented policy enforced consistently |
| TEST-20 | Concurrent uploads | Distinct files/keys, no corruption |
| TEST-21 | Disk-full or storage failure | Error reported; no false successful upload |
| TEST-22 | Invalid SQL-like filename/search input | Treated as data; no injection |

Useful integrity command:

```bash
sha256sum original.pdf
sha256sum downloaded.pdf
```

The hashes must match for an unchanged file. For stronger automation, assert this in integration tests rather than relying exclusively on manual comparison.

## 11. Development order and deliverables

### Phase 1 — Bootstrap and persistence

- [ ] Initialize Java backend project and PostgreSQL development instance.
- [ ] Configure environment-based secrets and DB credentials (do not commit passwords).
- [ ] Add Flyway and apply migration for `users`, `folders`, and `files`.
- [ ] Implement repository/data-access layer; test basic inserts/selects.
- [ ] Set up persistent private storage root and configuration.

**Deliverable:** backend starts, DB migration succeeds, persistent disk directory is configured.

### Phase 2 — Authentication and authorization

- [ ] Register/provision users and hash passwords.
- [ ] Implement login, logout, and request authentication.
- [ ] Enforce per-user ownership in services and repository queries.
- [ ] Add unauthorized-access tests using two independent users.

**Deliverable:** isolated authenticated accounts; protected endpoints reject unauthorized access.

### Phase 3 — Core files

- [ ] Implement `POST /api/files` with validation and pending/ready lifecycle.
- [ ] Implement list, metadata retrieval, and streamed download.
- [ ] Implement delete and cleanup/reconciliation logic.
- [ ] Add persistence, checksum, interrupted-upload, and concurrency tests.

**Deliverable:** users can upload, list, download, and delete their private files reliably.

### Phase 4 — Drive-like organization

- [ ] Implement nested folder create/list/rename/delete.
- [ ] Implement file rename and move.
- [ ] Implement folder move with anti-cycle checks.
- [ ] Define and test nonempty-folder deletion behavior.

**Deliverable:** file browser can show nested, owner-isolated directories.

### Phase 5 — Hardening and release readiness

- [ ] Add consistent API error format and logging.
- [ ] Enforce resource limits and handle storage/database outages gracefully.
- [ ] Add integration tests, use HTTPS in deployment, secure sessions/CORS/CSRF.
- [ ] Document environment setup, migrations, configuration, and API contract.
- [ ] Optionally deploy in Docker with persistent Postgres/data volumes.

**Deliverable:** a testable, documented MVP backend.

### 11.1 Minimal README/API documentation deliverables

- How to run PostgreSQL and backend locally.
- Configuration variables (DB host/name/user/password; storage root; max file size; session settings).
- How to run database migrations.
- HTTP endpoints and sample request/response formats.
- Authentication/cookie/CSRF model.
- File/folder ownership and deletion policies.
- How to run unit and integration tests.
- Known limitations (no sharing, versions, sync, previews).

## 12. Final implementation checklist

### SQL / data layer
- [ ] `users` table with unique email and hashed-password column
- [ ] `files` table with owner, folder, metadata, unique storage key, and status
- [ ] `folders` table with parent/owner and same-owner constraints
- [ ] Foreign keys, checks, and useful owner/folder indexes
- [ ] Database migrations and parameterized queries
- [ ] Correct handling of root (`NULL`) folder IDs
- [ ] Safe transaction/concurrency strategy for multi-step changes

### Java backend
- [ ] HTTP API and consistent validation/error responses
- [ ] Secure registration/provisioning, login, logout, session verification
- [ ] Ownership/authorization checks in all file/folder operations
- [ ] Upload, list, metadata, stream download, delete
- [ ] Rename and move files; create/list/rename/move/delete folders
- [ ] Persistent private storage and opaque internal keys
- [ ] Partial-upload and interrupted-deletion recovery
- [ ] File size limits and insufficient-space handling
- [ ] No direct filesystem path constructed from untrusted filename
- [ ] Tests covering multi-user isolation, persistence, integrity, errors, and concurrency

### Production deployment (if applicable)
- [ ] HTTPS, secure cookies/CSRF/CORS, secret management
- [ ] Database and storage backups with restoration procedure
- [ ] Rate limiting, basic monitoring and security logs
- [ ] Dependency updates and incident/error response procedures
- [ ] Review applicable privacy/data-protection obligations

## 13. Decisions to record before coding

The coding team/CLI should explicitly settle these assumptions; do not silently invent requirements:

1. Must the solution use **Spring Boot, JDBC/JPA, or raw Java sockets**? (University rules may constrain this.)
2. Is **user self-registration** allowed, or are accounts provisioned by an administrator?
3. Which **authentication/session strategy** is required?
4. What are **max file size, total storage capacity, and supported file types**?
5. Are folders, rename, and move mandatory according to the assignment? (Included in this Drive-like MVP.)
6. Should deleting a nonempty folder be rejected or recursive?
7. Are duplicate names allowed in one folder?
8. Will files be stored on a **local persistent disk** or a cloud object store?
9. Must the API support cross-origin access from a separately hosted frontend?
10. Are **sharing, quotas, file versioning, or recoverable deletion** explicitly required by the course specification?

**Implementation directive:** Build P0 first, then P1. Do not implement P3 extras until basic security and file integrity pass tests. Preserve this separation of concerns and all ownership checks even when adapting to an existing Java codebase.

## 14. References for further verification

- OWASP Application Security Verification Standard: https://asvs.owasp.org/
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP File Upload Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html
- PostgreSQL documentation: https://www.postgresql.org/docs/
- Spring Boot documentation: https://docs.spring.io/spring-boot/

