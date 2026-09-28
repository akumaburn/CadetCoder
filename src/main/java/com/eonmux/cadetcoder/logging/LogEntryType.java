package com.eonmux.cadetcoder.logging;

/** What a session log entry records. */
enum LogEntryType {
    USER_INPUT,
    COMMAND_START,
    COMMAND_OUTPUT,
    COMMAND_END,
    AI_REQUEST,
    AI_RESPONSE,
    LLM_REQUEST,
    LLM_RESPONSE,
    FILE_OPERATION,
    ERROR,
    SESSION_EVENT,
    SECURITY_EVENT,
    PERFORMANCE
}
