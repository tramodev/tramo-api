# Development

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

## Local configuration

Run commands from the repository root. If you don't already have a `.env`, copy
[`.env.example`](.env.example) to the root `.env` and fill in the required values.
Spring imports `.env` as a properties file; use plain `KEY=value` entries without
shell `export` statements or surrounding quotes. Never commit real credentials.

| Variables | Configuration |
| --- | --- |
| `DB_USERNAME`, `DB_PASSWORD` | Credentials for the local PostgreSQL database `tramo` on port 5432 |
| `GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `RECAPTCHA_SECRET_KEY` | reCAPTCHA secret key |
| `PATREON_CLIENT_ID`, `PATREON_CLIENT_SECRET` | Patreon OAuth credentials |
| `JWT_SECRET` | Base64-encoded signing key with at least 32 random bytes |
| `TOKEN_ENCRYPTION_KEY` | Base64-encoded 256-bit AES key for Patreon tokens at rest |
| `PROJECT_ID_SALT` | Stable salt for public project IDs |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY`, `R2_SECRET` | Cloudflare R2 credentials |
| `R2_BUCKET`, `R2_PRIVATE_BUCKET`, `R2_PUBLIC_BASE_URL` | Public and private buckets and the public base URL |
| `MAIL_ENABLED`, `RESEND_API_KEY` | Mail toggle and SMTP credential; the example disables mail locally |
| `PATREON_WEBHOOK_SECRET` | Required in `prod`; blank local configuration disables webhooks and logs a warning |

Generate each cryptographic key independently:

```bash
openssl rand -base64 32
```

Keep existing `TOKEN_ENCRYPTION_KEY` and `PROJECT_ID_SALT` values stable: changing
them makes stored tokens unreadable or changes public project IDs. Unreadable
Patreon credentials do not block login or profile reads; syncing asks the user to
reconnect and preserves the stored ciphertext. Restore the original encryption
key to read existing credentials; a new key alone cannot recover them. Rotating
`JWT_SECRET` invalidates existing signed tokens.

Migration V40 stores a SHA-256 fingerprint of `PROJECT_ID_SALT` on the first
startup after migration. Use the existing salt for that deployment: no earlier
fingerprint exists to detect a change made before this first startup. Later
startups fail if the configured salt differs. Keep the guard row with database
backups; do not delete or replace it to bypass the check.

Start the API on `http://localhost:8080`:

```bash
./mvnw spring-boot:run
```

The local frontend URL is `http://localhost:3000`; the frontend repository lives
at `../tramo-frontend`. The local Patreon callback is
`http://localhost:8080/api/auth/patreon/callback` and must match the Patreon app
configuration exactly.

## Production configuration

Set `SPRING_PROFILES_ACTIVE=prod` and provide the same credentials plus:

| Variable | Configuration |
| --- | --- |
| `DATABASE_URL` | JDBC PostgreSQL URL, beginning with `jdbc:postgresql://` |
| `FRONTEND_URL` | Deployed frontend URL |
| `PATREON_REDIRECT_URI` | Deployed callback URL, exactly matching the Patreon app configuration |

Supply these through the deployment environment or the root `.env`; `.env.prod`
is not automatically imported. Enable mail with `MAIL_ENABLED=true` and configure
`RESEND_API_KEY` when transactional email is needed.

## Testing

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
