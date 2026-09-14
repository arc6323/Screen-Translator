import java.net.URL
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.arc6323.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arc6323.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
}

val ocrAssets = layout.buildDirectory.dir("generated/ocrGuardAssets")
val prepareOcrGuard by tasks.registering {
    val revision = "87416418657359cb625c412a48b6e1d6d41c29bd"
    val models = mapOf(
        "eng.traineddata" to "bbef4675053b5b468cdb477053e28b1c698ba08e",
        "rus.traineddata" to "b146cb2263acbc6383f8e92ea0ce759537687bb8"
    )
    inputs.property("revision", revision)
    inputs.property("models", models)
    outputs.dir(ocrAssets)
    doLast {
        val directory = ocrAssets.get().dir("ocr-guard").asFile.apply { mkdirs() }
        for ((name, expected) in models) {
            val output = directory.resolve(name)
            fun valid(bytes: ByteArray): Boolean {
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update(("blob " + bytes.size + "\u0000").toByteArray(Charsets.UTF_8))
                return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == expected
            }
            if (output.exists() && valid(output.readBytes())) continue
            val connection = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" +
                revision + "/" + name).openConnection().apply {
                connectTimeout = 30000
                readTimeout = 60000
            }
            val bytes = connection.getInputStream().use { it.readBytes() }
            check(valid(bytes)) { "OCR model content does not match pinned Git blob: " + name }
            output.writeBytes(bytes)
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(ocrAssets)
tasks.named("preBuild").configure { dependsOn(prepareOcrGuard) }
