// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.gms.google-services") version "4.3.15" apply false
}

// Repositories are defined centrally in settings.gradle.kts (repositoriesMode = FAIL_ON_PROJECT_REPOS)
// so we must not declare them again here.
