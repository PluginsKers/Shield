package com.eyreland.shield.inference

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class InterventionKind(val wireName: String) {
    WARN("warn"),
    KICK("kick"),
    ;

    companion object {
        fun fromWire(value: String): InterventionKind = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("不支持的干预类型: $value")
    }
}

enum class InterventionOutcome(val wireName: String) {
    APPLIED("applied"),
    IGNORED_DISABLED("ignored_disabled"),
    IGNORED_COOLDOWN("ignored_cooldown"),
    PLAYER_OFFLINE("player_offline"),
    EXPIRED("expired"),
    INVALID("invalid"),
}

data class PlayerInterventionDirective(
    val directiveId: String,
    val playerUuid: UUID,
    val kind: InterventionKind,
    val reason: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
)

data class InterventionReceipt(
    val directiveId: String,
    val playerUuid: UUID,
    val kind: InterventionKind,
    val outcome: InterventionOutcome,
    val handledAtMs: Long,
)

internal object InferenceAckDecoder {
    private const val MAX_DIRECTIVES = 1_000
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val unsignedInteger = Regex("^(0|[1-9][0-9]*)$")

    fun decode(
        body: ByteArray,
        expectedBatchId: UUID? = null,
    ): List<PlayerInterventionDirective> {
        if (body.isEmpty()) return emptyList()
        val root = runCatching {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
            JsonParser.parseString(text).asJsonObject
        }.getOrElse { throw IllegalArgumentException("推理响应不是合法 JSON 对象", it) }
        val element = root.get("player_interventions") ?: return emptyList()
        require(element.isJsonArray) { "player_interventions 必须是数组" }
        val values = element.asJsonArray
        require(values.size() <= MAX_DIRECTIVES) {
            "单次响应干预指令不得超过 $MAX_DIRECTIVES 条"
        }
        if (values.size() > 0) {
            require(root.optionalString("schema_version") == "shield.events.ack.v2") {
                "干预指令只允许出现在 shield.events.ack.v2 响应"
            }
            if (expectedBatchId != null) {
                val responseBatchId = runCatching {
                    UUID.fromString(root.requiredString("batch_id"))
                }.getOrElse { throw IllegalArgumentException("响应 batch_id 格式无效", it) }
                require(responseBatchId == expectedBatchId) {
                    "推理响应 batch_id 与请求不一致"
                }
            }
        }
        val directives = values.map { value -> decodeDirective(value.asJsonObject) }
        require(directives.map { it.directiveId }.toSet().size == directives.size) {
            "player_interventions 存在重复 directive_id"
        }
        return directives
    }

    private fun decodeDirective(value: JsonObject): PlayerInterventionDirective {
        val directiveId = value.requiredString("directive_id")
        require(identifier.matches(directiveId)) { "directive_id 格式无效" }
        val playerUuid = runCatching { UUID.fromString(value.requiredString("player_uuid")) }
            .getOrElse { throw IllegalArgumentException("player_uuid 格式无效", it) }
        val kind = InterventionKind.fromWire(value.requiredString("kind"))
        val reason = value.requiredString("reason")
        require(reason.length in 1..128) { "reason 长度必须在 1..128" }
        val issuedAtMs = value.requiredLong("issued_at_ms")
        val expiresAtMs = value.requiredLong("expires_at_ms")
        require(issuedAtMs >= 0 && expiresAtMs > issuedAtMs) { "干预指令时间范围无效" }
        return PlayerInterventionDirective(
            directiveId,
            playerUuid,
            kind,
            reason,
            issuedAtMs,
            expiresAtMs,
        )
    }

    private fun JsonObject.requiredString(name: String): String {
        val element = get(name)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$name 必须是字符串"
        }
        return element.asString
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = get(name) ?: return null
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$name 必须是字符串"
        }
        return element.asString
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val element = get(name)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$name 必须是整数"
        }
        require(unsignedInteger.matches(element.asString)) { "$name 必须是非负十进制整数" }
        return runCatching { element.asLong }
            .getOrElse { throw IllegalArgumentException("$name 必须是整数", it) }
    }
}
