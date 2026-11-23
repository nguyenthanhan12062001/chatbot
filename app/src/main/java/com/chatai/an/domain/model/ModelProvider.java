package com.chatai.an.domain.model;

public enum ModelProvider {
    POLLINATIONS("pollinations", "Pollinations (Free)", AccessType.FREE, ProtocolType.POLLINATIONS_TEXT, "https://text.pollinations.ai"),
    KILO("kilo", "Kilo (Free)", AccessType.FREE, ProtocolType.KILO_FREE, "https://api.kilo.ai/api/openrouter"),
    OPENROUTER_FREE("openrouter_free", "OpenRouter (Free)", AccessType.FREE, ProtocolType.OPENROUTER_FREE, "https://openrouter.ai/api/v1"),
    GOOGLE_GEMINI("google_gemini", "Google Gemini", AccessType.PERSONAL_API, ProtocolType.GEMINI_API, "https://generativelanguage.googleapis.com"),
    OPENAI("openai", "OpenAI", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.openai.com/v1"),
    ANTHROPIC("anthropic", "Anthropic", AccessType.PERSONAL_API, ProtocolType.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1"),
    OPENROUTER("openrouter", "OpenRouter", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://openrouter.ai/api/v1"),
    GROQ("groq", "Groq", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.groq.com/openai/v1"),
    DEEPSEEK("deepseek", "DeepSeek", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.deepseek.com/v1"),
    TOGETHER("together", "Together AI", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.together.xyz/v1"),
    MISTRAL("mistral", "Mistral AI", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.mistral.ai/v1"),
    OLLAMA("ollama", "Ollama (Self-Hosted)", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "http://10.0.2.2:11434/v1"),
    LM_STUDIO("lm_studio", "LM Studio (Self-Hosted)", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "http://10.0.2.2:1234/v1"),
    CUSTOM_OPENAI("custom_openai", "Custom OpenAI-Compatible", AccessType.PERSONAL_API, ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.openai.com/v1"),
    LOCAL("local_device", "Local On-Device AI", AccessType.LOCAL, ProtocolType.LOCAL_RUNTIME, "local://device");

    private final String id;
    private final String displayName;
    private final AccessType defaultAccessType;
    private final ProtocolType defaultProtocol;
    private final String defaultBaseUrl;

    ModelProvider(
            String id,
            String displayName,
            AccessType defaultAccessType,
            ProtocolType defaultProtocol,
            String defaultBaseUrl
    ) {
        this.id = id;
        this.displayName = displayName;
        this.defaultAccessType = defaultAccessType;
        this.defaultProtocol = defaultProtocol;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AccessType getDefaultAccessType() {
        return defaultAccessType;
    }

    public ProtocolType getDefaultProtocol() {
        return defaultProtocol;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean requiresApiKey() {
        return defaultAccessType == AccessType.PERSONAL_API
                && this != OLLAMA
                && this != LM_STUDIO;
    }

    public static ModelProvider fromId(String id) {
        if (id == null) return POLLINATIONS;
        for (ModelProvider p : values()) {
            if (p.id.equalsIgnoreCase(id) || p.name().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return POLLINATIONS;
    }
}
