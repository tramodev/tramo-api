# Tramo development database reset

V27 changes the connection schema. Old connection data and snapshots are not
supported. Applied Flyway migrations remain unchanged; V27 refuses old connections
or snapshots instead of resetting data at application startup.

Stop the Tramo backend first. The development target configured in
`src/main/resources/application.properties` is PostgreSQL at `localhost:5432`,
database `tramo`. These commands apply only to that local Tramo development DB.
Use the DB role configured as `DB_USERNAME` in your local `.env`; `psql` prompts
for its password. Do not point these commands at another database or environment.

```bash
read -r TRAMO_DB_USER
psql -X -h localhost -p 5432 -U "$TRAMO_DB_USER" -d tramo -v ON_ERROR_STOP=1 -c 'SELECT current_database(), inet_server_addr(), inet_server_port();'
psql -X -h localhost -p 5432 -U "$TRAMO_DB_USER" -d tramo -v ON_ERROR_STOP=1 -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
./mvnw spring-boot:run
```

The schema reset removes all data and Flyway history in this app's `public`
schema. Startup applies V1 through V27 to the empty schema, validates it with
Hibernate and runs the existing tag seed. Register a development account and
use the existing example-project action to create notes, trails and connections.
No backups, historical readers or data conversion are provided.

Verification against an isolated empty PostgreSQL database:

```bash
docker info
./mvnw test
```

Testcontainers creates its own database; tests never reset the configured
local development database. The development reset is manual and has not been
performed as part of this change.
