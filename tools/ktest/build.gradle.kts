plugins { kotlin("jvm") version "2.0.21" }
// Μεταγλωττίζει όλο τον κώδικα της εφαρμογής με τις κλάσεις του Android (Robolectric android-all) αντί για το SDK.
sourceSets["main"].java.srcDirs("../../app/src/main/java")
dependencies {
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    testImplementation("org.robolectric:android-all:14-robolectric-10818077")
    testImplementation("junit:junit:4.13.2")
}
