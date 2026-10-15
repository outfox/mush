package dev.twodog.mush.host;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A real child process that can refuse all stdin without loading the IDE or Godot. */
public final class HostProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("malformed")) System.out.println("mush:hwnd invalid");
        System.out.println("mush:hwnd 12345");
        System.out.flush();
        if (args[0].equals("closed-output")) {
            System.out.close();
            Thread.sleep(60_000);
            return;
        }
        if (args[0].equals("non-reading")) {
            Thread.sleep(60_000);
            return;
        }
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("quit")) return;
            if (line.equals("exit")) System.exit(7);
            System.out.println("mush:reply " + line);
            System.out.flush();
        }
    }
}
