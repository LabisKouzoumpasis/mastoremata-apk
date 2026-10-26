# CLAUDE.md — Μαστορέματα (κέλυφος Android)

Το κέλυφος Android της εφαρμογής «Μαστορέματα». Οι οθόνες και όλη η λογική είναι στο αποθετήριο
**mastoremata** (`index.html`, τεστ jsdom, και το κύριο CLAUDE.md με τον χρήστη, τις ενότητες και τις συμβάσεις).
Εδώ μόνο Kotlin, πόροι και χτίσιμο. Βασίστηκε στο κέλυφος της «Καθημερινότητας» (`kathimerinotita`).
Επικοινωνία με τον χρήστη στα ελληνικά, απλά.

## Οθόνες: αντιγραφή HTML → assets
```
mastoremata/index.html  ──αντιγραφή──►  mastoremata-apk/app/src/main/assets/index.html
```
1. Στο mastoremata: αλλαγή στο `index.html`, `cd tools/test && npm install && npm test` (πρέπει να περνούν).
2. `cp ../mastoremata/index.html app/src/main/assets/index.html` (τα δύο αποθετήρια δίπλα-δίπλα).
3. Commit και push στο `main` εδώ → το GitHub Actions βγάζει APK.
Μην αλλάζεις το `assets/index.html` απευθείας εδώ· η πηγή είναι το mastoremata.

## Δομή
```
app/src/main/java/gr/mastoremata/
  MainActivity.kt  WebView, αρχεία (SAF), κάμερα (ACTION_IMAGE_CAPTURE) και συλλογή (ACTION_PICK_IMAGES / GET_CONTENT),
                   φάκελος αντιγράφων, κοινοποίηση
  Bridge.kt        Η γέφυρα `window.AndroidBridge` (@JavascriptInterface)· λίστα μεθόδων στο CLAUDE.md του mastoremata
  Db.kt            SQLite (singleton Db.get): records(kind,id,day,data,updated), meta. DB έκδοση 1.
  Photos.kt        Φωτογραφίες στο files/photos: μικραίνουν στα 1600 px, γυρίζουν κατά EXIF, JPEG 82. Ονόματα `Photos.NAME`.
  Backup.kt        Αντίγραφα .zip (data.json + photos/). Αυτόματα σε φάκελο (SAF), ένα τη μέρα· κρατά 7 ημέρες +
                   ένα ανά εβδομάδα για 8 εβδομάδες (`toDelete`). `unzip` δεν αντικαθιστά υπάρχουσες φωτογραφίες.
  ShareFiles.kt    ShareProvider (δικός μας μικρός FileProvider): `share/` μόνο ανάγνωση (αντίγραφο προς Drive),
                   `cam/` και εγγραφή (εκεί γράφει η κάμερα). ChooserReceiver (ποια εφαρμογή διαλέχτηκε → `consumeShared`).
```
Πακέτο / applicationId: `gr.mastoremata`. Καμία εξάρτηση AndroidX. minSdk 26, targetSdk/compileSdk 34.
**Καμία άδεια** στο manifest: η κάμερα ανοίγει με intent χωρίς CAMERA (αν δηλωθεί CAMERA χωρίς να δοθεί, το intent αποτυγχάνει).
Εικονίδιο: adaptive (vector κλειδί σε `drawable/ic_launcher_fg.xml`, `ic_launcher_mono.xml`, φόντο `@color/icon_bg`) — προσωρινό.

## Χτίσιμο και έκδοση
- **GitHub Actions** (`.github/workflows/build.yml`): σε κάθε push στο `main` ελέγχει ότι υπάρχει το `assets/index.html`,
  χτίζει με Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21 / JDK 17, υπογράφει και δημοσιεύει στα **Releases**
  ως `Mastoremata-1.0.<run>.apk` (tag `v1.0.<run>`). versionCode = αριθμός εκτέλεσης.
- Μυστικά: `KS_BASE64, KS_PASSWORD, KS_ALIAS, KS_KEY_PASSWORD`. Το κλειδί ΔΕΝ μπαίνει στο αποθετήριο (`.gitignore`).
  Χωρίς αυτό το APK υπογράφεται προσωρινά και δεν ενημερώνει την εγκατεστημένη εφαρμογή.
- Το αποθετήριο είναι ιδιωτικό, άρα χωρίς ενημέρωση μέσα από την εφαρμογή· εγκατάσταση με το APK από τα Releases.

## Τεστ
`cd tools/ktest && gradle test` — μεταγλωττίζει όλο το Kotlin με τις κλάσεις του Android 14 (Robolectric android-all
από το Maven Central, χωρίς SDK) και τρέχει τεστ JVM: `Backup.toDelete`, zip ↔ unzip με φωτογραφίες, απόρριψη
ονομάτων σαν `../`, `Photos.prune`, `Photos.sampleSize`. Τρέξε τα πριν από κάθε push που αλλάζει Kotlin.
Τα resources (XML) ελέγχονται μόνο στο κανονικό χτίσιμο (GitHub Actions). Κάμερα/συλλογή/SAF μόνο σε συσκευή.
