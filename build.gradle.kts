// Check for local.properties and provide a helpful error message if it's missing.
// This is to ensure the Android SDK is configured, especially in automated environments.
val localPropertiesFile = File(rootDir, "local.properties")
if (!localPropertiesFile.exists()) {
    throw GradleException(
        "The 'local.properties' file is missing. " +
                "This is required to configure the Android SDK path. " +
                "Please run the ./JULES.sh script to set up the environment before building."
    )
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}