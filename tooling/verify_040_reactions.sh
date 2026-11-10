#!/usr/bin/env bash
# =====================================================================================
# TOMATE 0.4.0 — vérification réelle des nouveaux chemins serveur.
#
# Utilise uniquement la clé PUBLIQUE du projet (aucun secret). Crée deux comptes
# anonymes réels, rejoue les chemins ajoutés par l'itération 0.4.0, nettoie tout,
# puis affiche un rapport PASS/FAIL.
#
#   ./tooling/verify_040_reactions.sh
#
# Prérequis : curl, jq, migrations 0001 → 0010 appliquées.
# Sortie : code 0 si toutes les assertions passent, 1 sinon.
# Les deux comptes anonymes restent dans auth.users (supprimables depuis le dashboard,
# comme pour rls_smoke_test.sh) ; toutes les lignes de test sont effacées.
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
        printf '  PASS  %-60s (%s)\n' "$label" "$actual"
        PASS=$((PASS + 1))
    else
        printf '  FAIL  %-60s attendu=%s obtenu=%s\n' "$label" "$expected" "$actual"
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

rest() {
    # rest <token> <method> <path+query> [body] — avec relecture (représentation).
    # NOTE : ne pas l'utiliser pour POST /conversations : la relecture PostgREST
    # intégrée y est refusée (403) alors que l'insertion a réussi — utiliser rest_min
    # comme les clients Android et web, puis vérifier par un GET séparé.
    local token="$1" method="$2" path="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /tmp/tomate_040_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -H "Prefer: return=representation" \
            -d "$body"
    else
        curl -sS -o /tmp/tomate_040_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Prefer: return=representation"
    fi
}

rest_min() {
    # rest_min <token> <method> <path+query> [body] — sans relecture (return=minimal),
    # exactement comme les clients Android et web pour les écritures.
    local token="$1" method="$2" path="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -sS -o /tmp/tomate_040_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Content-Type: application/json" \
            -H "Prefer: return=minimal" \
            -d "$body"
    else
        curl -sS -o /tmp/tomate_040_body -w '%{http_code}' -X "$method" \
            "$SUPABASE_URL/rest/v1/$path" \
            -H "apikey: $PUBLISHABLE_KEY" \
            -H "Authorization: Bearer $token" \
            -H "Prefer: return=minimal"
    fi
}

echo "== TOMATE 0.4.0 — chemins re joués contre le vrai serveur =="

read -r A_ID A_TOKEN <<< "$(signup)"
read -r B_ID B_TOKEN <<< "$(signup)"
echo "  comptes anonymes créés : ${A_ID:0:8}… et ${B_ID:0:8}…"

# 1. La migration 0010 est appliquée : la table se lit (vide), pas de PGRST205.
code=$(rest "$A_TOKEN" GET "message_reactions?select=message_id&limit=1")
check "0010 appliquée : message_reactions lisible" "200" "$code"

# 2. Profils (requis par les clés étrangères).
HANDLE_A="verif040a"
HANDLE_B="verif040b"
code=$(rest "$A_TOKEN" POST "profiles" "{\"id\":\"$A_ID\",\"handle\":\"$HANDLE_A\",\"display_name\":\"Vérif 040 A\"}")
check "profil A créé" "201" "$code"
code=$(rest "$B_TOKEN" POST "profiles" "{\"id\":\"$B_ID\",\"handle\":\"$HANDLE_B\",\"display_name\":\"Vérif 040 B\"}")
check "profil B créé" "201" "$code"

# 3. A crée une conversation (membre auto via trigger) — écriture minimale comme l'app.
CONV=$(cat /proc/sys/kernel/random/uuid)
code=$(rest_min "$A_TOKEN" POST "conversations" "{\"id\":\"$CONV\",\"title\":\"Vérif 0.4.0\",\"is_group\":true,\"created_by\":\"$A_ID\"}")
check "conversation créée (minimal, comme l'app)" "201" "$code"
code=$(rest "$A_TOKEN" GET "conversations?id=eq.$CONV&select=id")
check "conversation — relue par son créateur" "$CONV" "$(jq -r '.[0].id' /tmp/tomate_040_body)"

# 4. Renommage (chemin conversation_rename : PATCH conversations + relecture).
code=$(rest_min "$A_TOKEN" PATCH "conversations?id=eq.$CONV" '{"title":"Vérif 0.4.0 renommée"}')
check "renommage accepté" "204" "$code"
code=$(rest "$A_TOKEN" GET "conversations?id=eq.$CONV&select=title")
TITLE=$(jq -r '.[0].title' /tmp/tomate_040_body)
check "renommage — nouveau titre relu" "Vérif 0.4.0 renommée" "$TITLE"

# 5. Message + réponse citée (chemin reply_to_id).
C1=$(cat /proc/sys/kernel/random/uuid)
C2=$(cat /proc/sys/kernel/random/uuid)
M1=$(cat /proc/sys/kernel/random/uuid)
M2=$(cat /proc/sys/kernel/random/uuid)
code=$(rest_min "$A_TOKEN" POST "messages" "{\"id\":\"$M1\",\"client_id\":\"$C1\",\"conversation_id\":\"$CONV\",\"sender_id\":\"$A_ID\",\"body\":\"Message racine vérif 040\"}")
check "message racine envoyé" "201" "$code"
code=$(rest_min "$A_TOKEN" POST "messages" "{\"id\":\"$M2\",\"client_id\":\"$C2\",\"conversation_id\":\"$CONV\",\"sender_id\":\"$A_ID\",\"body\":\"Réponse citée vérif 040\",\"reply_to_id\":\"$M1\"}")
check "réponse avec reply_to_id envoyée" "201" "$code"
code=$(rest "$A_TOKEN" GET "messages?id=eq.$M2&select=reply_to_id")
REPLY_TO=$(jq -r '.[0].reply_to_id' /tmp/tomate_040_body)
check "réponse — citation relue" "$M1" "$REPLY_TO"

# 6. Réactions (chemins message_reaction_add/remove).
code=$(rest_min "$A_TOKEN" POST "message_reactions" "{\"message_id\":\"$M1\",\"user_id\":\"$A_ID\",\"emoji\":\"🍅\"}")
check "réaction 🍅 ajoutée" "201" "$code"
code=$(rest "$A_TOKEN" GET "message_reactions?message_id=eq.$M1&select=emoji")
N=$(jq 'length' /tmp/tomate_040_body)
check "réaction — 1 ligne relue" "1" "$N"
code=$(rest_min "$A_TOKEN" POST "message_reactions" "{\"message_id\":\"$M1\",\"user_id\":\"$A_ID\",\"emoji\":\"🍅\"}")
check "réaction — doublon refusé (409)" "409" "$code"
code=$(rest_min "$B_TOKEN" POST "message_reactions" "{\"message_id\":\"$M1\",\"user_id\":\"$B_ID\",\"emoji\":\"❤️\"}")
check "réaction — non-membre refusé (403)" "403" "$code"
code=$(rest "$B_TOKEN" GET "message_reactions?message_id=eq.$M1&select=emoji")
check "réaction — non-membre ne voit rien" "[]" "$(jq -c '.' /tmp/tomate_040_body)"
code=$(rest_min "$A_TOKEN" DELETE "message_reactions?message_id=eq.$M1&user_id=eq.$A_ID&emoji=eq.%F0%9F%8D%85")
check "réaction — suppression réelle" "204" "$code"
code=$(rest "$A_TOKEN" GET "message_reactions?message_id=eq.$M1&select=emoji")
check "réaction — relecture vide" "[]" "$(jq -c '.' /tmp/tomate_040_body)"

# 7. Édition de publication (chemin post_update : PATCH posts).
P1=$(cat /proc/sys/kernel/random/uuid)
code=$(rest_min "$A_TOKEN" POST "posts" "{\"id\":\"$P1\",\"author_id\":\"$A_ID\",\"body\":\"Brouillon vérif 040\",\"visibility\":\"Public\"}")
check "publication créée" "201" "$code"
code=$(rest_min "$A_TOKEN" PATCH "posts?id=eq.$P1" '{"body":"Texte modifié vérif 040"}')
check "édition acceptée" "204" "$code"
code=$(rest "$A_TOKEN" GET "posts?id=eq.$P1&select=body")
check "édition — nouveau texte relu" "Texte modifié vérif 040" "$(jq -r '.[0].body' /tmp/tomate_040_body)"
code=$(rest_min "$A_TOKEN" DELETE "posts?id=eq.$P1")
check "publication — suppression réelle" "204" "$code"
code=$(rest "$A_TOKEN" GET "posts?id=eq.$P1&select=id")
check "publication — relecture vide" "[]" "$(jq -c '.' /tmp/tomate_040_body)"

# 8. Nettoyage : la suppression de la conversation cascade tout.
code=$(rest_min "$A_TOKEN" DELETE "conversations?id=eq.$CONV")
check "conversation — suppression créateur" "204" "$code"
code=$(rest "$A_TOKEN" GET "messages?conversation_id=eq.$CONV&select=id")
check "conversation — messages en cascade effacés" "[]" "$(jq -c '.' /tmp/tomate_040_body)"
code=$(rest_min "$A_TOKEN" DELETE "profiles?id=eq.$A_ID")
check "profil A — supprimé" "204" "$code"
code=$(rest_min "$B_TOKEN" DELETE "profiles?id=eq.$B_ID")
check "profil B — supprimé" "204" "$code"

echo
echo "Résultat : $PASS PASS, $FAIL FAIL"
[ "$FAIL" -eq 0 ]
