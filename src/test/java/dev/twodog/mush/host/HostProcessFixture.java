package dev.twodog.mush.host;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A real child process that can refuse all stdin without loading the IDE or Godot. */
public final class HostProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("malformed")) System.out.println("mush:hwnd invalid");
        if (args[0].equals("godot-output")) {
            System.out.println("ERROR: plain");
            System.out.println("   at: ResourceLoader::_load (.\\core/io/resource_loader.cpp:325)");
            System.out.println("WARNING: not a problem");
            System.out.println("SCRIPT ERROR: from a script");
            System.out.println("a line that mentions ERROR: in passing");
        }
        System.out.println("mush:hwnd 12345");
        System.out.flush();
        if (args[0].equals("closed-output")) {
            // The launcher merges stderr into stdout. On POSIX these are separate file descriptors; both must
            // close before the reader sees EOF, even though they write to the same pipe.
            System.out.close();
            System.err.close();
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
