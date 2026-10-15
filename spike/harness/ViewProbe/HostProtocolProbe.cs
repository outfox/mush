using System.Diagnostics;

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
}
