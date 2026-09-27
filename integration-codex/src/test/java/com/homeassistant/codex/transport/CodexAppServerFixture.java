package com.homeassistant.codex.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** A disposable JSON-line process; never starts Codex or any managed runtime. */
public final class CodexAppServerFixture {
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern THREAD_ID = Pattern.compile("\"threadId\":\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        var mode = args[0];
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            if (line.contains("\"method\":\"initialize\"")) {
                respond(line, "{}");
                continue;
            }
            if (line.contains("\"method\":\"initialized\"")) {
                if (mode.equals("server-request")) {
                    print("{\"id\":\"server-request-1\",\"method\":\"unsupported/test\",\"params\":{}}");
                } else if (mode.equals("stall")) {
                    Thread.sleep(60_000);
                }
                continue;
            }
            if (line.contains("\"method\":\"thread/start\"")) {
                if (mode.equals("exit-on-thread-start")) return;
                respond(line, "{\"thread\":{\"id\":\"00000000-0000-0000-0000-000000000001\"}}");
                continue;
            }
            if (line.contains("\"method\":\"thread/resume\"")) {
                var threadId = value(THREAD_ID, line);
                respond(line, "{\"thread\":{\"id\":\"" + threadId + "\"}}");
                continue;
            }
            if (line.contains("\"method\":\"turn/start\"")) {
                var threadId = value(THREAD_ID, line);
                respond(line, "{\"turn\":{\"id\":\"turn-1\"}}");
                if (mode.equals("malformed-notification")) {
                    print("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + threadId
                        + "\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"agentMessage\",\"text\":\"answer\"}}}");
                } else {
                    print("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + threadId
                        + "\",\"turnId\":\"turn-1\",\"item\":{\"type\":\"agentMessage\",\"text\":\"answer\"},\"completedAtMs\":1}}");
                    print("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId
                        + "\",\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\",\"items\":[],\"error\":null}}}");
                }
                continue;
            }
            if (line.contains("\"method\":\"thread/unsubscribe\"")
                || line.contains("\"method\":\"turn/interrupt\"")) {
                respond(line, "{}");
                continue;
            }
            if (mode.equals("server-request") && line.contains("\"code\":-32601")) {
                print("{\"method\":\"thread/closed\",\"params\":{\"threadId\":\"00000000-0000-0000-0000-000000000001\"}}");
            }
        }
    }

    private static void respond(String request, String result) {
        print("{\"id\":" + value(ID, request) + ",\"result\":" + result + "}");
    }

    private static String value(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        if (!matcher.find()) throw new IllegalArgumentException("missing fixture value");
        return matcher.group(1);
    }

    private static void print(String message) {
        System.out.println(message);
        System.out.flush();
    }
}
