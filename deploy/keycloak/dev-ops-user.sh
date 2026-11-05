#!/usr/bin/env bash
# Creates a local ops account in the realm ops (PLAN.md §2) through Keycloak's admin REST API.
# DEV AND K3S ONLY: refuses to run unless PARITY_ENV is dev or k3s. Production accounts are created by the
# ops realm's administrators in Keycloak.
#
#   PARITY_ENV=dev deploy/keycloak/dev-ops-user.sh <keycloak base URL> <admin user> <admin password file> \
#       <new username> <password file>
#
# The account gets the realm role ops, its password from the file (never in an argument, the environment
# or the output), and the required action CONFIGURE_TOTP: the first login sets up the one-time code. The
# admin password is read from its file the same way. The new account's password must satisfy the realm's
# policy (14 characters, not the username).
set -euo pipefail
case "${PARITY_ENV:-}" in
  dev|k3s) ;;
  *) echo "dev-ops-user.sh: dev and k3s only (set PARITY_ENV=dev or k3s)" >&2; exit 2 ;;
esac
[[ $# == 5 ]] || { echo "usage: $0 <keycloak url> <admin user> <admin password file> <username> <password file>" >&2; exit 2; }
KC=${1%/}; ADMIN=$2; ADMIN_PW_FILE=$3; USERNAME=$4; PW_FILE=$5
[[ "$USERNAME" =~ ^[a-z0-9][a-z0-9._-]{1,62}$ ]] || { echo "dev-ops-user.sh: the username must be lowercase letters, digits, . _ -" >&2; exit 2; }
for f in "$ADMIN_PW_FILE" "$PW_FILE"; do
  [[ -r "$f" ]] || { echo "dev-ops-user.sh: can't read $f" >&2; exit 2; }
done
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT; chmod 700 "$tmp"

# The admin token (master realm, admin-cli): the password goes from its file (stripped) into the form
# body on stdin, never into an argument.
python3 -I - "$ADMIN" "$ADMIN_PW_FILE" <<'PY' | curl -fsS -o "$tmp/token.json" "$KC/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-binary @-
import sys, urllib.parse
password = open(sys.argv[2], encoding="utf-8").read().strip()
sys.stdout.write(urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": sys.argv[1],
                                         "password": password}))
PY
python3 -I - "$tmp/token.json" "$tmp/auth.hdr" <<'PY'
import json, sys
token = json.load(open(sys.argv[1]))["access_token"]
with open(sys.argv[2], "w") as f:
    f.write("Authorization: Bearer " + token + "\n")
PY
# The account: the password goes from its file into the JSON body on stdin.
python3 -I - "$USERNAME" "$PW_FILE" > "$tmp/user.json" <<'PY'
import json, sys
password = open(sys.argv[2], encoding="utf-8").read().strip()
print(json.dumps({"username": sys.argv[1], "enabled": True, "emailVerified": False,
                  "requiredActions": ["CONFIGURE_TOTP"],
                  "credentials": [{"type": "password", "value": password, "temporary": False}]}))
PY
code=$(curl -sS -o "$tmp/create.out" -w '%{http_code}' -X POST "$KC/admin/realms/ops/users" -H @"$tmp/auth.hdr" \
  -H 'Content-Type: application/json' --data-binary @"$tmp/user.json")
rm -f "$tmp/user.json"
case "$code" in
  201) ;;
  409) echo "dev-ops-user.sh: $USERNAME exists already in the realm ops" >&2; exit 1 ;;
  *) echo "dev-ops-user.sh: creating the account failed (HTTP $code)" >&2; exit 1 ;;
esac
curl -fsS -o "$tmp/found.json" -G "$KC/admin/realms/ops/users" -H @"$tmp/auth.hdr" --data-urlencode "username=$USERNAME" \
  --data-urlencode exact=true
uid=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["id"])' "$tmp/found.json")
curl -fsS -o "$tmp/role.json" "$KC/admin/realms/ops/roles/ops" -H @"$tmp/auth.hdr"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); print(json.dumps([{"id": r["id"], "name": r["name"]}]))' "$tmp/role.json" > "$tmp/roles.json"
curl -fsS -X POST "$KC/admin/realms/ops/users/$uid/role-mappings/realm" -H @"$tmp/auth.hdr" -H 'Content-Type: application/json' \
  --data-binary @"$tmp/roles.json"
echo "dev-ops-user.sh: created $USERNAME in the realm ops with the role ops; the first login sets up the one-time code"
