package com.chatai.an;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.chatai.an.ai.memory.MemoryMergeService;
import com.chatai.an.ai.tool.RecallTool;
import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.ApiKeyEntity;
import com.chatai.an.data.local.db.ChatDatabase;
import com.chatai.an.data.local.db.MemoryEntity;
import com.chatai.an.data.repository.LocalConversationRepository;
import com.chatai.an.data.repository.LocalMemoryRepository;
import com.chatai.an.data.repository.LocalProviderRepository;
import com.chatai.an.data.security.AndroidKeyStoreCipher;
import com.chatai.an.data.security.KeyCipher;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.model.ThemePreset;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ThemeRepository;
import com.chatai.an.presentation.themestudio.ThemeStudioViewModel;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = ChatAiApplication.class)
public class ChatAiRobolectricTest {

    @Test
    public void appName_isChatAI() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("ChatAI", context.getString(R.string.app_name));
    }

    @Test
    public void defaultTheme_isSunsetOrange() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ThemeConfig active = app.getAppContainer().getThemeRepository().getActiveThemeSnapshot();
        assertEquals(ThemePreset.SUNSET_ORANGE, active.getPreset());
    }

    @Test
    public void themeStudio_draftChangesDoNotMutateActiveThemeUntilApply() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ThemeRepository themeRepo = app.getAppContainer().getThemeRepository();

        ThemeStudioViewModel vm = new ThemeStudioViewModel(themeRepo);
        assertFalse(Boolean.TRUE.equals(vm.getHasUnsavedChanges().getValue()));

        vm.selectPreset(ThemePreset.OCEAN_BLUE);
        vm.updateColorToken(ThemeStudioViewModel.ColorToken.PRIMARY, Color.CYAN);

        assertEquals(ThemePreset.SUNSET_ORANGE, themeRepo.getActiveThemeSnapshot().getPreset());
        assertNotEquals(Color.CYAN, themeRepo.getActiveThemeSnapshot().getLightPalette().getPrimaryColor());
        assertTrue(Boolean.TRUE.equals(vm.getHasUnsavedChanges().getValue()));

        vm.applyDraftTheme();
        assertEquals(ThemePreset.OCEAN_BLUE, themeRepo.getActiveThemeSnapshot().getPreset());
        assertEquals(Color.CYAN, themeRepo.getActiveThemeSnapshot().getLightPalette().getPrimaryColor());
    }

    @Test
    public void modelCatalog_distinguishesAccessTypesLocalInactiveAndNoHardcodedOpenRouterFree() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ModelRepository modelRepo = app.getAppContainer().getModelRepository();

        AiModel freeDefault = modelRepo.getModelById("pollinations/openai");
        assertEquals(AccessType.FREE, freeDefault.getAccessType());
        assertEquals(AiModel.UsabilityStatus.USABLE, freeDefault.getUsabilityStatus());

        // OpenRouter Free must not contain hardcoded models prior to dynamic fetch
        List<AiModel> openRouterFreeModels = modelRepo.getGroupedModels().get(ModelProvider.OPENROUTER_FREE);
        assertNotNull(openRouterFreeModels);
        assertEquals(0, openRouterFreeModels.size());

        AiModel kiloRouter = modelRepo.getModelById("kilo/openrouter/free");
        assertEquals(AccessType.FREE, kiloRouter.getAccessType());
        assertEquals(CapabilityStatus.UNKNOWN, kiloRouter.getCapabilityStatus(ModelCapability.IMAGE));
        assertEquals(CapabilityStatus.SUPPORTED, kiloRouter.getProviderProtocolCapability(ModelCapability.IMAGE));
        assertEquals(CapabilityStatus.UNSUPPORTED, kiloRouter.getCapabilityStatus(ModelCapability.FILE));

        AiModel gemini = modelRepo.getModelById("gemini-2.5-flash");
        assertEquals(AccessType.PERSONAL_API, gemini.getAccessType());
        assertTrue(gemini.isDeepThinkingSupported());

        AiModel localRuntime = modelRepo.getModelById("local/device-runtime");
        assertEquals(AccessType.LOCAL, localRuntime.getAccessType());
        assertFalse(localRuntime.isAvailable());
        assertEquals(AiModel.UsabilityStatus.NO_LOCAL_RUNTIME, localRuntime.getUsabilityStatus());
        assertFalse(localRuntime.isImageSupported());
        assertFalse(localRuntime.isDeepThinkingSupported());
    }

    @Test
    public void providerRepository_encryptsApiKeysAndRotatesOnFailure() {
        Context context = ApplicationProvider.getApplicationContext();
        ChatDatabase db = Room.inMemoryDatabaseBuilder(context, ChatDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            KeyCipher cipher = new AndroidKeyStoreCipher();
            LocalProviderRepository repo = new LocalProviderRepository(
                    db.providerConfigDao(),
                    db.apiKeyDao(),
                    cipher,
                    AppExecutors.getInstance()
            );

            assertTrue(repo.addApiKey("openai", "Primary", "sk-secret-alpha-1234"));
            assertTrue(repo.addApiKey("openai", "Backup", "sk-secret-beta-5678"));

            List<ApiKeyEntity> stored = db.apiKeyDao().getByProviderSync("openai");
            assertEquals(2, stored.size());
            for (ApiKeyEntity entity : stored) {
                assertFalse(entity.encryptedKey.contains("sk-secret"));
                assertFalse(entity.maskedPreview.contains("alpha"));
                assertFalse(entity.maskedPreview.contains("beta"));
            }

            String firstKeyId = repo.getActiveKeyIdSync("openai");
            assertNotNull(firstKeyId);
            assertEquals("sk-secret-alpha-1234", repo.getActiveDecryptedKeySync("openai"));

            String rotatedKey = repo.rotateToNextAvailableKeySync("openai", firstKeyId, "HTTP 429", 60_000L);
            assertEquals("sk-secret-beta-5678", rotatedKey);
            assertEquals("sk-secret-beta-5678", repo.getActiveDecryptedKeySync("openai"));
        } finally {
            db.close();
        }
    }

    @Test
    public void memoryDao_persistsAndFiltersActiveConversationMemories() {
        Context context = ApplicationProvider.getApplicationContext();
        ChatDatabase db = Room.inMemoryDatabaseBuilder(context, ChatDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            long now = System.currentTimeMillis();
            db.memoryDao().insert(new MemoryEntity("m1", "PREFERENCE", null, "Global rule", true, now, now));
            db.memoryDao().insert(new MemoryEntity("m2", "PROJECT", "conv-1", "Conv 1 only", true, now, now));
            db.memoryDao().insert(new MemoryEntity("m3", "PROJECT", "conv-2", "Conv 2 only", true, now, now));
            db.memoryDao().insert(new MemoryEntity("m4", "FACT", null, "Disabled rule", false, now, now));

            List<MemoryEntity> activeForConv1 = db.memoryDao().getActiveForConversationSync("conv-1");
            assertEquals(2, activeForConv1.size());
        } finally {
            db.close();
        }
    }

    @Test
    public void rememberAndRecallTools_dedupAndTrackUsageAndEnforceProjectIsolation() {
        Context context = ApplicationProvider.getApplicationContext();
        ChatDatabase db = Room.inMemoryDatabaseBuilder(context, ChatDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            LocalMemoryRepository repo = new LocalMemoryRepository(db, AppExecutors.getInstance());
            RememberTool remember = new RememberTool(repo);
            RecallTool recall = new RecallTool(repo);

            JsonObject filler = new JsonObject();
            filler.addProperty("content", "ok");
            assertFalse(remember.execute(filler).isSuccess());

            JsonObject save1 = new JsonObject();
            save1.addProperty("content", "Use Clean Java MVVM without Kotlin");
            save1.addProperty("category", "PREFERENCE");
            save1.addProperty("scope", "GLOBAL");
            save1.addProperty("area", "CODE");
            save1.addProperty("importance", 5);
            ToolResult r1 = remember.execute(save1);
            assertTrue(r1.isSuccess());

            // Exact duplicate should refresh existing instead of creating a second row
            ToolResult dup = remember.execute(save1);
            assertTrue(dup.isSuccess());
            assertTrue(dup.getOutput().contains("Duplicate detected"));
            assertEquals(1, repo.getAllNonArchivedSync().size());

            // Save isolated PROJECT memory
            JsonObject saveProj = new JsonObject();
            saveProj.addProperty("content", "Project Zeta uses PostgreSQL 16");
            saveProj.addProperty("category", "PROJECT");
            saveProj.addProperty("scope", "PROJECT");
            saveProj.addProperty("projectId", "proj-zeta");
            assertTrue(remember.execute(saveProj).isSuccess());

            JsonObject recallOtherProj = new JsonObject();
            recallOtherProj.addProperty("query", "PostgreSQL");
            recallOtherProj.addProperty("projectId", "proj-alpha");
            ToolResult otherRes = recall.execute(recallOtherProj);
            assertTrue(otherRes.isSuccess());
            assertFalse(otherRes.getOutput().contains("PostgreSQL 16"));

            JsonObject recallSameProj = new JsonObject();
            recallSameProj.addProperty("query", "PostgreSQL");
            recallSameProj.addProperty("projectId", "proj-zeta");
            ToolResult sameRes = recall.execute(recallSameProj);
            assertTrue(sameRes.isSuccess());
            assertTrue(sameRes.getOutput().contains("PostgreSQL 16"));
        } finally {
            db.close();
        }
    }

    @Test
    public void memoryMergeAndRestore_isNonDestructiveAndRestorable() {
        Context context = ApplicationProvider.getApplicationContext();
        ChatDatabase db = Room.inMemoryDatabaseBuilder(context, ChatDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            LocalMemoryRepository repo = new LocalMemoryRepository(db, AppExecutors.getInstance());
            MemoryItem a = repo.addMemorySync(MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, MemoryArea.CODE, "", null, "Prefer concise Java Android code", 3, false);
            MemoryItem b = repo.addMemorySync(MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, MemoryArea.CODE, "", null, "Use Java Android Material Design 3", 4, true);

            MemoryMergeService mergeService = new MemoryMergeService(repo);
            List<MemoryMergeService.MergeProposal> proposals = mergeService.findMergeProposals();
            assertEquals(1, proposals.size());

            MemoryItem merged = mergeService.applyMerge(proposals.get(0), "Prefer concise Java Android code with Material Design 3");
            assertNotNull(merged);
            assertEquals(4, merged.getImportance());
            assertTrue(merged.isPinned());

            // Originals are preserved in DB with archived=true and mergedIntoId
            MemoryItem aStored = repo.getByIdSync(a.getId());
            MemoryItem bStored = repo.getByIdSync(b.getId());
            assertTrue(aStored.isArchived());
            assertTrue(bStored.isArchived());
            assertEquals(merged.getId(), aStored.getMergedIntoId());
            assertEquals(1, repo.getAllNonArchivedSync().size());

            // Restore restores originals safely
            mergeService.restoreMerged(merged.getId());
            assertFalse(repo.getByIdSync(a.getId()).isArchived());
            assertFalse(repo.getByIdSync(b.getId()).isArchived());
            assertNull(repo.getByIdSync(a.getId()).getMergedIntoId());
        } finally {
            db.close();
        }
    }

    @Test
    public void conversationCompactionMetadata_persistsIndependentlyFromMessages() {
        Context context = ApplicationProvider.getApplicationContext();
        ChatDatabase db = Room.inMemoryDatabaseBuilder(context, ChatDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            LocalConversationRepository convRepo = new LocalConversationRepository(db.conversationDao(), AppExecutors.getInstance());
            db.conversationDao().insert(new com.chatai.an.data.local.db.ConversationEntity("c1", "Test", "pollinations/openai", 10L, 10L, "hi"));
            convRepo.updateConversationProject("c1", "chatai");
            convRepo.updateConversationCompaction("c1", "Discussed SQLite Room migration", 500L);

            Conversation loaded = convRepo.getConversationSync("c1");
            assertNotNull(loaded);
            assertEquals("chatai", loaded.getProjectId());
            assertEquals("Discussed SQLite Room migration", loaded.getCompactionSummary());
            assertEquals(500L, loaded.getCompactedUntil());
        } finally {
            db.close();
        }
    }

    @Test
    public void toolRegistry_registersBuiltInAndMemoryToolsWithExpectedPermissions() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        var registry = app.getAppContainer().getToolRegistry();

        assertNotNull(registry.getTool("calculator"));
        assertNotNull(registry.getTool("device_info"));
        assertNotNull(registry.getTool("list_attachments"));
        assertNotNull(registry.getTool("web_fetch"));
        assertNotNull(registry.getTool("remember"));
        assertNotNull(registry.getTool("recall"));

        assertEquals(ToolPermission.AUTO_ALLOW, registry.getTool("remember").getDefinition().getPermission());
        assertEquals(ToolPermission.AUTO_ALLOW, registry.getTool("recall").getDefinition().getPermission());
        assertEquals(ToolPermission.REQUIRE_CONFIRMATION, registry.getTool("web_fetch").getDefinition().getPermission());
    }
}
