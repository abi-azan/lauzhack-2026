package railstatus.server

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import railstatus.model.*

/**
 * The dashboard is English only. When the operator edits the AI draft, the dialog sends just the English, and this
 * translates it into the missing languages at publish time, so every passenger gets what the operator approved.
 * If the call fails, the update goes out with English in those languages (`fillLanguages`), never the stale draft.
 */
class Translator(private val apiKey: String, private val model: String) {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        expectSuccess = false
    }

    /** [req] with title and body in all four languages; unchanged when nothing is missing or English is absent. */
    suspend fun complete(req: PublishUpdateRequest): PublishUpdateRequest {
        val missing = Lang.entries.filter { it != Lang.EN && (req.title[it].isNullOrBlank() || req.body[it].isNullOrBlank()) }
        val title = req.title[Lang.EN]?.takeIf(String::isNotBlank)
        val body = req.body[Lang.EN]?.takeIf(String::isNotBlank)
        if (missing.isEmpty() || title == null || body == null) return req
        return runCatching {
            val out = translate(title, body, missing)
            req.copy(
                title = req.title + missing.associateWith { out.getValue(it).first },
                body = req.body + missing.associateWith { out.getValue(it).second },
            )
        }.onFailure { log.warn("translation failed, publishing English for ${missing.joinToString()}: ${it.message}") }
            .getOrDefault(req)
    }

    private suspend fun translate(title: String, body: String, langs: List<Lang>): Map<Lang, Pair<String, String>> {
        val names = mapOf(Lang.DE to "German", Lang.FR to "French", Lang.IT to "Italian")
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { langs.forEach { add(it.name.lowercase()) } }
            putJsonObject("properties") {
                langs.forEach { l ->
                    putJsonObject(l.name.lowercase()) {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonArray("required") { add("title"); add("body") }
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("body") { put("type", "string") }
                        }
                    }
                }
            }
        }
        val request = buildJsonObject {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 1500)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "translation"); put("strict", true); put("schema", schema) }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "You translate SBB (Swiss railways) passenger status updates from English into " +
                        langs.joinToString { names.getValue(it) } + ". Keep the meaning exactly, the times, train numbers and " +
                        "station names as written (use the local station names, e.g. Genève, Renens VD), and SBB's usual tone. " +
                        "Use the formal address (Sie / vous / Lei). Translate the title and the body.")
                }
                addJsonObject { put("role", "user"); put("content", "Title: $title\n\nBody: $body") }
            }
        }
        val res = http.post("https://openrouter.ai/api/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request.toString())
        }
        val text = res.bodyAsText()
        require(res.status.isSuccess()) { "HTTP ${res.status.value} ${text.take(200)}" }
        val content = RailJson.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        val out = RailJson.parseToJsonElement(content.substringAfter("```json").substringBefore("```").trim()).jsonObject
        return langs.associateWith { l ->
            val o = out[l.name.lowercase()]!!.jsonObject
            o["title"]!!.jsonPrimitive.content to o["body"]!!.jsonPrimitive.content
        }.also { log.info("translated an edited update into ${langs.joinToString()}") }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Translator::class.java)
    }
}
