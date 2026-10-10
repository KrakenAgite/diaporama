# Diaporama

Fond d'écran animé Android qui fait défiler tes photos, sur l'écran d'accueil et l'écran de verrouillage.
Interface en français et en anglais.

*English: an Android live wallpaper that cycles through your photos on the home and lock screens.*

## Installer

Télécharge `diaporama-x.y.apk` depuis la [dernière release](https://github.com/KrakenAgite/diaporama/releases/latest)
et ouvre-le sur le téléphone (Android 15 ou plus).

Puis, dans l'app :

1. **Photos** : choisis des photos dans tes albums Google Photos, ajoute des dossiers ou coche des dossiers de la galerie.
2. **Définir** : dans l'aperçu, choisis **« Écran d'accueil et écran de verrouillage »**.

## Fonctions

| | |
|---|---|
| **Sources** | Photos d'albums Google Photos (sélecteur d'Android, 100 à la fois, à répéter), dossiers avec leurs sous-dossiers, dossiers de la galerie (Camera, Screenshots…) |
| **Changement** | Toutes les 1 min à 1 jour, à heures fixes, à chaque mise en veille (la nouvelle photo est prête au réveil), double-tap sur le bureau, secousse |
| **Manuel** | Bouton « Photo suivante » dans l'app, tuile « Fond suivant » des réglages rapides |
| **Affichage** | Ordre aléatoire, fondu, cadrage Remplir / Ajuster / Centrer, et pour chaque photo sa propre taille, rotation et position (pincer, tourner à deux doigts, glisser, quarts de tour) |
| **Mises à jour** | Vérification sur GitHub (au plus toutes les 12 h, à l'ouverture), téléchargement, contrôle de l'empreinte SHA-256 et installation automatique ; sinon une notification |

L'intervalle et les heures fixes ne tournent que quand le fond d'écran est visible, pour économiser la batterie :
un changement dû pendant que l'écran était éteint se fait au retour sur le bureau.

### Limites

- Android ne permet pas d'ajouter un album Google Photos entier : on y sélectionne les photos, et celles ajoutées
  plus tard à l'album ne sont pas reprises automatiquement.
- Le double-tap dépend du lanceur, qui doit transmettre les touchers au fond d'écran.

## Compiler

Avec le JDK d'Android Studio, téléphone branché (USB ou débogage sans fil) :

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

La release est signée avec la clé décrite dans `keystore.properties` à la racine (exclu de git) :

```properties
storeFile=/chemin/vers/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Sans ce fichier, la release n'est pas signée. Garder la même clé d'une version à l'autre : sinon Android refuse
la mise à jour et il faut désinstaller l'app, ce qui efface ses réglages.

## Code

| Fichier | Rôle |
|---|---|
| `SlideshowWallpaperService.kt` | Le fond d'écran : chargement des photos, déclencheurs, dessin et fondu |
| `MainActivity.kt` | Écran de réglages (Compose, Material You) |
| `PhotoEditor.kt` | Écran « Photo par photo » : aperçu et gestes pour retoucher chaque photo |
| `PhotoTransform.kt` | Taille, rotation et position propres à une photo, et la matrice de dessin commune |
| `Updates.kt` | Mises à jour depuis les releases GitHub |
| `PhotoSource.kt` | Liste des photos des dossiers, albums et sélections |
| `Settings.kt` | Réglages partagés (SharedPreferences) |
| `NextTileService.kt` | Tuile « Fond suivant » |
| `I18n.kt` | Textes français / anglais |
