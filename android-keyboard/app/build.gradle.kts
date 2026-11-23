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

// Whisper "base.en" model, 5-bit quantised (~57 MB). English only.
val modelFileName = "ggml-base.en-q5_1.bin"
val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelFileName"
val modelSha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"

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

val downloadModel by tasks.registering(DownloadVerified::class) {
    url.set(modelUrl)
    sha256.set(modelSha256)
    dest.set(downloadsDir.file(modelFileName))
}

val stageModel by tasks.registering(Sync::class) {
    from(downloadModel.flatMap { it.dest })
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
        versionCode = 8
        versionName = "1.6.0"

        buildConfigField("String", "MODEL_FILE", "\"$modelFileName\"")

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

    // Two editions from one codebase:
    // - "offline": Spokify. No internet permission and no AI code at all, so
    //   Android itself guarantees nothing leaves the phone.
    // - "ai": Spokify AI. Adds optional "AI polish" with the user's own API
    //   key (off by default), and therefore the internet permission.
    flavorDimensions += "edition"
    productFlavors {
        create("offline") {
            dimension = "edition"
            buildConfigField("boolean", "AI_EDITION", "false")
        }
        create("ai") {
            dimension = "edition"
            applicationIdSuffix = ".ai"
            buildConfigField("boolean", "AI_EDITION", "true")
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
        resources {
            // Duplicate licence/metadata files from the AI edition's libraries.
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**",
            )
        }
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
    // Claude, for the AI edition only. The offline edition doesn't include it.
    "aiImplementation"("com.anthropic:anthropic-java:3.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub there).
    testImplementation("org.json:json:20240303")
}
