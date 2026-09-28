package com.eonmux.cadetcoder.ai.providers;

/**
 * How a connector authenticates an outbound request. Mirrors the auth strategies
 * used by opencode's provider definitions.
 */
public enum AuthScheme {
    /** {@code Authorization: Bearer <key>} (OpenAI, OpenRouter, xAI, Groq, ...). */
    BEARER,
    /** {@code x-api-key: <key>} plus {@code anthropic-version} (Anthropic Messages API). */
    X_API_KEY,
    /** {@code api-key: <key>} (Azure OpenAI). */
    API_KEY_HEADER,
    /** {@code x-goog-api-key: <key>} (Google Generative AI / Gemini). */
    X_GOOG_API_KEY,
    /** AWS Signature V4 request signing (Amazon Bedrock without a bearer API key). */
    AWS_SIGV4,
    /** No credential required (local servers such as llama-server / LM Studio / Ollama). */
    NONE
}
