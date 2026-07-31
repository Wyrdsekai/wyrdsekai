using System.Diagnostics;
using System.Drawing;

namespace Wyrdsekai.Tray;

/// <summary>
/// Node/system config over the .conf (via <c>wyrd config set</c>), with inline help for the
/// common knobs. World/persona settings (voice, autonomy, bonds) live in-world — reached by the
/// button below.
/// </summary>
internal sealed class SettingsForm : Form
{
    /// <summary>
    /// Friendly one-liners for every key the installers seed (and the common extras).
    /// Anything not listed falls back to <see cref="Describe"/> so a row is never blank.
    /// </summary>
    private static readonly Dictionary<string, string> Help = new(StringComparer.OrdinalIgnoreCase)
    {
        // identity / paths
        ["WYRDSEKAI_PORT"] = "Web + world port. Open the world at http://localhost:<port>/app. Default 7070.",
        ["WYRDSEKAI_LANG"] = "Alias of WYRDSEKAI_LOCALE (lower precedence; kept for compat — onboarding writes both).",
        ["WYRDSEKAI_LOCALE"] = "Canonical interface language/locale: en, es, or ja.",
        ["WYRDSEKAI_NODE_NAME"] = "This node's name on your household network.",
        ["WYRDSEKAI_NODE_ID"] = "Stable unique id for this node (auto-generated; don't change).",
        ["WYRDSEKAI_COMPANION_NAME"] = "Your companion's born-with-it name. Shapes the soul at first boot.",
        ["WYRDSEKAI_HOME"] = "Install directory for the Wyrdsekai program files.",
        ["WYRDSEKAI_DATA_DIR"] = "Where your world lives: companion, memories, config, keys (~/.wyrdsekai).",
        ["WYRDSEKAI_CONF"] = "Path to this settings file.",
        ["WYRDSEKAI_CONFIG_FILE"] = "Path to this settings file.",

        // inference (where the companion thinks)
        ["WYRDSEKAI_INFERENCE_MODE"] = "Where the brain runs: local (run the local stack), cloud (never start a local stack; WYRDSEKAI_INFERENCE_URL points at the remote endpoint), or zone (ride the household).",
        ["WYRDSEKAI_INFERENCE_ENABLED"] = "Alias of WYRDSEKAI_LLAMA_ENABLED (lower precedence): run the bundled local model server. false = kill-switch for the local stack.",
        ["WYRDSEKAI_INFERENCE_URL"] = "Endpoint the companion sends its thinking to.",
        ["WYRDSEKAI_INFERENCE_CONCURRENCY"] = "How many inference requests run at once.",
        ["WYRDSEKAI_INFERENCE_TIMEOUT"] = "Seconds to wait for a model reply before giving up.",
        ["WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE"] = "Offer this PC's GPU to household members (true/false).",
        ["WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW"] = "Borrow a household peer's GPU when this PC has none (true/false).",

        // local model server (llama.cpp)
        ["WYRDSEKAI_LLAMA_ENABLED"] = "Run the local model server (llama.cpp) on this PC.",
        ["WYRDSEKAI_LLAMA_URL"] = "Address of the local model server. Default http://localhost:8200.",
        ["WYRDSEKAI_LLAMA_BACKEND"] = "Compute backend for the local model: cpu, cuda, vulkan, etc.",
        ["WYRDSEKAI_LLAMA_MODEL"] = "The main 'drive' model file the companion thinks with.",
        ["WYRDSEKAI_LLAMA_DRIVE_MODEL"] = "The main 'drive' model file the companion thinks with.",
        ["WYRDSEKAI_VOICE_ENABLED"] = "Run the smaller 'voice' model that shapes how replies sound.",
        ["WYRDSEKAI_LLAMA_VOICE_MODEL"] = "The 'voice' model file (tone / personality polish).",
        ["WYRDSEKAI_GPU_LAYERS"] = "How many model layers to offload to the GPU (0 = CPU only).",
        ["WYRDSEKAI_MODEL_PATH"] = "Folder where downloaded model files are kept.",
        ["WYRDSEKAI_EMBEDDING_MODEL"] = "Model used to index memories + the library for search.",

        // coding backend (Goose)
        ["WYRDSEKAI_CODING_DEFAULT_BACKEND"] = "Which coding agent the companion uses for code tasks (default: goose).",
        ["WYRDSEKAI_CODING_GOOSE_ENABLED"] = "Enable the Goose coding backend (true/false).",
        ["WYRDSEKAI_CODING_GOOSE_MODEL"] = "Model Goose uses for coding. Defaults to your local drive model.",
        ["WYRDSEKAI_CODING_GOOSE_PROVIDER"] = "API dialect Goose speaks. 'openai' here means the local model's OpenAI-compatible API, NOT OpenAI's cloud.",

        // mesh / relay
        ["WYRDSEKAI_NATS_URL"] = "Internal message bus address (NATS). Default nats://localhost:4222.",
        ["WYRDSEKAI_NATS_AUTO_START"] = "Start the internal message bus automatically (true/false).",
        ["WYRDSEKAI_BETWEEN_ENABLED"] = "Enable 'the Between' — the encrypted mesh that links household nodes.",
        ["WYRDSEKAI_RELAY_ENABLED"] = "Run behind a relay so phones / remote clients can reach this node.",
        ["WYRDSEKAI_RELAY_URL"] = "Address of the relay this node homes on.",
        ["WYRDSEKAI_RELAY_REGISTRATION_URL"] = "The relay this node homes on.",
        ["WYRDSEKAI_RELAY_FINGERPRINT"] = "Pinned certificate fingerprint of the relay (trust anchor).",
        ["WYRDSEKAI_RELAY_USE_NKEY"] = "Authenticate to the relay with an NKey (true/false).",

        // zone / federation / household
        ["WYRDSEKAI_ZONE_ID"] = "The world (zone) this node belongs to.",
        ["WYRDSEKAI_HOUSEHOLD_TAG"] = "Your household's shared tag — the trust boundary for GPU sharing.",
        ["WYRDSEKAI_FEDERATION_AUTO_ACCEPT"] = "Auto-accept federation requests from other zones (true/false).",
        ["WYRDSEKAI_PUBLIC_HOST"] = "Public hostname others use to reach this node, if any.",

        // search
        ["WYRDSEKAI_SEARXNG_URL"] = "Private web-search engine the companion uses (SearXNG).",
        ["WYRDSEKAI_METASEARCH_WIN_URL"] = "Private web-search engine address on Windows.",

        // library
        ["WYRDSEKAI_LIBRARY_LANGS"] = "Languages to seed the starter library in (e.g. en,ja).",
        ["WYRDSEKAI_LIBRARY_STARTER"] = "Whether to download the starter library at setup (true/false).",

        // optional installs
        ["WYRDSEKAI_SKIP_GOOSE_INSTALL"] = "Skip installing the Goose coding backend (true/false).",
        ["WYRDSEKAI_SKIP_METASEARCH_INSTALL"] = "Skip installing the private web-search engine (true/false).",
        ["WYRDSEKAI_SKIP_ORACLE_INSTALL"] = "Skip installing the Oracle classifier service (true/false).",
        ["WYRDSEKAI_NOSTR_ENABLED"] = "Publish/receive signed identity notes over Nostr (true/false).",

        // keys / secrets
        ["ANTHROPIC_API_KEY"] = "Cloud API key (used only when 'how it thinks' = Cloud).",
        ["WYRDSEKAI_API_KEY_OPENROUTER"] = "OpenRouter API key, if you route cloud thinking through OpenRouter.",
        ["WYRDSEKAI_ADMIN_TOKEN"] = "Admin token for privileged local operations. Keep secret.",
    };

    /// <summary>Help text for a key — the curated line, or a readable fallback so no row is blank.</summary>
    private static string Describe(string key)
    {
        if (Help.TryGetValue(key, out var h)) return h;
        // RELAY_LEG_1_URL etc. share the relay story.
        if (key.StartsWith("WYRDSEKAI_RELAY_LEG", StringComparison.OrdinalIgnoreCase))
            return "One relay 'leg' this node homes on (a zone can use several relays at once).";
        // Generic readable fallback: WYRDSEKAI_FOO_BAR → "Advanced: foo bar."
        var pretty = key;
        if (pretty.StartsWith("WYRDSEKAI_", StringComparison.OrdinalIgnoreCase))
            pretty = pretty["WYRDSEKAI_".Length..];
        pretty = pretty.Replace('_', ' ').ToLowerInvariant().Trim();
        return pretty.Length == 0 ? "Advanced node setting." : $"Advanced: {pretty}.";
    }

    private readonly DataGridView _grid = new()
    {
        Dock = DockStyle.Fill,
        AllowUserToAddRows = true,
        AllowUserToDeleteRows = true,
        RowHeadersVisible = false,
        AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
        AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells,
        ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize,
    };

    public SettingsForm()
    {
        Font = new Font("Segoe UI", 10F);
        AutoScaleMode = AutoScaleMode.Font;
        Text = "Wyrdsekai — Settings";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(840, 560);
        MinimumSize = new Size(640, 420);
        try { Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "wyrdsekai.ico")); } catch { /* default */ }

        var keyCol = new DataGridViewTextBoxColumn { HeaderText = "Setting", Name = "key", FillWeight = 30 };
        var valCol = new DataGridViewTextBoxColumn { HeaderText = "Value", Name = "val", FillWeight = 22 };
        var helpCol = new DataGridViewTextBoxColumn { HeaderText = "What it does", Name = "help", FillWeight = 48, ReadOnly = true };
        helpCol.DefaultCellStyle.WrapMode = DataGridViewTriState.True;
        helpCol.DefaultCellStyle.ForeColor = SystemColors.GrayText;
        _grid.Columns.AddRange(keyCol, valCol, helpCol);
        _grid.CellEndEdit += (_, e) => { if (e.ColumnIndex == 0) UpdateHelp(e.RowIndex); };

        var note = new Label
        {
            Dock = DockStyle.Top,
            AutoSize = false,
            Height = 64,
            Padding = new Padding(14, 10, 14, 10),
            Text = "These are node settings (ports, inference, relay, household sharing).\n" +
                   "Edit a Value and click “Save + Restart”. Your companion's voice, autonomy and " +
                   "bonds live inside the world — open them with the button below.",
            ForeColor = SystemColors.ControlText,
        };

        var bar = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = 60,
            Padding = new Padding(12, 10, 12, 10),
            WrapContents = false,
        };
        var save = new Button { Text = "Save + Restart", AutoSize = true, Padding = new Padding(10, 4, 10, 4) };
        save.Click += async (_, _) => await SaveAsync();
        var world = new Button { Text = "World && persona settings →", AutoSize = true, Padding = new Padding(10, 4, 10, 4) };
        world.Click += (_, _) => { try { Process.Start(new ProcessStartInfo { FileName = NodeController.AppUrl, UseShellExecute = true }); } catch { /* no browser */ } };
        bar.Controls.Add(save);
        bar.Controls.Add(world);

        Controls.Add(_grid);   // fill (added first so docked top/bottom reserve their space)
        Controls.Add(note);
        Controls.Add(bar);

        Load += async (_, _) => await LoadAsync();
    }

    private void UpdateHelp(int rowIndex)
    {
        if (rowIndex < 0 || rowIndex >= _grid.Rows.Count) return;
        var row = _grid.Rows[rowIndex];
        if (row.IsNewRow) return;
        var key = row.Cells[0].Value?.ToString()?.Trim() ?? "";
        row.Cells[2].Value = Help.TryGetValue(key, out var h) ? h : "";
    }

    private async Task LoadAsync()
    {
        _grid.Rows.Clear();
        var conf = NodeController.ConfFile;
        if (!File.Exists(conf)) return;
        foreach (var line in await File.ReadAllLinesAsync(conf))
        {
            var t = line.Trim();
            if (t.Length == 0 || t.StartsWith('#')) continue;
            var eq = t.IndexOf('=');
            if (eq <= 0) continue;
            var key = t[..eq].Trim();
            var val = t[(eq + 1)..];
            Help.TryGetValue(key, out var h);
            _grid.Rows.Add(key, val, h ?? "");
        }
    }

    private async Task SaveAsync()
    {
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (row.IsNewRow) continue;
            var key = row.Cells[0].Value?.ToString()?.Trim();
            var val = row.Cells[1].Value?.ToString() ?? "";
            if (string.IsNullOrEmpty(key)) continue;
            await NodeController.RunWyrdAsync("config", "set", key, val);
        }
        await NodeController.RestartAsync();
        MessageBox.Show("Saved. The node is restarting to apply changes.", "Wyrdsekai",
            MessageBoxButtons.OK, MessageBoxIcon.Information);
        Close();
    }
}
