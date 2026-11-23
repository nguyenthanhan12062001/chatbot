package com.chatai.an;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chatai.an.ai.agent.AgentEngine;
import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.memory.MemoryMergeService;
import com.chatai.an.ai.memory.MemoryRetrievalEngine;
import com.chatai.an.ai.provider.KiloProvider;
import com.chatai.an.ai.provider.OpenRouterFreeProvider;
import com.chatai.an.ai.tool.CalculatorTool;
import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.ai.tool.WebFetchTool;
import com.chatai.an.core.MarkdownRenderer;
import com.chatai.an.data.repository.UnifiedModelRepository;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelPricingType;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChatAiUnitTest {

    @Test
    public void markdownRenderer_splitsTextTablesAndCodeBlocks() {
        String sample = "# Title\n> Important note\n| Name | Role |\n| --- | --- |\n| Ada | Lead |\n```java\nSystem.out.println(\"hi\");\n```\nVisit [Docs](https://example.com)";
        List<MarkdownRenderer.MarkdownSegment> segments = MarkdownRenderer.parseSegments(sample);

        assertEquals(4, segments.size());
        assertEquals(MarkdownRenderer.SegmentType.TEXT, segments.get(0).getType());
        assertEquals(MarkdownRenderer.SegmentType.TABLE, segments.get(1).getType());
        assertEquals(Arrays.asList("Name", "Role"), segments.get(1).getTableHeaders());
        assertEquals(1, segments.get(1).getTableRows().size());
        assertEquals("Ada", segments.get(1).getTableRows().get(0).get(0));
        assertEquals(MarkdownRenderer.SegmentType.CODE_BLOCK, segments.get(2).getType());
        assertEquals("java", segments.get(2).getLanguage());
        assertEquals(MarkdownRenderer.SegmentType.TEXT, segments.get(3).getType());
        assertTrue(segments.get(3).getContent().contains("https://example.com"));
    }

    @Test
    public void markdownRenderer_handlesEmptyInput() {
        List<MarkdownRenderer.MarkdownSegment> segments = MarkdownRenderer.parseSegments("");
        assertNotNull(segments);
        assertEquals(0, segments.size());
    }

    @Test
    public void kiloProvider_verifiedZeroPricingChecksPromptAndCompletion() {
        JsonObject zeroModel = JsonParser.parseString(
                "{\"id\":\"openrouter/free\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0.0\"}}"
        ).getAsJsonObject();
        assertTrue(KiloProvider.isVerifiedZeroPricing(zeroModel));

        JsonObject fakeFreeModel = JsonParser.parseString(
                "{\"id\":\"vendor/model:free\",\"pricing\":{\"prompt\":\"0.0002\",\"completion\":\"0\"}}"
        ).getAsJsonObject();
        assertFalse(KiloProvider.isVerifiedZeroPricing(fakeFreeModel));

        JsonObject missingPricing = JsonParser.parseString("{\"id\":\"vendor/no-price\"}").getAsJsonObject();
        assertFalse(KiloProvider.isVerifiedZeroPricing(missingPricing));
    }

    @Test
    public void openRouterFree_distinguishesZeroPricingFromKeyUsability() {
        JsonArray arr = JsonParser.parseString("["
                + "{\"id\":\"meta-llama/llama-3.3-70b-instruct:free\",\"name\":\"Llama 3.3 70B Free\",\"context_length\":131072,\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}},"
                + "{\"id\":\"openai/gpt-4o\",\"name\":\"GPT-4o Paid\",\"context_length\":128000,\"pricing\":{\"prompt\":\"0.0025\",\"completion\":\"0.01\"}}"
                + "]").getAsJsonArray();

        List<AiModel> withoutKey = OpenRouterFreeProvider.parseDynamicFreeModels(arr, false);
        assertEquals(1, withoutKey.size());
        assertEquals(AiModel.UsabilityStatus.NEEDS_API_KEY, withoutKey.get(0).getUsabilityStatus());
        assertFalse(withoutKey.get(0).isAvailable());

        List<AiModel> withKey = OpenRouterFreeProvider.parseDynamicFreeModels(arr, true);
        assertEquals(1, withKey.size());
        assertEquals(AiModel.UsabilityStatus.USABLE, withKey.get(0).getUsabilityStatus());
        assertTrue(withKey.get(0).isAvailable());
    }

    @Test
    public void contextManager_isolatesConversationAndIncludesCompactionSummaryWithoutMutatingInput() {
        ContextManager contextManager = new ContextManager();
        List<Message> history = new ArrayList<>();
        history.add(new Message("m1", "conv-A", MessageRole.USER, "First long message in conv A " + "x".repeat(200), "m", false, 1L, 1L, Collections.emptyList()));
        history.add(new Message("m2", "conv-B", MessageRole.USER, "Secret message in conv B", "m", false, 2L, 2L, Collections.emptyList()));
        history.add(new Message("m3", "conv-A", MessageRole.ASSISTANT, "Error banner", "", "m", "", null, false, null, true, 3L, 3L, Collections.emptyList()));
        history.add(new Message("m4", "conv-A", MessageRole.USER, "Latest question in conv A", "m", false, 4L, 4L, Collections.emptyList()));

        List<Message> trimmed = contextManager.trimMessagesToBudget("conv-A", history, 40);
        assertEquals(4, history.size());
        assertEquals(1, trimmed.size());
        assertEquals("m4", trimmed.get(0).getId());

        Conversation conv = new Conversation("conv-A", "Chat", "m", "proj-alpha", "Compacted summary of turns 1..2", 2L, 1L, 4L, "");
        List<MemoryItem> memories = Collections.singletonList(
                new MemoryItem("mem1", MemoryCategory.PREFERENCE, null, "Respond in concise Java", true, 1L, 1L)
        );
        AiRequest req = contextManager.buildRequest(conv, "conv-A", null, history, memories, true, true, "");
        assertTrue(req.getSystemPrompt().contains("Respond in concise Java"));
        assertTrue(req.getSystemPrompt().contains("Compacted summary of turns 1..2"));
        assertTrue(req.getSystemPrompt().contains("Active Project Scope: proj-alpha"));
        assertTrue(req.getSystemPrompt().contains("step by step"));
    }

    @Test
    public void memoryRetrievalEngine_enforcesProjectIsolationAndPinPriority() {
        MemoryRetrievalEngine engine = new MemoryRetrievalEngine(null);
        List<MemoryItem> candidates = Arrays.asList(
                new MemoryItem("g1", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, MemoryArea.CODE, "", null, "Always write clean Java code with Javadoc", 4, true, true, false, null, 1L, 1L, 0L, 0),
                new MemoryItem("pA", MemoryCategory.PROJECT, MemoryScope.PROJECT, MemoryArea.CODE, "proj-alpha", null, "Project Alpha uses Room database v3", 4, true, false, false, null, 1L, 1L, 0L, 0),
                new MemoryItem("pB", MemoryCategory.PROJECT, MemoryScope.PROJECT, MemoryArea.CODE, "proj-beta", null, "Secret Project Beta credentials info", 5, true, true, false, null, 1L, 1L, 0L, 0),
                new MemoryItem("arch", MemoryCategory.FACT, MemoryScope.GLOBAL, MemoryArea.SHARED, "", null, "Archived old preference", 5, true, true, true, "g1", 1L, 1L, 0L, 0),
                new MemoryItem("off", MemoryCategory.FACT, MemoryScope.GLOBAL, MemoryArea.SHARED, "", null, "Disabled preference", 5, false, true, false, null, 1L, 1L, 0L, 0)
        );

        List<MemoryItem> picked = engine.rankAndFilter(candidates, "Help me refactor Room Java DAO", "proj-alpha", 600, 10, false);
        assertEquals(2, picked.size());
        assertEquals("g1", picked.get(0).getId());
        assertEquals("pA", picked.get(1).getId());
    }

    @Test
    public void memoryMergeService_clustersRelatedWithoutMixingProjects() {
        List<MemoryItem> items = Arrays.asList(
                new MemoryItem("1", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, MemoryArea.CODE, "", null, "User prefers concise Java Android examples", 3, true, false, false, null, 1L, 1L, 0L, 0),
                new MemoryItem("2", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, MemoryArea.CODE, "", null, "Always use Java Android without Kotlin", 4, true, true, false, null, 2L, 2L, 0L, 0),
                new MemoryItem("3", MemoryCategory.PREFERENCE, MemoryScope.PROJECT, MemoryArea.CODE, "proj-x", null, "In proj-x use Java Android MVVM", 4, true, false, false, null, 3L, 3L, 0L, 0)
        );

        List<MemoryMergeService.MergeProposal> proposals = MemoryMergeService.findProposalsFromList(items);
        assertEquals(1, proposals.size());
        assertEquals(2, proposals.get(0).getSources().size());
        assertEquals(MemoryScope.GLOBAL, proposals.get(0).getScope());
        assertTrue(proposals.get(0).isAnyPinned());
        assertEquals(4, proposals.get(0).getMaxImportance());
    }

    @Test
    public void aiCore_parsesAndStripsChatToolTags() {
        String output = "<chatai_tool name=\"remember\">{\"content\":\"User works on Android Java\",\"category\":\"PROFILE\"}</chatai_tool>\nGot it, I will remember that!";
        List<AiCore.ToolInvocation> calls = AiCore.parseToolInvocations(output);
        assertEquals(1, calls.size());
        assertEquals("remember", calls.get(0).toolName);
        assertEquals("PROFILE", calls.get(0).args.get("category").getAsString());
        assertEquals("Got it, I will remember that!", AiCore.stripToolTags(output));
    }

    @Test
    public void rememberTool_rejectsFiller() {
        assertTrue(RememberTool.isTrivialFiller("ok"));
        assertTrue(RememberTool.isTrivialFiller("Thanks!"));
        assertFalse(RememberTool.isTrivialFiller("User prefers dark mode and Sunset Orange theme"));
    }

    @Test
    public void aiCore_fallbackPolicy_blocksFreeToPersonalApi() {
        AiModel free1 = dummyModel("pollinations/openai", AccessType.FREE);
        AiModel free2 = dummyModel("kilo/openrouter/free", AccessType.FREE);
        AiModel paid1 = dummyModel("gpt-4o-mini", AccessType.PERSONAL_API);
        AiModel paid2 = dummyModel("gemini-2.5-flash", AccessType.PERSONAL_API);

        assertFalse(AiCore.isFallbackAllowed(free1, paid1));
        assertTrue(AiCore.isFallbackAllowed(free1, free2));
        assertTrue(AiCore.isFallbackAllowed(paid1, paid2));
        assertFalse(AiCore.isFallbackAllowed(paid1, paid1));
    }

    @Test
    public void calculatorTool_evaluatesExpressionAndHandlesErrors() {
        CalculatorTool tool = new CalculatorTool();
        JsonObject args = new JsonObject();
        args.addProperty("expression", "(2 + 3) * 4 - sqrt(16)");
        ToolResult ok = tool.execute(args);
        assertTrue(ok.isSuccess());
        assertTrue(ok.getOutput().endsWith("= 16"));

        JsonObject divZero = new JsonObject();
        divZero.addProperty("expression", "10 / 0");
        ToolResult err = tool.execute(divZero);
        assertFalse(err.isSuccess());
    }

    @Test
    public void webFetchTool_blocksLocalhostPrivateIpsAndNonHttp() {
        assertNotNull(WebFetchTool.validatePublicUrl("http://localhost:8080/secret"));
        assertNotNull(WebFetchTool.validatePublicUrl("http://127.0.0.1/admin"));
        assertNotNull(WebFetchTool.validatePublicUrl("http://169.254.169.254/latest/meta-data"));
        assertNotNull(WebFetchTool.validatePublicUrl("http://192.168.1.1"));
        assertNotNull(WebFetchTool.validatePublicUrl("http://10.0.0.1"));
        assertNotNull(WebFetchTool.validatePublicUrl("file:///etc/passwd"));
        assertNotNull(WebFetchTool.validatePublicUrl("ftp://example.com/a"));
        assertNull(WebFetchTool.validatePublicUrl("https://93.184.216.34/"));
    }

    @Test
    public void agentEngine_parseDirective_handlesToolJsonAndPlainFallback() {
        String fenced = "```json\n{\"thought\":\"need math\",\"action\":\"calculator\",\"args\":{\"expression\":\"2+2\"}}\n```";
        AgentEngine.AgentDirective d = AgentEngine.parseDirective(fenced);
        assertEquals("calculator", d.action);
        assertEquals("need math", d.thought);
        assertEquals("2+2", d.args.get("expression").getAsString());

        AgentEngine.AgentDirective plain = AgentEngine.parseDirective("Direct plain response");
        assertEquals("final_answer", plain.action);
        assertEquals("Direct plain response", plain.answer);
    }

    private AiModel dummyModel(String id, AccessType accessType) {
        return new AiModel(
                id,
                id,
                accessType == AccessType.FREE ? ModelProvider.POLLINATIONS : ModelProvider.OPENAI,
                accessType == AccessType.FREE ? ModelPricingType.FREE : ModelPricingType.API_KEY_REQUIRED,
                accessType,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "",
                "32K",
                32000,
                4096,
                true,
                "builtin",
                0L,
                UnifiedModelRepository.caps(
                        CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED,
                        CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED
                ),
                UnifiedModelRepository.caps(
                        CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED,
                        CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED
                )
        );
    }
}
