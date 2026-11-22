import java.time.LocalDate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Version automatique : yyyy-mm-dd-XX (XX = n° du build du jour) ----
val verFile = rootProject.file("version.properties")
val verProps = Properties().apply { if (verFile.exists()) verFile.inputStream().use { load(it) } }
val today: String = LocalDate.now().toString()
val isBuild = gradle.startParameter.taskNames.any { t -> listOf("assemble", "install", "bundle").any { t.contains(it, ignoreCase = true) } }
val lastN = verProps.getProperty("n", "0").toInt()
val buildN = if (verProps.getProperty("day") == today) (if (isBuild) lastN + 1 else lastN.coerceAtLeast(1)) else 1
if (isBuild) {
    verProps["day"] = today; verProps["n"] = buildN.toString()
    verFile.outputStream().use { verProps.store(it, "Numero de build du jour (auto)") }
}
val verName = "%s-%02d".format(today, buildN)
val verCode = today.replace("-", "").drop(2).toInt() * 100 + buildN   // yyMMddXX, toujours croissant

// ---- CHANGELOG.md (racine du dépôt) embarqué dans l'appli : un seul fichier à tenir à jour ----
val changelogDir = layout.buildDirectory.dir("generated/changelog").get().asFile
rootProject.file("CHANGELOG.md").takeIf { it.exists() }?.let { src ->
    changelogDir.mkdirs(); src.copyTo(File(changelogDir, "CHANGELOG.md"), overwrite = true)
}

android {
    namespace = "fr.soaresden.diallo"
    sourceSets.getByName("main").assets.srcDir(changelogDir)
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.soaresden.diallo"
        minSdk = 29
        targetSdk = 34
        versionCode = verCode
        versionName = verName
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.documentfile:documentfile:1.0.1")   // dossier de travail (SAF)
    implementation("org.osmdroid:osmdroid-android:6.1.20")   // carte OpenStreetMap (sans clé)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")   // débogage sans fil → enregistrement d'appels (Apache-2.0)
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("de.sciss:jump3r:1.0.5")   // encodeur MP3 (LAME en Java, LGPL)
}
