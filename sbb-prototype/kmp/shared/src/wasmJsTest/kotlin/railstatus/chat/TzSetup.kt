@file:OptIn(ExperimentalWasmJsInterop::class)

package railstatus.chat

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsModule

// kotlinx-datetime on wasm needs the IANA zones from js-joda for TimeZone.of("Europe/Zurich").

@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

private val jsJodaTz = JsJodaTimeZoneModule
