#Notifications push Android avec Firebase — mise en service définitive

Ce document décrit **exactement** ce qui est déjà dans le dépôt, puis les étapes qui ne peuvent
être faites que par vous (création du projet Firebase, clés, déploiement de la fonction).

## Ce qui est déjà développé

| Élément | Fichier | État |
|---|---|---|
| Table des notifications + déclencheurs serveur | `supabase/migrations/0007_compte_notifications.sql` | écrit, **à exécuter** dans le SQL Editor |
| Table `device_tokens` (jetons FCM, RLS personnelle) | même migration | idem |
| Fonction Edge d'envoi FCM v1 | `supabase/functions/push/index.ts` | écrit, **à déployer** |
| Client Android : service FCM, canal, permission, enregistrement du jeton | `app/.../notifications/`, `data/.../PushRepository.kt` | compilé (`assembleDebug` OK) |
| Centre de notifications dans l'app | Android : voir matrice ; PWA : `#/notifications` | PWA vérifiée, Android : notifications système via FCM |

Rien n'est simulé : le serveur crée les notifications (`messages`, `post_reactions`,
`post_comments`, `community_members`), et la fonction Edge les pousse vers Firebase.

## 1. Exécuter la migration 0007

Supabase → **SQL Editor** → coller le contenu de
`supabase/migrations/0007_compte_notifications.sql` → **Run**.
Attendu : `Success. No rows returned`.

Vérification :

```sql
select count(*) from public.notifications;
select column_name from information_schema.columns
 where table_name = 'device_tokens';
```

## 2. Créer le projet Firebase (mode production, pas démo)

1. <https://console.firebase.google.com> → **Ajouter un projet**.
2. Nom : `tomate`. **Désactivez Google Analytics** (inutile ici).
3. Dans le projet : **Ajouter une application** → **Android**.
4. Identifiant de package : `app.tomate`
   (l'application publiée ; `applicationId` dans `app/build.gradle.kts`).
5. Téléchargez `google-services.json`.
6. **Ajoutez une seconde application Android**, package `app.tomate.debug` (la variante de
   développement porte ce suffixe). Téléchargez de nouveau `google-services.json` : le fichier
   contient alors les deux clients.

Placez le fichier final dans **`app/google-services.json`** (à côté de `app/build.gradle.kts`).
Il est ignoré par Git : il reste sur votre machine, jamais dans le dépôt public.

Le plugin Gradle s'active **automatiquement** dès que ce fichier existe ; sans lui, le build
reste valide et l'application fonctionne avec le seul centre de notifications.

## 3. Activer l'API d'envoi

1. <https://console.cloud.google.com> → sélectionnez le projet Firebase créé.
2. **API et services → Bibliothèque** → recherchez **Firebase Cloud Messaging API** → **Activer**.
   (Elle est normalement déjà active pour un projet Firebase récent.)
3. **IAM et administration → Comptes de service** → **Créer un compte de service** :
   - nom : `tomate-push` ;
   - rôle : **Firebase Cloud Messaging API Admin** (ou `Firebase Admin SDK Administrator`).
4. Ouvrez le compte créé → **Clés** → **Ajouter une clé** → **JSON** → téléchargez le fichier.
   Ce fichier est un **secret** : ne le commitez pas, ne le collez pas dans le chat.

## 4. Déployer la fonction Edge

Le déploiement se fait depuis le tableau de bord, sans CLI ni jeton personnel :

1. Supabase → **Edge Functions** → **Deploy a new function** → **Via editor**.
2. Nom : `push`.
3. Collez intégralement `supabase/functions/push/index.ts`.
4. **Désactivez « Verify JWT »** pour cette fonction : le webhook ne transporte pas de JWT
   utilisateur, il s'authentifie par le secret ci-dessous.
5. **Secrets** (Edge Functions → Secrets, ou Project Settings → Edge Functions) :

| Nom | Valeur |
|---|---|
| `FCM_SERVICE_ACCOUNT` | le JSON complet du compte de service, **sur une seule ligne** |
| `PUSH_WEBHOOK_SECRET` | une chaîne aléatoire longue (par exemple 40 caractères) |

`SUPABASE_URL` et `SUPABASE_SERVICE_ROLE_KEY` sont fournis automatiquement par Supabase.

## 5. Brancher le webhook

Supabase → **Database → Webhooks → Create a new hook** :

- Nom : `push_on_notification`
- Table : `notifications`
- Événements : **Insert** uniquement
- Type : **HTTP Request**
- Méthode : `POST`
- URL : `https://<votre-ref>.supabase.co/functions/v1/push`
- En-têtes HTTP : `x-webhook-secret: <la même valeur que PUSH_WEBHOOK_SECRET>`
- Corps : valeur par défaut (`record` est attendu par la fonction)

## 6. Vérifier de bout en bout

1. Installez l'APK sur un téléphone Android (API 26+).
2. Au premier lancement, acceptez la permission « Notifications ».
3. Connectez-vous avec un compte qui a un profil TOMATE (ou créez-le).
4. Depuis un **second** compte, envoyez un message à ce compte, ou commentez sa publication.
5. Attendu : une ligne apparaît dans `notifications` (SQL Editor) puis la notification système
   s'affiche sur le téléphone en quelques secondes.

Contrôle du jeton côté base :

```sql
select user_id, platform, left(token, 12) as token_debut, updated_at
  from public.device_tokens order by updated_at desc limit 5;
```

Diagnostic si rien n'arrive :

| Symptôme | Cause probable |
|---|---|
| `device_tokens` vide | `google-services.json` absent, ou permission refusée, ou app non relancée |
| Fonction répond `401` | `PUSH_WEBHOOK_SECRET` différent de l'en-tête du webhook |
| Fonction répond `FCM_SERVICE_ACCOUNT absent` | secret non enregistré (ou JSON sur plusieurs lignes) |
| Fonction `500` avec `OAuth Google refusé` | clé du compte de service révoquée / mauvais projet |
| Fonction `200` avec `sent: 0` | aucun jeton pour ce destinataire |

## 7. Ce qui n'est pas couvert

- **iOS** : aucun client iOS n'existe dans le dépôt.
- **Web Push (navigateur fermé)** : la table `push_subscriptions` est prête, mais l'envoi Web Push
  (VAPID + chiffrement `aes128gcm`) n'est pas implémenté. La PWA affiche les notifications dans
  l'application, pas sur l'écran verrouillé quand elle est fermée.
- Les notifications sont **émises par le serveur** ; aucune n'est créée côté client.
