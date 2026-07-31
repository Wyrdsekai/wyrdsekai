// Windows for the Wyrdsekai menu-bar app: the world (WKWebView), the first-run
// onboarding wizard, and the node Settings editor.

import Cocoa
import WebKit

// MARK: - World window (the :7070/app web client)

final class WorldWindowController: NSWindowController, NSWindowDelegate {
    private var web: WKWebView!

    init() {
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1000, height: 720),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered, defer: false)
        win.title = "Wyrdsekai"
        win.center()
        win.setFrameAutosaveName("WyrdsekaiWorld")
        super.init(window: win)
        win.delegate = self

        web = WKWebView(frame: win.contentView!.bounds)
        web.autoresizingMask = [.width, .height]
        win.contentView!.addSubview(web)
        load()
    }

    required init?(coder: NSCoder) { fatalError("no coder") }

    func load() {
        if let url = URL(string: Node.appURL) {
            web.load(URLRequest(url: url))
        }
    }

    // Close to the menu bar (hide) instead of tearing the web view down.
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        sender.orderOut(nil)
        return false
    }

    override func showWindow(_ sender: Any?) {
        super.showWindow(sender)
        // Reload if the page never loaded (e.g. node was down at first open).
        if web.url == nil { load() }
    }
}

// MARK: - Onboarding wizard

final class OnboardingWindowController: NSWindowController {
    private let onComplete: () -> Void

    private let nameField = NSTextField(string: "Wyrd")
    private let langPopup = NSPopUpButton(frame: .zero, pullsDown: false)
    private let modePopup = NSPopUpButton(frame: .zero, pullsDown: false)
    private let keyField = NSSecureTextField(string: "")
    private let keyLabel = NSTextField(labelWithString: "API key")
    private let statusLabel = NSTextField(labelWithString: "")
    private let progress = NSProgressIndicator()
    private let createButton = NSButton(title: "Create my companion", target: nil, action: nil)

    init(onComplete: @escaping () -> Void) {
        self.onComplete = onComplete
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 560, height: 430),
            styleMask: [.titled, .closable],
            backing: .buffered, defer: false)
        win.title = "Welcome to Wyrdsekai"
        win.center()
        super.init(window: win)
        buildUI(in: win.contentView!)
    }

    required init?(coder: NSCoder) { fatalError("no coder") }

    private func buildUI(in v: NSView) {
        let intro = NSTextField(wrappingLabelWithString:
            "Name your companion and choose how it thinks.\nYou can change these later from the menu-bar icon.")
        intro.textColor = .secondaryLabelColor
        intro.frame = NSRect(x: 24, y: 372, width: 512, height: 40)
        v.addSubview(intro)

        func label(_ s: String, _ y: CGFloat) -> NSTextField {
            let l = NSTextField(labelWithString: s)
            l.frame = NSRect(x: 24, y: y, width: 150, height: 22)
            v.addSubview(l); return l
        }
        let fieldX: CGFloat = 184, fieldW: CGFloat = 352

        _ = label("Companion name", 330)
        nameField.frame = NSRect(x: fieldX, y: 328, width: fieldW, height: 24)
        v.addSubview(nameField)

        _ = label("Language", 290)
        langPopup.addItems(withTitles: ["English (en)", "Español (es)", "日本語 (ja)"])
        langPopup.frame = NSRect(x: fieldX, y: 286, width: fieldW, height: 26)
        v.addSubview(langPopup)

        _ = label("How it thinks", 250)
        modePopup.addItems(withTitles: [
            "Local model (Apple Silicon — downloads ~3GB on first run)",
            "Cloud API key — use a hosted model",
            "Decide later",
        ])
        modePopup.target = self
        modePopup.action = #selector(modeChanged)
        modePopup.frame = NSRect(x: fieldX, y: 246, width: fieldW, height: 26)
        v.addSubview(modePopup)

        keyLabel.frame = NSRect(x: 24, y: 210, width: 150, height: 22)
        keyLabel.textColor = .secondaryLabelColor
        v.addSubview(keyLabel)
        keyField.frame = NSRect(x: fieldX, y: 206, width: fieldW, height: 24)
        keyField.isEnabled = false
        keyField.placeholderString = "sk-ant-…"
        v.addSubview(keyField)

        statusLabel.frame = NSRect(x: 24, y: 70, width: 380, height: 36)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.maximumNumberOfLines = 2
        v.addSubview(statusLabel)

        progress.frame = NSRect(x: 410, y: 74, width: 126, height: 20)
        progress.style = .bar
        progress.isIndeterminate = true
        progress.isHidden = true
        v.addSubview(progress)

        createButton.frame = NSRect(x: 376, y: 20, width: 160, height: 34)
        createButton.bezelStyle = .rounded
        createButton.keyEquivalent = "\r"
        createButton.target = self
        createButton.action = #selector(create)
        v.addSubview(createButton)
    }

    @objc private func modeChanged() {
        keyField.isEnabled = (modePopup.indexOfSelectedItem == 1)
    }

    private func langCode() -> String {
        switch langPopup.indexOfSelectedItem { case 1: return "es"; case 2: return "ja"; default: return "en" }
    }
    private func modeCode() -> String {
        switch modePopup.indexOfSelectedItem { case 1: return "cloud"; case 2: return "later"; default: return "local" }
    }

    private func setBusy(_ busy: Bool, _ msg: String = "") {
        createButton.isEnabled = !busy
        nameField.isEnabled = !busy
        langPopup.isEnabled = !busy
        modePopup.isEnabled = !busy
        keyField.isEnabled = !busy && modePopup.indexOfSelectedItem == 1
        progress.isHidden = !busy
        if busy { progress.startAnimation(nil) } else { progress.stopAnimation(nil) }
        statusLabel.stringValue = msg
    }

    @objc private func create() {
        let name = nameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let companion = name.isEmpty ? "Wyrd" : name
        let mode = modeCode()
        let key = keyField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)

        setBusy(true, "Step 1 of 2: applying your choices… (you'll be asked to authenticate)")
        Node.onboard(name: companion, lang: langCode(), mode: mode, apiKey: key) { ok in
            if !ok {
                self.setBusy(false)
                let a = NSAlert()
                a.messageText = "Setup didn't complete"
                a.informativeText = "Authentication was cancelled or a step failed. You can try again."
                a.runModal()
                return
            }
            if mode == "local" {
                // Local brain: download the models (~5–7 GB on FIRST run) + start the
                // dual-MLX servers in the BACKGROUND, then hand off to the world ONLY
                // once inference actually answers. This used to run synchronously,
                // which (a) froze this window so it couldn't close, and (b) if the user
                // force-closed it, dropped them into the world to register/talk BEFORE
                // the brain finished downloading — so the companion couldn't reply.
                // The window is now a live progress indicator that closes itself the
                // moment the companion is ready.
                self.statusLabel.stringValue = "Waking \(companion)'s brain — downloading models (first run can take a few minutes)…"
                DispatchQueue.global(qos: .userInitiated).async {
                    Node.runWyrd(["inference", "setup-local"], timeout: 3600)
                }
                self.waitForInferenceThenEnter(companion: companion)
            } else {
                // Cloud / decide-later: nothing to download — hand off immediately.
                self.statusLabel.stringValue = "Step 2 of 2: waking \(companion)…"
                self.window?.close()
                self.onComplete()
            }
        }
    }

    /// Poll local inference health (the :8200 drive) and open the world ONLY once it
    /// answers, so the companion can reply the moment the user arrives. Bounded
    /// (~12 min) so a stalled first-run download still releases the UI rather than
    /// trapping the user in the onboarding window.
    private func waitForInferenceThenEnter(companion: String, tries: Int = 0) {
        if tries > 240 {
            self.statusLabel.stringValue = "Still setting up — opening the world; \(companion) will wake when the brain finishes."
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.window?.close(); self.onComplete() }
            return
        }
        Node.inferenceReady { ready in
            if ready {
                self.statusLabel.stringValue = "\(companion) is awake — entering the world…"
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { self.window?.close(); self.onComplete() }
            } else {
                if tries == 12 { self.statusLabel.stringValue = "Still downloading \(companion)'s brain — this only happens once…" }
                if tries == 80 { self.statusLabel.stringValue = "Almost there — loading the models into memory…" }
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                    self.waitForInferenceThenEnter(companion: companion, tries: tries + 1)
                }
            }
        }
    }
}

// MARK: - Settings editor

private final class ConfRow {
    let key: String
    var value: String
    let help: String
    init(_ key: String, _ value: String, _ help: String) {
        self.key = key; self.value = value; self.help = help
    }
}

final class SettingsWindowController: NSWindowController,
                                      NSTableViewDataSource, NSTableViewDelegate, NSTextFieldDelegate {
    /// Set by the app so the "World & persona settings" button can open the world.
    var openWorld: (() -> Void)?

    private var rows: [ConfRow] = []
    private let table = NSTableView()

    /// Curated help for the keys the installers seed; readable fallback otherwise.
    private static let help: [String: String] = [
        "WYRDSEKAI_PORT": "Web + world port. Open the world at http://localhost:<port>/app. Default 7070.",
        "WYRDSEKAI_LANG": "Interface language: en, es, or ja.",
        "WYRDSEKAI_LOCALE": "Interface language/locale: en, es, or ja.",
        "WYRDSEKAI_NODE_NAME": "This node's name on your household network.",
        "WYRDSEKAI_COMPANION_NAME": "Your companion's born-with-it name. Shapes the soul at first boot.",
        "WYRDSEKAI_DATA_DIR": "Where your world lives: companion, memories, config, keys (~/.wyrdsekai).",
        "WYRDSEKAI_ZONE_ID": "The world (zone) this node belongs to.",
        "WYRDSEKAI_BETWEEN_ENABLED": "Enable the encrypted mesh that links household nodes.",
        "WYRDSEKAI_NATS_URL": "Internal message bus address (NATS). Default nats://127.0.0.1:4222.",
        "WYRDSEKAI_INFERENCE_MODE": "Where the companion thinks: local, cloud, or zone.",
        "WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE": "Offer this Mac's GPU to household members (true/false).",
        "WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW": "Borrow a household peer's GPU when this Mac has none (true/false).",
        "WYRDSEKAI_LLAMA_ENABLED": "Run the local model server on this Mac.",
        "WYRDSEKAI_LLAMA_URL": "Address of the local skills model (Apple Silicon: mlx://127.0.0.1:8200).",
        "WYRDSEKAI_VOICE_ENABLED": "Run the smaller 'voice' model that shapes how replies sound.",
        "WYRDSEKAI_VOICE_URL": "Address of the local voice model (Apple Silicon: mlx://127.0.0.1:8201).",
        "WYRDSEKAI_RELAY_ENABLED": "Run behind a relay so phones / remote clients can reach this node.",
        "WYRDSEKAI_RELAY_URL": "Address of the relay this node homes on.",
        "WYRDSEKAI_RELAY_REGISTRATION_URL": "The relay this node homes on.",
        "ANTHROPIC_API_KEY": "Cloud API key (used only when 'how it thinks' = Cloud).",
    ]

    private static func describe(_ key: String) -> String {
        if let h = help[key] { return h }
        if key.hasPrefix("WYRDSEKAI_RELAY_LEG") {
            return "One relay 'leg' this node homes on (a zone can use several relays at once)."
        }
        var pretty = key
        if pretty.hasPrefix("WYRDSEKAI_") { pretty = String(pretty.dropFirst("WYRDSEKAI_".count)) }
        pretty = pretty.replacingOccurrences(of: "_", with: " ").lowercased()
        return pretty.isEmpty ? "Advanced node setting." : "Advanced: \(pretty)."
    }

    init() {
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 840, height: 560),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered, defer: false)
        win.title = "Wyrdsekai — Settings"
        win.center()
        win.setFrameAutosaveName("WyrdsekaiSettings")
        super.init(window: win)
        buildUI(in: win.contentView!)
    }

    required init?(coder: NSCoder) { fatalError("no coder") }

    private func buildUI(in v: NSView) {
        let note = NSTextField(wrappingLabelWithString:
            "These are node settings (ports, inference, relay, household sharing). Edit a Value and " +
            "click “Save + Restart”. Your companion's voice, autonomy and bonds live inside the world.")
        note.textColor = .labelColor
        note.frame = NSRect(x: 16, y: 506, width: 808, height: 44)
        note.autoresizingMask = [.width, .minYMargin]
        v.addSubview(note)

        let scroll = NSScrollView(frame: NSRect(x: 16, y: 64, width: 808, height: 432))
        scroll.autoresizingMask = [.width, .height]
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder

        let cKey = NSTableColumn(identifier: .init("key")); cKey.title = "Setting"; cKey.width = 230
        let cVal = NSTableColumn(identifier: .init("val")); cVal.title = "Value"; cVal.width = 200
        let cHelp = NSTableColumn(identifier: .init("help")); cHelp.title = "What it does"; cHelp.width = 350
        table.addTableColumn(cKey); table.addTableColumn(cVal); table.addTableColumn(cHelp)
        table.usesAlternatingRowBackgroundColors = true
        table.rowHeight = 34
        table.dataSource = self
        table.delegate = self
        scroll.documentView = table
        v.addSubview(scroll)

        let save = NSButton(title: "Save + Restart", target: self, action: #selector(saveAndRestart))
        save.bezelStyle = .rounded
        save.keyEquivalent = "\r"
        save.frame = NSRect(x: 686, y: 18, width: 138, height: 32)
        save.autoresizingMask = [.minXMargin, .maxYMargin]
        v.addSubview(save)

        let worldBtn = NSButton(title: "World & persona settings →", target: self, action: #selector(openWorldClicked))
        worldBtn.bezelStyle = .rounded
        worldBtn.frame = NSRect(x: 16, y: 18, width: 230, height: 32)
        worldBtn.autoresizingMask = [.maxXMargin, .maxYMargin]
        v.addSubview(worldBtn)
    }

    func reload() {
        rows.removeAll()
        if let text = try? String(contentsOfFile: Node.systemConf, encoding: .utf8) {
            for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
                let line = raw.trimmingCharacters(in: .whitespaces)
                if line.isEmpty || line.hasPrefix("#") { continue }
                guard let eq = line.firstIndex(of: "=") else { continue }
                let key = String(line[..<eq]).trimmingCharacters(in: .whitespaces)
                let val = String(line[line.index(after: eq)...])
                if key.isEmpty { continue }
                rows.append(ConfRow(key, val, Self.describe(key)))
            }
        }
        table.reloadData()
    }

    // MARK: table data + view

    func numberOfRows(in tableView: NSTableView) -> Int { rows.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard let col = tableColumn else { return nil }
        let r = rows[row]
        let tf: NSTextField
        switch col.identifier.rawValue {
        case "key":
            tf = NSTextField(labelWithString: r.key)
            tf.lineBreakMode = .byTruncatingTail
        case "val":
            tf = NSTextField(string: r.value)
            tf.isEditable = true
            tf.isBordered = true
            tf.bezelStyle = .roundedBezel
            tf.tag = row
            tf.delegate = self
        default:
            tf = NSTextField(wrappingLabelWithString: r.help)
            tf.textColor = .secondaryLabelColor
            tf.font = .systemFont(ofSize: 11)
        }
        return tf
    }

    // Capture value edits back into the model.
    func controlTextDidEndEditing(_ note: Notification) {
        guard let tf = note.object as? NSTextField, tf.tag >= 0, tf.tag < rows.count else { return }
        rows[tf.tag].value = tf.stringValue
    }

    @objc private func saveAndRestart() {
        let pairs = rows.map { "\($0.key)=\($0.value)" }
        Node.applySettings(pairs) { ok in
            let a = NSAlert()
            a.messageText = ok ? "Saved." : "Couldn't save"
            a.informativeText = ok ? "The node is restarting to apply your changes."
                                   : "Authentication was cancelled or a step failed."
            a.runModal()
            if ok { self.window?.close() }
        }
    }

    @objc private func openWorldClicked() { openWorld?() }
}
