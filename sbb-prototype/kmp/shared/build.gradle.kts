import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // The chat engine (railstatus.chat) formats clock times in Europe/Zurich.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Time zone database for kotlinx-datetime on wasm, for the chat engine's tests only (see TzSetup.kt).
        // Apps that use the engine on wasm load it themselves, like native/ does.
        wasmJsTest.dependencies {
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
    }
}
