using System.Threading;

namespace Wyrdsekai.Tray;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // Single instance — a second double-click should not spawn a second tray.
        using var mutex = new Mutex(initiallyOwned: true, @"Local\WyrdsekaiTray", out bool isNew);
        if (!isNew)
        {
            return; // already running in the tray
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayContext());
    }
}
