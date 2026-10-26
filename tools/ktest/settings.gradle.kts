// Τεστ JVM για τη λογική του Kotlin που δεν χρειάζεται συσκευή (π.χ. Backup.toDelete, zip αντιγράφων).
// Τρέξιμο: cd tools/ktest && gradle test   (κατεβάζει Kotlin και τις κλάσεις του Android 14 από το Maven Central)
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "ktest"
