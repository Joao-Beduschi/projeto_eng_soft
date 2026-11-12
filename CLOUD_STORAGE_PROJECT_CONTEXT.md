# Cloud Storage / File-Sharing System — Complete Project Context

> **Purpose:** Self-contained context for a coding CLI agent or developer taking over this project.
>
> **Status date:** 2026-11-12. **Language:** English, with original Portuguese requirement IDs and Java identifiers preserved.
>
> **Priority of evidence:** (1) the academic requirements PDF for course scope; (2) `cloud_storage_backend_requirements_java_sql.md` for the later user-requested backend scope; (3) checked-out source and independently reproduced results; (4) proposed architecture. **Proposals are not implemented features.**

## 0. Instructions for a coding CLI agent

1. Read this file completely before changing code. The exact currently supplied Java source is embedded at the end.
2. The **mandatory deliverable** is a simple **file-sharing/cloud storage application**: upload an existing local file to a central server; persist it; list server-side files; download the original bytes on another computer. This is **not** a collaborative Google Docs editor.
3. The current implementation is Java: a Spring Boot HTTP API with PostgreSQL and a same-origin browser interface, plus root Java HTTP client/launcher entrypoints. Preserve this stack unless the human explicitly requests a change.
4. The later backend requirements and the user's follow-up requests expand scope beyond the academic PDF minimum to include accounts, folders, SQL metadata, Docker PostgreSQL and a browser UI.
5. Treat **path traversal and arbitrary-file access as critical**. Do not expose the current server to the public internet. Fix path handling before broad deployment or untrusted clients.
6. Preserve uploaded file bytes exactly through HTTP streaming and private server storage; never use text conversion or Base64 as the primary file representation.
7. Distinguish **current facts** from **design suggestions**; record whether code was compiled, endpoints were checked, or full workflows/tests were run.
8. When changing API or storage behavior, keep the backend, root Java client and browser interface aligned; add appropriate regression coverage.
9. Avoid silently expanding scope. The PDF is authoritative about must-haves and non-required features.
10. If this file is moved into a repository, compare its embedded source snapshot to the *actual* Java files: the repository may contain newer revisions. Treat the checked-out code as authoritative for its current state, and update this context accordingly.

## 0.1 Later backend request and implementation status (2026-11-12)

The user later requested a backend following `cloud_storage_backend_requirements_java_sql.md`. This explicitly expands the implementation task beyond the academic PDF's minimum and authorizes a Java SQL-backed HTTP API with accounts, ownership, folders, and file management. The user also requested integration with the root Java files and a web interface: `DocsServer.java` launches Spring Boot, `DocsClient.java` is an interactive HTTP client, and Spring Boot serves the browser app from the same origin. The old unauthenticated TCP text protocol has been removed from these entrypoints.

An initial backend module now exists under `backend/`:

- Spring Boot 4.1.2 / Java 21 Maven module using JDBC, PostgreSQL and Flyway.
- Registration, login, logout, PBKDF2 password hashes, in-memory server sessions, CSRF tokens and configurable credentialed CORS.
- Owner-scoped file upload/list/metadata/download/delete and rename/move endpoints; bytes stream to private UUID-keyed local storage, with size checks, SHA-256, pending/ready/deleting states, atomic writes and startup reconciliation.
- Nested folder create/list/contents/rename/move/delete endpoints; nonempty deletion is rejected and cycles are checked while moves are serialized per owner.
- Same-origin browser UI at `/` for registration/login, folder navigation, file upload/download/rename/move/delete, folder creation/rename/move/delete, search, and drag-and-drop uploads. It uses the backend session cookie and CSRF token endpoints.
- Integration test sources use Testcontainers and cover authentication/CSRF, byte integrity, owner isolation, incomplete uploads, folder rules and concurrent uploads.

**Verification status (2026-11-12):** OpenJDK 21 and project-local Maven 3.10.0 are available. Docker Snap 29.8 and Compose 5.5.1 are active and accessible. The backend compiled and is running with Spring Boot on port 8080 against the Compose `postgres:16-alpine` service at `127.0.0.1:55432`; the named volume is `backend_postgres_data`. Flyway applied V1. The root page, `/assets/app.js`, `/assets/styles.css`, and `GET /api/auth/csrf` returned HTTP 200; unauthenticated `GET /api/users/me` returned HTTP 401. Integration tests and complete browser account/upload workflows have not been run. The former workspace PostgreSQL cluster is stopped; its database had zero users, folders and files before switching. Maven and its cache, plus the stopped local PostgreSQL cluster, live under ignored `backend/.tools/` and `backend/.local/`; credentials are in the ignored, mode-600 root `.env`; uploaded data is under ignored `storage/`. Do not copy credentials into this context document.

The integrated root Java entrypoints were compiled with Java 21 into `backend/target/cli`; their interactive API workflow has not been exercised, and no test suite was run for this integration. The academic requirements/status tables and TCP protocol analysis below describe the **pre-integration socket demo baseline** and are historical; the current backend, root Java entrypoints and browser UI use the HTTP API.

## 1. Original artifacts and provenance

| Source | What it contains | Status |
|---|---|---|
| `Doc_Req_Eng_Software.pdf` | Academic requirements specification, 15 pages, titled **Documento de Requisitos — Sistema de Compartilhamento de Arquivos** | Authoritative requirements |
| `cloud_storage_backend_requirements_java_sql.md` | User-requested Java SQL HTTP backend specification | Backend implementation scope |
| `DocsServer.java` | Java launcher for the Spring Boot backend | Integrated with project-local Maven and `.env`; compiled |
| `DocsClient.java` | Interactive Java HTTP client for authenticated backend operations | Integrated with login/register and file/folder management; compiled, API workflow not run |
| `backend/` | Spring Boot HTTP backend, same-origin web UI, SQL migration, local storage, API and integration test sources | Implemented, compiled and running against Docker Compose PostgreSQL; integration tests not run |
| This document | Consolidated analysis, architecture proposal, backlog, tests and exact Java source | Context and recommendations; not an implementation |

Academic context: **Tópicos em Engenharia de Software**, UFSCar, document dated **25 September 2026**. The PDF identifies Prof. Dr. Delano Medeiros Beder as the responsible professor. The academic specification defines **16 functional requirements (RF001–RF016)** and **8 non-functional requirements (RNF001–RNF008)**.

### Source-of-truth boundaries

- The specification states **what** is required; it does not prescribe Java, a database, HTTP, a cloud provider, or a web-only GUI.
- The root Java files now launch and use the HTTP backend; the browser UI is served at `/`; historical TCP observations below are explicitly labeled as the previous implementation.
- Test results below were reproduced independently on 2026-11-12 in a temporary directory with OpenJDK 21.
- Designs labeled **Proposed** are options for future implementation, not descriptions of the existing code.

## 2. Project mission, scope and success criteria

### 2.1 Goal

Build a basic centralized file storage and transfer application similar in its *core behavior* to Google Drive/Dropbox/OneDrive, **without attempting a full clone**. A file uploaded from **Computer A** must be stored on a server, appear in the listing accessible from **Computer B**, and be downloadable to Computer B without any byte changes.

```text
Computer A -> [UPLOAD] -> Server / persistent storage
Computer B -> [LIST]   -> See available files
Computer B -> [DOWNLOAD] -> Original file bytes saved locally
```

Core flow from the requirements PDF (Section 6.1):

```text
Upload -> Storage -> Listing -> Download
```

### 2.2 Acceptance scenario from PDF Section 7 (p. 14)

1. User at Computer A selects a local `arquivo.pdf`.
2. Computer A sends the file to the server.
3. Server persists the file.
4. Computer B connects to the application.
5. Computer B asks for a list of stored files.
6. `arquivo.pdf` appears in that list.
7. Computer B selects it and downloads it locally.
8. The downloaded bytes must match the uploaded bytes exactly.

**Concrete test assertion:**

```text
SHA256(original on A) == SHA256(stored on server) == SHA256(downloaded on B)
```

SHA-256 is a **proposed verification technique**; the PDF requires equal content but does not mandate a hashing algorithm.

### 2.3 Scope explicitly excluded from the first version (PDF Section 5)

- In-app viewing of PDFs, images, videos and office documents.
- Real-time synchronization between machines; a manual refresh/new request is sufficient.
- Editing stored files inside the application, including collaborative editing.
- Per-file public/private sharing links.
- Folder hierarchy or virtual directories.
- User registration/login/authentication and access-role management.

The PDF also places **file deletion**, richer metadata, authentication, folders, links and previews under *optional future features* (Section 6.2). Do not mark these as required.

### 2.4 What “cloud” means for this assignment

A server reachable by multiple computers on a LAN can demonstrate the required client-server file transfer. The requirements do **not** demand a commercial cloud host or public internet exposure. A deployed internet-facing service would require a stronger security model, persistent volume, TLS and preferably authentication; those are **recommendations for public deployment**, not PDF requirements for the minimum submission.

## 3. Functional requirements — complete traceability

Status key: **Missing** = behavior does not exist; **Partial** = low-level primitive exists or analogous text behavior exists, but the requirement is not fully met; **Compatible** = architecture allows it but no complete end-user operation; **Not met** = present behavior conflicts with the requirement.

| ID | Requirement from PDF (faithful summary) | Current Java status | Required implementation / verification |
|---|---|---|---|
| **RF001** | User selects a file already stored on their computer | **Missing** | Prompt for local path or file chooser; check it is a readable regular file |
| **RF002** | User sends selected file to server | **Missing** | Binary `UPLOAD` request streaming file bytes |
| **RF003** | Server stores uploaded file for access from other computers | **Partial** | Server currently saves hard-coded text; persist arbitrary uploaded bytes |
| **RF004** | Tell user successful upload completed | **Partial** | Current `SALVAR` returns confirmation; implement upload-specific confirmation only after durable/complete storage |
| **RF005** | Inform user of upload error | **Partial** | Some I/O errors log server-side; add structured upload failure responses and client-side display |
| **RF006** | Show a list of files stored on server | **Missing** | `LIST` command |
| **RF007** | Show at least the filename for each listed file | **Missing** | Return original display name for every entry |
| **RF008** | Same list accessible from different computers | **Missing** | Any connected client can `LIST` the shared storage |
| **RF009** | User can refresh list by refreshing page or making new server request | **Missing** | Explicit `LIST`/Refresh menu operation; real-time push unnecessary |
| **RF010** | User selects a listed file to download | **Missing** | Select by stable file ID or unambiguous index |
| **RF011** | Selected file transferred to user's computer | **Missing** | Binary `DOWNLOAD` response written to local file |
| **RF012** | Downloaded file content matches original | **Not met** | Binary byte-preserving transfer; checksum regression tests |
| **RF013** | Original filename preserved on download whenever possible | **Missing** | Return `originalFilename` as safe suggested local name |
| **RF014** | Inform user if requested file does not exist | **Not met** | `DOWNLOAD` returns `NOT_FOUND`; current `ABRIR` *creates* missing documents |
| **RF015** | Stored files accessible from computers connected to application | **Partial** | Multiple TCP clients can connect/read text; add arbitrary-file access |
| **RF016** | File-list updates need not be real-time; manual refresh allowed | **Compatible** | Explicit re-query is sufficient; do not build synchronization engine |

The PDF's minimum required file-list field is **name**. Displaying size, type and upload timestamp is **optional**.

## 4. Non-functional requirements — complete traceability

| ID | Requirement from PDF | Current status | Proposed way to satisfy / verify |
|---|---|---|---|
| **RNF001** | Simple, easy-to-use interface | **Not demonstrated** | CLI menu with clearly labeled actions or minimal web interface |
| **RNF002** | Upload/download actions easy to identify | **Missing** | User-visible action names, selection prompts and success/error messages |
| **RNF003** | Common navigation responds within **3 seconds**, excluding transfer duration | **Not measured** | Time file-list/menu interactions on specified hardware/network; do not assume guaranteed 3 s under every condition |
| **RNF004** | Original uploaded content preserved | **Not met for arbitrary files** | Write original bytes unchanged; no newline transformations |
| **RNF005** | Downloaded content equals originally uploaded content | **Not met for arbitrary files** | Stream exact bytes and compare SHA-256 |
| **RNF006** | Store different file types (PDF, text, office, images, video, ZIP, others) | **Not met** | Byte-stream transfer, format-agnostic storage |
| **RNF007** | Uploaded files must not be executed automatically | **Partially addressed** | Current code writes text rather than executing it; new storage system must also never launch uploaded files |
| **RNF008** | Files accessible while server is running | **Partial** | TCP server listens; validate through multi-computer integration test and appropriate persistent storage |

**Important:** RNF003 excludes transfer time from its 3-second navigation target; the PDF does not specify a hard upload/download duration.

## 5. Exact existing code: behavior, architecture and dependencies

### 5.1 Original demo and current repository layout

The original TCP demo supplied **two Java source files**. They are now integrated with the HTTP backend and browser UI:

```text
<project directory>/
  DocsServer.java           # starts the Spring Boot backend
  DocsClient.java           # interactive Java HTTP client
  backend/
    pom.xml
    src/main/java/com/cloudstorage/...
    src/main/resources/application.properties
    src/main/resources/db/migration/V1__initial_schema.sql
    src/main/resources/static/ # browser interface served by Spring Boot
    src/test/java/com/cloudstorage/CloudStorageIntegrationTest.java
    target/cli/              # compiled root Java entrypoints; ignored by Git
```

The former root TCP server and line-based protocol have been replaced. `DocsServer` invokes the backend's Maven `spring-boot:run` goal and loads the ignored root `.env`; `DocsClient` uses JDK `HttpClient`, session cookies, and CSRF tokens to call the backend API. The backend owns authentication, SQL metadata, file storage and authorization. Spring Boot also serves the browser interface from its static resources, using the same-origin session and CSRF flow.

The two root entrypoints rely only on the **Java standard library**. The backend remains a separate Maven module using Spring Boot, PostgreSQL, JDBC and Flyway.

### 5.2 Historical TCP server snapshot (pre-integration)

Key points:

- Class `DocsServer`, `main()`.
- `PORTA = 5000`.
- `PASTA_ARQUIVOS = "./documentos_servidor"` — relative to the process's working directory.
- Creates directory with `new File(PASTA_ARQUIVOS).mkdirs()`; does not check the result.
- Starts `new ServerSocket(PORTA)`; continuously calls blocking `accept()`.
- Starts `new Thread(new ManipuladorDeCliente(clientSocket)).start()` per connection.
- Inner class `ManipuladorDeCliente implements Runnable`.
- Opens `BufferedReader` and `PrintWriter` with UTF-8 around each client socket.
- Reads one textual command per line with `in.readLine()`.
- Parses with `payloadRecebido.split("\\|", 3)` into `acao`, `nomeArquivo`, `conteudo`.
- Constructs `Paths.get(PASTA_ARQUIVOS, nomeArquivo)` directly from the client-supplied name — **unsafe**.
- `ABRIR`: reads whole existing file as UTF-8 text, changes newline representation, sends `OK|...`; if missing, **creates empty document** and returns `OK|`.
- `SALVAR`: replaces literal `\\n` with actual newline, writes whole text via `Files.writeString()`, truncating/replacing existing content; `synchronized (DocsServer.class)` serializes only the save block.
- Other action: returns `ERRO|Ação desconhecida: ...`.
- Closes socket upon disconnect; prints certain exceptions to console.

**Concurrency distinction:** one thread per client allows simultaneous connections; `synchronized` does **not** prevent two editors from overwriting each other's work (last-save-wins). It also does not synchronize `ABRIR` with `SALVAR`.

### 5.3 Historical TCP client snapshot (pre-integration)

- `IP_SERVIDOR = "localhost"` and `PORTA_SERVIDOR = 5000` are compile-time constants.
- Opens a TCP socket and matching UTF-8 text reader/writer.
- Uses hard-coded `nomeDoc = "projeto_tcc.txt"`.
- Sends `ABRIR|projeto_tcc.txt|`.
- Sends hard-coded text with two lines, serialized using `.replace("\n", "\\n")`.
- Sends `SALVAR|projeto_tcc.txt|<serialized text>`.
- Sends `ABRIR|projeto_tcc.txt|` again and prints returned text to terminal.
- No interactive menu, local file reading, actual arbitrary-file upload, server file list or local file download.
- Client closes connection when its scripted sequence ends.

### 5.4 Historical TCP protocol (retired)

```text
Transport: TCP
Host (client): localhost
Port: 5000
Encoding: UTF-8 text
Framing: one line per request/response
Request grammar: ACAO|NOME_ARQUIVO|CONTEUDO
Response grammar: OK|... or ERRO|...
Actions: ABRIR, SALVAR
```

Examples:

```text
Client -> Server: ABRIR|projeto_tcc.txt|
Server -> Client: OK|
Client -> Server: SALVAR|projeto_tcc.txt|Título do Documento\nEste texto foi enviado via TCP para o servidor.
Server -> Client: OK|Documento 'projeto_tcc.txt' salvo com sucesso no servidor!
Client -> Server: ABRIR|projeto_tcc.txt|
Server -> Client: OK|Título do Documento\nEste texto foi enviado via TCP para o servidor.
```

This line-based text protocol is no longer used by the current root Java files. Its `BufferedReader`/`PrintWriter` framing and newline substitutions were unsuitable for arbitrary binary files.

### 5.5 Current root Java integration

- `DocsServer` checks `/api/auth/csrf`; if the API is already responding it reports that and exits. Otherwise it loads environment values from the project `.env` and starts `spring-boot:run`, preferring `backend/.tools/apache-maven-3.10.0/bin/mvn` when present.
- `DocsClient` uses a Java `HttpClient` with a `CookieManager`. It registers or logs in, refreshes and sends the CSRF token, and offers file upload/list/download/rename/move/delete and folder create/list/contents/rename/move/delete operations.
- Uploads use multipart HTTP with the original file bytes; downloads stream response bytes to the destination path.
- `CLOUD_STORAGE_API_URL` can override the default API origin (`http://localhost:8080`).
- The Java entrypoints compiled successfully with Java 21. Their interactive API workflow has not been exercised; the integration-test suite was not run.
- The browser interface lives in `backend/src/main/resources/static/` and supports registration/login, nested folders, search, file upload/download, rename/move/delete, and drag-and-drop uploads.

### 5.6 How to compile and run the integrated Java entrypoints

Requirements: Java 21, the configured Compose PostgreSQL service, and the project environment file or exported backend variables. Run from the project root.

Terminal 1:

```bash
javac -encoding UTF-8 -d backend/target/cli DocsServer.java DocsClient.java
java -cp backend/target/cli DocsServer
```

`DocsServer` starts the backend and keeps its logs in the terminal. In a second terminal:

```bash
java -cp backend/target/cli DocsClient
```

The client prompts for an account and provides file operations. Pass an API origin as an argument to connect to another host, for example `java -cp backend/target/cli DocsClient http://192.168.1.15:8080`.

To use the browser interface, leave the backend running and open `http://localhost:8080/` in a browser.

## 6. Historical test results for the original TCP demo (2026-11-12)

Tests were conducted in an **isolated temporary directory** on OpenJDK **21.0.12.1** with compiled copies of both supplied Java files. These observations concern the **original source**, before any fixes. Do **not** infer that suggested features were implemented or tested.

| Test | Input / method | Observed result | Outcome |
|---|---|---|---|
| Compile | `javac DocsServer.java DocsClient.java` | Exit code 0, no errors | **PASS** |
| Original demo | Start `java DocsServer`; run `java DocsClient` | Client exits 0; server file contains two expected text lines; client prints them | **PASS** |
| File listing command | Send `LISTAR|unused|` | `ERRO|Ação desconhecida: LISTAR` | **NOT IMPLEMENTED** |
| Literal backslash-`n` preservation | Send text `text\\nnot-newline` in `SALVAR` | Stored bytes are `text` + actual LF + `not-newline` | **DATA MODIFIED** |
| Directory traversal | Send `SALVAR|../outside.txt|outside` | Server writes `outside.txt` one directory **outside** `documentos_servidor/` | **CRITICAL VULNERABILITY** |

The traversal test was limited to the temporary test directory and did not touch unrelated user files.

The table above reports behavior of the pre-integration TCP source only. Backend tests and interactive client/API integration tests have not been run.

The previous discussion also described a lost-update scenario, which is logically possible from the code: two clients read the same old version and then save sequentially; the later save overwrites the earlier one. Treat it as a **design/concurrency risk** rather than a requirement for the cloud-storage MVP (document editing is out of scope).

## 7. Historical defects in the original TCP demo

The findings in this section apply to the pre-integration `DocsServer` implementation described above. The current root `DocsServer.java` is only a backend launcher and accepts no file paths or client payloads; the HTTP backend stores bytes under generated UUID keys.

### 7.1 Historical — Path traversal and unsafe filenames

**Where in the old TCP snapshot:** `DocsServer.java` approximately line 73.

```java
Path caminhoArquivo = Paths.get(PASTA_ARQUIVOS, nomeArquivo);
```

The filename comes directly from the network. `../outside.txt` escapes the intended directory (confirmed in testing). Depending on permissions, other paths may be read or overwritten. The comment describing this as a safe path is wrong.

**Recommended fix:** choose a canonical storage root and never use a client-supplied path for the physical storage destination. Generate an internal ID (e.g., UUID) and physical name; preserve the original filename as **display metadata**, after validating/normalizing it. On download, resolve by internal ID and verify that it points to an actual regular file within storage. Do not follow user-controlled symlinks. Restrict file listing/access to the storage root. Test `../`, absolute paths, separator variants and symlink cases.

### 7.2 HIGH — Wrong file transfer representation

**Where:** text reader/writer and `ABRIR`/`SALVAR`.

- Data is decoded/encoded as UTF-8, which does not preserve arbitrary byte sequences.
- Embedded newlines are serialized manually and **not reversibly**.
- `ABRIR` removes carriage-return characters (`\r`), potentially changing text bytes.
- `Files.readString` reads a whole file into memory; large files are problematic.
- There is no encoded file length, so the protocol cannot delimit arbitrary binary payloads.

**Recommended fix:** streaming binary transfer with explicit lengths and exact byte-count checks.

### 7.3 HIGH — Incomplete / interrupted writes and overwrite semantics

- `Files.writeString()` overwrites existing data in place, with no versioning or atomic replace guarantee.
- No partial-upload cleanup or transfer-completion validation.
- No filename collision policy. Two uploads with the same display name could collide if physical names reuse it.
- Error reporting is insufficient to distinguish complete committed uploads from partially written files.

**Recommended fix:** write to a temporary file under the storage root, count bytes received, optionally hash while streaming, then commit/move to a unique stored path and only advertise the file after commit. Clean temporary files on failure. Avoid overwriting unrelated existing files. Define a metadata/storage-consistency policy.

### 7.4 HIGH — Lack of access control for public deployment

The PDF does not require accounts/login for MVP. Therefore the design assumes a **shared file space** with one generic user role. That is acceptable in a controlled demonstration, **not** on a publicly exposed server with private files.

**Recommended operating assumption:** trusted local network or otherwise access-restricted test environment. If internet access becomes a requirement, introduce authentication/authorization, TLS, quotas, rate limiting, observability and storage limits **before** public exposure.

### 7.5 MEDIUM — Unbounded client threads and no timeouts

- One new Java thread per accepted socket without a maximum client count.
- `readLine()` can block indefinitely for a slow/idle client.
- No explicit socket timeout or command-size limit.
- `PrintWriter` can silently record I/O errors unless `checkError()` is inspected.

**Recommended fix:** bounded `ExecutorService`, socket read timeouts, request validation, bounded filename/metadata lengths, configurable file-size and storage caps, explicit I/O errors.

### 7.6 MEDIUM — Incorrect missing-file behavior

`ABRIR` currently creates an empty file if no document exists. For the cloud-storage MVP, a download of a nonexistent file must instead return an error (RF014). Creating a file can only occur through a deliberate upload/create operation.

### 7.7 MEDIUM — Last-save-wins collaborative editing

Concurrent clients can independently load the same text and overwrite one another. Synchronizing the server's write section does not implement version conflict detection. The PDF expressly excludes in-app editing, so **do not prioritize collaborative editor logic** over core storage functionality.

### 7.8 LOW / scope risk — Unnecessary framework rewrite

An earlier solution proposed Python + FastAPI, HTML/CSS/JS and optionally SQLite. This is one possible web architecture, but it is **not present in the uploaded code** and **not mandated by the PDF**. The following TCP architecture proposal predates the user's later request for a Java SQL-backed HTTP API and should be treated as historical, not as the current implementation plan.

## 8. Historical proposal — Java TCP MVP

> **DESIGN PROPOSAL, not existing implementation.** Optimize for correctness, simplicity and requirements coverage.

```text
    Computer A / Java CLI                     Computer B / Java CLI
    ---------------------                     ---------------------
    Select local file                         LIST -> select ID
    UPLOAD bytes                              DOWNLOAD bytes
            |                                         ^
            |              TCP port 5000              |
            +------------------+----------------------+ 
                               |
                        +------v-------+
                        | Java server  |
                        | DocsServer   |
                        +------+-------+
                               |
                   bounded client handlers
                               |
                     command dispatcher
                      /       |        \
                 UPLOAD      LIST     DOWNLOAD
                      \       |        /
                       FileManager
                       /         \
                Metadata index   Storage directory
                ID, name, size   internal-ID files
```

### 8.1 Minimal technology choice

| Layer | MVP choice | Mandatory? | Notes |
|---|---|---|---|
| Language | Java (existing) | No, but preferred for continuation | Standard library is enough |
| Server transport | TCP sockets (existing) | No technology mandated | Fine for CLI academic project |
| Client UI | Terminal menu | UI needed; specific format not mandated | Simple upload/list/download prompts |
| Stored data | Local persistent filesystem | Persistent storage required | Use server-owned files/IDs |
| Metadata | In-memory scan or small persistent index | Not mandated | SQLite optional, not essential |
| Hash testing | SHA-256 | Not mandated | Useful round-trip proof |
| Web UI / HTTP | Later optional | Not mandatory | Can be separate frontend/API layer |
| Docker/cloud deployment | Later optional | Not mandatory | Persistent volume required if used |

### 8.2 Suggested responsibilities

```text
CloudStorage/                    # PROPOSED, not supplied
├── src/
│   ├── server/
│   │   ├── DocsServer.java       # listen, accept, executor lifecycle
│   │   ├── ClientHandler.java    # protocol command dispatch
│   │   └── FileManager.java      # secure storage/list/lookup, metadata
│   ├── client/
│   │   ├── DocsClient.java       # socket calls + binary streams
│   │   └── ClientMenu.java       # CLI interactions
│   └── protocol/
│       └── Protocol.java         # command codes, framing, status
├── storage/                     # created at runtime; gitignored
├── test/                        # protocol/integration tests
└── README.md
```

Do not assume this directory tree already exists. Refactoring all classes/packages is optional; functional correctness matters more than file count.

### 8.3 CLI user experience (proposed)

```text
========== CLOUD STORAGE ==========
Server: 192.168.1.15:5000
1. Upload file
2. List available files
3. Download file
4. Exit
Option: 1
Local path: /home/user/Documents/arquivo.pdf
Upload complete: arquivo.pdf

Option: 2
ID                                    Name
52d6b9...                              arquivo.pdf

Option: 3
File ID: 52d6b9...
Destination directory: /home/user/Downloads
Download complete: /home/user/Downloads/arquivo.pdf
```

Local paths should be supplied by the user or a chooser; never hardcode `projeto_tcc.txt` as the only document. Client should report errors without terminating the entire menu unnecessarily.

## 9. Proposed binary TCP protocol v1 (illustrative contract)

> **PROPOSAL ONLY.** The current source does not implement this. All protocol constants below are examples; once selected, document and test them in one shared `Protocol.java` and use the same version on both ends.

### 9.1 Principles

- Binary-safe framing via `DataInputStream` and `DataOutputStream` (or `InputStream`/`OutputStream` with explicitly encoded headers).
- Persistent TCP connection is optional; a straightforward first implementation can use one request per connection, or a documented repeat-request loop.
- Use a fixed request header; include a protocol version to detect incompatible clients.
- Transfer exactly the declared `long` file size (checked range). Reject negative sizes, oversized files, truncated streams and invalid filenames.
- Stream data in chunks (e.g., 64 KiB); **do not** allocate a byte array equal to full file size.
- Return status **after** successful complete storage for upload, not merely after reading the first bytes.
- Return a stable file ID and the original display filename in listing. Download by ID, not a path string.
- Never execute, parse or transform uploaded file contents.

### 9.2 One possible request/response layout

The following example uses Java `DataOutputStream` methods. Both sides must follow exactly the same order and types. `writeUTF` uses Java's **modified UTF-8** and a limited encoded length; acceptable for short validated filenames in an all-Java protocol, but use an explicit UTF-8 byte-length encoding if non-Java clients are expected later.

```text
Common request header:
  int   magic = 0x43465331            # ASCII "CFS1"
  byte  protocolVersion = 1
  byte  command                         # 1=UPLOAD, 2=LIST, 3=DOWNLOAD

UPLOAD request payload:
  UTF   originalFilename
  long  fileSizeBytes
  byte[fileSizeBytes] raw file contents

LIST request payload:
  (none)

DOWNLOAD request payload:
  UTF   fileId

Common response header:
  int   magic = 0x43465331
  byte  protocolVersion = 1
  byte  status                          # 0=OK, 1=ERROR

If ERROR:
  UTF   errorCode
  UTF   humanReadableMessage

If UPLOAD OK:
  UTF   fileId

If LIST OK:
  int   count
  repeat count times:
    UTF  fileId
    UTF  originalFilename
    long fileSizeBytes

If DOWNLOAD OK:
  UTF   originalFilename
  long  fileSizeBytes
  byte[fileSizeBytes] raw file contents
```

**Limits:** cap listing count and metadata lengths; validate file size against a configurable maximum. Never trust values sent by a client. On EOF before full payload, abort and delete partial temporary data. For download, confirm complete bytes on client before reporting success. If response bodies can be large, list pagination is a later improvement, not an MVP requirement.

### 9.3 Streaming pseudocode (proposal)

```java
long remaining = expectedSize;
byte[] buffer = new byte[64 * 1024];
while (remaining > 0) {
    int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
    if (n < 0) throw new EOFException("Truncated transfer");
    if (n == 0) continue;
    output.write(buffer, 0, n);
    remaining -= n;
}
output.flush();
```

The snippet illustrates exact-length copying; **additional safeguards** are required for file-size limits, cleanup, metadata commits, timeouts and secure path handling. Java `InputStream.transferTo()` alone is not enough when exact declared length must be enforced in a persistent protocol.

### 9.4 Filename and collision policy (recommended)

- Keep `originalFilename` as user-facing metadata.
- Generate independent physical stored filename, such as UUID with a safe fixed suffix (or content-addressed ID if explicitly designed).
- Never trust client path components or write using an unvalidated `originalFilename`.
- Two distinct uploads both named `report.pdf` must not silently overwrite one another. Return separate IDs.
- Client downloads using ID and gets the original filename as a *suggestion*, but validates local destination and avoids overwriting an existing local file without an explicit policy.
- Resolve storage root and targets securely; disallow symlink escape or unexpected types.

### 9.5 Simple persistence choices (decision not yet made)

**Option A: filesystem-only MVP.** Store each file under a generated ID; keep filename/size metadata in a small sidecar or manifest, with transactional/atomic writes where possible. Ensure metadata survives server restart.

**Option B: SQLite index + filesystem contents.** SQLite tracks `id`, `original_filename`, `stored_filename`, `size_bytes`, `created_at` (last optional). File bytes remain on disk. Extra setup, but enables reliable listing and lookups. If chosen, plan crash-consistency and recovery between database transactions and file moves.

A database is **not a stated requirement** and should not block initial implementation.

## 10. API alternatives (not part of the current Java TCP implementation)

Earlier planning proposed a potential **HTTP REST API** for a web frontend:

| Proposed HTTP endpoint | Purpose |
|---|---|
| `POST /api/files` | Upload using `multipart/form-data` |
| `GET /api/files` | Return file list |
| `GET /api/files/{id}/download` | Download original bytes |

A minimal browser UI could have `Choose File`, `Upload`, a file table with `Download`, and `Refresh`. Python/FastAPI + SQLite or Java HTTP frameworks are optional choices **only if a web version is authorized**. Do not combine an unframed raw TCP protocol and HTTP on the same port; each requires its own defined interface or adapter.

## 11. Development milestones and priority backlog

The milestone ordering favors keeping the application buildable, making a security fix early and delivering the PDF acceptance scenario.

### M0 — Baseline and safe development environment

- [ ] Put the two source files under version control.
- [ ] Add `.gitignore` for `.class`, runtime storage, temporary files and secrets.
- [ ] Confirm JDK version/build commands; record baseline test output.
- [ ] Use a disposable test storage directory; never test untrusted paths in a directory containing valuable files.
- [ ] Preserve source snapshot until new protocol behavior passes tests.

### M1 — Secure filesystem handling

- [ ] Prevent `../`, absolute path, symlink and other path escapes.
- [ ] Decide how internal IDs, original names and physical storage paths map.
- [ ] Add tests proving uploads/downloads cannot reach outside storage.
- [ ] Validate storage root creation and permissions.

### M2 — Binary UPLOAD

- [ ] Add shared protocol definition.
- [ ] Read an existing local file from client using binary streams.
- [ ] Send validated filename and `long` byte size; stream exact bytes.
- [ ] Store in temporary file, verify complete receipt, commit to persistent storage.
- [ ] Only acknowledge success once the file is committed; return structured error otherwise.
- [ ] Handle empty files, duplicates, incomplete transfer and size cap.

### M3 — LIST

- [ ] Return at least original display filenames (and stable IDs).
- [ ] Permit distinct clients to query the same storage.
- [ ] Allow manual refresh/new query.
- [ ] Ensure listing only shows committed readable files.

### M4 — Binary DOWNLOAD

- [ ] Download by safe internal ID; not raw user-supplied path.
- [ ] Return `NOT_FOUND` if absent (do not create a blank file).
- [ ] Return filename + exact byte count + raw bytes.
- [ ] Save file on client with a safe overwrite policy.
- [ ] Detect truncated downloads and clean partial local files.

### M5 — User interface and operational robustness

- [ ] Add terminal menu or alternative minimal GUI.
- [ ] Configurable host/port/storage path, rather than hardcoded parameters.
- [ ] Errors clearly displayed (I/O, invalid filename, missing file, connection failure).
- [ ] Add bounded concurrent clients, socket timeouts and sensible size limits.
- [ ] Verify availability from a *different* host on trusted LAN.

### M6 — Validation and handoff

- [ ] Automated byte-for-byte / SHA-256 tests for `.txt`, `.pdf`, `.png`, `.zip`, and random binary payloads.
- [ ] Test 0-byte file, medium/large file, duplicate display names, missing IDs, invalid names.
- [ ] Test abrupt disconnect and retry, restart persistence, concurrent clients.
- [ ] Check navigation operations against RNF003 (3-second target, transfer excluded).
- [ ] Produce a README with build, start, run and demonstration instructions.
- [ ] Maintain a table mapping all RF/RNF IDs to source code and tests.

## 12. Test plan with precise expected behavior

The following are **acceptance tests to implement**, not claims that they already pass:

| Test ID | Scenario | Expected result | Relevant requirements |
|---|---|---|---|
| T01 | Upload local `arquivo.pdf` from A | File committed on server, success response | RF001–RF005 |
| T02 | Query list from B | `arquivo.pdf` visible after manual refresh | RF006–RF009, RF015–RF016 |
| T03 | Download listed file on B | Bytes match A; original name offered | RF010–RF013 |
| T04 | Download nonexistent ID | Explicit `NOT_FOUND`, no file creation | RF014 |
| T05 | Upload PNG/ZIP/DOCX/random bytes | Same bytes after round trip | RNF004–RNF006 |
| T06 | Upload two `report.pdf` | Both separately retrievable; no silent overwrite | Recommended collision policy |
| T07 | Upload 0-byte file | Valid empty file can be listed/downloaded | Data integrity |
| T08 | Interrupt upload midway | Incomplete file not exposed; temp cleaned | Reliability |
| T09 | Interrupt download midway | No false success; partial destination handled | Reliability |
| T10 | Supply `../outside.txt` and absolute paths | No read/write outside storage root | Security |
| T11 | Symlink/path edge cases | Cannot escape allowed storage | Security |
| T12 | Two or more clients connect simultaneously | Distinct uploads/list/downloads work | RF008, RF015, RNF008 |
| T13 | Restart server | Previously committed files still listed and downloadable | RF003, RNF008 |
| T14 | Attempt oversized upload | Early controlled rejection; no memory exhaustion | Proposed resource limits |
| T15 | Upload `text\\nwith\\tliterals` | Exact literal bytes preserved | RNF004–RNF005 |
| T16 | Time normal UI/list operations | Under 3 s under documented test conditions | RNF003 |

### SHA-256 verification (Linux)

```bash
sha256sum /path/on/ComputerA/arquivo.pdf
sha256sum /path/on/ComputerB/arquivo.pdf
```

If checking server storage bytes, hash its actual committed internal file too. Identical SHA-256 values across the three files provide strong evidence of byte preservation.

### Demo script for evaluators

1. Start Java server on computer designated as the server.
2. Start client A, select `arquivo.pdf`, upload it, display success.
3. Start client B on another computer, request file list, show `arquivo.pdf`.
4. Download `arquivo.pdf` from B to a local directory.
5. Compare hashes and demonstrate equality.
6. Optionally demonstrate error feedback for missing file and invalid name.

## 13. Historical design decisions and unresolved questions

These notes predate the later backend request. The user subsequently selected the Java SQL-backed HTTP API and requested that the root Java entrypoints use it; that choice supersedes TCP-specific recommendations below.

**Confirmed:**

- Academic scope: file upload/storage/list/download; arbitrary file formats; unchanged bytes.
- Real-time sync, editing, folders, links, authentication, previews not required initially.
- Existing Java socket code listens on TCP 5000 and demonstrates UTF-8 text editing.
- Existing Java code is vulnerable to path traversal and corrupts some textual byte sequences.

**Recommended defaults (not formally approved):**

- Keep Java, add a simple interactive CLI.
- Reuse TCP port 5000 locally/LAN for the MVP; make configurable.
- Use a versioned binary framing protocol (`UPLOAD`, `LIST`, `DOWNLOAD`).
- Use physical filenames generated server-side; user-facing original names in metadata.
- Limit individual file size initially (e.g., configurable 100 MiB; this is an engineering suggestion, **not** in the PDF).
- Restrict demo to trusted LAN/localhost until security hardening.
- Add tests before extra UI/cloud infrastructure.

**Open decisions for the team:**

1. Is a graphical/web interface specifically required by the instructor, beyond the current PDF? The supplied specification describes a simple interface but does not unambiguously mandate a browser-based implementation.
2. Will clients and server run only on a LAN or be hosted online?
3. What file-size and total-storage limits are appropriate?
4. Should metadata live in a small durable manifest, sidecar files, or SQLite?
5. What should happen if a user downloads into a directory with an existing same-named file?
6. Which upload collision policy is desired? Recommended: duplicate *display* names allowed with distinct IDs.
7. Should existing `ABRIR`/`SALVAR` remain as separate experimental commands or be removed? Recommended: do not let the old text editor complicate the required storage MVP.

When uncertain, preserve the minimum academic scope and document the choice; do not invent instructor requirements.

## 14. Glossary and terminology

- **Cliente / Client:** program/device requesting operations from server.
- **Servidor / Server:** receives, stores, lists and transmits files.
- **Upload / Envio:** transfer of file data from client to server.
- **Download:** transfer from server into a local file on client.
- **Arquivo / File:** arbitrary binary sequence with name metadata; not necessarily UTF-8 text.
- **TCP socket:** reliable ordered byte-stream connection, but **does not** automatically define file framing, message boundaries or application semantics.
- **Binary-safe:** copying bytes without interpreting them as text.
- **Metadata:** filename, ID and optionally size/date/type, stored separately from file contents.
- **Path traversal:** using uncontrolled pathname components such as `../` to escape a permitted directory.
- **SHA-256:** hash used to confirm equality of file content after upload/download.
- **RF:** requisito funcional / functional requirement.
- **RNF:** requisito não funcional / non-functional requirement.
- **MVP:** minimum viable version meeting the mandated requirements.

## 15. One-screen state summary for the next CLI session

```text
PROJECT: Simple cloud storage / file sharing (UFSCar software engineering)
INPUTS: Doc_Req_Eng_Software.pdf; cloud_storage_backend_requirements_java_sql.md;
        DocsServer.java; DocsClient.java; backend/
CURRENT BACKEND: Spring Boot 4.1.2 / Java 21; PostgreSQL JDBC + Flyway;
                 session auth + CSRF; owner-scoped file API; private UUID storage;
                 nested folder API; Compose PostgreSQL volume backend_postgres_data.
BACKEND STATUS: compiled and running on port 8080 against Docker Compose;
                Flyway V1 applied; GET /api/auth/csrf returned HTTP 200.
JAVA ENTRYPOINTS: DocsServer launches the backend; DocsClient is an interactive
                  Java HTTP client with file/folder operations; both compile.
TEST STATUS: backend integration tests and interactive client workflows not run.
HISTORICAL TCP NOTES: Sections 3–7 and Appendices A/B describe pre-integration
                      code and its old line protocol, not current root sources.
NEXT: run backend integration tests and exercise a multi-client SHA-256 upload /
      download round-trip before claiming end-to-end acceptance.
```

---

## Appendix A — Exact supplied `DocsServer.java` (unaltered source snapshot)

This is the code **as uploaded**, not the proposed implementation. Its path handling is unsafe; do not expose it to untrusted clients.

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class DocsServer {
    private static final int PORTA = 5000;
    private static final String PASTA_ARQUIVOS = "./documentos_servidor";

    public static void main(String[] args) {
        // 1. Garante que a pasta onde os arquivos ficam salvos exista no servidor
        new File(PASTA_ARQUIVOS).mkdirs();

        // 2. Cria o ServerSocket na porta 5000
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("Servidor Google Docs TCP iniciado na porta " + PORTA);
            System.out.println("Aguardando conexões de usuários...");

            // Loop infinito para aceitar múltiplos usuários conectando
            while (true) {
                // O método accept() TRAVA a execução até que um cliente se conecte.
                // Quando conecta, ele retorna um objeto Socket exclusivo para aquele cliente.
                Socket clientSocket = serverSocket.accept();
                System.out.println("Usuário conectado: " + clientSocket.getInetAddress().getHostAddress());

                // 3. Cria uma nova Thread para atender esse usuário sem bloquear os próximos
                new Thread(new ManipuladorDeCliente(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        }
    }

    // Classe interna que roda em uma Thread separada para cada usuário conectado
    private static class ManipuladorDeCliente implements Runnable {
        private Socket socket;

        public ManipuladorDeCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // 4. Abrimos os canais de Entrada (in) e Saída (out) usando UTF-8 para não quebrar acentos
            try (
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                // O 'true' no PrintWriter ativa o autoFlush (envia os dados imediatamente pela rede)
                PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true
                )
            ) {
                String payloadRecebido;

                // 5. Fica lendo cada comando enviado pelo usuário enquanto a conexão estiver aberta
                while ((payloadRecebido = in.readLine()) != null) {
                    System.out.println("Payload recebido: " + payloadRecebido);

                    // Quebra o payload em até 3 partes usando o separador "|"
                    // Formato: ACAO|NOME_ARQUIVO|CONTEUDO
                    String[] partes = payloadRecebido.split("\\|", 3);
                    if (partes.length < 2) {
                        out.println("ERRO|Payload inválido. Use ACAO|ARQUIVO|CONTEUDO");
                        continue;
                    }

                    String acao = partes[0];
                    String nomeArquivo = partes[1];
                    String conteudo = (partes.length == 3) ? partes[2] : "";

                    // Caminho seguro para o arquivo dentro da pasta do servidor
                    Path caminhoArquivo = Paths.get(PASTA_ARQUIVOS, nomeArquivo);

                    // 6. Roteamento da lógica baseada na ACAO do Payload
                    switch (acao.toUpperCase()) {
                        case "ABRIR":
                            if (Files.exists(caminhoArquivo)) {
                                // Lê todo o arquivo do disco
                                String textoDoArquivo = Files.readString(caminhoArquivo, StandardCharsets.UTF_8);
                                // Escapa as quebras de linha reais para enviar em uma única linha TCP
                                String textoSerializado = textoDoArquivo.replace("\n", "\\n").replace("\r", "");
                                out.println("OK|" + textoSerializado);
                            } else {
                                // Se não existir, cria um documento em branco (igual ao Google Docs)
                                Files.writeString(caminhoArquivo, "", StandardCharsets.UTF_8);
                                out.println("OK|");
                            }
                            break;

                        case "SALVAR":
                            // Synchronized impede que dois usuários corrompam o arquivo salvando no mesmo milissegundo
                            synchronized (DocsServer.class) {
                                // Desfaz o escape do \n para salvar no disco com as quebras de linha reais
                                String textoReal = conteudo.replace("\\n", "\n");
                                Files.writeString(caminhoArquivo, textoReal, StandardCharsets.UTF_8);
                            }
                            out.println("OK|Documento '" + nomeArquivo + "' salvo com sucesso no servidor!");
                            break;

                        default:
                            out.println("ERRO|Ação desconhecida: " + acao);
                    }
                }
            } catch (IOException e) {
                System.out.println("Usuário desconectou: " + e.getMessage());
            } finally {
                // 7. Garante o fechamento do socket ao encerrar
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}```

## Appendix B — Exact supplied `DocsClient.java` (unaltered source snapshot)

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class DocsClient {
    // Se o servidor estiver em outra máquina na rede local, troque "localhost" pelo IP dela (ex: "192.168.1.15")
    private static final String IP_SERVIDOR = "localhost";
    private static final int PORTA_SERVIDOR = 5000;

    public static void main(String[] args) {
        // 1. O cliente cria o Socket. Neste exato momento o Java faz o Handshake TCP com o Servidor!
        try (
            Socket socket = new Socket(IP_SERVIDOR, PORTA_SERVIDOR);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            PrintWriter out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true
            )
        ) {
            System.out.println("Conectado ao Servidor de Documentos!");

            String nomeDoc = "projeto_tcc.txt";

            // --- PASSO 1: SIMULANDO A ABERTURA DE UM DOCUMENTO ---
            String payloadAbrir = "ABRIR|" + nomeDoc + "|";
            System.out.println("\n[Enviando Payload]: " + payloadAbrir);
            out.println(payloadAbrir); // Envia pela rede

            // Aguarda a resposta do servidor
            String respostaAbrir = in.readLine();
            System.out.println("[Resposta do Servidor]: " + respostaAbrir);


            // --- PASSO 2: SIMULANDO O USUÁRIO DIGITANDO E SALVANDO ---
            String textoDigitadoPeloUsuario = "Título do Documento\nEste texto foi enviado via TCP para o servidor.";
            // Prepara o payload escapando o \n para não quebrar o protocolo de linha
            String conteudoSerializado = textoDigitadoPeloUsuario.replace("\n", "\\n");
            
            String payloadSalvar = "SALVAR|" + nomeDoc + "|" + conteudoSerializado;
            System.out.println("\n[Enviando Payload]: " + payloadSalvar);
            out.println(payloadSalvar); // Envia pela rede

            // Aguarda confirmação de salvamento
            String respostaSalvar = in.readLine();
            System.out.println("[Resposta do Servidor]: " + respostaSalvar);


            // --- PASSO 3: LENDO NOVAMENTE PARA CONFIRMAR QUE GRAVOU NO SERVIDOR ---
            out.println("ABRIR|" + nomeDoc + "|");
            String respostaLeitura = in.readLine();
            
            // Separa o STATUS do CONTEUDO ("OK|conteudo...")
            String[] partesResposta = respostaLeitura.split("\\|", 2);
            if (partesResposta[0].equals("OK")) {
                String conteudoRecebido = partesResposta[1].replace("\\n", "\n");
                System.out.println("\n--- CONTEÚDO ATUAL DO DOCUMENTO NO SERVIDOR ---");
                System.out.println(conteudoRecebido);
                System.out.println("-----------------------------------------------");
            }

        } catch (IOException e) {
            System.err.println("Erro ao conectar ao servidor: " + e.getMessage());
        }
    }
}```

---

**End of project context.** This document is intended to remain usable without chat history. Update its "current implementation" and "verified tests" sections after material code changes, and keep recommendations clearly separated from implemented functionality.
