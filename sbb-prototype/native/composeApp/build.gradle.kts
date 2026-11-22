import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.Inflater
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------------------------
// Assets live once, in native/assets; the build derives the rest:
//   - SBB icons      assets/icons/*.svg (SBB icons, Apache-2.0)  -> generated Kotlin (IconData), drawn at runtime
//   - illustrations  assets/illustrations/*.webp (official SBB scenes) -> composeResources/drawable (gitignored)
//   - SBB fonts      SBB's CDN ("SBB Web" v1.9 subset)           -> composeResources/font (gitignored), WOFF -> TTF
// The tab bar's measured motion table is committed Kotlin (ui/chrome/TabBarMotionData.kt).
// ---------------------------------------------------------------------------------------------

val assetsDir: File = rootDir.resolve("assets")
val sharedApiDir: File = rootDir.resolve("../kmp/shared/src/commonMain/kotlin")
val composeResDir: File = projectDir.resolve("src/commonMain/composeResources")

/** Generates Kotlin for the icon set: one raw string per SVG, so icons are available synchronously everywhere. */
abstract class GenSbbSources : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val icons: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = outputDir.get().asFile.apply { deleteRecursively() }
        val iconDir = root.resolve("railstatus/mobile/ui/components").apply { mkdirs() }
        val entries = icons.files.sortedBy { it.name }.joinToString(",\n") { f ->
            val svg = f.readText().trim().replace("$", "\${'$'}")
            "        \"${f.nameWithoutExtension}\" to \"\"\"$svg\"\"\""
        }
        iconDir.resolve("IconData.kt").writeText(
            """
            |// GENERATED from native/assets/icons (SBB icons, Apache-2.0) by :composeApp:genSbbSources. Do not edit.
            |package railstatus.mobile.ui.components
            |
            |internal object IconData {
            |    val svgs: Map<String, String> = mapOf(
            |$entries,
            |    )
            |}
            |""".trimMargin(),
        )
    }
}

/**
 * Downloads the "SBB Web" fonts (v1.9 subset, the files the web app loaded) into a gitignored cache,
 * converts WOFF -> TrueType and puts them in composeResources/font as sbb_thin/light/roman/bold.ttf.
 */
abstract class FetchSbbFonts : DefaultTask() {
    @get:Input abstract val baseUrl: Property<String>
    @get:Input abstract val names: MapProperty<String, String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val out = outputDir.get().asFile.apply { mkdirs() }
        for ((remote, local) in names.get()) {
            val ttf = cache.resolve("$remote.ttf")
            if (!ttf.exists()) {
                val conn = URI("${baseUrl.get()}/$remote.woff").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 20_000
                if (conn.responseCode != 200) throw GradleException("Could not fetch SBB font $remote: HTTP ${conn.responseCode}")
                ttf.writeBytes(woffToSfnt(conn.inputStream.use { it.readBytes() }))
                logger.lifecycle("Fetched SBB font $remote")
            }
            ttf.copyTo(out.resolve("$local.ttf"), overwrite = true)
        }
    }

    /** WOFF 1.0 -> sfnt: inflate each table and write a plain TrueType/OpenType file. */
    private fun woffToSfnt(woff: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(woff)
        check(b.getInt(0) == 0x774F4646) { "not a WOFF file" }
        val flavor = b.getInt(4)
        val numTables = b.getShort(12).toInt() and 0xFFFF
        class Table(val tag: Int, val checksum: Int, val data: ByteArray)
        val tables = (0 until numTables).map { i ->
            val o = 44 + i * 20
            val tag = b.getInt(o); val off = b.getInt(o + 4); val comp = b.getInt(o + 8); val orig = b.getInt(o + 12); val sum = b.getInt(o + 16)
            val raw = woff.copyOfRange(off, off + comp)
            val data = if (comp < orig) ByteArray(orig).also { buf ->
                val inf = Inflater(); inf.setInput(raw); var n = 0
                while (n < orig) { val r = inf.inflate(buf, n, orig - n); check(r > 0 || !inf.needsInput()) { "bad zlib stream" }; n += r }
                inf.end()
            } else raw
            Table(tag, sum, data)
        }.sortedBy { Integer.toUnsignedLong(it.tag) }
        val bytes = ByteArrayOutputStream()
        val s = DataOutputStream(bytes)
        var pow = 1; var log = 0
        while (pow * 2 <= numTables) { pow *= 2; log++ }
        s.writeInt(flavor); s.writeShort(numTables); s.writeShort(pow * 16); s.writeShort(log); s.writeShort(numTables * 16 - pow * 16)
        var offset = 12 + numTables * 16
        for (t in tables) {
            s.writeInt(t.tag); s.writeInt(t.checksum); s.writeInt(offset); s.writeInt(t.data.size)
            offset += (t.data.size + 3) and 3.inv()
        }
        for (t in tables) {
            s.write(t.data)
            repeat(((t.data.size + 3) and 3.inv()) - t.data.size) { s.writeByte(0) }
        }
        return bytes.toByteArray()
    }
}

val genSbbSources = tasks.register<GenSbbSources>("genSbbSources") {
    description = "Generates Kotlin for the SBB icons (native/assets/icons)"
    icons.from(fileTree(assetsDir.resolve("icons")) { include("*.svg") })
    outputDir.set(layout.buildDirectory.dir("generated/sbb/kotlin"))
}

val copyIllustrations = tasks.register<Sync>("copyIllustrations") {
    description = "Copies the SBB scene illustrations (native/assets/illustrations) into composeResources/drawable (gitignored)"
    from(assetsDir.resolve("illustrations")) {
        include("*.webp")
        rename { it.replace('-', '_') }
    }
    from(assetsDir.resolve("touch-tiles")) { // Touch-timetable map tiles (scripts/touch-tiles), as touch_<id>.webp
        include("*.webp")
        rename { "touch_" + it.replace('-', '_') }
    }
    into(composeResDir.resolve("drawable"))
}

val copyMapData = tasks.register<Sync>("copyMapData") {
    description = "Copies the map data (native/assets/map/*.json, OpenStreetMap via OpenFreeMap) into composeResources/files/map (gitignored)"
    from(assetsDir.resolve("map")) { include("*.json") }
    into(composeResDir.resolve("files/map"))
}

val fetchSbbFonts = tasks.register<FetchSbbFonts>("fetchSbbFonts") {
    description = "Downloads the SBB Web fonts (not committed) into composeResources/font"
    baseUrl.set("https://cdn.app.sbb.ch/fonts/v1_9_subset")
    names.set(mapOf("SBBWeb-Thin" to "sbb_thin", "SBBWeb-Light" to "sbb_light", "SBBWeb-Roman" to "sbb_roman", "SBBWeb-Bold" to "sbb_bold"))
    cacheDir.set(layout.projectDirectory.dir("sbb-fonts"))
    outputDir.set(composeResDir.resolve("font"))
    outputs.upToDateWhen { composeResDir.resolve("font").listFiles()?.size == 4 }
}

// The Compose resource tasks read composeResources/{drawable,font}: fill them first.
tasks.configureEach {
    if (name != copyIllustrations.name && name != fetchSbbFonts.name && name != copyMapData.name &&
        (name.contains("ComposeResources") || name.contains("ResourceAccessors") || name.contains("ResourceCollectors") ||
            name.contains("ComposeResClass") || name.contains("ValueResources"))
    ) dependsOn(copyIllustrations, fetchSbbFonts, copyMapData)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
            // Tests run in headless Chrome/Chromium (set CHROME_BIN if it isn't found); timeouts in karma.config.d/.
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        binaries.executable()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain {
            // The backend's API types (kotlinx.serialization only), straight from the KMP backend's sources.
            kotlin.srcDir(sharedApiDir)
            kotlin.srcDir(genSbbSources.map { it.outputDir })
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.compose.backhandler)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            // Time zone database for kotlinx-datetime on wasm (TimeZone.of("Europe/Zurich")).
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
    }
}

android {
    namespace = "railstatus.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "railstatus.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "railstatus.mobile.resources"
    generateResClass = always
}
