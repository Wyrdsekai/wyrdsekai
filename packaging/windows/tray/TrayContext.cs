using System.Diagnostics;

namespace Wyrdsekai.Tray;

/// <summary>The system-tray control panel: world launcher + node lifecycle + settings + uninstall.</summary>
internal sealed class TrayContext : ApplicationContext
{
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _poll;
    private readonly ToolStripMenuItem _statusItem;

    public TrayContext()
    {
        _statusItem = new ToolStripMenuItem("Node: checking…") { Enabled = false };

        var services = new ToolStripMenuItem("Services");
        services.DropDownItems.Add("Start inference", null, async (_, _) => await NodeController.RunWyrdAsync("inference", "start"));
        services.DropDownItems.Add("Stop inference", null, async (_, _) => await NodeController.RunWyrdAsync("inference", "stop"));
        services.DropDownItems.Add("Start oracle", null, async (_, _) => await NodeController.RunWyrdAsync("oracle", "start"));
        services.DropDownItems.Add("Stop oracle", null, async (_, _) => await NodeController.RunWyrdAsync("oracle", "stop"));

        var menu = new ContextMenuStrip();
        menu.Items.Add("Enter World", null, (_, _) => EnterWorld());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_statusItem);
        menu.Items.Add("Start node", null, async (_, _) => await NodeController.StartAsync());
        menu.Items.Add("Stop node", null, async (_, _) => await NodeController.StopAsync());
        menu.Items.Add("Restart node", null, async (_, _) => await NodeController.RestartAsync());
        menu.Items.Add(services);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Settings…", null, (_, _) => new SettingsForm().ShowDialog());
        menu.Items.Add("Open data folder", null, (_, _) => NodeController.OpenDataFolder());
        menu.Items.Add("Open logs", null, (_, _) => NodeController.OpenLogs());
        menu.Items.Add("Join a household…", null, (_, _) => JoinHousehold());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Uninstall…", null, (_, _) => Uninstall());
        menu.Items.Add("Quit", null, (_, _) => Quit());

        _tray = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "Wyrdsekai",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.DoubleClick += (_, _) => EnterWorld();

        _poll = new System.Windows.Forms.Timer { Interval = 4000 };
        _poll.Tick += async (_, _) => await RefreshStatusAsync();
        _poll.Start();

        BeginStartup();
    }

    private async void BeginStartup()
    {
        if (NodeController.NeedsOnboarding())
        {
            using var wizard = new OnboardingForm();
            if (wizard.ShowDialog() != DialogResult.OK)
            {
                await RefreshStatusAsync();
                return; // user bailed out of first-run; stay in tray, node not started
            }
            // wizard wrote the name + config and kicked off the first boot
        }
        else if (!await NodeController.IsRunningAsync())
        {
            await NodeController.StartAsync();
        }

        await WaitForNodeAsync(TimeSpan.FromSeconds(90));
        EnterWorld();
    }

    private static async Task WaitForNodeAsync(TimeSpan timeout)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            if (await NodeController.IsRunningAsync()) return;
            await Task.Delay(1500);
        }
    }

    // Launch the world page in the user's default browser.
    private static void OpenInBrowser()
    {
        try { Process.Start(new ProcessStartInfo { FileName = NodeController.AppUrl, UseShellExecute = true }); }
        catch { /* no default browser — nothing useful to do */ }
    }

    // Open the world in the user's default browser. The world UI is a normal web page at
    // :7070/app, which renders reliably on all hardware — unlike an embedded WebView2 control,
    // which white-screens on some GPU/driver combos. Wait for first boot so we never open a
    // dead page.
    private async void EnterWorld()
    {
        await WaitForNodeAsync(TimeSpan.FromMinutes(3));
        OpenInBrowser();
    }

    private async Task RefreshStatusAsync()
    {
        var running = await NodeController.IsRunningAsync();
        _statusItem.Text = running ? "Node: ● Running" : "Node: ○ Stopped";
        _tray.Text = running ? "Wyrdsekai — running" : "Wyrdsekai — stopped";
    }

    private async void Uninstall()
    {
        var first = MessageBox.Show(
            "Uninstall Wyrdsekai?\n\nThis opens Windows “Apps & features”, where you remove the program.",
            "Uninstall Wyrdsekai", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
        if (first != DialogResult.OK) return;

        // Give the user the explicit choice to also wipe their per-user world data.
        // (The MSI never touches it — — so this is the
        // one place a full wipe is offered, opt-in and clearly warned.)
        var wipe = MessageBox.Show(
            "Also permanently delete your world data?\n\n" +
            "Your companion, memories, bonds, settings and keys in:\n" +
            $"    {NodeController.DataDir}\n" +
            "will be ERASED and cannot be recovered.\n\n" +
            "Yes = delete everything     No = keep my data     Cancel = don't uninstall",
            "Delete world data?", MessageBoxButtons.YesNoCancel, MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);
        if (wipe == DialogResult.Cancel) return;

        if (wipe == DialogResult.Yes)
        {
            try
            {
                await NodeController.StopAsync();     // release DB / model file locks first
                await Task.Delay(1500);
                if (Directory.Exists(NodeController.DataDir))
                    Directory.Delete(NodeController.DataDir, recursive: true);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "Could not fully delete the data folder (something may still be holding a file):\n\n" +
                    ex.Message + "\n\nYou can delete it manually:\n" + NodeController.DataDir,
                    "Wyrdsekai", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        NodeController.LaunchUninstaller();
        Quit();
    }

    // Join this PC to a household over a relay: paste the invite token, then run
    // `wyrd relay join <token>` (redeems it, pins the CA fingerprint, writes the
    // relay legs). The PowerShell CLI forwards `relay join` to the Java
    // RelayNkeyAdminMain; the user applies it with `wyrd restart` — we offer that.
    private async void JoinHousehold()
    {
        using var form = new Form
        {
            Text = "Join a household",
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MinimizeBox = false,
            MaximizeBox = false,
            ClientSize = new Size(460, 132),
            Font = new Font("Segoe UI", 9F),
        };
        var label = new Label { Text = "Paste the invitation token you were given:", AutoSize = true, Location = new Point(12, 14) };
        var box = new TextBox { Location = new Point(12, 42), Width = 436, PlaceholderText = "wyrdjoin://relay.example.com:4443/CODE.fingerprint" };
        var ok = new Button { Text = "Join", DialogResult = DialogResult.OK, Location = new Point(292, 92), Width = 75 };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Location = new Point(373, 92), Width = 75 };
        form.Controls.Add(label);
        form.Controls.Add(box);
        form.Controls.Add(ok);
        form.Controls.Add(cancel);
        form.AcceptButton = ok;
        form.CancelButton = cancel;

        if (form.ShowDialog() != DialogResult.OK) return;
        var token = box.Text.Trim();
        if (string.IsNullOrEmpty(token)) return;

        var rc = await NodeController.RunWyrdAsync("relay", "join", token);
        if (rc != 0)
        {
            MessageBox.Show($"Couldn't join — check the token and try again (exit {rc}).",
                "Wyrdsekai", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        // relay join writes the conf but doesn't restart the node on Windows —
        // offer to apply it now so the household link comes up without a manual step.
        var apply = MessageBox.Show(
            "Joined the household. Restart the node now to connect through the relay?",
            "Wyrdsekai", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
        if (apply == DialogResult.Yes) await NodeController.RestartAsync();
    }

    private void Quit()
    {
        _poll.Stop();
        _tray.Visible = false;
        _tray.Dispose();
        ExitThread();
    }

    private static Icon LoadIcon()
    {
        try
        {
            var p = Path.Combine(AppContext.BaseDirectory, "wyrdsekai.ico");
            if (File.Exists(p)) return new Icon(p);
        }
        catch { /* fall through */ }
        return SystemIcons.Application;
    }
}
