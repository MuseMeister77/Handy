import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Third-party inputs. Both are downloaded once at build time and checked
// against a pinned SHA-256, so the build is reproducible and the finished app
// never needs the internet.
// ---------------------------------------------------------------------------

// whisper.cpp v1.8.3 source (MIT). Taken from the whisper-rs-sys crate on
// crates.io, which ships an unmodified copy of upstream whisper.cpp.
val whisperArchiveUrl = "https://static.crates.io/crates/whisper-rs-sys/whisper-rs-sys-0.15.0.crate"
val whisperArchiveSha256 = "6986c0fe081241d391f09b9a071fbcbb59720c3563628c3c829057cf69f2a56f"

// Two Whisper models, both 5-bit quantised:
// - "base.en" (~57 MB): English only, fast. Used for English.
// - "small" (~181 MB): multilingual. Used for Bahasa Melayu, and to tell
//   English from Malay when the language is set to auto-detect.
data class Model(val file: String, val sha256: String) {
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}
val englishModel = Model("ggml-base.en-q5_1.bin", "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f")
val multilingualModel = Model("ggml-small-q5_1.bin", "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")

val downloadsDir = rootProject.layout.projectDirectory.dir(".downloads")
val whisperSrcDir = layout.buildDirectory.dir("third_party/whisper")
val modelAssetsDir = layout.buildDirectory.dir("generated/modelAssets")

abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun run() {
        val out = dest.get().asFile
        if (out.exists() && sha(out) == sha256.get()) return
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val actual = sha(tmp)
        if (actual != sha256.get()) {
            tmp.delete()
            throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        tmp.renameTo(out)
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val downloadWhisper by tasks.registering(DownloadVerified::class) {
    url.set(whisperArchiveUrl)
    sha256.set(whisperArchiveSha256)
    dest.set(downloadsDir.file("whisper-rs-sys-0.15.0.tar.gz"))
}

val extractWhisper by tasks.registering(Sync::class) {
    dependsOn(downloadWhisper)
    from(tarTree(resources.gzip(downloadWhisper.flatMap { it.dest }))) {
        include("whisper-rs-sys-0.15.0/whisper.cpp/**")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray()) }
        includeEmptyDirs = false
    }
    into(whisperSrcDir)
}

val downloadEnglishModel by tasks.registering(DownloadVerified::class) {
    url.set(englishModel.url)
    sha256.set(englishModel.sha256)
    dest.set(downloadsDir.file(englishModel.file))
}

val downloadMultilingualModel by tasks.registering(DownloadVerified::class) {
    url.set(multilingualModel.url)
    sha256.set(multilingualModel.sha256)
    dest.set(downloadsDir.file(multilingualModel.file))
}

val stageModel by tasks.registering(Sync::class) {
    from(downloadEnglishModel.flatMap { it.dest })
    from(downloadMultilingualModel.flatMap { it.dest })
    into(modelAssetsDir.map { it.dir("models") })
}

// Optional release keystore, kept out of git. Without it the APK is signed
// with the local debug key, which is fine for installing on your own phone.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.offlinevoice.keyboard"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.offlinevoice.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"

        buildConfigField("String", "MODEL_ENGLISH", "\"${englishModel.file}\"")
        buildConfigField("String", "MODEL_MULTILINGUAL", "\"${multilingualModel.file}\"")

        ndk {
            // Practically every Android phone sold since 2017 is 64-bit ARM.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Speech recognition is far too slow without optimisation,
                // so the native code is always built in Release mode.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DWHISPER_SRC_DIR=${whisperSrcDir.get().asFile.absolutePath}",
                )
            }
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].assets.srcDir(modelAssetsDir)

    androidResources {
        // Store the model uncompressed so it copies out quickly on first run.
        noCompress += "bin"
    }

    packaging {
        jniLibs {
            // ggml picks the fastest CPU variant for the phone at runtime by
            // loading it from the native library folder, so the libraries
            // must be extracted to disk on install.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") { dependsOn(extractWhisper, stageModel) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") ||
        name.startsWith("generateJsonModel")
    ) {
        dependsOn(extractWhisper)
    }
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(stageModel)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
