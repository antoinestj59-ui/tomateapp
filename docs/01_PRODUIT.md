# 01 — TOMATE : produit, périmètre et écrans

## 1. Vision

TOMATE est un réseau social Android **natif**, **offline-first**, en **français**, dont la
promesse tient en trois points :

1. **Ça marche sans réseau.** Publier, commenter, réagir, discuter : tout est écrit
   localement d'abord, puis synchronisé quand la connexion revient.
2. **Ça marche sans serveur à proximité.** Deux téléphones peuvent échanger directement en
   Bluetooth Low Energy ou en Wi-Fi local, et un troisième peut relayer sans lire.
3. **Ça ne ment pas.** Aucun compteur inventé, aucun bouton décoratif, aucune promesse de
   chiffrement de bout en bout tant qu'elle n'est pas tenue.

Public visé : usage communautaire de proximité (quartier, événement, établissement), là où
la connexion est mauvaise ou chère.

## 2. Principes de conception

| Principe | Traduction concrète |
|---|---|
| Offline-first | Room est la source de vérité locale ; le réseau est un synchroniseur, pas un prérequis |
| Identité légère | Compte anonyme Supabase, pseudo choisi localement, aucun e-mail obligatoire |
| Honnêteté de l'état | Chaque écran a ses états : chargement, vide, erreur, hors ligne |
| Sobriété | Pas de publicité, pas de traqueur, pas de permission inutile |
| Contenu opaque en relais | Un relais transporte une enveloppe, jamais du contenu lisible par lui |
| Modération réelle | Signalement, blocage, suppression, rôles de communauté — pas seulement des menus |

## 3. Écrans livrés

| Écran | Contenu réel | Dépendances |
|---|---|---|
| **Onboarding** | Pseudo (validé 3–20 `[a-z0-9_]`, réservés refusés), visibilité du profil, avertissement explicite sur le compte anonyme | `ProfileRepository`, Supabase |
| **Accueil / Fil** | Composeur (2000 caractères), fil local, réactions, compteur de commentaires, bandeau hors ligne, compteur d'opérations en attente | Room, `FeedRepository`, `SyncEngine` |
| **Détail publication** | Publication, commentaires (ajout, suppression), réaction, signalement, blocage de l'auteur, suppression de sa publication | Room, modération |
| **Communautés** | Liste réelle, création (publique/privée), adhésion, demande en attente, sortie | Room, `CommunityRepository` |
| **Détail communauté** | Description, nombre de membres, rôle, composeur réservé aux membres actifs, fil de la communauté | Room, RLS |
| **Messages** | Liste des conversations avec dernier message et état de livraison | Room |
| **Conversation** | Messages, envoi local-first, déduplication par `client_id`, état « en attente », bandeau E2EE honnête | Room, sync |
| **Proximité** | Permissions runtime, découverte BLE/Wi-Fi, envoi direct, activation du relais, file de relais | `:net` |
| **Profil** | Profil éditable, identifiant de compte, code de récupération, blocages, opérations en attente, déconnexion | Room, Keystore, modération |
| **Réglages** | Thème (système/clair/sombre), couleurs dynamiques, relais, économie de batterie, confidentialité, à propos | `SettingsStore` |

## 4. États obligatoires

Chaque liste et chaque action respecte la même grammaire visuelle :

- **Chargement** : indicateur + libellé explicite (« Chargement du fil… »).
- **Vide** : icône, titre, explication, action quand elle a un sens.
- **Erreur** : message en français issu de `AppError`, action de réessai quand possible.
- **Hors ligne** : bandeau informatif, jamais une erreur bloquante.
- **En attente** : opérations non synchronisées comptées et visibles.
- **Indisponible** : fonction présente mais non opérationnelle → affichée comme telle
  (ex. restauration de compte, E2EE).

## 5. Ce que TOMATE ne fait pas (volontairement)

| Non-fonctionnalité | Raison | Où c'est dit |
|---|---|---|
| Chiffrement de bout en bout | Aucun protocole vérifié intégré ; ne pas mentir | Profil, Conversation, Réglages, `docs/04_SECURITE.md` |
| Restauration de compte sur un autre appareil | Nécessite une fonction serveur non déployée | Profil |
| Appels audio/vidéo | Hors périmètre de la version 0.1 | — |
| Upload d'avatars depuis l'app | Bucket et politiques fournis, UI d'upload non branchée | `docs/05_SUPABASE_SETUP.md` |
| Notifications push | Nécessite FCM + fonction serveur | — |
| Suppression de compte côté serveur | Exigée par Play Store, à implémenter avant publication | `docs/06_TESTS_ET_LIVRAISON.md` |

## 6. Parcours principal

```
Premier lancement
  └─ Onboarding (pseudo + visibilité)
       └─ Fil (vide → composer une publication)
            ├─ Détail publication → commenter / réagir / signaler / bloquer
            ├─ Communautés → créer / rejoindre → publier dans la communauté
            ├─ Messages → conversation → envoi (local-first)
            └─ Proximité → permissions → découverte → envoi direct / relais
```

## 7. Accessibilité et ergonomie

- Cibles tactiles ≥ 48 dp, contrastes du thème Material 3, tailles de texte dynamiques.
- Tous les textes sont en français, sans jargon technique dans l'interface.
- Les erreurs réseau ne bloquent jamais la lecture du contenu déjà présent.
- Les actions destructrices (déconnexion, suppression) demandent confirmation.
- `contentDescription` renseigné sur les icônes interactives.
