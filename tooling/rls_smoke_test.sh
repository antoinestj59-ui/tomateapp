#!/usr/bin/env bash
# =====================================================================================
# TOMATE — vérification RLS avec deux comptes anonymes réels.
#
# Utilise uniquement la clé PUBLIQUE du projet (aucun secret). Crée deux comptes anonymes
# réels sur le projet Supabase, exécute 7 assertions RLS, puis affiche un rapport.
#
#   ./tooling/rls_smoke_test.sh
#
# Prérequis : curl, jq, et les migrations 0001 + 0002 appliquées.
# Sortie : code 0 si toutes les assertions passent, 1 sinon.
# =====================================================================================
set -uo pipefail

SUPABASE_URL="${SUPABASE_URL:-https://omsupwmlncuapikbjumg.supabase.co}"
PUBLISHABLE_KEY="${PUBLISHABLE_KEY:-sb_publishable_IOga6ANcbYMivphwt4mR7Q_VziRyrIj}"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq est requis" >&2
    exit 1
fi

PASS=0
FAIL=0

check() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        printf '  PASS  %-58s (%s)\n' "$label" "$actual"
        PASS=$((PASS + 1))
    else
        printf '  FAIL  %-58s attendu=%s obtenu=%s\n' "$label" "$expected" "$actual"
        FAIL=$((FAIL + 1))
    fi
}

signup() {
    local response
    response=$(curl -sS -X POST "$SUPABASE_URL/auth/v1/signup" \
        -H "apikey: $PUBLISHABLE_KEY" \
        -H "Content-Type: application/json" \
        -d '{}')
    local token user
    token=$(printf '%s' "$response" | jq -r '.access_token // empty')
    user=$(printf '%s' "$response" | jq -r '.user.id // empty')
    if [ -z "$token" ] || [ -z "$user" ]; then
        echo "Impossible de créer un compte anonyme : $response" >&2
        echo "Vérifier que les connexions anonymes sont activées." >&2
        exit 1
    fi
    printf '%s %s' "$user" "$token"
}

rest() {
    # rest <token> <method> <path+query> [body]
    local token="$1" method="$2" path="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /tmp/tomate_rls_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -H "Prefer: return=representation" \
            -d "$body"
    else
        curl -sS -o /tmp/tomate_rls_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Prefer: return=representation"
    fi
}

rest_min() {
    # rest_min <token> <method> <path> [body] — pas de relecture de la ligne (return=minimal),
    # comme les clients Android et web pour conversations / membres.
    local token="$1" method="$2" path="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /tmp/tomate_rls_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -H "Prefer: return=minimal" \
            -d "$body"
    else
        curl -sS -o /tmp/tomate_rls_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Prefer: return=minimal"
    fi
}

body_rows() { jq 'if type == "array" then length else 0 end' /tmp/tomate_rls_body 2>/dev/null || echo 0; }

echo "== Création de deux comptes anonymes réels =="
read -r USER_A TOKEN_A <<< "$(signup)"
read -r USER_B TOKEN_B <<< "$(signup)"
echo "  A = $USER_A"
echo "  B = $USER_B"

HANDLE_A="rls_a_$(date +%s | tail -c 6)"
HANDLE_B="rls_b_$(date +%s | tail -c 6)"

echo "== Préparation des profils =="
status=$(rest "$TOKEN_A" POST profiles "{\"id\":\"$USER_A\",\"handle\":\"$HANDLE_A\",\"display_name\":\"Compte A\",\"visibility\":\"Public\"}")
if [ "$status" != "201" ]; then
    echo "  Échec de création du profil A (HTTP $status) : $(cat /tmp/tomate_rls_body)" >&2
    echo "  → les migrations 0001/0002 sont-elles appliquées ?" >&2
    exit 1
fi
rest "$TOKEN_B" POST profiles "{\"id\":\"$USER_B\",\"handle\":\"$HANDLE_B\",\"display_name\":\"Compte B\",\"visibility\":\"Public\"}" >/dev/null

echo "== Assertions RLS =="
rest "$TOKEN_A" GET "profiles?id=eq.$USER_A" >/dev/null
check "A relit son propre profil" "1" "$(body_rows)"

rest "$TOKEN_B" GET "profiles?id=eq.$USER_A" >/dev/null
check "B lit le profil public de A" "1" "$(body_rows)"

status=$(rest "$TOKEN_B" PATCH "profiles?id=eq.$USER_A" '{"bio":"piraté"}')
check "B ne peut pas modifier le profil de A (0 ligne)" "0" "$(body_rows)"

status=$(rest "$TOKEN_B" POST posts "{\"author_id\":\"$USER_A\",\"body\":\"usurpation\",\"visibility\":\"Public\"}")
check "B ne peut pas publier sous l'identité de A (403)" "403" "$status"

rest "$TOKEN_A" POST posts "{\"author_id\":\"$USER_A\",\"body\":\"secret de A\",\"visibility\":\"Private\"}" >/dev/null
rest "$TOKEN_B" GET "posts?author_id=eq.$USER_A&visibility=eq.Private" >/dev/null
check "B ne lit pas une publication privée de A (0 ligne)" "0" "$(body_rows)"

rest "$TOKEN_A" POST blocks "{\"blocker_id\":\"$USER_A\",\"blocked_id\":\"$USER_B\"}" >/dev/null
rest "$TOKEN_B" GET "profiles?id=eq.$USER_A" >/dev/null
check "Après blocage, B ne lit plus le profil de A (0 ligne)" "0" "$(body_rows)"

CONV_ID=$(cat /proc/sys/kernel/random/uuid)
status=$(rest_min "$TOKEN_A" POST conversations "{\"id\":\"$CONV_ID\",\"created_by\":\"$USER_A\",\"is_group\":false}")
check "A crée une conversation (201)" "201" "$status"
rest_min "$TOKEN_A" POST conversation_members "{\"conversation_id\":\"$CONV_ID\",\"user_id\":\"$USER_A\"}" >/dev/null
status=$(rest_min "$TOKEN_A" POST messages "{\"id\":\"$(cat /proc/sys/kernel/random/uuid)\",\"client_id\":\"$(cat /proc/sys/kernel/random/uuid)\",\"conversation_id\":\"$CONV_ID\",\"sender_id\":\"$USER_A\",\"body\":\"message privé\"}")
check "A envoie un message (201)" "201" "$status"

rest "$TOKEN_B" GET "messages?conversation_id=eq.$CONV_ID" >/dev/null
check "B ne lit pas les messages de A (0 ligne)" "0" "$(body_rows)"

rest "$TOKEN_B" GET "conversations?id=eq.$CONV_ID" >/dev/null
check "B ne lit pas la conversation de A (0 ligne)" "0" "$(body_rows)"

echo
echo "== Rapport =="
echo "  PASS : $PASS"
echo "  FAIL : $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
