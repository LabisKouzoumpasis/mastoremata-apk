plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ο αριθμός έκδοσης ανεβαίνει μόνος του σε κάθε χτίσιμο στο GitHub.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystoreFile = System.getenv("KS_FILE")

android {
    namespace = "gr.mastoremata"
    compileSdk = 34

    defaultConfig {
        applicationId = "gr.mastoremata"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KS_PASSWORD")
                keyAlias = System.getenv("KS_ALIAS")
                keyPassword = System.getenv("KS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Χωρίς το κλειδί σου υπογράφεται με προσωρινό κλειδί (δεν ενημερώνει την εγκατεστημένη εφαρμογή).
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
