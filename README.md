# tramo-api

![CI](https://github.com/tramodev/tramo-api/actions/workflows/ci.yml/badge.svg)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Backend for **Tramo**, a tool for capturing ideas as an associative graph and then
carving ordered, shareable paths through them. The model is a modern take on
Vannevar Bush's Memex: atomic **items** connected by directed **associations** (the
graph), and **trails** that linearize a subset of that graph into something you can
read and study one step at a time.

## Core concepts

The core domain lives in the `trail` package and is built on two layers.

**The graph** — how ideas actually relate, non-linear and reusable:

- **Item** — an atomic unit of content (title + rich-text body in `ItemContent`).
  An item can appear in many trails at once (transclusion): it is referenced, not
  copied.
- **Association** — a directed connection from one note to another note in the same
  project, with optional shared text (up to 2,000 characters). Direction means
  “from this note to that note”; it implies neither dependency nor reading order.
  Each directed pair is unique; the reverse connection is independent. A note
  cannot connect to itself. Deleting a note deletes its incoming and outgoing
  connections, leaving other notes intact.

**The trail** — an independent reading order:

- **Trail** — a named, ordered sequence of notes, versioned and forkable.
- **TrailItem** — membership of a note in a trail, with its `orderIndex`.
  Adding, removing or reordering memberships never creates or changes connections.
  Notes reused in several trails retain the same content and connection text.

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
- **Caffeine** + **Bucket4j** for rate limiting, **Hashids**
  for opaque public project IDs
- **Testcontainers** + JUnit for integration tests, **JaCoCo** for coverage

## Prerequisites

- JDK 17
- A PostgreSQL instance (local or Docker)
- Docker (integration tests use Testcontainers)

## Testing notes

Integration tests extend `AbstractIntegrationTest` and run against a real Postgres
container. Query-count tests (`QueryCountTest`, `EditorQueryCountTest`) assert that
read endpoints don't scale their query count with content size — the guard against
N+1 regressions. When adding a hot read path, add a matching query-count assertion.

Docker must be running before executing integration tests:

```bash
docker info
./mvnw test
./mvnw -Dtest='NotificationTest' test
```

Surefire reports are written to `target/surefire-reports/`; check their timestamps
when reviewing results from previous runs.
