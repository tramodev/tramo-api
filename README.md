# tramo-api

![CI](https://github.com/tramodev/tramo-api/actions/workflows/ci.yml/badge.svg)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Backend for **Tramo**, a tool for capturing ideas as an associative graph and then
carving ordered, shareable paths through them. The model is a modern take on
Vannevar Bush's Memex: atomic **items** connected by typed **associations** (the
graph), and **trails** that linearize a subset of that graph into something you can
read and study one step at a time.

## Core concepts

The core domain lives in the `trail` package and is built on two layers.

**The graph** — how ideas actually relate, non-linear and reusable:

- **Item** — an atomic unit of content (title + rich-text body in `ItemContent`).
  An item can appear in many trails at once (transclusion): it is referenced, not
  copied.
- **Association** — a typed, directed link. Its type carries the *reason* two things
  relate: `REQUIRES`, `ELABORATES`, `CONTRADICTS`, `EXAMPLE_OF`, `RELATED`. Its
  target is polymorphic (`AssociationTargetType`: `ITEM` or `TRAIL`), so an item can
  point at another item or at a whole trail.

**The trail** — a human ordering of the graph, made for reading and study:

- **Trail** — a named, ordered walk over items. Can be forked from another trail
  (`forkedFrom`) and is versioned.
- **TrailItem** — one step in a trail. Holds the `orderIndex` (the sequence), an
  optional `annotation` (human text connecting this step to the previous one), and a
  reference to the `Association` traversed to reach it (`null` = a deliberate jump).
  This is what fuses the two layers: a trail is a walk *through* the graph, not a
  separate ordering that ignores it.

A **Project** groups trails and loose items and is the unit of sharing and forking:
publishing snapshots the project to the public explore feed; forking copies its
items, associations and trails into the forker's own space so later edits to the
original never mutate the fork.

## Tech stack

- **Java 17**, **Spring Boot 4.0** (Web MVC, Data JPA, Security, Validation, Mail)
- **PostgreSQL** via Hibernate/JPA
- **Cloudflare R2** (S3-compatible) for image storage
- **JWT** auth (access + refresh tokens) with **Google OAuth** sign-in
- **Resend** for transactional email (SMTP)
- **Caffeine** for in-process caching, **Bucket4j** for rate limiting, **Hashids**
  for opaque public project IDs
- **Testcontainers** + JUnit 5 for integration tests, **JaCoCo** for coverage

## Getting started

### Prerequisites

- JDK 17
- A PostgreSQL instance (local or Docker)
- Docker (integration tests use Testcontainers)

### Configuration

The app reads secrets and environment-specific values from environment variables.
Create a local `.env` (or export them) before running:

| Variable | Purpose |
| --- | --- |
| `DB_USERNAME` / `DB_PASSWORD` | Postgres credentials |
| `JWT_SECRET` | Signing secret for access/refresh tokens |
| `GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `PROJECT_ID_SALT` | Salt for Hashids public project IDs |
| `RESEND_API_KEY` | Resend SMTP password (email) |
| `MAIL_ENABLED` | Toggle outbound email (defaults to `true`) |
| `R2_ACCOUNT_ID` | Cloudflare R2 account ID |
| `R2_ACCESS_KEY` / `R2_SECRET` | R2 credentials with access to both configured buckets |
| `R2_BUCKET` | Public bucket for avatars, banners and thumbnails |
| `R2_PRIVATE_BUCKET` | Separate private bucket for note images; required in every environment |
| `PUBLIC_EDITOR_IMAGES_ENABLED` | Legacy public editor uploads; defaults to `false` |
| `R2_PUBLIC_BASE_URL` | Public base URL for served images |

The default datasource points at `jdbc:postgresql://localhost:5432/mypath` — adjust
`spring.datasource.url` in `application.properties` if yours differs.

> Schema is managed by Flyway (`spring.jpa.hibernate.ddl-auto=validate` — Hibernate only
> checks the entity mappings match, it never alters schema). Migrations live in
> `src/main/resources/db/migration/`; every schema change is a new sequential
> `V{n}__description.sql` file, applied automatically on boot. Never edit a migration
> that's already been applied — add a new one instead.

### Production

`application.properties` hardcodes a few values to `localhost` for local dev
convenience (datasource URL, frontend URL, Patreon OAuth redirect). Run with the
`prod` profile active (`SPRING_PROFILES_ACTIVE=prod`) to override them via
`application-prod.properties`, which requires these additional environment
variables (no localhost fallback — the app fails fast at startup if they're
missing):

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Full JDBC URL for the production Postgres instance |
| `FRONTEND_URL` | Public URL of the deployed frontend (used in emails, etc.) |
| `PATREON_REDIRECT_URI` | Must exactly match the redirect URI registered in the Patreon app config, or the OAuth callback fails at Patreon's side |

### Run

```bash
# start the API (uses the Maven wrapper, no local Maven needed)
./mvnw spring-boot:run

# run the test suite (spins up Postgres via Testcontainers)
./mvnw test

# build a jar
./mvnw clean package
```

The API starts on `http://localhost:8080`.

## API surface

All routes are under `/api`. Public, unauthenticated reads live under `/api/public`;
everything else expects a bearer token.

| Prefix | Area |
| --- | --- |
| `/api/auth` | Register, login, token refresh, email verification, password reset |
| `/api/public` | Public projects and profiles for the explore feed (cacheable) |
| `/api/project` | Authoring: projects, trails, items, associations, publishing |
| `/api/profile` | The signed-in user's own content |
| `/api/comment` | Comments on projects, and reporting them |
| `/api/tags` | Tag autocomplete |
| `/api/uploads` | Image upload to R2 |
| `/api/notifications` | User notifications, including the SSE stream |
| `/api/subscription` | Plans and supporter subscription |
| `/api/auth/patreon`, `/api/webhooks/patreon` | Patreon OAuth and webhook callbacks |
| `/api/users` | User lookup |
| `/api/admin` | Moderation and admin actions |

`GET /api/notifications/stream` is a **Server-Sent Events** endpoint: the frontend keeps it
open and receives unread-count updates live. It sends periodic heartbeat comments so proxies
don't drop the connection, and emitters are evicted as soon as a write to a gone client fails.

## Access tokens and permission changes

Access JWTs expire after 15 minutes by default (`app.jwt.access-token-ttl-ms=900000`).
After verifying signature and expiration, authentication loads the user once by the signed
user ID from PostgreSQL. Authorities come from the current database role; deleted, banned,
or disabled users receive 401, including on public routes when their token is presented.
A database lookup failure returns 503 without authenticating from claims. Permission state
is not cached: bans and role changes apply to the next request after the change commits,
without waiting for the 15-minute expiry. Requests already authorized may finish.
Refresh continues to load the current user and reject bans before rotation or retry handling.
Malformed or expired JWTs retain the existing anonymous behavior on public routes and are
rejected on protected routes.

This checks current account state, rather than permanently revoking access tokens or sessions.
After an unban, an earlier unexpired access token works again with the current role.
The existing admin ban action deletes refresh tokens; an unban does not restore those tokens.
Logout still deletes the supplied refresh token and does not invalidate access JWTs.

Open SSE notification streams are authorized at connection time and are not revalidated by
this per-request check. Ban and role changes do not explicitly close existing emitters;
immediate termination of an open stream would require explicit emitter closure. Streams may
continue until disconnect, a failed write, or their configured timeout
(`app.notifications.sse-timeout-ms`, 30 minutes by default). Reconnecting checks current state.

## Project layout

```
src/main/java/com/tramo/backend/
├── auth          # tokens, email verification, password reset
├── user          # accounts, profiles
├── trail         # core domain: Item, Association, Trail, TrailItem
├── project       # projects, votes, bookmarks, views
├── comment       # comments on projects
├── moderation    # reports and moderation log
├── notification  # user notifications and the SSE stream
├── subscription  # plans, payments, subscriptions
├── tag           # project tags and autocomplete
├── upload        # R2 image records and cleanup
├── security      # JWT filters, rate limiting, age gate, auth config
├── common        # shared utilities
└── exception     # global error handling
```

Every domain package is cut the same way: `controller/ dto/ entity/ repository/ service/`.

## Image lifecycle

Note images use a separate private R2 bucket. The editor persists version-2 image nodes with an
opaque `imageId`, never a storage URL. Uploads reserve quota, use a ten-minute signed PUT into
`temporary/`, and require owner confirmation before an immutable copy under `images/` can be
attached to content. Confirmation downloads the inspected ETag, verifies the actual SHA-256
against `contentHash`, and decodes JPEG, PNG, WebP (TwelveMonkeys ImageIO), or every GIF frame.
Declared MIME metadata alone is insufficient. Invalid, truncated, or mismatched files are rejected.
The existing request and response shapes are unchanged; the frontend already hashes the uploaded bytes.

Validation respects `app.limits.max-upload-bytes` and also caps each dimension at 8192,
each frame at 4 million pixels, animations at 256 frames and 40 million total pixels,
and decoding at five seconds (checked between frames, during metadata validation and at decoder progress callbacks).
Metadata is limited to 1 MiB total per file, counting encoded metadata payloads plus the full expanded
size of PNG `zTXt`, compressed `iTXt`, and `iCCP`, with at most 1024 metadata blocks. Inflation uses
an 8 KiB scratch buffer and discards the output; incomplete, corrupt, dictionary-dependent, or trailing
zlib streams are rejected before ImageIO. PNG metadata is checked even when it appears after image data.
JPEG APP/COM segments, GIF extensions, and WebP ancillary chunks share the same per-file budget;
WebP ICC profile headers must also match the bounded payload size before the reader loads them.
ImageIO is asked to ignore metadata, but this is only an optimization: reader behavior varies by
format and JDK, palette PNG still reads some ancillary data, and the WebP reader loads ICC profiles
regardless of that flag. Original object bytes and hashes are preserved; metadata is not stripped.
At most two validations download/decode concurrently per backend process. Busy validations can be retried.
The original bytes, including animation, are preserved; no image transcoding occurs.

Confirmation stores a 60-second lease and a unique attempt ID. A retry during the lease is rejected;
a retry after expiration revalidates an existing final object and finishes without overwriting it,
or validates the temporary object and retries the ETag-conditioned copy if no final object exists.
The copy also uses R2's `cf-copy-destination-if-none-match: *` to keep the destination immutable
([R2 conditional copy documentation](https://developers.cloudflare.com/r2/api/s3/extensions/)).
The verified hash is persisted before copying so recovery can compare it, including for uploads
created before hash persistence was introduced. Existing `READY` objects are left untouched.

PostgreSQL session advisory locks coordinate claiming, final copying/confirmation, and purging
across backend processes. They hold a connection, but no database transaction remains open during R2
calls. At most two coordinated mutations run concurrently per process, leaving pool connections
available for the short database transactions (the default connection pool has ten connections).
A short final copy still holding its lock blocks takeover even if its lease expires;
retry after the operation finishes or the connection closes. An older validator cannot copy,
finish, or reset the state after another attempt takes over. Failed uploads remain unusable,
and abandoned temporary/final objects are reclaimed together by the existing purge and grace period.
Confirmation can be repeated after success without replacing the confirmed object.

Reading resolves up to 100 image IDs per request after checking the project's current permissions
and the image's membership in the requested content. Published reads use the latest publish
snapshot; historical reads use the selected publish snapshot. Private authoring reads require
ownership. GET URLs expire after five minutes and are delivered with `private, no-store`.
Anyone holding a signed URL can use it until expiration, even after visibility changes; already
downloaded copies cannot be revoked.

Snapshots retain image references. Forks get their own image IDs and quota accounting while
sharing immutable physical objects, so deleting the source does not break the fork. Removing
an image schedules reclamation through a 24-hour grace period. The job locks objects before
claiming deletion, checks references from every item and snapshot, and retries failed R2 deletes.
Legacy public URL reference tracking and cleanup remain for public objects already in the bucket.

Avatars, banners and independently uploaded thumbnails remain public. Do not put confidential
information there. Note images are not used as automatic or selectable public thumbnails.
`PUBLIC_EDITOR_IMAGES_ENABLED=false` blocks new legacy public editor uploads; it does not revoke
existing objects or make arbitrary external URLs private. The editor does not accept external
image hosts. Do not migrate private attachments into the public bucket.

### Private bucket setup

1. Create the bucket named by `R2_PRIVATE_BUCKET`, different from `R2_BUCKET`. Keep both its
   `r2.dev` endpoint and custom public domains disabled. Give the application's S3 credentials
   access to the public and private buckets without granting account-wide access unnecessarily.
2. Configure private bucket CORS for the exact frontend origins (`http://localhost:3000` locally),
   methods `PUT`, `GET`, `HEAD`, allowed header `Content-Type`, and exposed header `ETag`.
3. Add an R2 lifecycle rule deleting the `temporary/` prefix after one day. Never apply this rule
   to `images/`: confirmed images are cleaned by the application after reference checks.
4. Set frontend `NEXT_PUBLIC_R2_PRIVATE_ORIGIN=https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com`
   and rebuild/restart it. The SDK uses path-style URLs so this exact origin covers private reads.
5. Restart the backend to apply the new Flyway migration. Deploy both applications together:
   version-1 URL image nodes are rejected on save. There is no automatic data or object migration.
   For disposable local data, export anything needed before explicitly resetting the database;
   deleting the database does not delete objects in R2.
6. Before enabling uploads, verify a test object's unsigned URL is denied, its signed GET works,
   its expired GET is denied, and it cannot be reached through any public domain. Changing CORS
   alone does not restrict direct reads.

The private image endpoints are `POST /api/uploads/editor-images/presign`,
`POST /api/uploads/editor-images/{imageId}/complete`, and
`POST /api/{public/}project/{projectId}/editor-images/resolve`. Resolution accepts
`{imageIds, snapshotId?}` and returns `{images: [{imageId, url, expiresAt}]}`. The public form
allows anonymous readers only when project visibility permits; URLs are never cached in explore.

## Testing notes

Integration tests extend `AbstractIntegrationTest` and run against a real Postgres
container. Query-count tests (`QueryCountTest`, `EditorQueryCountTest`) assert that
read endpoints don't scale their query count with content size — the guard against
N+1 regressions. When adding a hot read path, add a matching query-count assertion.

## Safe application logs

Handled application failures log an event, severity, error code, server-generated UUID (`trackingId`),
exception types in a bounded cause chain and up to five application class/method/line locations
per cause. They never serialize exception messages, provider bodies, suppressed exceptions,
DTOs, entities, HTTP bodies, headers or query strings. HTTP errors handled by the advice and
an unavailable authentication database and rejected Patreon callbacks return `X-Tracking-Id` for correlation. SSE disconnects
remain quiet. Expected HTTP errors use fixed public messages, with an explicit allowlist for
selected existing auth and subscription messages; validation fields use `Invalid value`.
Error response shapes and status codes are preserved, but clients must not depend on the old
free-form messages. Unknown errors still produce ERROR logs. Both Spring scheduler variants use the same safe
error handler; failed recurring tasks keep running without dumping raw SQL/provider exceptions.

The base configuration keeps development safe too: SQL/bind/extract logging, request details,
mail session debugging and HTTP wire/header traces are disabled. Production additionally
turns off SQL formatting. Hibernate connection summaries and Flyway JDBC URL summaries are
suppressed because connection URLs can contain credentials. Spring Boot 4 error controls use `spring.web.error.*`, including
omitting error paths; they apply to the default error endpoint as well as the safe advice.
Hibernate's JDBC error logger is disabled because it can print SQL and bound values before
an exception reaches the application. The application records safe diagnostics for handled
failures instead. No external provider is contacted by the production configuration test.

Treat logs as restricted operational data even with these safeguards. Grant read access only
to operators who need it, protect exports/backups, and configure rotation and a documented,
short retention period in the system that collects logs (recommended default: 14 days unless
an incident or applicable requirement needs longer). Delete expired copies there. This repo
writes application logs to the console and does not implement retention or access controls.
It does not control logs generated by a reverse proxy, VPS, SMTP provider or hosting platform;
audit their request/header/query capture, access and retention separately.

Environment variables, command-line arguments, JVM properties, external config and logging
configuration files can override these defaults. Review `LOGGING_LEVEL_*`, `SPRING_APPLICATION_JSON`,
`JAVA_TOOL_OPTIONS`, `--logging.level.*`, `logging.config`, `debug`/`trace`,
`SPRING_JPA_SHOW_SQL`, `SPRING_MVC_LOG_REQUEST_DETAILS`, and mail session properties
(`mail.debug`, `mail.debug.auth`, `mail.debug.auth.username`, `mail.debug.auth.password`).
A child logger override can reactivate DEBUG/TRACE despite a WARN parent; review SQL binds,
Spring Security, Spring Web, SMTP, AWS request logs, Apache HTTP wire/header logs and Google
HTTP logging specifically. Do not enable sensitive tracing in development either. The JUL
default bridge routes Google/HTTP diagnostics through application logging; custom JUL handlers
or SDK logging configuration need their own review. Configuration is a safe default, not a
redaction layer for arbitrary overrides.
