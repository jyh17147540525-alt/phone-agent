package com.pocketagent.provider.openaicompat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI Chat Completions 协议的 DTO。
 *
 * 这一套结构被大量厂商兼容，因此本模块同时服务于：
 *   OpenAI / DeepSeek / 阿里通义(DashScope 兼容模式) / 火山方舟 / 月之暗面 Kimi /
 *   智谱 GLM / 硅基流动 / OpenRouter / Azure OpenAI / 本地 Ollama、LM Studio
 *
 * ⚠️ 厂商差异（已在 Provider 实现中处理，不要在这里加字段）：
 *  - 鉴权：多数用 `Authorization: Bearer`，Azure 用 `api-key` 头
 *  - 推理内容：DeepSeek/部分厂商用 `reasoning_content`，而非标准字段
 *  - 错误结构：多数是 `{"error": {"message": ...}}`，少数厂商直接返回字符串
 *  - 视觉：图片用 `image_url` + data URI，但部分厂商只接受 http URL
 */

// ═══════════════════════════════════════════════════════════════
//  请求
// ═══════════════════════════════════════════════════════════════

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<RequestTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    /** 部分厂商要求显式关闭流式用量统计 */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
internal data class RequestMessage(
    val role: String,
    /** 纯文本时是 String，多模态时是 Array，故用 JsonElement */
    val content: JsonElement?,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<RequestToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class RequestTool(
    val type: String = "function",
    val function: FunctionDef,
) {
    @Serializable
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JsonElement,
    )
}

@Serializable
internal data class RequestToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
) {
    @Serializable
    data class FunctionCall(val name: String, val arguments: String)
}

@Serializable
internal data class ResponseFormat(
    val type: String,
)

@Serializable
internal data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

/** 多模态内容片段（OpenAI 格式） */
@Serializable
internal sealed interface RequestContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : RequestContentPart

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(@SerialName("image_url") val imageUrl: Image) : RequestContentPart {
        @Serializable
        data class Image(val url: String)
    }
}

// ═══════════════════════════════════════════════════════════════
//  响应
// ═══════════════════════════════════════════════════════════════

@Serializable
internal data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null,
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class ResponseMessage(
        val role: String? = null,
        val content: String? = null,
        /** DeepSeek 等厂商的推理内容 */
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ResponseToolCall>? = null,
    )

    @Serializable
    data class ResponseToolCall(
        val id: String? = null,
        val type: String? = null,
        val function: FunctionCall? = null,
    ) {
        @Serializable
        data class FunctionCall(val name: String? = null, val arguments: String? = null)
    }
}

/** 流式分片 */
@Serializable
internal data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null,
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
    )

    @Serializable
    data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val function: FunctionDelta? = null,
    ) {
        @Serializable
        data class FunctionDelta(val name: String? = null, val arguments: String? = null)
    }
}

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptDetails: PromptDetails? = null,
) {
    @Serializable
    data class PromptDetails(
        @SerialName("cached_tokens") val cachedTokens: Int = 0,
    )
}

/** /models 列表响应 */
@Serializable
internal data class ModelsResponse(
    val data: List<ModelDto> = emptyList(),
) {
    @Serializable
    data class ModelDto(
        val id: String,
        val owned_by: String? = null,
        /** 部分厂商（如 OpenRouter）会给出上下文长度与价格 */
        val context_length: Int? = null,
        val pricing: Pricing? = null,
    ) {
        @Serializable
        data class Pricing(
            val prompt: String? = null,
            val completion: String? = null,
        )
    }
}

/** 统一错误结构 */
@Serializable
internal data class ErrorResponse(
    val error: ErrorDetail? = null,
    /** 部分厂商把错误放在顶层 */
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    @Serializable
    data class ErrorDetail(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null,
        /** 火山方舟等厂商会给出更细的错误码 */
        val param: String? = null,
    )

    val bestMessage: String
        get() = error?.message ?: message ?: "未知错误"

    val bestCode: String?
        get() = error?.code ?: code
}
