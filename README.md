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
| `R2_ACCESS_KEY` / `R2_SECRET_KEY` | R2 credentials |
| `R2_BUCKET` | R2 bucket name |
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

Editor images are uploaded to R2 and tracked per item by `ItemImageReference`. Because an item
can be transcluded into several trails, an image that stops being used is **not deleted right
away**: it goes to `PendingImageDeletion`, and a scheduled job removes it from R2 only after a
grace period *and* only if no other item still references that URL.

## Testing notes

Integration tests extend `AbstractIntegrationTest` and run against a real Postgres
container. Query-count tests (`QueryCountTest`, `EditorQueryCountTest`) assert that
read endpoints don't scale their query count with content size — the guard against
N+1 regressions. When adding a hot read path, add a matching query-count assertion.