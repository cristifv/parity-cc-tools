#!/usr/bin/env bash
# parity-cc-tools' local CI (PLAN.md §10). Stops at the first failure. Under 5 minutes (nice 10, mvn -T 2).
#   1. tools: Java 21, Maven >= 3.9, docker, python3;
#   2. parity-contracts is the version this build pins (the tenant-routing schemas, the masking cases);
#   3. the test images: postgres and Keycloak 26.8.1 by digest, Redpanda v26.2.3 checked against its digest;
#   4. the vendored HTMX and its SSE extension match their recorded hashes;
#   5. the shared Error Prone checks (parity-telephony-controller/tools/errorprone-checks);
#   6. mvn verify: the enforcer, javac -Xlint:all -Werror with Error Prone, the unit tests (capability
#      coverage, token checks, risk tiers, the drivers' state machines, health, schema of every request),
#      the integration tests on Testcontainers (Postgres, Redpanda) with the parity-rs, Kubernetes and
#      opensips stand-ins (audit fail-closed, audit rows, a saga resuming after a restart, the Kafka
#      client, the web layer), the jar, the SBOM, the licence allow-list;
#   7. the logs (log-record.v1): every line the tests captured, against the schema, this service's
#      catalogue and the masking rules, by parity-contracts' contract-check; one violation fails;
#   8. the realm as code (deploy/keycloak/ops-realm.json): no person, no credential, the client secret only
#      as a placeholder, OTP required, PKCE S256; then imported into Keycloak 26.8.1 (a container): the
#      issuer, the flow, the client, and deploy/keycloak/dev-ops-user.sh creating an account with the ops
#      role and CONFIGURE_TOTP;
#   9. advisories: OSV-Scanner on the SBOM (needs the network).
#   CI_OFFLINE=1              mvn --offline, the advisory scan skipped (said so, not hidden)
#   CI_SKIP_CHECKS_INSTALL=1  use the installed Error Prone checks jar
#   CI_SKIP_KEYCLOAK=1        skip the Keycloak import (step 8's container part; said so)
#   PARITY_CONTRACTS_DIR      where parity-contracts is (default: ../parity-contracts)
#   CONTRACT_CHECK            the contract-check binary (default: parity-contracts' rust/target/debug)
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CONTRACTS=${PARITY_CONTRACTS_DIR:-$ROOT/../parity-contracts}
EXPECTED_CONTRACTS_VERSION=0.17.1
CHECKS=${PARITY_ERRORPRONE_CHECKS:-$ROOT/../parity-telephony-controller/tools/errorprone-checks}
# OSV-Scanner v2.7.0 (2026-10-12), by digest (as parity-store).
OSV_IMAGE=ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0
POSTGRES_IMAGE=postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea
REDPANDA_IMAGE=docker.redpanda.com/redpandadata/redpanda:v26.2.3
REDPANDA_DIGEST=sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9
# Keycloak 26.8.1 (tag 26.8.1-0, 2026-10-13, as admin-console).
KEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
step() { printf '== %s\n' "$*"; }
die() { printf 'ci: %s\n' "$*" >&2; exit 3; }
mvn_flags=(-B -ntp -T 2)
if [[ "${CI_OFFLINE:-0}" == 1 ]]; then mvn_flags+=(--offline -Dcyclonedx.skip=true); fi
started=$SECONDS

TEST_LABEL=parity.test=parity-cc-tools
cleanup() {
  local ids
  ids=$(docker ps -aq --filter "label=$TEST_LABEL" 2>/dev/null || true)
  if [[ -n "$ids" ]]; then
    echo "removing leftover test containers: $(echo $ids | tr '\n' ' ')"
    docker rm -f $ids >/dev/null
  fi
  rm -rf "$ROOT/target/ci-kc"
}

step "tools"
java_version=$(java -version 2>&1 | head -1)
[[ "$java_version" == *'"21.'* ]] || die "need Java 21, found: $java_version"
mvn_version=$(mvn -v 2>/dev/null | head -1)
[[ "$mvn_version" =~ Apache\ Maven\ 3\.(9|[1-9][0-9])\. ]] || die "need Maven >= 3.9, found: ${mvn_version:-none}"
command -v docker >/dev/null || die "docker is missing (the tests need it)"
docker info >/dev/null 2>&1 || die "docker is not reachable"
command -v python3 >/dev/null || die "python3 is missing (the realm checks)"
echo "$java_version; $mvn_version; $(docker --version)"
cleanup
trap cleanup EXIT

step "parity-contracts $EXPECTED_CONTRACTS_VERSION"
[[ "$(cat "$CONTRACTS/VERSION")" == "$EXPECTED_CONTRACTS_VERSION" ]] \
  || die "parity-contracts is $(cat "$CONTRACTS/VERSION"), parity-cc-tools expects $EXPECTED_CONTRACTS_VERSION"
for f in schemas/tenant-routing/tenant-routing-requests.v1.json schemas/tenant-routing/tenant-routing-replies.v1.json \
         schemas/tenant-routing/tenant-routing-events.v1.json schemas/logging/log-record.v1.json masking/cases.v1.json \
         fixtures/tenant-routing.v1/valid/request-move-tenant.json; do
  [[ -e "$CONTRACTS/$f" ]] || die "parity-contracts lacks $f"
done
echo "$CONTRACTS"

step "test images"
for img in "$POSTGRES_IMAGE" "$REDPANDA_IMAGE" "$KEYCLOAK_IMAGE"; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull -q "$img"
done
digests=$(docker image inspect --format '{{join .RepoDigests " "}}' "$REDPANDA_IMAGE")
[[ " $digests " == *"@$REDPANDA_DIGEST "* ]] || die "$REDPANDA_IMAGE is $digests, not $REDPANDA_DIGEST"
echo "postgres ${POSTGRES_IMAGE#*@}; redpanda v26.2.3 $REDPANDA_DIGEST; keycloak ${KEYCLOAK_IMAGE#*@}"

step "vendored HTMX and htmx-ext-sse"
(cd "$ROOT/src/main/resources/static/vendor/htmx" && sha256sum -c --quiet SHA256SUMS) \
  || die "the vendored scripts don't match SHA256SUMS (scripts/vendor-htmx.sh)"
echo "htmx.min.js, sse.js: $(cut -c1-12 "$ROOT/src/main/resources/static/vendor/htmx/SHA256SUMS" | tr '\n' ' ')"

step "Error Prone checks (NoDataInMessages, NoSynchronized)"
if [[ "${CI_SKIP_CHECKS_INSTALL:-0}" == 1 ]]; then
  ls ~/.m2/repository/parity/parity-errorprone-checks/0.1.0/parity-errorprone-checks-0.1.0.jar >/dev/null \
    || die "CI_SKIP_CHECKS_INSTALL=1 but the checks jar isn't installed"
  echo "using the installed checks jar"
else
  nice -n 10 mvn "${mvn_flags[@]}" -q -f "$CHECKS/pom.xml" install
fi

step "mvn verify"
cd "$ROOT"
# The captured logs are appended to: start from none.
rm -rf "$ROOT/target/test-logs"
nice -n 10 mvn "${mvn_flags[@]}" -Dparity.contracts.dir="$CONTRACTS" verify

step "logs (log-record.v1)"
CC="${CONTRACT_CHECK:-$CONTRACTS/rust/target/debug/contract-check}"
if [[ ! -x "$CC" ]]; then
  command -v cargo >/dev/null || die "contract-check is missing at $CC and there is no cargo to build it"
  (cd "$CONTRACTS/rust" && cargo build --locked -q -p contract-check)
fi
mapfile -t captured < <(find "$ROOT/target/test-logs" -maxdepth 1 -type f -name '*.ndjson' -size +0 2>/dev/null | sort)
(( ${#captured[@]} >= 2 )) || die "the tests captured only ${#captured[@]} log files (target/test-logs)"
log_args=()
for f in "${captured[@]}"; do log_args+=(--logs "$f"); done
"$CC" --root "$CONTRACTS" --catalogue "$ROOT/logging/events.parity-cc-tools.v1.json" "${log_args[@]}" --skip-non-json

step "the realm as code (deploy/keycloak/ops-realm.json)"
REALM="$ROOT/deploy/keycloak/ops-realm.json"
python3 -I - "$REALM" <<'PY' || die "$REALM breaks a rule"
import json, re, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
bad = []
def walk(n, at):
    if isinstance(n, dict):
        for k, v in n.items():
            if k == "credentials":
                bad.append(at + "/" + k)
            if k == "secret" and v != "${PARITY_CC_TOOLS_CLIENT_SECRET}":
                bad.append(at + "/secret is a value, not the placeholder")
            walk(v, at + "/" + k)
    elif isinstance(n, list):
        for i, v in enumerate(n):
            walk(v, at + "/" + str(i))
walk(r, "")
if r.get("users"): bad.append("/users: no person in the realm file")
if r["realm"] != "ops": bad.append("realm isn't ops")
for k in ("registrationAllowed", "resetPasswordAllowed", "rememberMe", "loginWithEmailAllowed"):
    if r.get(k) is not False: bad.append(k + " must be false")
if r.get("bruteForceProtected") is not True: bad.append("brute-force detection off")
if r.get("otpPolicyType") != "totp" or r.get("otpPolicyDigits") != 6 or r.get("otpPolicyPeriod") != 30 or r.get("otpPolicyAlgorithm") != "HmacSHA1":
    bad.append("the OTP policy isn't TOTP/SHA1/6/30")
if "length(14)" not in r.get("passwordPolicy", "") or "notUsername" not in r["passwordPolicy"]:
    bad.append("the password policy")
ra = {a["alias"]: a for a in r.get("requiredActions", [])}
if not (ra.get("CONFIGURE_TOTP", {}).get("enabled") and ra["CONFIGURE_TOTP"].get("defaultAction")):
    bad.append("CONFIGURE_TOTP isn't a default required action")
flows = {f["alias"]: f for f in r["authenticationFlows"]}
forms = flows.get("ops forms", {}).get("authenticationExecutions", [])
otp = [e for e in forms if e.get("authenticator") == "auth-otp-form"]
if r.get("browserFlow") != "ops browser" or not otp or otp[0]["requirement"] != "REQUIRED":
    bad.append("the browser flow doesn't require the OTP form")
cfg = {c["alias"]: c["config"] for c in r.get("authenticatorConfig", [])}
if cfg.get(otp[0].get("authenticatorConfig") if otp else "", {}).get("default.reference.value") != "otp":
    bad.append("the OTP form doesn't set amr otp")
c = [x for x in r["clients"] if x["clientId"] == "parity-cc-tools"]
if len(c) != 1: bad.append("no client parity-cc-tools")
else:
    c = c[0]
    if c.get("publicClient") or not c.get("standardFlowEnabled") or c.get("implicitFlowEnabled") or c.get("directAccessGrantsEnabled") \
            or c.get("serviceAccountsEnabled") or c.get("fullScopeAllowed") or c.get("frontchannelLogout"):
        bad.append("the client's flows: confidential, standard flow only, full scope off, front-channel logout off")
    if c.get("attributes", {}).get("pkce.code.challenge.method") != "S256": bad.append("PKCE S256")
    if any("*" in u for u in c.get("redirectUris", [])) or not c.get("redirectUris"): bad.append("redirect URIs must be exact")
    mappers = {m["protocolMapper"] for m in c.get("protocolMappers", [])}
    if not {"oidc-usermodel-realm-role-mapper", "oidc-amr-mapper"} <= mappers: bad.append("the roles and amr mappers")
placeholders = set(re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?\}", open(sys.argv[1]).read()))
if placeholders != {"PARITY_CC_TOOLS_CLIENT_SECRET", "PARITY_CC_TOOLS_BASE_URL", "PARITY_CC_TOOLS_BACKCHANNEL_BASE_URL"}:
    bad.append("placeholders: " + ", ".join(sorted(placeholders)))
if bad:
    print("ci: " + "; ".join(bad), file=sys.stderr)
    sys.exit(1)
print("no person, no credential, the secret a placeholder; OTP required (amr otp), PKCE S256, exact redirects")
PY
bash -n "$ROOT/deploy/keycloak/dev-ops-user.sh"
if [[ "${CI_SKIP_KEYCLOAK:-0}" == 1 ]]; then
  echo "SKIPPED: CI_SKIP_KEYCLOAK=1 (the import into Keycloak 26.8.1)"
else
  kc="$ROOT/target/ci-kc"
  mkdir -p -m 700 "$kc" "$kc/import"
  cp "$REALM" "$kc/import/"
  chmod 755 "$kc/import"; chmod 644 "$kc/import/ops-realm.json"
  # Test-only values, generated here, never printed.
  printf '%s' "$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')" > "$kc/admin-password"
  printf 'Ops-dev-%s' "$(head -c 18 /dev/urandom | base64 | tr -d '/+=\n')" > "$kc/user-password"
  chmod 600 "$kc/admin-password" "$kc/user-password"
  cid=$(docker run -d --label "$TEST_LABEL" --security-opt label=disable -p 127.0.0.1::8080 \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=kc-bootstrap -e KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$kc/admin-password")" \
    -e PARITY_CC_TOOLS_CLIENT_SECRET="ci-$(head -c 12 /dev/urandom | base64 | tr -d '/+=')" \
    -e PARITY_CC_TOOLS_BASE_URL=https://ops.ci.invalid -e PARITY_CC_TOOLS_BACKCHANNEL_BASE_URL=http://cct.ci.invalid:8095 \
    -v "$kc/import:/opt/keycloak/data/import:ro" "$KEYCLOAK_IMAGE" start-dev --import-realm)
  port=$(docker port "$cid" 8080/tcp | head -1 | sed 's/.*://')
  KC_URL="http://127.0.0.1:$port"
  for _ in $(seq 1 120); do
    if curl -fsS -o "$kc/wellknown.json" "$KC_URL/realms/ops/.well-known/openid-configuration" 2>/dev/null; then break; fi
    if [[ -z "$(docker ps -q --filter "id=$cid")" ]]; then docker logs "$cid" 2>&1 | tail -20 >&2; die "Keycloak exited"; fi
    sleep 1
  done
  [[ -s "$kc/wellknown.json" ]] || { docker logs "$cid" 2>&1 | tail -30 >&2; die "the realm ops didn't come up"; }
  python3 -I -c 'import json,sys; w=json.load(open(sys.argv[1])); assert w["issuer"]==sys.argv[2], w["issuer"]; assert "S256" in w["code_challenge_methods_supported"]' \
    "$kc/wellknown.json" "$KC_URL/realms/ops" || die "the realm's issuer or PKCE support"
  PARITY_ENV=dev bash "$ROOT/deploy/keycloak/dev-ops-user.sh" "$KC_URL" kc-bootstrap "$kc/admin-password" ci-operator "$kc/user-password"
  python3 -I -c 'import sys, urllib.parse; sys.stdout.write(urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
      "username": "kc-bootstrap", "password": open(sys.argv[1]).read().strip()}))' "$kc/admin-password" \
    | curl -fsS -o "$kc/token.json" "$KC_URL/realms/master/protocol/openid-connect/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' --data-binary @-
  python3 -I -c 'import json,sys; print("Authorization: Bearer " + json.load(open(sys.argv[1]))["access_token"])' "$kc/token.json" > "$kc/auth.hdr"
  curl -fsS -o "$kc/users.json" -H @"$kc/auth.hdr" "$KC_URL/admin/realms/ops/users?username=ci-operator&exact=true"
  curl -fsS -o "$kc/client.json" -H @"$kc/auth.hdr" "$KC_URL/admin/realms/ops/clients?clientId=parity-cc-tools"
  uid=$(python3 -I -c 'import json,sys; u=json.load(open(sys.argv[1]))[0]; assert u["requiredActions"]==["CONFIGURE_TOTP"]; print(u["id"])' "$kc/users.json") \
    || die "the dev account lacks CONFIGURE_TOTP"
  curl -fsS -o "$kc/roles.json" -H @"$kc/auth.hdr" "$KC_URL/admin/realms/ops/users/$uid/role-mappings/realm"
  python3 -I - "$kc/roles.json" "$kc/client.json" <<'PY' || die "the imported realm differs from the file"
import json, sys
roles = {r["name"] for r in json.load(open(sys.argv[1]))}
assert "ops" in roles, roles
c = json.load(open(sys.argv[2]))[0]
assert c["attributes"]["pkce.code.challenge.method"] == "S256"
assert c["redirectUris"] == ["https://ops.ci.invalid/login/oauth2/code/ops"], c["redirectUris"]
assert not c["publicClient"] and not c["directAccessGrantsEnabled"] and not c["fullScopeAllowed"]
assert {m["protocolMapper"] for m in c["protocolMappers"]} >= {"oidc-usermodel-realm-role-mapper", "oidc-amr-mapper"}
print("imported into Keycloak 26.8.1: issuer, PKCE S256, exact redirect, mappers; dev account with ops + CONFIGURE_TOTP")
PY
  docker rm -f "$cid" >/dev/null
fi

step "advisories (OSV-Scanner on target/bom.json)"
if [[ "${CI_OFFLINE:-0}" == 1 ]]; then
  echo "SKIPPED: CI_OFFLINE=1 (OSV-Scanner needs osv.dev)"
else
  [[ -f target/bom.json ]] || die "no SBOM at target/bom.json"
  docker run --rm --network bridge --security-opt label=disable --read-only --cap-drop ALL \
    -v "$ROOT/target/bom.json:/src/bom.json:ro" -v "$ROOT/osv-scanner.toml:/src/osv-scanner.toml:ro" \
    "$OSV_IMAGE" scan source --config /src/osv-scanner.toml -L /src/bom.json
fi

step "parity-cc-tools ok ($((SECONDS - started)) s)"
