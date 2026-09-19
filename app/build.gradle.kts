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
        versionCode = 5
        versionName = "0.5.0"
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
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

val russianAssets = layout.buildDirectory.dir("generated/russianAssets")
val prepareRussianModel by tasks.registering {
    val revision = "e050376e960175e8ddc5cff85025dc8436cddd68"
    val models = mapOf(
        "onnx/encoder_model_quantized.onnx" to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
        "onnx/decoder_model_merged_quantized.onnx" to "7efefcd793a5663bc204224a92a123524f3dfe2ffad59e5f73d6734d1b11ad04",
        "tokenizer.json" to "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb"
    )
    inputs.property("revision", revision)
    inputs.property("models", models)
    inputs.file(rootProject.file("scripts/compile_tokenizer.py"))
    outputs.dir(russianAssets)
    doLast {
        val directory = russianAssets.get().dir("russian").asFile.apply { mkdirs() }
        for ((path, expected) in models) {
            val output = directory.resolve(path.substringAfterLast('/'))
            fun valid(file: java.io.File): Boolean {
                if (!file.isFile) return false
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { source ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected
            }
            if (valid(output)) continue
            val temporary = directory.resolve(output.name + ".tmp")
            val connection = URL("https://huggingface.co/Xenova/opus-mt-en-ru/resolve/$revision/$path")
                .openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
            connection.getInputStream().use { source -> temporary.outputStream().use { source.copyTo(it) } }
            check(valid(temporary)) { "Bundled Russian model checksum mismatch: $path" }
            check(temporary.renameTo(output)) { "Cannot install verified Russian asset" }
        }
        exec {
            commandLine("python3", rootProject.file("scripts/compile_tokenizer.py"),
                directory.resolve("tokenizer.json"), directory.resolve("tokenizer.bin"))
        }
        val compiledDigest = MessageDigest.getInstance("SHA-256").digest(directory.resolve("tokenizer.bin").readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(compiledDigest == "148cd45d68d9702a4e64e594ff13ffefe30aea383b9cb052a61bcabfd8041ed7")
        check(directory.resolve("tokenizer.json").delete())
    }
}
android.sourceSets.getByName("main").assets.srcDir(russianAssets)
tasks.named("preBuild").configure { dependsOn(prepareRussianModel) }
