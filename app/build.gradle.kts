import java.util.Properties
import java.io.File

// Load local.properties (kept out of VCS) to read secret values like firebaseApiKey and firebaseWebClientId
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}
val firebaseApiKey: String? = localProperties.getProperty("firebaseApiKey")
val firebaseWebClientId: String? = localProperties.getProperty("firebaseWebClientId")

// CI guidance snippet: in CI set env vars FIREBASE_API_KEY and FIREBASE_WEB_CLIENT_ID and write them to local.properties
// Example (GitHub Actions) - add to your workflow before running Gradle:
// - name: Write local.properties
//   run: |
//     echo "firebaseApiKey=${{ secrets.FIREBASE_API_KEY }}" >> local.properties
//     echo "firebaseWebClientId=${{ secrets.FIREBASE_WEB_CLIENT_ID }}" >> local.properties
//     echo "firebaseProjectNumber=${{ secrets.FIREBASE_PROJECT_NUMBER }}" >> local.properties
//     echo "firebaseProjectId=${{ secrets.FIREBASE_PROJECT_ID }}" >> local.properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "co.uk.doverguitarteacher.activpal"
    // Bumped to latest stable SDK to remove compatibility warnings
    compileSdk = 36

    defaultConfig {
        applicationId = "co.uk.doverguitarteacher.activpal"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Expose firebase secrets to the app as BuildConfig fields (only from local.properties).
        // These values are not committed to VCS because they come from local.properties which is gitignored.
        if (!firebaseApiKey.isNullOrBlank()) {
            buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseApiKey}\"")
        } else {
            buildConfigField("String", "FIREBASE_API_KEY", "\"\"")
        }
        if (!firebaseWebClientId.isNullOrBlank()) {
            buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"${firebaseWebClientId}\"")
        } else {
            buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"\"")
        }
        val projectNumber = localProperties.getProperty("firebaseProjectNumber") ?: ""
        val projectId = localProperties.getProperty("firebaseProjectId") ?: ""
        val storageBucket = localProperties.getProperty("firebaseStorageBucket") ?: ""
        buildConfigField("String", "FIREBASE_PROJECT_NUMBER", "\"${projectNumber}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${projectId}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${storageBucket}\"")

        // google-services plugin present; default_web_client_id will come from google-services.json.
// CI reminder: write local.properties from secrets (do NOT commit local.properties). Example GitHub Actions steps:
// - name: Write local.properties
//   run: |
//     echo "firebaseApiKey=${{ secrets.FIREBASE_API_KEY }}" >> local.properties
//     echo "firebaseWebClientId=${{ secrets.FIREBASE_WEB_CLIENT_ID }}" >> local.properties
//     echo "firebaseProjectNumber=${{ secrets.FIREBASE_PROJECT_NUMBER }}" >> local.properties
//     echo "firebaseProjectId=${{ secrets.FIREBASE_PROJECT_ID }}" >> local.properties

    }
// Ensure any stale gradle-generated resValues files are removed to prevent duplicate resource errors

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.2"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Jetpack Compose dependencies
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.3")

    // Firebase: switched from BoM to explicit version per request
    // (Previous: implementation(platform("com.google.firebase:firebase-bom:34.2.0")))
    implementation("com.google.firebase:firebase-auth-ktx:22.2.0")
    // firebase-common-ktx not needed explicitly; comes transitively from auth

    // Google Sign-In (Play Services Auth)
    implementation("com.google.android.gms:play-services-auth:21.4.0")

    // Play Services Location for GPS / fused location updates
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Coil for Compose - load profile images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Standard Testing libraries
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
