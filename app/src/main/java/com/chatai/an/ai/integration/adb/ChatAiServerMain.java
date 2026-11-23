package com.chatai.an.ai.integration.adb;

import android.os.Process;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Privileged ChatAI ADB Server process spawned via app_process under ADB shell (UID 2000).
 * Listens exclusively on 127.0.0.1 and authenticates every request with a per-launch random token.
 */
public final class ChatAiServerMain {

    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private static volatile boolean running = true;

    private ChatAiServerMain() {}

    public static void main(String[] args) {
        int port = 39527;
        String token = "";
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--port=")) {
                    try {
                        port = Integer.parseInt(arg.substring("--port=".length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (arg != null && arg.startsWith("--token=")) {
                    token = arg.substring("--token=".length()).trim();
                }
            }
        }

        if (token.isEmpty()) {
            System.err.println("Error: missing --token");
            System.exit(2);
            return;
        }

        final String expectedToken = token;
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket server = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("Info: chatai_server listening on 127.0.0.1:" + port + " uid=" + Process.myUid());
            System.out.flush();

            while (running) {
                Socket client = server.accept();
                pool.execute(() -> handleClient(client, expectedToken, server));
            }
        } catch (Throwable t) {
            System.err.println("chatai_server fatal error: " + t.getMessage());
            System.exit(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void handleClient(Socket client, String expectedToken, ServerSocket server) {
        try (Socket sock = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {

            sock.setSoTimeout(120_000);
            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.split(" ", 5);
            if (parts.length < 2 || !constantTimeEquals(expectedToken, parts[0])) {
                writer.println("ERR unauthorized");
                return;
            }

            String op = parts[1];
            if ("PING".equals(op)) {
                writer.println("PONG " + Process.myUid() + " " + Process.myPid());
            } else if ("STOP".equals(op)) {
                writer.println("BYE");
                running = false;
                try {
                    server.close();
                } catch (Exception ignored) {
                }
                System.exit(0);
            } else if ("EXEC".equals(op) && parts.length >= 5) {
                int timeoutSec = 25;
                try {
                    timeoutSec = Math.max(3, Math.min(120, Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                }
                String workdir = decodeB64(parts[3]);
                String command = decodeB64(parts[4]);
                String jsonResp = runShell(command, workdir, timeoutSec);
                writer.println(jsonResp);
            } else {
                writer.println("ERR bad_request");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String runShell(String command, String workdir, int timeoutSec) {
        if (command == null || command.trim().isEmpty()) {
            return formatJson(-1, Process.myUid(), false, "", "Empty command");
        }
        java.lang.Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            if (workdir != null && !workdir.trim().isEmpty()) {
                File dir = new File(workdir.trim());
                if (dir.exists() && dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            proc = pb.start();
            final java.lang.Process p = proc;
            final ByteArrayOutputStream outBos = new ByteArrayOutputStream();
            final ByteArrayOutputStream errBos = new ByteArrayOutputStream();

            Thread tOut = new Thread(() -> copyLimited(p.getInputStream(), outBos));
            Thread tErr = new Thread(() -> copyLimited(p.getErrorStream(), errBos));
            tOut.start();
            tErr.start();

            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                return formatJson(
                        -1,
                        Process.myUid(),
                        true,
                        outBos.toString(StandardCharsets.UTF_8.name()),
                        errBos.toString(StandardCharsets.UTF_8.name()) + "\n[Timed out after " + timeoutSec + "s]"
                );
            }
            tOut.join(1000);
            tErr.join(1000);
            return formatJson(
                    proc.exitValue(),
                    Process.myUid(),
                    false,
                    outBos.toString(StandardCharsets.UTF_8.name()),
                    errBos.toString(StandardCharsets.UTF_8.name())
            );
        } catch (Throwable t) {
            if (proc != null) {
                try {
                    proc.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            return formatJson(-1, Process.myUid(), false, "", t.getMessage() != null ? t.getMessage() : "Shell error");
        }
    }

    private static void copyLimited(InputStream is, ByteArrayOutputStream dst) {
        try (InputStream in = is) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                int take = Math.min(n, MAX_OUTPUT_BYTES - total);
                if (take > 0) {
                    dst.write(buf, 0, take);
                    total += take;
                }
                if (total >= MAX_OUTPUT_BYTES) break;
            }
        } catch (Throwable ignored) {
        }
    }

    private static String formatJson(int exitCode, int uid, boolean timedOut, String stdout, String stderr) {
        String outB64 = Base64.getEncoder().encodeToString((stdout != null ? stdout : "").getBytes(StandardCharsets.UTF_8));
        String errB64 = Base64.getEncoder().encodeToString((stderr != null ? stderr : "").getBytes(StandardCharsets.UTF_8));
        return "{\"exitCode\":" + exitCode
                + ",\"uid\":" + uid
                + ",\"timedOut\":" + timedOut
                + ",\"stdout\":\"" + outB64
                + "\",\"stderr\":\"" + errB64 + "\"}";
    }

    private static String decodeB64(String b64) {
        if (b64 == null || b64.isEmpty() || "-".equals(b64)) return "";
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
