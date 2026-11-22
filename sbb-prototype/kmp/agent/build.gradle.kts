import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The AI agents, built with Koog (JetBrains' Kotlin agent framework), in commonMain.
// It's a separate module from :shared because native/ compiles :shared's sources straight into the app,
// and the app must not pull in Koog. The server runs these agents, so the API key never leaves the server.
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
            api(project(":shared"))
            api(libs.koog.agents)
            // koog-agents 1.3 doesn't bundle the OpenRouter client.
            implementation(libs.koog.openrouter)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koog.agents.test)
        }
    }
}
