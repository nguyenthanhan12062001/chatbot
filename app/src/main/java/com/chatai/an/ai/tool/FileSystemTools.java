package com.chatai.an.ai.tool;

import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Modular File System tools backed by WorkspaceFileManager (App Workspace, SAF granted folders, and optional Shizuku).
 */
public final class FileSystemTools {
    private FileSystemTools() {
    }

    public static void registerAll(ToolRegistry registry, WorkspaceFileManager fm) {
        registry.register(new ListDirTool(fm));
        registry.register(new ReadFileTool(fm));
        registry.register(new WriteFileTool(fm));
        registry.register(new EditFileTool(fm));
        registry.register(new SearchFilesTool(fm));
        registry.register(new GrepContentTool(fm));
        registry.register(new MkdirTool(fm));
        registry.register(new CopyPathTool(fm));
        registry.register(new MovePathTool(fm));
        registry.register(new RenamePathTool(fm));
        registry.register(new DeletePathTool(fm));
        registry.register(new FileInfoTool(fm));
    }

    public static class ListDirTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_list_dir",
                "List files and subdirectories in the workspace, SAF folder (saf://alias/path), or accessible storage path.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Directory path or empty for active workspace\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                false
        );
        private final WorkspaceFileManager fm;

        public ListDirTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                List<WorkspaceFileManager.FileEntry> entries = fm.listDirectory(path);
                if (entries.isEmpty()) {
                    return new ToolResult(DEF.getName(), true, "(Empty directory)", "");
                }
                StringBuilder sb = new StringBuilder();
                for (WorkspaceFileManager.FileEntry e : entries) {
                    sb.append(e.directory ? "[DIR]  " : "[FILE] ")
                            .append(e.name)
                            .append(e.directory ? "/" : (" (" + e.sizeBytes + " B)"))
                            .append(" -> ").append(e.path).append("\n");
                }
                return new ToolResult(DEF.getName(), true, sb.toString().trim(), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class ReadFileTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_read_file",
                "Read text/source file contents with optional 1-based line range.",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"end_line\":{\"type\":\"integer\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                false
        );
        private final WorkspaceFileManager fm;

        public ReadFileTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                if (path.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing path");
                int start = optInt(args, "start_line", -1);
                int end = optInt(args, "end_line", -1);
                String text = fm.readFileText(path, start, end);
                return new ToolResult(DEF.getName(), true, text, "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class WriteFileTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_write_file",
                "Create a new file or write/append text content to a file. Overwriting existing non-empty files requires confirmation.",
                "{\"type\":\"object\",\"required\":[\"path\",\"content\"],\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"append\":{\"type\":\"boolean\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public WriteFileTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                if (path.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing path");
                String content = optRawStr(args, "content");
                boolean append = args != null && args.has("append") && args.get("append").getAsBoolean();
                String resolved = fm.writeFileText(path, content, append);
                return new ToolResult(DEF.getName(), true, (append ? "Appended to " : "Wrote ") + resolved + " (" + content.length() + " chars)", "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class EditFileTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_edit_file",
                "Edit an existing file by replacing an exact snippet (old_text -> new_text).",
                "{\"type\":\"object\",\"required\":[\"path\",\"old_text\",\"new_text\"],\"properties\":{\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"},\"replace_all\":{\"type\":\"boolean\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public EditFileTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                String oldText = args != null && args.has("old_text") ? args.get("old_text").getAsString() : "";
                String newText = args != null && args.has("new_text") ? args.get("new_text").getAsString() : "";
                boolean replaceAll = args != null && args.has("replace_all") && args.get("replace_all").getAsBoolean();
                String summary = fm.editFileReplace(path, oldText, newText, replaceAll);
                return new ToolResult(DEF.getName(), true, summary, "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class SearchFilesTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_search_files",
                "Search recursively for files or folders matching a name substring.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"pattern\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                false
        );
        private final WorkspaceFileManager fm;

        public SearchFilesTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                String pattern = optStr(args, "pattern");
                List<WorkspaceFileManager.FileEntry> hits = fm.searchFiles(path, pattern);
                if (hits.isEmpty()) return new ToolResult(DEF.getName(), true, "No matching files found.", "");
                StringBuilder sb = new StringBuilder();
                for (WorkspaceFileManager.FileEntry e : hits) {
                    sb.append(e.directory ? "[DIR] " : "[FILE] ").append(e.path).append("\n");
                }
                return new ToolResult(DEF.getName(), true, sb.toString().trim(), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class GrepContentTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_grep",
                "Search inside files for matching text or regex lines with file:line references.",
                "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"path\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"regex\":{\"type\":\"boolean\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                false
        );
        private final WorkspaceFileManager fm;

        public GrepContentTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String path = optStr(args, "path");
                String query = optStr(args, "query");
                boolean regex = args != null && args.has("regex") && args.get("regex").getAsBoolean();
                List<String> matches = fm.grepContent(path, query, regex);
                if (matches.isEmpty()) return new ToolResult(DEF.getName(), true, "No content matches found.", "");
                return new ToolResult(DEF.getName(), true, String.join("\n", matches), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class MkdirTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_mkdir",
                "Create a new directory (including intermediate parent directories).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public MkdirTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                String created = fm.createDirectory(optStr(args, "path"));
                return new ToolResult(DEF.getName(), true, "Directory ready: " + created, "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class CopyPathTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_copy",
                "Copy a file or directory to a destination path.",
                "{\"type\":\"object\",\"required\":[\"source\",\"destination\"],\"properties\":{\"source\":{\"type\":\"string\"},\"destination\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public CopyPathTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                return new ToolResult(DEF.getName(), true, fm.copyPath(optStr(args, "source"), optStr(args, "destination")), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class MovePathTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_move",
                "Move a file or directory to a new path.",
                "{\"type\":\"object\",\"required\":[\"source\",\"destination\"],\"properties\":{\"source\":{\"type\":\"string\"},\"destination\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public MovePathTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                return new ToolResult(DEF.getName(), true, fm.movePath(optStr(args, "source"), optStr(args, "destination")), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class RenamePathTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_rename",
                "Rename a file or directory in place.",
                "{\"type\":\"object\",\"required\":[\"path\",\"new_name\"],\"properties\":{\"path\":{\"type\":\"string\"},\"new_name\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public RenamePathTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                return new ToolResult(DEF.getName(), true, fm.renamePath(optStr(args, "path"), optStr(args, "new_name")), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class DeletePathTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_delete",
                "Delete a file or directory (always requires user confirmation).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},\"recursive\":{\"type\":\"boolean\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.FILE_SYSTEM,
                true
        );
        private final WorkspaceFileManager fm;

        public DeletePathTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                boolean rec = args != null && args.has("recursive") && args.get("recursive").getAsBoolean();
                return new ToolResult(DEF.getName(), true, fm.deletePath(optStr(args, "path"), rec), "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class FileInfoTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fs_info",
                "Get metadata, size in bytes, and modified time for a file or directory.",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.FILE_SYSTEM,
                false
        );
        private final WorkspaceFileManager fm;

        public FileInfoTool(WorkspaceFileManager fm) {
            this.fm = fm;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            try {
                WorkspaceFileManager.FileEntry info = fm.getFileInfo(optStr(args, "path"));
                String dateStr = info.lastModified > 0
                        ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(info.lastModified))
                        : "unknown";
                String out = "Name: " + info.name
                        + "\nPath: " + info.path
                        + "\nType: " + (info.directory ? "Directory" : "File")
                        + "\nSize: " + info.sizeBytes + " bytes"
                        + "\nModified: " + dateStr;
                return new ToolResult(DEF.getName(), true, out, "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    private static String optStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString().trim()
                : "";
    }

    private static String optRawStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString()
                : "";
    }

    private static int optInt(JsonObject args, String key, int def) {
        if (args != null && args.has(key) && args.get(key).isJsonPrimitive()) {
            try {
                return args.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return def;
    }
}
