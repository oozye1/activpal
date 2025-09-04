import java.util.Properties

// Load local.properties (kept out of VCS) to read secret values like firebaseApiKey and firebaseWebClientId
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}
val firebaseApiKey: String? = localProperties.getProperty("firebaseApiKey")
val firebaseWebClientId: String? = localProperties.getProperty("firebaseWebClientId")

// Robustly inject the firebaseApiKey into app/google-services.json if present locally.
// Use a regex to replace the entire api_key array (handles whitespace and existing content).
if (!firebaseApiKey.isNullOrBlank()) {
    val gsFile = rootProject.file("app/google-services.json")
    if (gsFile.exists()) {
        val text = gsFile.readText(Charsets.UTF_8)
        val apiKeyRegex = Regex("\"api_key\"\\s*:\\s*\\[[^\\]]*\\]")
        if (apiKeyRegex.containsMatchIn(text) && !text.contains("\"current_key\"")) {
            val replacement = "\"api_key\": [{\"current_key\": \"${firebaseApiKey}\"}]"
            val newText = apiKeyRegex.replace(text, replacement)
            gsFile.writeText(newText, Charsets.UTF_8)
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "co.uk.doverguitarteacher.activpal"
    compileSdk = 34

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

        // Inject default_web_client_id for Google Sign-In if provided in local.properties
        if (!firebaseWebClientId.isNullOrBlank()) {
            resValue("string", "default_web_client_id", firebaseWebClientId)
        }
    }

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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Jetpack Compose dependencies
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Firebase Bill of Materials (BoM)
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))

    // Firebase Authentication library
    implementation("com.google.firebase:firebase-auth-ktx")

    // Google Sign-In (Play Services Auth)
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Coil for Compose - load profile images
    implementation("io.coil-kt:coil-compose:2.4.0")

    // Standard Testing libraries
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
