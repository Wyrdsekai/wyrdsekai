using System.Drawing;

namespace Wyrdsekai.Tray;

/// <summary>
/// First-run wizard — the GUI face of <c>wyrd setup</c> + the born-with-it companion-name prompt.
/// Writes WYRDSEKAI_COMPANION_NAME (the name shapes the soul at first boot) + conf keys, runs
/// base setup, optionally installs a local brain, then triggers the first <c>wyrd start</c>.
/// While setting up it shows a step counter (k of N) + a live progress bar so long steps
/// (embedding model, local-model download) don't look frozen.
/// </summary>
internal sealed class OnboardingForm : Form
{
    private readonly TextBox _name = new() { Text = "Wyrd" };
    private readonly ComboBox _lang = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox _inference = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox _apiKey = new() { UseSystemPasswordChar = true, Enabled = false };
    private readonly Button _finish = new() { Text = "Create my companion", AutoSize = true, Padding = new Padding(10, 4, 10, 4) };
    private readonly Label _busy = new() { Text = "", AutoSize = false, ForeColor = SystemColors.GrayText, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
    private readonly ProgressBar _progress = new() { Style = ProgressBarStyle.Marquee, MarqueeAnimationSpeed = 30, Visible = false, Dock = DockStyle.Fill };

    public OnboardingForm()
    {
        // DPI-aware, font-scaled layout so it renders crisply + roomily on scaled displays.
        Font = new Font("Segoe UI", 10F);
        AutoScaleMode = AutoScaleMode.Font;
        Text = "Welcome to Wyrdsekai";
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.Sizable;  // resizable — never let the user get stuck on a tiny window
        ClientSize = new Size(620, 500);
        MinimumSize = new Size(560, 480);
        MaximizeBox = false;
        try { Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "wyrdsekai.ico")); } catch { /* default */ }

        _lang.Items.AddRange(new object[] { "English (en)", "Español (es)", "日本語 (ja)" });
        _lang.SelectedIndex = 0;
        _inference.Items.AddRange(new object[]
        {
            "Local model — download a brain to this PC",
            "Cloud API key — use a hosted model",
            "Decide later",
        });
        _inference.SelectedIndex = 0;
        _inference.SelectedIndexChanged += (_, _) => _apiKey.Enabled = _inference.SelectedIndex == 1;

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            Padding = new Padding(24, 18, 24, 18),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var intro = new Label
        {
            Text = "Name your companion and choose how it thinks.\nYou can change these later from the tray menu.",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = SystemColors.GrayText,
            AutoSize = false,
        };
        root.Controls.Add(intro, 0, 0);
        root.SetColumnSpan(intro, 2);
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 64));   // intro

        void Field(string label, Control c)
        {
            int row = root.RowStyles.Count;
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
            root.Controls.Add(new Label
            {
                Text = label,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleLeft,
                AutoSize = false,
            }, 0, row);
            c.Anchor = AnchorStyles.Left | AnchorStyles.Right;   // stretch wide, centre vertically in the row
            c.Margin = new Padding(3, 9, 3, 9);
            root.Controls.Add(c, 1, row);
        }

        Field("Companion name", _name);
        Field("Language", _lang);
        Field("How it thinks", _inference);
        Field("API key", _apiKey);

        // filler row pushes the progress + buttons to the bottom
        int fillerRow = root.RowStyles.Count;
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.Controls.Add(new Panel { Dock = DockStyle.Fill }, 0, fillerRow);

        // progress row (step text on the left, animated bar on the right) — spans both columns
        int progRow = root.RowStyles.Count;
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        var progPanel = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Margin = new Padding(0) };
        progPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
        progPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
        progPanel.Controls.Add(_busy, 0, 0);
        _progress.Margin = new Padding(8, 4, 0, 4);
        progPanel.Controls.Add(_progress, 1, 0);
        root.Controls.Add(progPanel, 0, progRow);
        root.SetColumnSpan(progPanel, 2);

        // button row (spans both columns)
        int btnRow = root.RowStyles.Count;
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));
        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
        };
        _finish.Click += async (_, _) => await FinishAsync();
        buttons.Controls.Add(_finish);
        root.Controls.Add(buttons, 0, btnRow);
        root.SetColumnSpan(buttons, 2);
        root.RowCount = root.RowStyles.Count;

        Controls.Add(root);
        AcceptButton = _finish;
    }

    private static string LangCode(int i) => i switch { 1 => "es", 2 => "ja", _ => "en" };

    private async Task FinishAsync()
    {
        var name = string.IsNullOrWhiteSpace(_name.Text) ? "Wyrd" : _name.Text.Trim();
        int mode = _inference.SelectedIndex;                  // 0 local, 1 cloud, 2 later
        int total = 4 + (mode == 1 ? 1 : 0) + (mode == 0 ? 1 : 0);
        int step = 0;
        void Step(string msg) => SetStep(++step, total, msg);

        SetBusy(true);
        try
        {
            Directory.CreateDirectory(NodeController.DataDir);

            // 1. Born-with-it companion name → the system conf. `wyrd start` loads wyrdsekai.conf
            //    into the server's environment, but it does NOT source ~/.wyrdsekai/env on Windows,
            //    so the name must go through `config set` (the conf) to reach first boot.
            Step("Naming your companion…");
            await NodeController.RunWyrdAsync("config", "set", "WYRDSEKAI_COMPANION_NAME", name);

            // 2. Language → conf via the CLI (single source of truth across platforms).
            //    Dual-write (docs/CONFIG.md §1): WYRDSEKAI_LOCALE is the canonical key;
            //    WYRDSEKAI_LANG is the accepted lower-precedence alias kept for compat.
            Step("Setting language…");
            await NodeController.RunWyrdAsync("config", "set", "WYRDSEKAI_LOCALE", LangCode(_lang.SelectedIndex));
            await NodeController.RunWyrdAsync("config", "set", "WYRDSEKAI_LANG", LangCode(_lang.SelectedIndex));

            // 3. Cloud key path.
            if (mode == 1 && !string.IsNullOrWhiteSpace(_apiKey.Text))
            {
                Step("Saving your API key…");
                await NodeController.RunWyrdAsync("config", "set", "WYRDSEKAI_INFERENCE_MODE", "cloud");
                await NodeController.RunWyrdAsync("config", "set", "ANTHROPIC_API_KEY", _apiKey.Text.Trim());
            }
            else if (mode == 1)
            {
                Step("Saving your choice…"); // cloud chosen but key blank — still counts as a step
            }

            // 4. Base setup (embedding model, household scan, FIRST_ENCOUNTER).
            Step("Preparing your household node (downloading helper models)…");
            await NodeController.RunWyrdAsync("setup");

            // 5. Local brain download, if chosen (the long pole). Persist mode=local
            //    explicitly (docs/CONFIG.md §5) so `wyrd` start/setup runs the local
            //    stack by choice, not by default — symmetric with the cloud branch.
            if (mode == 0)
            {
                await NodeController.RunWyrdAsync("config", "set", "WYRDSEKAI_INFERENCE_MODE", "local");
                Step("Downloading the local model — the longest step, a few minutes depending on your connection…");
                await NodeController.RunWyrdAsync("inference", "install");
            }

            // 6. First boot — the soul is born with the chosen name. First boot loads the model +
            //    seeds the world, so this step legitimately takes a few minutes (it is not frozen).
            Step("Waking your companion — first boot loads the model, this can take a few minutes…");
            await NodeController.StartAsync();

            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception ex)
        {
            MessageBox.Show("Setup hit a snag:\n\n" + ex.Message, "Wyrdsekai",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            SetBusy(false);
        }
    }

    private void SetStep(int step, int total, string msg)
    {
        _busy.Text = $"Step {step} of {total}: {msg}";
        Update();
    }

    private void SetBusy(bool busy)
    {
        _finish.Enabled = !busy;
        _progress.Visible = busy;
        _name.Enabled = _lang.Enabled = _inference.Enabled = !busy;
        _apiKey.Enabled = !busy && _inference.SelectedIndex == 1;
        if (!busy) _busy.Text = "";
        Update();
    }

}
