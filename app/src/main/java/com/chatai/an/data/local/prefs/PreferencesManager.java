package com.chatai.an.data.local.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.chatai.an.core.AppLogger;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AppLanguage;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.theme.ThemePresetCatalog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class PreferencesManager {
    private static final String PREFS_NAME = "chatai_preferences";
    private static final String KEY_ACTIVE_THEME = "active_theme_json";
    private static final String KEY_SELECTED_MODEL_ID = "selected_model_id";
    private static final String KEY_DEEP_THINKING_PREFERRED = "deep_thinking_preferred";
    private static final String KEY_CONFIRM_DELETE = "confirm_before_delete";
    private static final String KEY_AUTO_TITLE = "auto_title_first_message";
    private static final String KEY_LANGUAGE_TAG = "app_language_tag";

    // Phase 4: Agent & Tool System keys
    private static final String KEY_DEFAULT_AGENT_MODE = "default_agent_mode";
    private static final String KEY_AGENT_MAX_STEPS = "agent_max_steps";
    private static final String KEY_TOOL_TIMEOUT_SECONDS = "tool_timeout_seconds";
    private static final String KEY_CHAT_TOOLS_ENABLED = "chat_tools_enabled";
    private static final String KEY_CONFIRM_MUTATING_TOOLS = "confirm_mutating_tools";
    private static final String KEY_CONFIRM_SHELL_EXTERNAL_TOOLS = "confirm_shell_external_tools";
    private static final String KEY_PREFERRED_SEARCH_ENGINE = "preferred_search_engine";
    private static final String KEY_ACTIVE_WORKSPACE_PATH = "active_workspace_path";
    private static final String KEY_DOWNLOAD_DEST_PATH = "download_destination_path";
    private static final String KEY_ALLOW_SHIZUKU_FILE_FALLBACK = "allow_shizuku_file_fallback";
    private static final String KEY_DISABLED_TOOLS = "disabled_tool_names";
    private static final String KEY_SAF_GRANTED_TREES_JSON = "saf_granted_trees_json";

    public static final String DEFAULT_MODEL_ID = "pollinations/openai";

    private final SharedPreferences prefs;
    private final Gson gson;

    public PreferencesManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }

    public ThemeConfig loadActiveTheme() {
        String json = prefs.getString(KEY_ACTIVE_THEME, null);
        if (json != null && !json.isEmpty()) {
            try {
                ThemeConfig parsed = gson.fromJson(json, ThemeConfig.class);
                if (parsed != null && parsed.getLightPalette() != null && parsed.getDarkPalette() != null) {
                    return parsed;
                }
            } catch (Exception e) {
                AppLogger.e("Preferences", "Failed to parse stored ThemeConfig", e);
            }
        }
        return ThemePresetCatalog.createDefaultConfig();
    }

    public void saveActiveTheme(ThemeConfig config) {
        if (config == null) return;
        String json = gson.toJson(config);
        prefs.edit().putString(KEY_ACTIVE_THEME, json).apply();
    }

    public String getSelectedModelId() {
        return prefs.getString(KEY_SELECTED_MODEL_ID, DEFAULT_MODEL_ID);
    }

    public void setSelectedModelId(String modelId) {
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply();
    }

    public AppSettings loadSettings() {
        Set<String> disabled = prefs.getStringSet(KEY_DISABLED_TOOLS, Collections.emptySet());
        return new AppSettings(
                getSelectedModelId(),
                prefs.getBoolean(KEY_DEEP_THINKING_PREFERRED, false),
                prefs.getBoolean(KEY_CONFIRM_DELETE, true),
                prefs.getBoolean(KEY_AUTO_TITLE, true),
                AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE_TAG, AppLanguage.ENGLISH.getTag())),
                AgentMode.fromName(prefs.getString(KEY_DEFAULT_AGENT_MODE, AgentMode.CHAT.name())),
                prefs.getInt(KEY_AGENT_MAX_STEPS, 8),
                prefs.getInt(KEY_TOOL_TIMEOUT_SECONDS, 30),
                prefs.getBoolean(KEY_CHAT_TOOLS_ENABLED, true),
                prefs.getBoolean(KEY_CONFIRM_MUTATING_TOOLS, true),
                prefs.getBoolean(KEY_CONFIRM_SHELL_EXTERNAL_TOOLS, true),
                prefs.getString(KEY_PREFERRED_SEARCH_ENGINE, "auto"),
                prefs.getString(KEY_ACTIVE_WORKSPACE_PATH, ""),
                prefs.getString(KEY_DOWNLOAD_DEST_PATH, ""),
                prefs.getBoolean(KEY_ALLOW_SHIZUKU_FILE_FALLBACK, false),
                disabled != null ? new HashSet<>(disabled) : Collections.emptySet()
        );
    }

    public void setDeepThinkingPreferred(boolean preferred) {
        prefs.edit().putBoolean(KEY_DEEP_THINKING_PREFERRED, preferred).apply();
    }

    public void setConfirmBeforeDelete(boolean confirm) {
        prefs.edit().putBoolean(KEY_CONFIRM_DELETE, confirm).apply();
    }

    public void setAutoTitleFromFirstMessage(boolean autoTitle) {
        prefs.edit().putBoolean(KEY_AUTO_TITLE, autoTitle).apply();
    }

    public void setLanguage(AppLanguage language) {
        if (language == null) return;
        prefs.edit().putString(KEY_LANGUAGE_TAG, language.getTag()).apply();
    }

    public void setDefaultAgentMode(AgentMode mode) {
        if (mode == null) return;
        prefs.edit().putString(KEY_DEFAULT_AGENT_MODE, mode.name()).apply();
    }

    public void setAgentMaxSteps(int maxSteps) {
        prefs.edit().putInt(KEY_AGENT_MAX_STEPS, Math.max(2, Math.min(12, maxSteps))).apply();
    }

    public void setToolTimeoutSeconds(int timeoutSeconds) {
        prefs.edit().putInt(KEY_TOOL_TIMEOUT_SECONDS, Math.max(10, Math.min(120, timeoutSeconds))).apply();
    }

    public void setChatToolsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CHAT_TOOLS_ENABLED, enabled).apply();
    }

    public void setConfirmMutatingTools(boolean confirm) {
        prefs.edit().putBoolean(KEY_CONFIRM_MUTATING_TOOLS, confirm).apply();
    }

    public void setConfirmShellAndExternalTools(boolean confirm) {
        prefs.edit().putBoolean(KEY_CONFIRM_SHELL_EXTERNAL_TOOLS, confirm).apply();
    }

    public void setPreferredSearchEngine(String engine) {
        prefs.edit().putString(KEY_PREFERRED_SEARCH_ENGINE, engine != null ? engine : "auto").apply();
    }

    public void setActiveWorkspacePath(String path) {
        prefs.edit().putString(KEY_ACTIVE_WORKSPACE_PATH, path != null ? path.trim() : "").apply();
    }

    public void setDownloadDestinationPath(String path) {
        prefs.edit().putString(KEY_DOWNLOAD_DEST_PATH, path != null ? path.trim() : "").apply();
    }

    public void setAllowShizukuFileFallback(boolean allowed) {
        prefs.edit().putBoolean(KEY_ALLOW_SHIZUKU_FILE_FALLBACK, allowed).apply();
    }

    public void setToolEnabled(String toolName, boolean enabled) {
        if (toolName == null || toolName.trim().isEmpty()) return;
        Set<String> current = prefs.getStringSet(KEY_DISABLED_TOOLS, Collections.emptySet());
        Set<String> updated = current != null ? new HashSet<>(current) : new HashSet<>();
        if (enabled) {
            updated.remove(toolName.trim());
        } else {
            updated.add(toolName.trim());
        }
        prefs.edit().putStringSet(KEY_DISABLED_TOOLS, updated).apply();
    }

    public Map<String, String> getSafGrantedTrees() {
        String json = prefs.getString(KEY_SAF_GRANTED_TREES_JSON, "");
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
            Map<String, String> map = gson.fromJson(json, type);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public void saveSafGrantedTrees(Map<String, String> trees) {
        prefs.edit().putString(KEY_SAF_GRANTED_TREES_JSON, gson.toJson(trees != null ? trees : Collections.emptyMap())).apply();
    }
}
