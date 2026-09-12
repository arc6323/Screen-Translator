plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.arc6323.screentranslator"; compileSdk = 35
    defaultConfig { applicationId = "com.arc6323.screentranslator"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}
