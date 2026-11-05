# parity-cc-tools

The internal ops UI (design: [PLAN.md](PLAN.md)): tenant routing through parity-rs (view tenants and
routes, move a tenant between units, failover zone, pins, drains, rollouts; parity-rs decides and
executes), service control (cordon an instance, take a media node out of rotation, stop a whole
service, each a saga run here), and the platform health dashboard. Internal network / VPN only; login
through the Keycloak realm `ops` (local accounts + TOTP); one role, `ops`; every action audited.

Spring Boot 4.1.2, Thymeleaf + HTMX 2.0.11 and htmx-ext-sse 2.2.4 (vendored, `scripts/vendor-htmx.sh`),
Spring Security OAuth2 client (a BFF), Spring Session JDBC (attributes encrypted), Postgres (Flyway),
plain kafka-clients, `java.net.http` for the Kubernetes API, the services' probes and opensips's
`mi_http`.

## Build and test

```
scripts/ci.sh                      # everything (≈ 2 min): build, tests, logs, realm import, advisories
CI_OFFLINE=1 scripts/ci.sh         # without the network (no advisory scan)
nice -n 10 mvn -B -o -T 2 verify -Dparity.contracts.dir=../parity-contracts   # tests only
```

Needs Java 21, Maven ≥ 3.9, docker (Testcontainers: Postgres, Redpanda; Keycloak for the realm check),
`../parity-contracts` at the pinned version (0.17.1) and the shared Error Prone checks
(`../parity-telephony-controller/tools/errorprone-checks`). Test containers carry the label
`parity.test=parity-cc-tools`.

## Run

| Port | What | Property |
|---|---|---|
| 8095 | the UI | `server.port` / `PARITY_CC_TOOLS_HTTP_PORT` |
| 8096 | `/livez`, `/readyz`, `/metrics` | `management.server.port` / `PARITY_CC_TOOLS_MGMT_PORT` |

Secrets are files, never values in configuration: `PARITY_CC_TOOLS_SECRETS_DIR` (configtree; the file
`parity-cc-tools.identity.client-secret`), `PARITY_CC_TOOLS_DB_PASSWORD_FILE`,
`PARITY_CC_TOOLS_SESSION_KEY_FILE` (32 random bytes, base64), `PARITY_CC_TOOLS_EU1A_KAFKA_PASSWORD_FILE`.
Per zone (`parity-cc-tools.zones.<zone>`): Kafka, the Kubernetes API, the opensips SBC nodes' `mi-urls`
and the opensips pods' namespace and selector, and the service catalogue (a YAML file at
`PARITY_CC_TOOLS_CATALOGUE`; sample: `deploy/k3s/catalogue.eu1a.yaml`). The database migrations run in
the app only as the owner (`PARITY_CC_TOOLS_FLYWAY_USER` + its password file); the app role gets
SELECT/INSERT on the audit tables, SELECT/INSERT/UPDATE on `service_op`, the session tables.

Health files: `/tmp/parity-cc-tools.healthy` (started, ready, not draining) and
`/tmp/parity-cc-tools.drained` (drained in time). Exit codes: 0 drained, 1 failed, 2 configuration,
128 + n on a second signal. Logs: log-record.v1 on stdout, catalogue
`logging/events.parity-cc-tools.v1.json`; every record of an action carries its `op_id`.

## Identity

`deploy/keycloak/ops-realm.json` is the realm `ops` as code (no users, no secrets: the client secret,
base URL and back-channel base URL are import placeholders). A local account for dev or k3s:

```
PARITY_ENV=dev deploy/keycloak/dev-ops-user.sh http://127.0.0.1:8180 <admin> <admin-password-file> <username> <password-file>
```

## Layout

`security/` the ops login, token checks, step-up, capabilities · `audit/` the audit log ·
`routing/` the parity-rs client, the saga projection · `ops/` actions, risk tiers, confirmation ·
`control/` service-control sagas (drivers, the reconciler) · `k8s/`, `mi/` the Kubernetes and opensips
clients · `health/` the collector · `web/` controllers and the SSE streams · `deploy/` the realm and the
k3s requests.
