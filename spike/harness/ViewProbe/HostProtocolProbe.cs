using System.Diagnostics;
using System.Text;

internal static class HostProtocolProbe
{
    public static void Run(string hostPath)
    {
        var info = new ProcessStartInfo(hostPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        foreach (var argument in new[] { "--headless", "--mush-scene", "res://scenes/sample_mixed.tscn" })
            info.ArgumentList.Add(argument);
        using var host = Process.Start(info) ?? throw new InvalidOperationException("Host did not start.");
        var errors = host.StandardError.ReadToEndAsync();
        try
        {
            Expect("mush:view 1 1 3d", beforeLoad: true);
            ReadReply("mush:loaded");
            host.StandardInput.WriteLine("view 0 1 2d");
            Expect("mush:view 0 1 2d");
            host.StandardInput.WriteLine("view 1 0 3d");
            Expect("mush:view 1 0 3d");
            host.StandardInput.WriteLine("load res://scenes/sample_mixed.tscn");
            Expect("mush:view 1 0 3d", beforeLoad: true);
            ReadReply("mush:loaded");
            host.StandardInput.WriteLine("quit");
            if (!host.WaitForExit(5000) || host.ExitCode != 0)
                throw new InvalidOperationException("Host did not quit successfully.");
            var stderr = errors.GetAwaiter().GetResult();
            if (stderr.Contains("ERROR:") || stderr.Contains("WARNING:"))
                throw new InvalidOperationException(stderr);
            Console.WriteLine("PASS: real host view handshake, commands, reload preservation and graceful quit.");
        }
        finally
        {
            if (!host.HasExited) host.Kill();
        }

        RunDocumentCases(hostPath);

        void Expect(string expected, bool beforeLoad = false)
        {
            var actual = ReadReply("mush:view", beforeLoad);
            if (actual != expected)
                throw new InvalidOperationException($"Expected '{expected}', received '{actual}'.");
        }

        string ReadReply(string prefix, bool beforeLoad = false)
        {
            while (true)
            {
                var read = host.StandardOutput.ReadLineAsync();
                if (!read.Wait(TimeSpan.FromSeconds(10)))
                    throw new TimeoutException($"Host did not reply with {prefix}.");
                var line = read.GetAwaiter().GetResult()
                    ?? throw new InvalidOperationException($"Host output closed before {prefix}.");
                if (line.StartsWith("mush:error")) throw new InvalidOperationException(line);
                if (beforeLoad && line.StartsWith("mush:loaded"))
                    throw new InvalidOperationException("Scene loaded without the view handshake; host and toolbar are incompatible.");
                if (line.StartsWith(prefix)) return line;
            }
        }
    }

    // What a person typing can do to the host. These print Godot errors on purpose, so they stay out of the strict check.
    private static void RunDocumentCases(string hostPath)
    {
        const string scene = "res://scenes/probe.tscn";
        const string good = "[gd_scene format=3]\n\n[node name=\"Root\" type=\"Node2D\"]\n\n[node name=\"Child\" type=\"Node2D\" parent=\".\"]\n";

        using (var host = new DocumentHost(hostPath))
        {
            Require(host.Push(scene, good).StartsWith("mush:loaded"), "a good document loads");
            Require(host.Ask("count", "mush:count") == "mush:count 2", "the good document's nodes are shown");

            Require(host.Push(scene, "[node name=").StartsWith("mush:error"), "a syntax error is reported");
            Require(host.Ask("count", "mush:count") == "mush:count 2", "a syntax error keeps the last good scene");

            // The base64 field cannot be empty, so the plugin sends an empty document as one newline.
            Require(host.Push(scene, "\n").StartsWith("mush:error cannot parse"), "an empty document is a parse error, not a bad command");
            Require(host.Ask("count", "mush:count") == "mush:count 2", "an empty document keeps the last good scene");

            var withSpace = "res://scenes/a scene with spaces.tscn";
            Require(host.Push(withSpace, good).StartsWith("mush:loaded"), "unsaved text loads for a path with spaces");
            host.Send($"load {withSpace}");
            Require(host.ReadLoad() == $"mush:error cannot load {withSpace}", "a disk load keeps the whole path");

            host.Send($"load   {withSpace}  ");
            Require(host.ReadLoad() == $"mush:error cannot load {withSpace}", "spacing around a path is not part of it");
            var doubleSpaced = "res://scenes/two  spaces.tscn";
            host.Send($"load {doubleSpaced}");
            Require(host.ReadLoad() == $"mush:error cannot load {doubleSpaced}", "spacing inside a path is part of it");
            host.Send($"text   {DocumentHost.Encoded(good)}   {scene}  ");
            Require(host.ReadLoad().StartsWith("mush:loaded"), "spacing between text fields is free");

            Require(host.Push(scene, InlineScript("queue_free()")).StartsWith("mush:loaded"), "a self-freeing scene loads");
            host.Ask("ping", "mush:pong"); // Let the deferred free run.
            Require(host.Push(scene, good).StartsWith("mush:loaded"), "a good document loads after the scene freed itself");
            Require(host.Ask("count", "mush:count") == "mush:count 2", "the replacement scene is shown");

            Require(host.Push(scene, InlineScript("get_tree().change_scene_to_file.call_deferred(\\\"res://main.tscn\\\")")).StartsWith("mush:loaded"),
                "a scene-switching scene loads");
            Thread.Sleep(300); // Godot swaps scenes over its next frames, and the replacement must arrive first.
            Require(host.Push(scene, good).StartsWith("mush:loaded"), "a good document loads after the scene switched");
            Require(host.Ask("count", "mush:count") == "mush:count 2", "nothing from the switched-to scene stays behind");

            host.Send("visible 0");
            host.Send("select Child");
            host.Send("select Missing/Node");
            host.Send("select");
            host.Send("reset");
            host.Send("visible 1");
            host.Ask("ping", "mush:pong");
        }

        // Split editors run one host each and push the same edit at the same moment.
        using (var first = new DocumentHost(hostPath))
        using (var second = new DocumentHost(hostPath))
        {
            for (var i = 0; i < 40; i++)
            {
                var edit = good + $"\n[node name=\"Edit{i}\" type=\"Node2D\" parent=\".\"]\n";
                first.SendText(scene, edit);
                second.SendText(scene, edit);
                var replies = new[] { first.ReadLoad(), second.ReadLoad() };
                Require(replies.All(r => r.StartsWith("mush:loaded")), $"two hosts can load the same scene at once: {string.Join("; ", replies)}");
            }
        }
        Console.WriteLine("PASS: broken documents, paths with spaces, scenes that free or replace themselves, and two hosts on one scene.");
    }

    private static string InlineScript(string body) =>
        "[gd_scene format=3]\n\n[sub_resource type=\"GDScript\" id=\"1\"]\n" +
        $"script/source = \"extends Node2D\nfunc _ready():\n\t{body}\n\"\n\n" +
        "[node name=\"Root\" type=\"Node2D\"]\nscript = SubResource(\"1\")\n";

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class DocumentHost : IDisposable
    {
        private readonly Process _process;

        public DocumentHost(string hostPath)
        {
            var info = new ProcessStartInfo(hostPath)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            info.ArgumentList.Add("--headless");
            _process = Process.Start(info) ?? throw new InvalidOperationException("Host did not start.");
            _ = _process.StandardError.ReadToEndAsync();
        }

        public void Send(string command) => _process.StandardInput.WriteLine(command);

        public static string Encoded(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

        public void SendText(string path, string text) => Send($"text {Encoded(text)} {path}");

        public string Push(string path, string text)
        {
            SendText(path, text);
            return ReadLoad();
        }

        public string ReadLoad() => Read(line => line.StartsWith("mush:loaded") || line.StartsWith("mush:error"));

        // Fails on any error since the last load, including from commands sent without asking for a reply.
        public string Ask(string command, string replyPrefix)
        {
            Send(command);
            return Read(line => line.StartsWith("mush:error")
                ? throw new InvalidOperationException($"Host reported an error before {command} replied: {line}")
                : line.StartsWith(replyPrefix));
        }

        private string Read(Func<string, bool> wanted)
        {
            while (true)
            {
                var read = _process.StandardOutput.ReadLineAsync();
                if (!read.Wait(TimeSpan.FromSeconds(10)))
                    throw new TimeoutException("Host stopped replying.");
                var line = read.GetAwaiter().GetResult() ?? throw new InvalidOperationException("Host output closed.");
                if (wanted(line)) return line;
            }
        }

        public void Dispose()
        {
            if (!_process.HasExited) _process.Kill();
            _process.WaitForExit(5000);
            _process.Dispose();
        }
    }
}
