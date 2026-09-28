package com.eonmux.cadetcoder.ai.providers;

/**
 * The wire protocol a connector speaks. Determines which backend implementation is
 * instantiated and how the request body is shaped.
 */
public enum ConnectorProtocol {
    /** OpenAI Chat Completions ({@code POST {base}/chat/completions}). The large majority. */
    OPENAI_CHAT,
    /** Anthropic Messages API ({@code POST {base}/messages}). */
    ANTHROPIC_MESSAGES,
    /** Google Generative AI ({@code POST {base}/models/{model}:generateContent}). */
    GOOGLE_GENERATIVE_AI,
    /** Amazon Bedrock Converse ({@code POST {base}/model/{model}/converse}). */
    BEDROCK_CONVERSE
}
