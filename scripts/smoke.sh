#!/usr/bin/env bash
# End-to-end smoke test against a running stack: signs in to Keycloak, uploads every file given
# to it, waits for ingestion, asks questions, and prints what the admin console would show.
#
#   docker compose up -d && ./mvnw -pl backend spring-boot:run   # in another terminal
#   ./scripts/smoke.sh                          # uses the sample corpus below
#   ./scripts/smoke.sh mydoc.pdf notes.md       # or your own files
set -euo pipefail

API="${API:-http://localhost:8081}"
KC="${KC:-http://localhost:8180}"
REALM="${REALM:-ossian}"
CLIENT="${CLIENT:-ossian-frontend}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

token() {
  curl -sf -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d "client_id=$CLIENT" -d grant_type=password \
    -d "username=$1" -d "password=$2" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

say "1. sign in as $USERNAME"
TOK=$(token "$USERNAME" "$PASSWORD")
python3 - "$TOK" <<'PY'
import sys, json, base64
p = sys.argv[1].split('.')[1]; p += '=' * (-len(p) % 4)
c = json.loads(base64.urlsafe_b64decode(p))
print(f"   {c.get('preferred_username')}  roles={c.get('realm_access',{}).get('roles')}")
PY

say "2. upload"
FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=(docs/samples/*.txt docs/samples/*.md)
fi
for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "   skip (not a file): $f"; continue; }
  curl -sf -X POST "$API/api/documents" -H "Authorization: Bearer $TOK" -F "file=@$f" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('   %-28s %s%s' % ('$(basename "$f")', d['status'], ' (duplicate)' if d['duplicate'] else ''))"
done

say "3. wait for ingestion"
for _ in $(seq 1 60); do
  PENDING=$(curl -sf -H "Authorization: Bearer $TOK" "$API/api/documents?size=100" \
    | python3 -c "import json,sys; print(sum(1 for d in json.load(sys.stdin)['content'] if d['status'] not in ('READY','FAILED')))")
  [ "$PENDING" = "0" ] && break
  sleep 2
done
curl -sf -H "Authorization: Bearer $TOK" "$API/api/documents?size=100" \
| python3 -c "
import json,sys
for d in json.load(sys.stdin)['content']:
    print('   %-28s %-8s %3s chunks' % (d['filename'], d['status'], d.get('chunkCount')))"

say "4. ask"
QS=(
  "Can we deploy to production on a Friday?"
  "When does the on-call rotation start?"
  "How many days of notice do I need to give before taking leave?"
  "What is the refund window for enterprise customers?"
  "Who won the 1998 world cup?"
)
for q in "${QS[@]}"; do
  echo "   Q: $q"
  curl -sf -X POST "$API/api/chat" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"question": sys.argv[1]}))' "$q")" \
  | python3 -c "
import json,sys,textwrap
d = json.load(sys.stdin)
for line in textwrap.wrap(d['answer'], 92)[:4]:
    print('   A: ' + line)
cites = ', '.join('%s (%.2f)' % (c['filename'], c['score']) for c in d['citations']) or 'none'
print('      grounded=%s  cites=%s  %dms\n' % (d['answeredFromContext'], cites, d['latencyMs']))"
done

say "5. a machine credential, confined to one namespace"
# The isolation that exists in a single-organisation deployment is not between people -- they
# all work for the same company -- but between credentials. A key issued to one pipeline should
# not read the rest of the corpus if it leaks.
KEYJSON=$(curl -sf -X POST "$API/api/admin/api-keys" -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-confined","roles":["ossian-user"],"namespace":"default"}')
KEY=$(printf '%s' "$KEYJSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["secret"])')
printf '   issued %s\n' "$(printf '%s' "$KEYJSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"]["keyPrefix"])')"
curl -sf -X POST "$API/api/chat" -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"question":"Can we deploy to production on a Friday?"}' \
| python3 -c "import json,sys; d=json.load(sys.stdin); print('   key can ask its own namespace:', d['answeredFromContext'])"
printf '   key naming another namespace -> HTTP %s (403 expected)\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: $KEY" "$API/api/documents?namespace=hr-policies")"
printf '   key reaching the admin console -> HTTP %s (403 expected)\n' \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: $KEY" "$API/api/admin/stats/corpus")"

say "6. role check — a plain user must not reach the admin console"
USERTOK=$(token user user)
printf '   user  -> /api/admin/stats/corpus  HTTP %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $USERTOK" "$API/api/admin/stats/corpus")"
printf '   admin -> /api/admin/stats/corpus  HTTP %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" "$API/api/admin/stats/corpus")"

say "7. what the admin console shows"
for p in stats/corpus stats/retrieval gaps; do
  printf '   %-16s ' "$p"
  curl -sf -H "Authorization: Bearer $TOK" "$API/api/admin/$p" | head -c 300; echo
done
