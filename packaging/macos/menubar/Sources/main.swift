// Wyrdsekai macOS menu-bar app — the friendly front door (#1285).
//
// A status-bar control panel + WKWebView world window + first-run onboarding
// wizard, mirroring the Windows tray shell. macOS differences baked in:
//   • the node runs as a root LaunchDaemon, so start/stop/restart + editing the
//     system conf + uninstall go through ONE native auth prompt each (Touch ID
//     / password) via `osascript … with administrator privileges`;
//   • multi-step privileged work lives in bundled helper scripts so each action
//     is a single prompt;
//   • inference is already set up by the .pkg (dual-MLX), so onboarding is fast.

import Cocoa
import WebKit

// MARK: - Node: talks to the local node + the bundled `wyrd` CLI / helper scripts.

enum Node {
    static let baseURL = "http://localhost:7070"
    static var appURL: String { baseURL + "/app" }
    static let wyrd = "/usr/local/bin/wyrd"
    static let scriptsDir = "/usr/local/wyrdsekai/scripts"
    static let systemConf = "/etc/wyrdsekai/wyrdsekai.conf"
    static let serverSvc = "system/com.wyrdsekai.server"
    static let serverPlist = "/Library/LaunchDaemons/com.wyrdsekai.server.plist"

    static var dataDir: String {
        (NSHomeDirectory() as NSString).appendingPathComponent(".wyrdsekai")
    }

    /// First run (or never-named) → show the wizard. The system conf gains a
    /// WYRDSEKAI_COMPANION_NAME line once onboarding completes.
    static func needsOnboarding() -> Bool {
        guard let text = try? String(contentsOfFile: systemConf, encoding: .utf8) else { return true }
        return !text.contains("WYRDSEKAI_COMPANION_NAME=")
    }

    /// True once the local node answers on :7070.
    static func isRunning(_ done: @escaping (Bool) -> Void) {
        guard let url = URL(string: baseURL + "/") else {
            DispatchQueue.main.async { done(false) }; return
        }
        var req = URLRequest(url: url); req.timeoutInterval = 4
        URLSession.shared.dataTask(with: req) { _, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 500
            let ok = (err == nil) && code < 500
            DispatchQueue.main.async { done(ok) }
        }.resume()
    }

    /// True once the local drive model answers on :8200 with a 200. mlx_lm.server
    /// loads the model weights BEFORE it starts serving /v1/models, so a 200 here
    /// means generation actually works. Used to gate first-run world-entry behind a
    /// warm brain — so the user is never dropped into the world (to register/talk)
    /// before the companion's local model has finished downloading + loading.
    static func inferenceReady(_ done: @escaping (Bool) -> Void) {
        guard let url = URL(string: "http://127.0.0.1:8200/v1/models") else {
            DispatchQueue.main.async { done(false) }; return
        }
        var req = URLRequest(url: url); req.timeoutInterval = 4
        URLSession.shared.dataTask(with: req) { data, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 500
            let ok = (err == nil) && code == 200 && (data?.count ?? 0) > 0
            DispatchQueue.main.async { done(ok) }
        }.resume()
    }

    // MARK: privileged actions — one native auth prompt each

    static func onboard(name: String, lang: String, mode: String, apiKey: String,
                        done: @escaping (Bool) -> Void) {
        let cmd = "\(scriptsDir)/gui-onboard.sh \(shq(name)) \(shq(lang)) \(shq(mode)) \(shq(apiKey))"
        runAdmin(cmd, done: done)
    }

    static func applySettings(_ pairs: [String], done: @escaping (Bool) -> Void) {
        let cmd = (["\(scriptsDir)/gui-apply-settings.sh"] + pairs.map(shq)).joined(separator: " ")
        runAdmin(cmd, done: done)
    }

    static func uninstall(wipe: Bool, done: @escaping (Bool) -> Void) {
        let cmd = "\(scriptsDir)/gui-uninstall.sh " + (wipe ? "--wipe-data" : "")
        runAdmin(cmd, done: done)
    }

    static func restartNode(done: @escaping (Bool) -> Void) {
        runAdmin("/bin/launchctl kickstart -k \(serverSvc)", done: done)
    }
    static func stopNode(done: @escaping (Bool) -> Void) {
        runAdmin("/bin/launchctl bootout \(serverSvc)", done: done)
    }
    static func startNode(done: @escaping (Bool) -> Void) {
        runAdmin("/bin/launchctl enable \(serverSvc); /bin/launchctl bootstrap system \(serverPlist); /bin/launchctl kickstart -k \(serverSvc)", done: done)
    }

    // MARK: user-context actions (no auth)

    /// Inference on Apple Silicon is the user's MLX LaunchAgent — restart it in
    /// user context, no admin needed.
    static func runWyrd(_ args: [String], timeout: TimeInterval = 1800, done: ((Int32) -> Void)? = nil) {
        DispatchQueue.global().async {
            let code = runProcess(wyrd, args, timeout: timeout)
            if let done = done { DispatchQueue.main.async { done(code) } }
        }
    }

    static func openDataFolder() { NSWorkspace.shared.open(URL(fileURLWithPath: dataDir)) }
    static func openLogs() {
        let log = (dataDir as NSString).appendingPathComponent("server.log")
        if FileManager.default.fileExists(atPath: log) {
            NSWorkspace.shared.open(URL(fileURLWithPath: log))
        } else { openDataFolder() }
    }

    /// Remove this app's own per-user LaunchAgent (called after uninstall, in
    /// user context, since the root helper can't reach ~/Library cleanly).
    static func removeOwnAgents() {
        let agents = (NSHomeDirectory() as NSString).appendingPathComponent("Library/LaunchAgents")
        for label in ["com.wyrdsekai.menubar", "com.wyrdsekai.mlx-voice"] {
            let plist = (agents as NSString).appendingPathComponent("\(label).plist")
            try? FileManager.default.removeItem(atPath: plist)
        }
    }

    // MARK: helpers

    /// Run a privileged shell command behind a single native auth prompt.
    static func runAdmin(_ shell: String, done: @escaping (Bool) -> Void) {
        DispatchQueue.global().async {
            let escaped = shell
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
            let script = "do shell script \"\(escaped)\" with administrator privileges"
            let code = runProcess("/usr/bin/osascript", ["-e", script], timeout: 600)
            DispatchQueue.main.async { done(code == 0) }
        }
    }

    /// POSIX single-quote a value for embedding in a command string.
    static func shq(_ s: String) -> String {
        "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func runProcess(_ path: String, _ args: [String], timeout: TimeInterval) -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let outPipe = Pipe(), errPipe = Pipe(), inPipe = Pipe()
        p.standardOutput = outPipe; p.standardError = errPipe; p.standardInput = inPipe
        do { try p.run() } catch { return -1 }
        inPipe.fileHandleForWriting.closeFile()  // EOF on stdin → no prompt hang
        let drain = DispatchQueue(label: "wyrd.drain")
        drain.async { _ = outPipe.fileHandleForReading.readDataToEndOfFile() }
        drain.async { _ = errPipe.fileHandleForReading.readDataToEndOfFile() }
        let timer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: timer)
        p.waitUntilExit()
        timer.cancel()
        return p.terminationStatus
    }
}

// MARK: - AppDelegate: menu-bar control panel + lifecycle.

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private let statusMenuItem = NSMenuItem(title: "Node: checking…", action: nil, keyEquivalent: "")
    private var pollTimer: Timer?

    private var world: WorldWindowController?
    private var onboarding: OnboardingWindowController?
    private var settings: SettingsWindowController?

    func applicationDidFinishLaunching(_ note: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            // Brand glyph (torii + cats), bundled as a template PNG in Resources
            // (menubar-icon.png + @2x). isTemplate → macOS renders it monochrome, adapting
            // to light/dark menu bars. Fall back to an SF Symbol if the asset is missing.
            if let logo = NSImage(named: NSImage.Name("menubar-icon")) {
                logo.isTemplate = true
                logo.size = NSSize(width: 18, height: 18)
                button.image = logo
            } else {
                button.image = NSImage(systemSymbolName: "sparkles", accessibilityDescription: "Wyrdsekai")
                button.image?.isTemplate = true
            }
        }
        statusItem.menu = buildMenu()

        pollTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in
            self?.refreshStatus()
        }
        refreshStatus()
        beginStartup()
    }

    // This is an LSUIElement (menu-bar-only) app, so double-clicking it in /Applications —
    // or clicking it in the Dock when it's already running via the login agent — sends a
    // "reopen" with no window to show, which otherwise looks like nothing happened. Surface
    // the world (or resume onboarding if the node was never set up) so the click does something.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if Node.needsOnboarding() { showOnboarding() } else { enterWorld() }
        return true
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()
        menu.addItem(withTitle: "Enter World", action: #selector(enterWorld), keyEquivalent: "").target = self
        menu.addItem(.separator())

        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)
        menu.addItem(withTitle: "Restart node", action: #selector(restartNode), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Stop node", action: #selector(stopNode), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Start node", action: #selector(startNode), keyEquivalent: "").target = self

        let services = NSMenuItem(title: "Services", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        sub.addItem(withTitle: "Restart inference", action: #selector(restartInference), keyEquivalent: "").target = self
        sub.addItem(withTitle: "Stop inference", action: #selector(stopInference), keyEquivalent: "").target = self
        services.submenu = sub
        menu.addItem(services)

        menu.addItem(.separator())
        menu.addItem(withTitle: "Settings…", action: #selector(openSettings), keyEquivalent: ",").target = self
        menu.addItem(withTitle: "Open data folder", action: #selector(openData), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Open logs", action: #selector(openLogs), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Join a household…", action: #selector(joinHousehold), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Uninstall…", action: #selector(uninstall), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Quit", action: #selector(quit), keyEquivalent: "q").target = self
        return menu
    }

    // MARK: startup

    private func beginStartup() {
        if Node.needsOnboarding() {
            showOnboarding()
        } else {
            Node.isRunning { running in
                if !running { Node.startNode { _ in self.waitForNodeThenEnter() } }
                else { self.enterWorld() }
            }
        }
    }

    private func waitForNodeThenEnter(_ tries: Int = 0) {
        if tries > 60 { enterWorld(); return }   // ~90s
        Node.isRunning { running in
            if running { self.enterWorld() }
            else { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.waitForNodeThenEnter(tries + 1) } }
        }
    }

    private func refreshStatus() {
        Node.isRunning { running in
            self.statusMenuItem.title = running ? "Node: ● Running" : "Node: ○ Stopped"
        }
    }

    // MARK: actions

    @objc private func enterWorld() {
        if world == nil { world = WorldWindowController() }
        world?.showWindow(nil)
        world?.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private func showOnboarding() {
        // Idempotent: if the onboarding window is already up, just bring it forward
        // instead of stacking a SECOND "create your companion" window. On a fresh
        // install BOTH beginStartup() (app launch) AND applicationShouldHandleReopen()
        // (the installer's `open -a` / dock re-activation of the menu-bar login agent)
        // call this — which previously created two identical windows one behind the
        // other that the user had to close by hand.
        if let existing = onboarding {
            existing.showWindow(nil)
            existing.window?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        onboarding = OnboardingWindowController { [weak self] in
            self?.onboarding = nil
            self?.waitForNodeThenEnter()
        }
        onboarding?.showWindow(nil)
        onboarding?.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func openSettings() {
        if settings == nil {
            settings = SettingsWindowController()
            settings?.openWorld = { [weak self] in self?.enterWorld() }
        }
        settings?.reload()
        settings?.showWindow(nil)
        settings?.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func restartNode() { Node.restartNode { _ in self.refreshStatus() } }
    @objc private func stopNode()    { Node.stopNode    { _ in self.refreshStatus() } }
    @objc private func startNode()   { Node.startNode   { _ in self.refreshStatus() } }
    @objc private func restartInference() { Node.runWyrd(["inference", "start"], timeout: 600) }
    @objc private func stopInference()    { Node.runWyrd(["inference", "stop"], timeout: 120) }
    @objc private func openData() { Node.openDataFolder() }
    @objc private func openLogs() { Node.openLogs() }

    // Join this node to a household over a relay: paste the invite token, then
    // run `wyrd relay join <token>` (which redeems it, pins the CA fingerprint,
    // writes the relay legs, and supervises a restart). No relay/zone fiddling.
    @objc private func joinHousehold() {
        let alert = NSAlert()
        alert.messageText = "Join a household"
        alert.informativeText = "Paste the invitation token you were given:"
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 360, height: 24))
        field.placeholderString = "wyrdjoin://relay.example.com:4443/CODE.fingerprint"
        alert.accessoryView = field
        alert.addButton(withTitle: "Join")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let token = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }
        Node.runWyrd(["relay", "join", token]) { code in
            let done = NSAlert()
            if code == 0 {
                done.messageText = "Joined the household"
                done.informativeText = "This node is reconnecting through the relay."
            } else {
                done.messageText = "Couldn't join"
                done.informativeText = "Check the token and try again (exit \(code))."
            }
            done.runModal()
        }
    }

    @objc private func uninstall() {
        let a = NSAlert()
        a.messageText = "Uninstall Wyrdsekai?"
        a.informativeText = "This stops the node and removes the program. You'll be asked for your password (or Touch ID)."
        a.alertStyle = .warning
        a.addButton(withTitle: "Uninstall")            // .alertFirstButtonReturn
        a.addButton(withTitle: "Uninstall + delete my data")  // .alertSecondButtonReturn
        a.addButton(withTitle: "Cancel")               // .alertThirdButtonReturn
        let resp = a.runModal()
        if resp == .alertThirdButtonReturn { return }
        let wipe = (resp == .alertSecondButtonReturn)
        if wipe {
            let c = NSAlert()
            c.messageText = "Permanently delete your world data?"
            c.informativeText = "Your companion, memories, bonds and settings in \(Node.dataDir) will be erased and cannot be recovered."
            c.alertStyle = .critical
            c.addButton(withTitle: "Delete everything")
            c.addButton(withTitle: "Cancel")
            if c.runModal() != .alertFirstButtonReturn { return }
        }
        Node.uninstall(wipe: wipe) { ok in
            Node.removeOwnAgents()
            let r = NSAlert()
            r.messageText = ok ? "Wyrdsekai removed." : "Uninstall may be incomplete."
            r.informativeText = ok ? "Thanks for trying it." : "Some files may remain; check \(Node.dataDir)."
            r.runModal()
            self.quit()
        }
    }

    @objc private func quit() {
        pollTimer?.invalidate()
        NSStatusBar.system.removeStatusItem(statusItem)
        NSApp.terminate(nil)
    }
}

// MARK: - entry point

let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menu-bar agent (no Dock icon)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
