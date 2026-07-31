using System.Diagnostics;
using System.Net.Http;

namespace Wyrdsekai.Tray;

/// <summary>
/// Locates the install + data dirs and drives the local node through the bundled
/// <c>wyrd.cmd</c> (→ wyrd.ps1). The shell never reimplements lifecycle/config — it
/// shells out to the same CLI verbs the Linux/mac bootstrap uses.
/// </summary>
internal static class NodeController
{
    public const string BaseUrl = "http://localhost:7070";
    public static string AppUrl => BaseUrl + "/app";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(4) };

    /// <summary>Directory the tray exe runs from — wyrd.cmd is staged beside it (jpackage app\).</summary>
    public static string InstallDir => AppContext.BaseDirectory;

    public static string DataDir =>
        Environment.GetEnvironmentVariable("WYRDSEKAI_DATA_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".wyrdsekai");

    public static string EnvFile => Path.Combine(DataDir, "env");
    public static string ConfFile => Path.Combine(DataDir, "wyrdsekai.conf");

    /// <summary>The bundled Windows CLI shim (wyrd.cmd → wyrd.ps1).</summary>
    private static string WyrdCmd
    {
        get
        {
            var beside = Path.Combine(InstallDir, "wyrd.cmd");
            if (File.Exists(beside)) return beside;
            var underApp = Path.Combine(InstallDir, "app", "wyrd.cmd");
            return File.Exists(underApp) ? underApp : beside;
        }
    }

    /// <summary>True once the local node answers on :7070.</summary>
    public static async Task<bool> IsRunningAsync()
    {
        try
        {
            using var resp = await Http.GetAsync(BaseUrl + "/");
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    /// <summary>Fresh install / never-named → run the onboarding wizard first.</summary>
    public static bool NeedsOnboarding()
    {
        if (!Directory.Exists(DataDir)) return true;
        var named = File.Exists(EnvFile)
            && File.ReadAllText(EnvFile).Contains("WYRDSEKAI_COMPANION_NAME=", StringComparison.Ordinal);
        return !named;
    }

    public static Task StartAsync() => RunWyrdAsync("start");
    public static Task StopAsync() => RunWyrdAsync("stop");
    public static Task RestartAsync() => RunWyrdAsync("restart");

    public static Task<int> RunWyrdAsync(params string[] args) =>
        RunWyrdAsync(TimeSpan.FromMinutes(30), args);

    /// <summary>
    /// Shell out to wyrd.cmd with the given args; resolves when the process exits.
    /// Streams are redirected on purpose: stdin is closed so any interactive prompt
    /// (`Read-Host`) gets EOF instead of hanging the GUI, and stdout/stderr are drained
    /// so a full output pipe can't deadlock the child. A timeout kills a stuck process
    /// so the wizard never freezes ("Not Responding") on a misbehaving step.
    /// </summary>
    public static async Task<int> RunWyrdAsync(TimeSpan timeout, params string[] args)
    {
        var psi = new ProcessStartInfo
        {
            FileName = WyrdCmd,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = InstallDir,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        try
        {
            using var p = Process.Start(psi);
            if (p is null) return -1;

            // EOF on stdin → interactive prompts return immediately instead of blocking.
            try { p.StandardInput.Close(); } catch { /* ignore */ }
            // Drain output so the child can't block on a full pipe (we don't need the text).
            var drainOut = p.StandardOutput.ReadToEndAsync();
            var drainErr = p.StandardError.ReadToEndAsync();

            var wait = p.WaitForExitAsync();
            var done = await Task.WhenAny(wait, Task.Delay(timeout));
            if (done != wait)
            {
                try { p.Kill(entireProcessTree: true); } catch { /* ignore */ }
                Debug.WriteLine($"wyrd {string.Join(' ', args)} timed out after {timeout}");
                return -2;
            }
            // Do NOT block until the drains hit EOF. `wyrd start` spawns DETACHED background
            // daemons (the node server, llama-server, …) that inherit the stdout/stderr write
            // handle, so the pipe never closes even though `wyrd` itself has already exited —
            // awaiting the drains here would hang the wizard on its final step forever. The
            // drains have already done their only job (keep the pipe from filling); their text
            // isn't needed, so give them a short grace window and move on.
            await Task.WhenAny(Task.WhenAll(drainOut, drainErr), Task.Delay(TimeSpan.FromSeconds(2)));
            return p.ExitCode;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"wyrd {string.Join(' ', args)} failed: {ex.Message}");
            return -1;
        }
    }

    public static void OpenDataFolder()
    {
        Directory.CreateDirectory(DataDir);
        Process.Start(new ProcessStartInfo { FileName = DataDir, UseShellExecute = true });
    }

    public static void OpenLogs()
    {
        var log = Path.Combine(DataDir, "server.log");
        if (File.Exists(log))
            Process.Start(new ProcessStartInfo { FileName = log, UseShellExecute = true });
        else
            OpenDataFolder();
    }

    /// <summary>
    /// Open Windows “Apps &amp; features” so the user can run the MSI uninstaller.
    /// A running exe cannot remove its own files, so the caller must exit afterward.
    /// </summary>
    public static void LaunchUninstaller()
    {
        Process.Start(new ProcessStartInfo { FileName = "ms-settings:appsfeatures", UseShellExecute = true });
    }
}
