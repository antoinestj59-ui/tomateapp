#!/usr/bin/env bash
# =====================================================================================
# TOMATE 0.5.0 — vérification réelle du chiffrement de bout en bout côté serveur.
#
# Ce que ce script prouve, en HTTP réel contre le projet Supabase :
#   1. les colonnes de clés publiques existent (migration 0011) ;
#   2. un compte publie ses clés publiques ;
#   3. il ne peut PAS modifier les clés d'un autre compte (RLS) ;
#   4. l'autre compte lit les bonnes clés ;
#   5. un message chiffré par le module de la PWA est stocké tel quel : le serveur ne voit
#      que du chiffré, jamais le texte clair ;
#   6. le destinataire relit exactement le corps chiffré envoyé.
#
#   ./tooling/verify_050_e2ee.sh
#
# Prérequis : curl, jq, node, migration 0011 appliquée.
# Sortie : code 0 si tout passe, 1 sinon. Les comptes anonymes restent dans auth.users
# (supprimables depuis le dashboard) ; toutes les lignes de test sont effacées.
# =====================================================================================
set -uo pipefail

SUPABASE_URL="${SUPABASE_URL:-https://omsupwmlncuapikbjumg.supabase.co}"
PUBLISHABLE_KEY="${PUBLISHABLE_KEY:-sb_publishable_IOga6ANcbYMivphwt4mR7Q_VziRyrIj}"

command -v jq >/dev/null 2>&1 || { echo "jq est requis" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "node est requis" >&2; exit 1; }

PASS=0
FAIL=0

check() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        printf '  PASS  %-62s (%s)\n' "$label" "$actual"
        PASS=$((PASS + 1))
    else
        printf '  FAIL  %-62s attendu=%s obtenu=%s\n' "$label" "$expected" "$actual"
        FAIL=$((FAIL + 1))
    fi
}

signup() {
    local response token user
    response=$(curl -sS -X POST "$SUPABASE_URL/auth/v1/signup" \
        -H "apikey: $PUBLISHABLE_KEY" \
        -H "Content-Type: application/json" \
        -d '{}')
    token=$(printf '%s' "$response" | jq -r '.access_token // empty')
    user=$(printf '%s' "$response" | jq -r '.user.id // empty')
    if [ -z "$token" ] || [ -z "$user" ]; then
        echo "Impossible de créer un compte anonyme : $response" >&2
        exit 1
    fi
    printf '%s %s' "$user" "$token"
}

# rest <token> <method> <path+query> [body] -> code HTTP, corps dans /tmp/tomate_050_body
rest() {
    local token="$1" method="$2" path="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /tmp/tomate_050_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -H "Prefer: return=representation" \
            -d "$body"
    else
        curl -sS -o /tmp/tomate_050_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Accept: application/json"
    fi
}

# insert without representation (same path as Android and the PWA)
rest_min() {
    local token="$1" path="$2" body="$3"
    curl -sS -o /tmp/tomate_050_body -w '%{http_code}' -X POST \
        "$SUPABASE_URL/rest/v1/$path" \
        -H "apikey: $PUBLISHABLE_KEY" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -H "Prefer: return=minimal" \
        -d "$body"
}

echo "TOMATE 0.5.0 — chiffrement de bout en bout (HTTP réel)"

read -r ALICE_ID ALICE_TOKEN <<< "$(signup)"
read -r BOB_ID BOB_TOKEN <<< "$(signup)"
CONVERSATION_ID="$(node -e 'console.log(crypto.randomUUID())')"
SUFFIX="$(node -e 'console.log(Math.random().toString(36).slice(2, 8))')"

# Les clés étrangères exigent un profil avant toute conversation (comme l'onboarding de l'app).
CODE=$(rest "$ALICE_TOKEN" POST "profiles" "{\"id\":\"$ALICE_ID\",\"handle\":\"e2ee_a_$SUFFIX\",\"display_name\":\"Vérif E2EE A\"}")
check "profil Alice créé" "201" "$CODE"
CODE=$(rest "$BOB_TOKEN" POST "profiles" "{\"id\":\"$BOB_ID\",\"handle\":\"e2ee_b_$SUFFIX\",\"display_name\":\"Vérif E2EE B\"}")
check "profil Bob créé" "201" "$CODE"

# --- 1. Les colonnes de clés existent -------------------------------------------------
CODE=$(rest "$ALICE_TOKEN" GET "profiles?select=e2ee_agree_key,e2ee_sign_key&id=eq.$ALICE_ID")
check "migration 0011 : colonnes de clés lisibles" "200" "$CODE"
if [ "$CODE" != "200" ]; then
    echo "  NOTE  migration 0011 non appliquée : les contrôles d'annuaire ci-dessous échouent"
    echo "        (le chiffrement lui-même est déjà vérifié plus bas)."
fi

# --- 2. Les identités et le message chiffré sont produits par le vrai module PWA ------
FIXTURE=$(node "$(dirname "$0")/e2ee_seal.mjs" "$ALICE_ID" "$BOB_ID" "$CONVERSATION_ID")
if [ -z "$FIXTURE" ]; then
    echo "  FAIL  génération du message chiffré impossible"
    FAIL=$((FAIL + 1))
    exit 1
fi
ALICE_AGREE=$(printf '%s' "$FIXTURE" | jq -r '.aliceAgree')
ALICE_SIGN=$(printf '%s' "$FIXTURE" | jq -r '.aliceSign')
BOB_AGREE=$(printf '%s' "$FIXTURE" | jq -r '.bobAgree')
BOB_SIGN=$(printf '%s' "$FIXTURE" | jq -r '.bobSign')
SEALED=$(printf '%s' "$FIXTURE" | jq -r '.body')
PLAINTEXT=$(printf '%s' "$FIXTURE" | jq -r '.plaintext')

check "corps chiffré au bon format" "oui" "$(printf '%s' "$SEALED" | grep -q '^\[\[tomate:e2ee:v1\]\]' && echo oui || echo non)"
check "le corps chiffré ne contient pas le texte clair" "non" "$(printf '%s' "$SEALED" | grep -qF "$PLAINTEXT" && echo oui || echo non)"

# --- 3. Publication des clés d'Alice ---------------------------------------------------
CODE=$(rest "$ALICE_TOKEN" PATCH "profiles?id=eq.$ALICE_ID" \
    "{\"e2ee_agree_key\":\"$ALICE_AGREE\",\"e2ee_sign_key\":\"$ALICE_SIGN\"}")
check "Alice publie ses clés publiques" "200" "$CODE"
CODE=$(rest "$BOB_TOKEN" PATCH "profiles?id=eq.$BOB_ID" \
    "{\"e2ee_agree_key\":\"$BOB_AGREE\",\"e2ee_sign_key\":\"$BOB_SIGN\"}")
check "Bob publie ses clés publiques" "200" "$CODE"

# --- 4. Un compte ne peut pas écrire les clés d'un autre (RLS) ------------------------
rest "$BOB_TOKEN" GET "profiles?select=e2ee_agree_key,e2ee_sign_key&id=eq.$ALICE_ID" >/dev/null
BEFORE_KEYS=$(jq -c '.[0] // {}' /tmp/tomate_050_body)
rest "$BOB_TOKEN" PATCH "profiles?id=eq.$ALICE_ID" \
    "{\"e2ee_agree_key\":\"AAA\",\"e2ee_sign_key\":\"AAA\"}" >/dev/null
rest "$BOB_TOKEN" GET "profiles?select=e2ee_agree_key,e2ee_sign_key&id=eq.$ALICE_ID" >/dev/null
AFTER_KEYS=$(jq -c '.[0] // {}' /tmp/tomate_050_body)
check "Bob ne peut pas réécrire les clés d'Alice" "$BEFORE_KEYS" "$AFTER_KEYS"

# --- 5. Lecture croisée des clés ------------------------------------------------------
CODE=$(rest "$BOB_TOKEN" GET "profiles?select=e2ee_agree_key,e2ee_sign_key&id=eq.$ALICE_ID")
READ_AGREE=$(jq -r '.[0].e2ee_agree_key' /tmp/tomate_050_body 2>/dev/null)
READ_SIGN=$(jq -r '.[0].e2ee_sign_key' /tmp/tomate_050_body 2>/dev/null)
check "Bob lit la clé d'accord d'Alice" "$ALICE_AGREE" "$READ_AGREE"
check "Bob lit la clé de signature d'Alice" "$ALICE_SIGN" "$READ_SIGN"

# --- 6. Le message chiffré voyage et se stocke tel quel -------------------------------
CODE=$(rest_min "$ALICE_TOKEN" "conversations" \
    "{\"id\":\"$CONVERSATION_ID\",\"title\":\"e2ee-check\",\"is_group\":false,\"created_by\":\"$ALICE_ID\"}")
check "conversation créée" "201" "$CODE"
CODE=$(rest "$ALICE_TOKEN" GET "conversation_members?select=user_id&conversation_id=eq.$CONVERSATION_ID&user_id=eq.$ALICE_ID")
check "Alice membre (ajoutée par le trigger)" "1" "$(jq 'length' /tmp/tomate_050_body)"
CODE=$(rest_min "$ALICE_TOKEN" "conversation_members" \
    "{\"conversation_id\":\"$CONVERSATION_ID\",\"user_id\":\"$BOB_ID\"}")
check "Bob membre" "201" "$CODE"

MESSAGE_ID="$(node -e 'console.log(crypto.randomUUID())')"
CLIENT_ID="$(node -e 'console.log(crypto.randomUUID())')"
PAYLOAD="{\"id\":\"$MESSAGE_ID\",\"client_id\":\"$CLIENT_ID\",\"conversation_id\":\"$CONVERSATION_ID\",\"sender_id\":\"$ALICE_ID\",\"body\":\"$SEALED\",\"encrypted\":true}"
CODE=$(rest_min "$ALICE_TOKEN" "messages" "$PAYLOAD")
check "message chiffré accepté par le serveur" "201" "$CODE"

CODE=$(rest "$BOB_TOKEN" GET "messages?select=body,encrypted&conversation_id=eq.$CONVERSATION_ID")
STORED=$(jq -r '.[0].body' /tmp/tomate_050_body 2>/dev/null)
STORED_ENCRYPTED=$(jq -r '.[0].encrypted' /tmp/tomate_050_body 2>/dev/null)
check "Bob relit exactement le corps chiffré" "$SEALED" "$STORED"
check "le drapeau encrypted est vrai en base" "true" "$STORED_ENCRYPTED"
check "le texte clair n'est pas en base" "non" "$(jq -r '.[0].body' /tmp/tomate_050_body | grep -qF "$PLAINTEXT" && echo oui || echo non)"

# --- 7. Nettoyage ---------------------------------------------------------------------
rest "$ALICE_TOKEN" DELETE "messages?conversation_id=eq.$CONVERSATION_ID" >/dev/null
rest "$ALICE_TOKEN" DELETE "conversations?id=eq.$CONVERSATION_ID" >/dev/null
rest_min "$ALICE_TOKEN" "profiles?id=eq.$ALICE_ID" >/dev/null 2>&1 || true
curl -sS -o /dev/null -X DELETE "$SUPABASE_URL/rest/v1/profiles?id=eq.$ALICE_ID" \
    -H "apikey: $PUBLISHABLE_KEY" -H "Authorization: Bearer $ALICE_TOKEN" -H "Prefer: return=minimal"
curl -sS -o /dev/null -X DELETE "$SUPABASE_URL/rest/v1/profiles?id=eq.$BOB_ID" \
    -H "apikey: $PUBLISHABLE_KEY" -H "Authorization: Bearer $BOB_TOKEN" -H "Prefer: return=minimal"

LEFTOVER=$(rest "$BOB_TOKEN" GET "messages?select=id&conversation_id=eq.$CONVERSATION_ID" >/dev/null; jq 'length' /tmp/tomate_050_body 2>/dev/null || echo "?")
check "aucune trace laissée" "0" "$LEFTOVER"

echo
echo "  $PASS PASS / $FAIL FAIL"
[ "$FAIL" -eq 0 ] || exit 1
