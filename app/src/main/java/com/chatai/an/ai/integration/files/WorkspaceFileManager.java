package com.chatai.an.ai.integration.files;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.data.local.prefs.PreferencesManager;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.repository.SettingsRepository;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages real file access across:
 * 1. ChatAI's dedicated project workspace directory.
 * 2. User-granted SAF (Storage Access Framework) folders with persisted URI permissions.
 * 3. Accessible Android internal storage directories (subject to Android scoped storage rules).
 * 4. Optional Shizuku privileged shell fallback when explicitly enabled & connected.
 */
public class WorkspaceFileManager {

    public static class GrantedFolder {
        public final String alias;
        public final String treeUri;
        public final boolean valid;

        public GrantedFolder(String alias, String treeUri, boolean valid) {
            this.alias = alias;
            this.treeUri = treeUri;
            this.valid = valid;
        }
    }

    public static class FileEntry {
        public final String name;
        public final String path;
        public final boolean directory;
        public final long sizeBytes;
        public final long lastModified;

        public FileEntry(String name, String path, boolean directory, long sizeBytes, long lastModified) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }
    }

    private static final int MAX_TEXT_READ_BYTES = 256 * 1024;
    private static final int MAX_SEARCH_RESULTS = 80;

    private final Context context;
    private final PreferencesManager prefs;
    private final SettingsRepository settingsRepository;
    private ShizukuIntegrationManager shizukuManager;
    private final MutableLiveData<List<GrantedFolder>> grantedFoldersLiveData = new MutableLiveData<>(Collections.emptyList());

    public WorkspaceFileManager(
            Context context,
            PreferencesManager prefs,
            SettingsRepository settingsRepository
    ) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.settingsRepository = settingsRepository;
        ensureDefaultWorkspace();
        refreshGrantedFolders();
    }

    public void setShizukuManager(ShizukuIntegrationManager shizukuManager) {
        this.shizukuManager = shizukuManager;
    }

    public File getDefaultWorkspaceDir() {
        File ext = context.getExternalFilesDir("workspaces");
        File root = ext != null ? new File(ext, "default") : new File(context.getFilesDir(), "workspaces/default");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    public String getActiveWorkspacePath() {
        String configured = settingsRepository.getSettingsSnapshot().getActiveWorkspacePath();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return getDefaultWorkspaceDir().getAbsolutePath();
    }

    private void ensureDefaultWorkspace() {
        getDefaultWorkspaceDir();
    }

    public IntegrationStatus getStatus() {
        File ws = getDefaultWorkspaceDir();
        if (ws.exists() && ws.canRead() && ws.canWrite()) {
            return IntegrationStatus.ACTIVE;
        }
        return IntegrationStatus.ERROR;
    }

    public String getStatusSummary() {
        List<GrantedFolder> granted = refreshGrantedFolders();
        String base = "Workspace ready (" + getActiveWorkspacePath() + ")";
        if (!granted.isEmpty()) {
            return base + " • " + granted.size() + " SAF folder(s) granted";
        }
        return base + " • Grant shared folders via SAF";
    }

    public LiveData<List<GrantedFolder>> observeGrantedFolders() {
        return grantedFoldersLiveData;
    }

    public List<GrantedFolder> refreshGrantedFolders() {
        Map<String, String> stored = prefs.getSafGrantedTrees();
        List<GrantedFolder> list = new ArrayList<>();
        for (Map.Entry<String, String> e : stored.entrySet()) {
            boolean ok = hasPersistedPermission(e.getValue());
            list.add(new GrantedFolder(e.getKey(), e.getValue(), ok));
        }
        grantedFoldersLiveData.postValue(Collections.unmodifiableList(list));
        return list;
    }

    public String registerSafTreeUri(Uri treeUri) {
        if (treeUri == null) return "";
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {
            try {
                context.getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored2) {
            }
        }
        String alias = deriveTreeAlias(treeUri);
        Map<String, String> stored = new LinkedHashMap<>(prefs.getSafGrantedTrees());
        stored.put(alias, treeUri.toString());
        prefs.saveSafGrantedTrees(stored);
        refreshGrantedFolders();
        return "saf://" + alias;
    }

    public void revokeSafFolder(String alias) {
        if (alias == null) return;
        Map<String, String> stored = new LinkedHashMap<>(prefs.getSafGrantedTrees());
        String uriStr = stored.remove(alias);
        prefs.saveSafGrantedTrees(stored);
        if (uriStr != null) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(uriStr),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
        }
        if (("saf://" + alias).equals(getActiveWorkspacePath())) {
            settingsRepository.setActiveWorkspacePath("");
        }
        refreshGrantedFolders();
    }

    private boolean hasPersistedPermission(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return false;
        try {
            List<android.content.UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
            for (android.content.UriPermission p : perms) {
                if (p.getUri().toString().equals(uriStr) && p.isReadPermission()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        // Fallback check in case unit test runs without ContentResolver persisted table
        return uriStr.startsWith("content://");
    }

    private String deriveTreeAlias(Uri treeUri) {
        String docId = "";
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception ignored) {
        }
        if (docId == null || docId.isEmpty()) {
            docId = treeUri.getLastPathSegment();
        }
        if (docId == null || docId.isEmpty()) {
            docId = "folder_" + System.currentTimeMillis();
        }
        int colon = docId.indexOf(':');
        if (colon >= 0 && colon + 1 < docId.length()) {
            docId = docId.substring(colon + 1);
        }
        String clean = docId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (clean.isEmpty()) clean = "folder";
        return clean;
    }

    // =========================================================================
    // Unified Path Operations (File & SAF content:// & saf://alias)
    // =========================================================================

    public boolean exists(String rawPath) throws Exception {
        SafRef saf = resolveSafRef(rawPath);
        if (saf != null) {
            return findSafDocumentId(saf) != null;
        }
        File f = resolveRegularFile(rawPath);
        return f.exists();
    }

    public boolean isNonEmptyExistingFile(String rawPath) {
        try {
            SafRef saf = resolveSafRef(rawPath);
            if (saf != null) {
                String docId = findSafDocumentId(saf);
                if (docId == null) return false;
                FileEntry info = statSafDocument(saf.treeUri, docId, rawPath);
                return info != null && !info.directory && info.sizeBytes > 0;
            }
            File f = resolveRegularFile(rawPath);
            return f.exists() && f.isFile() && f.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public List<FileEntry> listDirectory(String rawPath) throws Exception {
        String effective = (rawPath == null || rawPath.trim().isEmpty()) ? getActiveWorkspacePath() : rawPath.trim();
        SafRef saf = resolveSafRef(effective);
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId == null) {
                throw new IllegalArgumentException("SAF directory not found: " + effective);
            }
            return listSafChildren(saf.treeUri, docId, effective);
        }

        File dir = resolveRegularFile(effective);
        if (!dir.exists()) {
            if (canUseShizukuFallback()) {
                return listViaShizuku(dir.getAbsolutePath());
            }
            throw new IllegalArgumentException("Directory does not exist: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Path is not a directory: " + dir.getAbsolutePath());
        }
        File[] children = dir.listFiles();
        if (children == null) {
            if (canUseShizukuFallback()) {
                return listViaShizuku(dir.getAbsolutePath());
            }
            throw new SecurityException("Android scoped storage denied reading directory: " + dir.getAbsolutePath()
                    + ". Grant folder access via Settings → File Access (SAF) or Shizuku.");
        }
        List<FileEntry> entries = new ArrayList<>();
        for (File c : children) {
            entries.add(new FileEntry(c.getName(), c.getAbsolutePath(), c.isDirectory(), c.length(), c.lastModified()));
        }
        entries.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return entries;
    }

    public String readFileText(String rawPath, int startLine, int endLine) throws Exception {
        SafRef saf = resolveSafRef(rawPath);
        String content;
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId == null) throw new IllegalArgumentException("File not found: " + rawPath);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(saf.treeUri, docId);
            try (InputStream is = context.getContentResolver().openInputStream(docUri)) {
                if (is == null) throw new SecurityException("Cannot open SAF URI for reading");
                content = readStreamLimited(is);
            }
        } else {
            File file = resolveRegularFile(rawPath);
            if (!file.exists()) {
                if (canUseShizukuFallback()) {
                    return readViaShizuku(file.getAbsolutePath(), startLine, endLine);
                }
                throw new IllegalArgumentException("File does not exist: " + file.getAbsolutePath());
            }
            if (file.isDirectory()) {
                throw new IllegalArgumentException("Path is a directory, not a file: " + file.getAbsolutePath());
            }
            if (!file.canRead()) {
                if (canUseShizukuFallback()) {
                    return readViaShizuku(file.getAbsolutePath(), startLine, endLine);
                }
                throw new SecurityException("Permission denied reading file: " + file.getAbsolutePath());
            }
            try (InputStream is = new FileInputStream(file)) {
                content = readStreamLimited(is);
            }
        }
        return sliceLines(content, startLine, endLine);
    }

    public String writeFileText(String rawPath, String content, boolean append) throws Exception {
        String data = content != null ? content : "";
        SafRef saf = resolveSafRef(rawPath);
        if (saf != null) {
            Uri docUri = ensureSafFileUri(saf);
            String mode = append ? "wa" : "wt";
            try (OutputStream os = context.getContentResolver().openOutputStream(docUri, mode)) {
                if (os == null) throw new SecurityException("Cannot open SAF URI for writing");
                os.write(data.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return rawPath;
        }

        File file = resolveRegularFile(rawPath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            fos.write(data.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (SecurityException | java.io.IOException e) {
            if (canUseShizukuFallback()) {
                writeViaShizuku(file.getAbsolutePath(), data, append);
                return file.getAbsolutePath();
            }
            throw e;
        }
        return file.getAbsolutePath();
    }

    public String editFileReplace(String rawPath, String oldText, String newText, boolean replaceAll) throws Exception {
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("old_text cannot be empty");
        }
        String current = readFileText(rawPath, -1, -1);
        int idx = current.indexOf(oldText);
        if (idx < 0) {
            throw new IllegalArgumentException("Target snippet old_text was not found in " + rawPath);
        }
        String updated;
        int replacements = 0;
        if (replaceAll) {
            updated = current.replace(oldText, newText != null ? newText : "");
            int scan = 0;
            while ((scan = current.indexOf(oldText, scan)) >= 0) {
                replacements++;
                scan += oldText.length();
            }
        } else {
            updated = current.substring(0, idx)
                    + (newText != null ? newText : "")
                    + current.substring(idx + oldText.length());
            replacements = 1;
        }
        writeFileText(rawPath, updated, false);
        return "Edited " + rawPath + " (" + replacements + " replacement(s), new length " + updated.length() + " chars).";
    }

    public String createDirectory(String rawPath) throws Exception {
        SafRef saf = resolveSafRef(rawPath);
        if (saf != null) {
            Uri dirUri = ensureSafDirUri(saf);
            return dirUri != null ? rawPath : "";
        }
        File dir = resolveRegularFile(rawPath);
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IllegalStateException("A file already exists at: " + dir.getAbsolutePath());
            return dir.getAbsolutePath();
        }
        if (!dir.mkdirs()) {
            if (canUseShizukuFallback()) {
                shizukuManager.executeShell("mkdir -p " + quoteShell(dir.getAbsolutePath()), null, 15);
                return dir.getAbsolutePath();
            }
            throw new SecurityException("Could not create directory: " + dir.getAbsolutePath());
        }
        return dir.getAbsolutePath();
    }

    public String deletePath(String rawPath, boolean recursive) throws Exception {
        SafRef saf = resolveSafRef(rawPath);
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId == null) throw new IllegalArgumentException("Path does not exist: " + rawPath);
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(saf.treeUri, docId);
            boolean ok = DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            if (!ok) throw new IllegalStateException("Failed to delete SAF item: " + rawPath);
            return "Deleted " + rawPath;
        }
        File target = resolveRegularFile(rawPath);
        if (!target.exists()) throw new IllegalArgumentException("Path does not exist: " + target.getAbsolutePath());
        if (isCriticalSystemPath(target.getAbsolutePath())) {
            throw new SecurityException("Refusing to delete protected system path: " + target.getAbsolutePath());
        }
        boolean ok = deleteRecursive(target, recursive);
        if (!ok) throw new SecurityException("Failed to delete: " + target.getAbsolutePath());
        return "Deleted " + target.getAbsolutePath();
    }

    public String copyPath(String srcPath, String destPath) throws Exception {
        File src = resolveSafRef(srcPath) == null ? resolveRegularFile(srcPath) : null;
        File dst = resolveSafRef(destPath) == null ? resolveRegularFile(destPath) : null;
        if (src != null && dst != null) {
            if (!src.exists()) throw new IllegalArgumentException("Source missing: " + src.getAbsolutePath());
            if (src.isDirectory()) {
                copyDirectoryRecursive(src, dst);
            } else {
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                    pipeStreams(in, out);
                }
            }
            return "Copied " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath();
        }
        // Fallback across SAF <-> regular file
        String text = readFileText(srcPath, -1, -1);
        writeFileText(destPath, text, false);
        return "Copied " + srcPath + " -> " + destPath;
    }

    public String movePath(String srcPath, String destPath) throws Exception {
        if (resolveSafRef(srcPath) == null && resolveSafRef(destPath) == null) {
            File src = resolveRegularFile(srcPath);
            File dst = resolveRegularFile(destPath);
            if (!src.exists()) throw new IllegalArgumentException("Source missing: " + src.getAbsolutePath());
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            if (src.renameTo(dst)) {
                return "Moved " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath();
            }
            copyPath(srcPath, destPath);
            deleteRecursive(src, true);
            return "Moved " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath();
        }
        copyPath(srcPath, destPath);
        deletePath(srcPath, true);
        return "Moved " + srcPath + " -> " + destPath;
    }

    public String renamePath(String srcPath, String newName) throws Exception {
        if (newName == null || newName.trim().isEmpty() || newName.contains("/") || newName.contains("\\")) {
            throw new IllegalArgumentException("Invalid new filename: " + newName);
        }
        SafRef saf = resolveSafRef(srcPath);
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId == null) throw new IllegalArgumentException("Path missing: " + srcPath);
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(saf.treeUri, docId);
            Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), uri, newName.trim());
            if (renamed == null) throw new IllegalStateException("SAF rename failed");
            return "Renamed to " + newName.trim();
        }
        File src = resolveRegularFile(srcPath);
        if (!src.exists()) throw new IllegalArgumentException("Path missing: " + src.getAbsolutePath());
        File dst = new File(src.getParentFile(), newName.trim());
        if (!src.renameTo(dst)) throw new IllegalStateException("Rename failed");
        return "Renamed " + src.getName() + " -> " + dst.getAbsolutePath();
    }

    public FileEntry getFileInfo(String rawPath) throws Exception {
        SafRef saf = resolveSafRef(rawPath);
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId == null) throw new IllegalArgumentException("Path missing: " + rawPath);
            return statSafDocument(saf.treeUri, docId, rawPath);
        }
        File file = resolveRegularFile(rawPath);
        if (!file.exists()) throw new IllegalArgumentException("Path does not exist: " + file.getAbsolutePath());
        return new FileEntry(file.getName(), file.getAbsolutePath(), file.isDirectory(), file.length(), file.lastModified());
    }

    public List<FileEntry> searchFiles(String rootPath, String namePattern) throws Exception {
        String effective = (rootPath == null || rootPath.trim().isEmpty()) ? getActiveWorkspacePath() : rootPath.trim();
        String needle = namePattern != null ? namePattern.trim().toLowerCase(Locale.ROOT) : "";
        List<FileEntry> results = new ArrayList<>();
        SafRef saf = resolveSafRef(effective);
        if (saf != null) {
            String docId = findSafDocumentId(saf);
            if (docId != null) {
                searchSafRecursive(saf.treeUri, docId, effective, needle, results, 0);
            }
            return results;
        }
        File dir = resolveRegularFile(effective);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Search root is not a directory: " + dir.getAbsolutePath());
        }
        searchFileRecursive(dir, needle, results, 0);
        return results;
    }

    public List<String> grepContent(String rootPath, String query, boolean regex) throws Exception {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query cannot be empty");
        }
        String effective = (rootPath == null || rootPath.trim().isEmpty()) ? getActiveWorkspacePath() : rootPath.trim();
        Pattern pattern = regex
                ? Pattern.compile(query, Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        List<String> matches = new ArrayList<>();

        List<FileEntry> candidates = searchFiles(effective, "");
        for (FileEntry entry : candidates) {
            if (entry.directory || entry.sizeBytes > MAX_TEXT_READ_BYTES) continue;
            try {
                String content = readFileText(entry.path, -1, -1);
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = pattern.matcher(lines[i]);
                    if (m.find()) {
                        String linePreview = lines[i].length() > 180 ? lines[i].substring(0, 180) + "..." : lines[i];
                        matches.add(entry.path + ":" + (i + 1) + ": " + linePreview);
                        if (matches.size() >= MAX_SEARCH_RESULTS) {
                            return matches;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return matches;
    }

    // =========================================================================
    // Resolution Helpers
    // =========================================================================

    public File resolveRegularFile(String rawPath) {
        String p = rawPath != null ? rawPath.trim() : "";
        if (p.isEmpty() || ".".equals(p)) {
            return new File(getActiveWorkspacePath());
        }
        File f = new File(p);
        if (f.isAbsolute()) {
            return f;
        }
        String ws = getActiveWorkspacePath();
        if (ws.startsWith("saf://") || ws.startsWith("content://")) {
            return new File(getDefaultWorkspaceDir(), p);
        }
        return new File(ws, p);
    }

    private static class SafRef {
        final Uri treeUri;
        final List<String> segments;

        SafRef(Uri treeUri, List<String> segments) {
            this.treeUri = treeUri;
            this.segments = segments;
        }
    }

    private SafRef resolveSafRef(String rawPath) {
        String p = rawPath != null ? rawPath.trim() : "";
        if (p.isEmpty()) {
            p = getActiveWorkspacePath();
        }
        Map<String, String> trees = prefs.getSafGrantedTrees();
        if (p.startsWith("saf://")) {
            String rest = p.substring("saf://".length());
            int slash = rest.indexOf('/');
            String alias = slash >= 0 ? rest.substring(0, slash) : rest;
            String tail = slash >= 0 ? rest.substring(slash + 1) : "";
            String uriStr = trees.get(alias);
            if (uriStr != null) {
                return new SafRef(Uri.parse(uriStr), splitCleanSegments(tail));
            }
        } else if (p.startsWith("content://")) {
            return new SafRef(Uri.parse(p), Collections.emptyList());
        } else if (!new File(p).isAbsolute()) {
            String ws = getActiveWorkspacePath();
            if (ws.startsWith("saf://")) {
                return resolveSafRef(ws + (ws.endsWith("/") ? "" : "/") + p);
            }
        }
        return null;
    }

    private List<String> splitCleanSegments(String rel) {
        List<String> list = new ArrayList<>();
        if (rel == null || rel.isEmpty()) return list;
        for (String seg : rel.split("/")) {
            if (!seg.isEmpty() && !".".equals(seg) && !"..".equals(seg)) {
                list.add(seg);
            }
        }
        return list;
    }

    private String findSafDocumentId(SafRef ref) {
        String docId = DocumentsContract.getTreeDocumentId(ref.treeUri);
        for (String seg : ref.segments) {
            docId = findChildDocIdByName(ref.treeUri, docId, seg);
            if (docId == null) return null;
        }
        return docId;
    }

    private String findChildDocIdByName(Uri treeUri, String parentDocId, String name) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = context.getContentResolver().query(childrenUri, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String dn = c.getString(1);
                    if (name.equals(dn)) {
                        return c.getString(0);
                    }
                }
            }
        }
        return null;
    }

    private List<FileEntry> listSafChildren(Uri treeUri, String parentDocId, String baseDisplayPath) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        List<FileEntry> entries = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(childrenUri, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.getLong(3);
                    long mod = c.getLong(4);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    String childPath = (baseDisplayPath.endsWith("/") ? baseDisplayPath : baseDisplayPath + "/") + name;
                    entries.add(new FileEntry(name, childPath, isDir, size, mod));
                }
            }
        }
        return entries;
    }

    private FileEntry statSafDocument(Uri treeUri, String docId, String displayPath) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor c = context.getContentResolver().query(docUri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                String mime = c.getString(1);
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                return new FileEntry(name, displayPath, isDir, c.getLong(2), c.getLong(3));
            }
        }
        return new FileEntry(displayPath, displayPath, false, 0L, 0L);
    }

    private Uri ensureSafDirUri(SafRef ref) throws Exception {
        ContentResolver cr = context.getContentResolver();
        String docId = DocumentsContract.getTreeDocumentId(ref.treeUri);
        for (String seg : ref.segments) {
            String nextId = findChildDocIdByName(ref.treeUri, docId, seg);
            if (nextId == null) {
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, docId);
                Uri created = DocumentsContract.createDocument(cr, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, seg);
                if (created == null) throw new IllegalStateException("Cannot create SAF folder: " + seg);
                nextId = DocumentsContract.getDocumentId(created);
            }
            docId = nextId;
        }
        return DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, docId);
    }

    private Uri ensureSafFileUri(SafRef ref) throws Exception {
        if (ref.segments.isEmpty()) throw new IllegalArgumentException("Missing filename in SAF path");
        List<String> parentSegs = ref.segments.subList(0, ref.segments.size() - 1);
        String fileName = ref.segments.get(ref.segments.size() - 1);
        Uri parentUri = ensureSafDirUri(new SafRef(ref.treeUri, parentSegs));
        String parentDocId = DocumentsContract.getDocumentId(parentUri);
        String existingId = findChildDocIdByName(ref.treeUri, parentDocId, fileName);
        if (existingId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, existingId);
        }
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parentUri, "text/plain", fileName);
        if (created == null) throw new IllegalStateException("Could not create SAF file: " + fileName);
        return created;
    }

    private void searchSafRecursive(Uri treeUri, String docId, String path, String needle, List<FileEntry> out, int depth) {
        if (depth > 5 || out.size() >= MAX_SEARCH_RESULTS) return;
        for (FileEntry e : listSafChildren(treeUri, docId, path)) {
            if (needle.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(e);
                if (out.size() >= MAX_SEARCH_RESULTS) return;
            }
            if (e.directory) {
                String childId = findChildDocIdByName(treeUri, docId, e.name);
                if (childId != null) {
                    searchSafRecursive(treeUri, childId, e.path, needle, out, depth + 1);
                }
            }
        }
    }

    private void searchFileRecursive(File dir, String needle, List<FileEntry> out, int depth) {
        if (depth > 7 || out.size() >= MAX_SEARCH_RESULTS) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String n = c.getName();
            if (".git".equals(n) || "build".equals(n) || "node_modules".equals(n) || ".gradle".equals(n)) continue;
            if (needle.isEmpty() || n.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(new FileEntry(n, c.getAbsolutePath(), c.isDirectory(), c.length(), c.lastModified()));
                if (out.size() >= MAX_SEARCH_RESULTS) return;
            }
            if (c.isDirectory()) {
                searchFileRecursive(c, needle, out, depth + 1);
            }
        }
    }

    private boolean canUseShizukuFallback() {
        return settingsRepository.getSettingsSnapshot().isAllowShizukuFileFallback()
                && shizukuManager != null
                && shizukuManager.isReady();
    }

    private List<FileEntry> listViaShizuku(String absPath) throws Exception {
        ShizukuIntegrationManager.ShellResult res = shizukuManager.executeShell("ls -la " + quoteShell(absPath), null, 15);
        if (res.exitCode != 0) {
            throw new SecurityException("Shizuku ls failed: " + res.stderr);
        }
        List<FileEntry> entries = new ArrayList<>();
        for (String line : res.stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue;
            String[] parts = trimmed.split("\\s+", 8);
            if (parts.length >= 8) {
                String name = parts[7];
                if (".".equals(name) || "..".equals(name)) continue;
                boolean isDir = parts[0].startsWith("d");
                long size = 0L;
                try { size = Long.parseLong(parts[4]); } catch (Exception ignored) {}
                entries.add(new FileEntry(name, absPath + "/" + name, isDir, size, 0L));
            }
        }
        return entries;
    }

    private String readViaShizuku(String absPath, int startLine, int endLine) throws Exception {
        ShizukuIntegrationManager.ShellResult res = shizukuManager.executeShell("cat " + quoteShell(absPath), null, 15);
        if (res.exitCode != 0) throw new SecurityException("Shizuku read failed: " + res.stderr);
        return sliceLines(res.stdout, startLine, endLine);
    }

    private void writeViaShizuku(String absPath, String data, boolean append) throws Exception {
        String op = append ? ">>" : ">";
        String b64 = android.util.Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        String cmd = "printf %s " + quoteShell(b64) + " | base64 -d " + op + " " + quoteShell(absPath);
        ShizukuIntegrationManager.ShellResult res = shizukuManager.executeShell(cmd, null, 15);
        if (res.exitCode != 0) throw new SecurityException("Shizuku write failed: " + res.stderr);
    }

    private String readStreamLimited(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int read;
        while ((read = is.read(buf)) != -1) {
            int toWrite = Math.min(read, MAX_TEXT_READ_BYTES - total);
            bos.write(buf, 0, toWrite);
            total += toWrite;
            if (total >= MAX_TEXT_READ_BYTES) break;
        }
        String text = bos.toString(StandardCharsets.UTF_8.name());
        if (total >= MAX_TEXT_READ_BYTES) {
            text += "\n\n...[truncated at 256 KB]...";
        }
        return text;
    }

    private String sliceLines(String content, int startLine, int endLine) {
        if (startLine <= 0 && endLine <= 0) return content;
        String[] lines = content.split("\n", -1);
        int from = startLine > 0 ? Math.max(1, startLine) : 1;
        int to = endLine > 0 ? Math.min(lines.length, endLine) : lines.length;
        if (from > to || from > lines.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from - 1; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]);
            if (i + 1 < to) sb.append("\n");
        }
        return sb.toString();
    }

    private boolean deleteRecursive(File target, boolean recursive) {
        if (target.isDirectory()) {
            File[] kids = target.listFiles();
            if (kids != null && kids.length > 0 && !recursive) {
                return false;
            }
            if (kids != null) {
                for (File k : kids) {
                    if (!deleteRecursive(k, true)) return false;
                }
            }
        }
        return target.delete();
    }

    private void copyDirectoryRecursive(File src, File dst) throws Exception {
        if (!dst.exists() && !dst.mkdirs()) throw new IllegalStateException("Cannot create target dir: " + dst);
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File next = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyDirectoryRecursive(k, next);
            } else {
                try (InputStream in = new FileInputStream(k); OutputStream out = new FileOutputStream(next)) {
                    pipeStreams(in, out);
                }
            }
        }
    }

    private void pipeStreams(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static boolean isCriticalSystemPath(String absPath) {
        if (absPath == null) return true;
        String clean = absPath.trim();
        if ("/".equals(clean) || "/system".equals(clean) || "/data".equals(clean) || "/vendor".equals(clean)) {
            return true;
        }
        String extRoot = Environment.getExternalStorageDirectory() != null
                ? Environment.getExternalStorageDirectory().getAbsolutePath() : "/storage/emulated/0";
        return extRoot.equals(clean);
    }

    private static String quoteShell(String s) {
        return "'" + (s != null ? s.replace("'", "'\"'\"'") : "") + "'";
    }
}
