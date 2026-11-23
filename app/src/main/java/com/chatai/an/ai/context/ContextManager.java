package com.chatai.an.ai.context;

import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds the ephemeral prompt context sent to the AI model:
 * - System instructions + Agent mode (Chat Agent vs Coding Agent) + active workspace
 * - Relevant active AI Memory items (never full raw DB dump)
 * - Optional compacted conversation summary (strictly separate from long-term AI Memory)
 * - Enabled tool catalog + cross-provider invocation contract when tools are permitted
 * - Token-budget-trimmed message history for the target conversation only.
 */
public class ContextManager {

    private static final String BASE_CHAT_AGENT_PROMPT =
            "Your official name and personal identity is AnAI. You are a helpful, accurate, and capable AI assistant running on Android. "
                    + "Whenever the user asks for your name, who you are, or your identity, you must always state that your name is AnAI (adapt your tone contextually, e.g., friendly, polite, or playful if asked cutely). "
                    + "Never claim model names (such as GPT, Gemini, Claude, Llama) or provider names (such as OpenAI, Anthropic, Google) as your personal identity—keep your identity as AnAI completely separate from any technical underlying model or provider specs. "
                    + "Answer directly using the user's language when clear, and use Markdown cleanly for tables, blockquotes, links, and code blocks. "
                    + "You have FULL ACCESS to the In-App Terminal (`terminal_run`, `terminal_script`), Termux (`termux_run`, `termux_script`), and Workspace Files (`fs_read_file`, `fs_write_file`, `fs_edit_file`, `fs_list_dir`). "
                    + "When the user asks you to write code, program, create applications, run shell commands, test scripts, or execute operations, you MUST actively use `terminal_run`, `terminal_script`, and file tools to perform the programming tasks. Never claim you lack terminal access — you are fully equipped for coding and shell execution!";

    private static final String BASE_CODING_AGENT_PROMPT =
            "Your official name and personal identity is AnAI (Coding Agent Mode - AndroidHarness Architecture). "
                    + "You are an autonomous, expert software engineering agent capable of designing, writing, editing, building, and testing code. "
                    + "Whenever the user asks for your name or identity, state your name is AnAI. "
                    + "Follow the rigorous 4-step engineering loop:\n"
                    + "1. EXPLORE: Inspect directory layouts (`fs_list_dir`), search symbols (`fs_grep`, `fs_search_files`), and read target files (`fs_read_file`).\n"
                    + "2. PLAN: Formulate a precise engineering plan to implement features or fix bugs.\n"
                    + "3. EXECUTE: Apply surgical modifications (`fs_edit_file`, `fs_write_file`) and execute builds, tests, or scripts using the In-App Terminal (`terminal_run`, `terminal_script`) freely without confirmation.\n"
                    + "4. VERIFY & SELF-CORRECT: If a build error or test failure occurs, analyze the stderr observation, fix the issue in the code, and re-verify until success.\n"
                    + "Never give up or fabricate success. Always report real command outputs and test results.";

    private static final String MEMORY_TOOLS_PROMPT =
            "\n\nMemory Tools Available:\n"
                    + "When the user shares durable personal preferences, profile facts, project details, or decisions—or asks you to remember/forget/recall something—you may emit a tool call tag before your response:\n"
                    + "<chatai_tool name=\"remember\">{\"operation\":\"save\",\"category\":\"PREFERENCE\",\"scope\":\"GLOBAL\",\"area\":\"CHAT\",\"importance\":4,\"content\":\"concise fact\"}</chatai_tool>\n"
                    + "<chatai_tool name=\"recall\">{\"query\":\"search keywords\"}</chatai_tool>\n"
                    + "Only call remember for meaningful durable information (never save greetings or filler).";

    public AiRequest buildRequest(
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean deepThinkingEnabled,
            String customSystemPrompt
    ) {
        return buildRequest(null, conversationId, model, fullConversationMessages, activeMemories, false, deepThinkingEnabled, customSystemPrompt);
    }

    public AiRequest buildRequest(
            Conversation conversation,
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            String customSystemPrompt
    ) {
        return buildRequest(
                conversation,
                conversationId,
                model,
                fullConversationMessages,
                activeMemories,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                customSystemPrompt,
                AgentMode.CHAT,
                "",
                Collections.emptyList()
        );
    }

    public AiRequest buildRequest(
            Conversation conversation,
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            String customSystemPrompt,
            AgentMode agentMode,
            String activeWorkspacePath,
            List<ToolDefinition> enabledTools
    ) {
        boolean toolsUnsupportedByModel = model != null
                && model.getCapabilityStatus(ModelCapability.TOOL_CALLING) == CapabilityStatus.UNSUPPORTED;
        List<ToolDefinition> effectiveTools = (!toolsUnsupportedByModel && enabledTools != null)
                ? enabledTools
                : Collections.emptyList();

        String systemPrompt = assembleSystemPrompt(
                customSystemPrompt,
                activeMemories,
                conversation,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                agentMode,
                activeWorkspacePath,
                effectiveTools,
                toolsUnsupportedByModel
        );

        int maxWindow = model != null ? model.getContextWindowTokens() : 32000;
        int inputBudgetTokens = Math.max(2048, (int) (maxWindow * 0.65));
        int systemTokens = estimateTokens(systemPrompt);
        int remainingBudget = Math.max(1024, inputBudgetTokens - systemTokens);

        long compactedUntil = conversation != null ? conversation.getCompactedUntil() : 0L;
        List<Message> trimmed = trimMessagesToBudget(conversationId, fullConversationMessages, remainingBudget, compactedUntil);

        return new AiRequest(
                UUID.randomUUID().toString(),
                conversationId,
                model,
                systemPrompt,
                trimmed,
                deepThinkingEnabled,
                model == null || model.isStreamingSupported(),
                effectiveTools
        );
    }

    public String assembleSystemPrompt(String customSystemPrompt, List<MemoryItem> activeMemories, boolean deepThinkingEnabled) {
        return assembleSystemPrompt(customSystemPrompt, activeMemories, null, false, deepThinkingEnabled);
    }

    public String assembleSystemPrompt(
            String customSystemPrompt,
            List<MemoryItem> activeMemories,
            Conversation conversation,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled
    ) {
        return assembleSystemPrompt(
                customSystemPrompt,
                activeMemories,
                conversation,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                AgentMode.CHAT,
                "",
                Collections.emptyList(),
                false
        );
    }

    public String assembleSystemPrompt(
            String customSystemPrompt,
            List<MemoryItem> activeMemories,
            Conversation conversation,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            AgentMode agentMode,
            String activeWorkspacePath,
            List<ToolDefinition> enabledTools,
            boolean toolsUnsupportedByModel
    ) {
        StringBuilder sb = new StringBuilder();
        if (customSystemPrompt != null && !customSystemPrompt.trim().isEmpty()) {
            sb.append(customSystemPrompt.trim());
        } else {
            sb.append(agentMode == AgentMode.CODING ? BASE_CODING_AGENT_PROMPT : BASE_CHAT_AGENT_PROMPT);
        }

        if (deepThinkingEnabled) {
            sb.append("\n\nThink carefully step by step before giving your final answer.");
        }

        if (conversation != null && !conversation.getProjectId().isEmpty()) {
            sb.append("\n\nActive Project Scope: ").append(conversation.getProjectId());
        }

        if (activeWorkspacePath != null && !activeWorkspacePath.trim().isEmpty()) {
            sb.append("\nActive Workspace Directory: ").append(activeWorkspacePath.trim());
        }

        if (activeMemories != null && !activeMemories.isEmpty()) {
            StringBuilder memBlock = new StringBuilder();
            for (MemoryItem item : activeMemories) {
                if (item != null && item.isEnabled() && !item.isArchived() && !item.getContent().isEmpty()) {
                    memBlock.append("- [")
                            .append(item.getCategory().name())
                            .append("/")
                            .append(item.getScope() == MemoryScope.PROJECT ? "PROJECT:" + item.getProjectId() : "GLOBAL")
                            .append("] ")
                            .append(item.getContent())
                            .append("\n");
                }
            }
            if (memBlock.length() > 0) {
                sb.append("\n\nRelevant Long-Term Memories (use when helpful):\n").append(memBlock);
            }
        }

        if (conversation != null && !conversation.getCompactionSummary().isEmpty()) {
            sb.append("\n\nEarlier Conversation Summary (compacted history, not long-term memory):\n")
                    .append(conversation.getCompactionSummary());
        }

        if (toolsUnsupportedByModel) {
            sb.append("\n\nNote: The currently selected model reports Tool Calling as UNSUPPORTED. Answer using conversation context only and inform the user if real-time tool execution is needed.");
        } else if (enabledTools != null && !enabledTools.isEmpty()) {
            sb.append("\n\nEnabled Tools (You have direct access to execute these tools. Call via native tool calls OR <chatai_tool name=\"tool_name\">{\"param\":\"value\"}</chatai_tool> tags before answering; the app executes them and feeds results back to you):\n");
            sb.append(ToolRegistry.formatCatalogSummary(enabledTools));
            sb.append("\nExample tool invocation for executing terminal commands:\n");
            sb.append("<chatai_tool name=\"terminal_run\">{\"command\":\"ls -la\"}</chatai_tool>\n");
            sb.append("When the user asks you to write code, program, or run commands, ALWAYS use terminal_run or terminal_script. Only invoke tools when genuinely needed. Wait for real tool output before stating results.");
        } else if (includeMemoryToolInstructions) {
            sb.append(MEMORY_TOOLS_PROMPT);
        }

        return sb.toString();
    }

    public List<Message> trimMessagesToBudget(String conversationId, List<Message> allMessages, int tokenBudget) {
        return trimMessagesToBudget(conversationId, allMessages, tokenBudget, 0L);
    }

    public List<Message> trimMessagesToBudget(String conversationId, List<Message> allMessages, int tokenBudget, long compactedUntil) {
        if (allMessages == null || allMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> selectedReversed = new ArrayList<>();
        int usedTokens = 0;

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Message msg = allMessages.get(i);
            if (msg == null) continue;
            if (conversationId != null && !conversationId.isEmpty()
                    && msg.getConversationId() != null && !msg.getConversationId().isEmpty()
                    && !conversationId.equals(msg.getConversationId())) {
                continue;
            }
            if (msg.isError()) continue;
            if (compactedUntil > 0L && msg.getCreatedAt() <= compactedUntil && !selectedReversed.isEmpty()) {
                continue;
            }

            int msgTokens = estimateTokens(msg.getContent()) + 16;
            if (!selectedReversed.isEmpty() && usedTokens + msgTokens > tokenBudget) {
                break;
            }
            selectedReversed.add(msg);
            usedTokens += msgTokens;
        }
        Collections.reverse(selectedReversed);
        return selectedReversed;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }
}
