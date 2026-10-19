package com.homeassistant.llm.codex.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** A disposable line-oriented child process; never starts Codex or any managed runtime. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            if (line.equals("exit")) return;
            System.out.println(line);
            System.out.flush();
        }
    }
}
