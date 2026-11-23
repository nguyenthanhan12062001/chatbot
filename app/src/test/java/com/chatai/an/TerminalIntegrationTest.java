package com.chatai.an;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.chatai.an.ai.integration.terminal.AnsiTextParser;
import com.chatai.an.ai.integration.terminal.AppTerminalManager;
import com.chatai.an.ai.integration.terminal.TerminalSession;
import com.chatai.an.ai.tool.AiTool;
import com.chatai.an.ai.tool.TerminalTools;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.presentation.terminal.TerminalActivity;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = ChatAiApplication.class)
public class TerminalIntegrationTest {

    @Test
    public void ansiTextParser_parsesSgrColorsAndStripsControlSequences() {
        String raw = "\u001B[1;32mOK\u001B[0m plain \u001B[31mERR\u001B[0m\u001B]0;title\u0007";
        assertEquals("OK plain ERR", AnsiTextParser.stripAnsi(raw));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        AnsiTextParser.appendAnsiStyled(sb, raw, Color.WHITE);
        assertEquals("OK plain ERR", sb.toString());

        ForegroundColorSpan[] fgSpans = sb.getSpans(0, sb.length(), ForegroundColorSpan.class);
        StyleSpan[] styleSpans = sb.getSpans(0, sb.length(), StyleSpan.class);
        assertTrue(fgSpans.length >= 3);
        assertEquals(Color.parseColor("#3FB950"), fgSpans[0].getForegroundColor());
        assertEquals(Typeface.BOLD, styleSpans[0].getStyle());
    }

    @Test
    public void appTerminalManager_executesRealShellCommandsAndCapturesStdoutStderrExitCode() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        AppTerminalManager manager = app.getAppContainer().getAppTerminalManager();
        assertNotNull(manager);

        AppTerminalManager.CommandResult ok = manager.executeCommand("echo \"hello chatai\" && pwd", null, 15);
        assertTrue("Expected command launch", ok.launched);
        assertTrue("Expected exit 0, stderr=" + ok.stderr, ok.isSuccess());
        assertEquals(0, ok.exitCode);
        assertTrue(ok.stdout.contains("hello chatai"));
        assertFalse(ok.workdir.isEmpty());

        AppTerminalManager.CommandResult fail = manager.executeCommand("echo \"err_line\" >&2; exit 42", null, 15);
        assertTrue(fail.launched);
        assertFalse(fail.isSuccess());
        assertEquals(42, fail.exitCode);
        assertTrue(fail.stderr.contains("err_line"));
    }

    @Test
    public void terminalSession_persistsCwdExportsHistoryAndTabCompletion() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        AppTerminalManager manager = app.getAppContainer().getAppTerminalManager();
        TerminalSession session = new TerminalSession(
                manager,
                app.getAppContainer().getTermuxIntegrationManager(),
                TerminalBackend.APP_TERMINAL
        );

        String initialDir = session.getCurrentWorkdir();
        String subName = "cotg_sub_" + System.currentTimeMillis();

        session.submitCommand("mkdir -p " + subName + " && cd " + subName, 15);
        String afterCdDir = session.getCurrentWorkdir();
        assertTrue("Expected workdir to change to " + subName + " but was " + afterCdDir,
                afterCdDir.endsWith("/" + subName) || afterCdDir.endsWith(File.separator + subName));

        session.submitCommand("export COTG_VAR=ready_val", 15);
        session.submitCommand("echo \"$COTG_VAR\"", 15);
        assertTrue(session.getOutputSnapshot().toString().contains("ready_val"));

        session.submitCommand("unset COTG_VAR", 15);
        assertEquals("unset COTG_VAR", session.navigateHistory(true));
        assertEquals("echo \"$COTG_VAR\"", session.navigateHistory(true));
        assertEquals("unset COTG_VAR", session.navigateHistory(false));
        assertEquals("", session.navigateHistory(false));

        // Tab completion inside initialDir after returning back
        session.submitCommand("cd \"" + initialDir + "\"", 15);
        String completed = session.completeInput("cd cotg_sub_");
        assertTrue("Expected completion to match " + subName + " but got " + completed,
                completed.startsWith("cd " + subName));

        // Cleanup created temp directory
        session.submitCommand("rmdir \"" + subName + "\"", 15);

        // Backend switch updates session backend
        session.setActiveBackend(TerminalBackend.TERMUX);
        assertEquals(TerminalBackend.TERMUX, session.getActiveBackend());
    }

    @Test
    public void terminalTools_routeToPreferredBackendWhilePreservingTermuxTools() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ToolRegistry registry = app.getAppContainer().getToolRegistry();
        SettingsRepository settingsRepo = app.getAppContainer().getSettingsRepository();
        ToolExecutor executor = app.getAppContainer().getToolExecutor();

        // Both unified terminal tools and preserved Termux tools must be registered
        AiTool terminalRun = registry.getTool("terminal_run");
        AiTool terminalScript = registry.getTool("terminal_script");
        AiTool termuxRun = registry.getTool("termux_run");
        AiTool termuxScript = registry.getTool("termux_script");

        assertNotNull(terminalRun);
        assertNotNull(terminalScript);
        assertNotNull(termuxRun);
        assertNotNull(termuxScript);

        // Persistence of backend setting
        settingsRepo.setPreferredTerminalBackend(TerminalBackend.TERMUX);
        assertEquals(TerminalBackend.TERMUX, settingsRepo.getSettingsSnapshot().getPreferredTerminalBackend());
        assertEquals(TerminalBackend.TERMUX, TerminalTools.resolveTargetBackend(new JsonObject(), settingsRepo));

        JsonObject explicitAppArgs = new JsonObject();
        explicitAppArgs.addProperty("backend", "app");
        explicitAppArgs.addProperty("command", "echo explicit_app_ok");
        assertEquals(TerminalBackend.APP_TERMINAL, TerminalTools.resolveTargetBackend(explicitAppArgs, settingsRepo));

        assertFalse(executor.requiresConfirmation(terminalRun, explicitAppArgs));
        assertTrue(executor.describeConfirmationReason("terminal_run", explicitAppArgs).contains("In-App Terminal"));
        assertTrue(executor.describeConfirmationReason("terminal_run", new JsonObject()).contains("Termux"));

        // Execute with App Terminal backend
        settingsRepo.setPreferredTerminalBackend(TerminalBackend.APP_TERMINAL);
        assertEquals(TerminalBackend.APP_TERMINAL, TerminalTools.resolveTargetBackend(new JsonObject(), settingsRepo));

        ToolResult runRes = terminalRun.execute(explicitAppArgs);
        assertTrue(runRes.isSuccess());
        assertTrue(runRes.getOutput().contains("backend=APP_TERMINAL"));
        assertTrue(runRes.getOutput().contains("explicit_app_ok"));

        JsonObject scriptArgs = new JsonObject();
        scriptArgs.addProperty("script", "X=7\nY=5\necho \"sum=$((X+Y))\"");
        ToolResult scriptRes = terminalScript.execute(scriptArgs);
        assertTrue(scriptRes.isSuccess());
        assertTrue(scriptRes.getOutput().contains("sum=12"));
    }

    @Test
    public void terminalActivity_launchesAndSwitchesBackendPreference() {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        SettingsRepository settingsRepo = app.getAppContainer().getSettingsRepository();
        settingsRepo.setPreferredTerminalBackend(TerminalBackend.APP_TERMINAL);

        ActivityController<TerminalActivity> controller = Robolectric.buildActivity(TerminalActivity.class).setup();
        TerminalActivity activity = controller.get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        TextView btnApp = activity.findViewById(R.id.btn_backend_app);
        TextView btnTermux = activity.findViewById(R.id.btn_backend_termux);
        TextView tvOutput = activity.findViewById(R.id.tv_terminal_output);
        assertNotNull(btnApp);
        assertNotNull(btnTermux);
        assertNotNull(tvOutput);
        assertTrue(tvOutput.getText().toString().contains("ChatAI Terminal"));

        btnTermux.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TerminalBackend.TERMUX, settingsRepo.getSettingsSnapshot().getPreferredTerminalBackend());

        btnApp.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(TerminalBackend.APP_TERMINAL, settingsRepo.getSettingsSnapshot().getPreferredTerminalBackend());

        controller.pause().stop().destroy();
    }
}
