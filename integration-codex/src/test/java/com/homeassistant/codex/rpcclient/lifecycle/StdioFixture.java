package com.homeassistant.codex.rpcclient.lifecycle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** A disposable child process; never starts Codex or any managed runtime. */
public final class StdioFixture {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        if (args[0].equals("stall")) {
            System.out.println("ready");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            if (line.equals("eof")) {
                System.out.close();
                Thread.sleep(60_000);
                return;
            }
            System.out.println(line);
            System.out.flush();
        }
    }
}
