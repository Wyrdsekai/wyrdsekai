#!/usr/bin/env python3
# Wyrdsekai Linux desktop — the friendly front door (#1286).
#
# A system-tray (AppIndicator) control panel + first-run onboarding wizard +
# settings editor, mirroring the Windows tray / macOS menu-bar shells. Linux
# specifics baked in:
#   • the node runs as a root systemd service (`wyrdsekai`), so start/stop/
#     restart + editing /etc/wyrdsekai/wyrdsekai.conf + uninstall go through ONE
#     PolicyKit auth prompt each via `pkexec` (the native GUI auth);
#   • multi-step privileged work lives in bundled helper scripts;
#   • "Enter World" opens the default browser at :7070/app (idiomatic on Linux).
#
# Launch modes (used by the .desktop files):
#   wyrdsekai-desktop            → normal (tray + onboarding/enter-world)
#   wyrdsekai-desktop --settings → open the Settings window
#   wyrdsekai-desktop --world    → just open the world

import gi
import os
import sys
import subprocess
import threading
import urllib.request

gi.require_version("Gtk", "3.0")
gi.require_version("Pango", "1.0")
from gi.repository import Gtk, GLib, Pango  # noqa: E402

# AppIndicator (Ayatana preferred, classic fallback). Optional — without it the
# tray is absent but the wizard / settings / world still work via the launchers.
AppIndicator3 = None
try:
    gi.require_version("AyatanaAppIndicator3", "0.1")
    from gi.repository import AyatanaAppIndicator3 as AppIndicator3
except (ValueError, ImportError):
    try:
        gi.require_version("AppIndicator3", "0.1")
        from gi.repository import AppIndicator3
    except (ValueError, ImportError):
        AppIndicator3 = None

WYRD = "/usr/local/bin/wyrd"
HELPERS = "/opt/wyrdsekai/desktop/helpers"
SYSCONF = "/etc/wyrdsekai/wyrdsekai.conf"
DATADIR = "/var/lib/wyrdsekai"
BASEURL = "http://localhost:7070"
APPURL = BASEURL + "/app"
SVC = "wyrdsekai"

# Curated help for the keys the installers seed; readable fallback otherwise.
HELP = {
    "WYRDSEKAI_PORT": "Web + world port. Open the world at http://localhost:<port>/app. Default 7070.",
    "WYRDSEKAI_LANG": "Interface language: en, es, or ja.",
    "WYRDSEKAI_LOCALE": "Interface language/locale: en, es, or ja.",
    "WYRDSEKAI_NODE_NAME": "This node's name on your household network.",
    "WYRDSEKAI_COMPANION_NAME": "Your companion's born-with-it name. Shapes the soul at first boot.",
    "WYRDSEKAI_DATA_DIR": "Where your world lives: companion, memories, config, keys (/var/lib/wyrdsekai).",
    "WYRDSEKAI_ZONE_ID": "The world (zone) this node belongs to.",
    "WYRDSEKAI_BETWEEN_ENABLED": "Enable the encrypted mesh that links household nodes.",
    "WYRDSEKAI_NATS_URL": "Internal message bus address (NATS). Default nats://127.0.0.1:4222.",
    "WYRDSEKAI_INFERENCE_MODE": "Where the companion thinks: local, cloud, or zone.",
    "WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE": "Offer this machine's GPU to household members (true/false).",
    "WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW": "Borrow a household peer's GPU when this machine has none (true/false).",
    "WYRDSEKAI_LLAMA_ENABLED": "Run the local model server on this machine.",
    "WYRDSEKAI_LLAMA_URL": "Address of the local skills model. Default http://127.0.0.1:8200.",
    "WYRDSEKAI_VOICE_ENABLED": "Run the smaller 'voice' model that shapes how replies sound.",
    "WYRDSEKAI_RELAY_ENABLED": "Run behind a relay so phones / remote clients can reach this node.",
    "WYRDSEKAI_RELAY_URL": "Address of the relay this node homes on.",
    "WYRDSEKAI_RELAY_REGISTRATION_URL": "The relay this node homes on.",
    "ANTHROPIC_API_KEY": "Cloud API key (used only when 'how it thinks' = Cloud).",
}


def describe(key):
    if key in HELP:
        return HELP[key]
    if key.startswith("WYRDSEKAI_RELAY_LEG"):
        return "One relay 'leg' this node homes on (a zone can use several relays at once)."
    pretty = key[len("WYRDSEKAI_"):] if key.startswith("WYRDSEKAI_") else key
    pretty = pretty.replace("_", " ").lower().strip()
    return "Advanced node setting." if not pretty else "Advanced: %s." % pretty


# ── node helpers ──────────────────────────────────────────────────────────

def needs_onboarding():
    try:
        with open(SYSCONF) as f:
            return "WYRDSEKAI_COMPANION_NAME=" not in f.read()
    except OSError:
        return True


def is_running():
    try:
        urllib.request.urlopen(BASEURL + "/", timeout=4)
        return True
    except Exception:
        return False


def run_bg(fn, cb=None):
    def worker():
        r = fn()
        if cb:
            GLib.idle_add(cb, r)
    threading.Thread(target=worker, daemon=True).start()


def run_proc(args, timeout=1800):
    """Run a child; stdin closed (no prompt hang), output drained, timeout-kill."""
    try:
        p = subprocess.run(args, stdin=subprocess.DEVNULL,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout)
        return p.returncode
    except subprocess.TimeoutExpired:
        return -2
    except Exception:
        return -1


def pkexec(args, timeout=600):
    """Run a privileged command behind one PolicyKit auth prompt."""
    return run_proc(["pkexec"] + args, timeout=timeout)


def open_url(url):
    subprocess.Popen(["xdg-open", url])


def open_path(path):
    subprocess.Popen(["xdg-open", path])


# privileged actions — one auth prompt each
def onboard(name, lang, mode, key):
    return pkexec([os.path.join(HELPERS, "gui-onboard.sh"), name, lang, mode, key])


def apply_settings(pairs):
    return pkexec([os.path.join(HELPERS, "gui-apply-settings.sh")] + pairs)


def do_uninstall(wipe):
    return pkexec([os.path.join(HELPERS, "gui-uninstall.sh")] + (["--wipe-data"] if wipe else []))


def restart_node():
    return pkexec(["systemctl", "restart", SVC])


def stop_node():
    return pkexec(["systemctl", "stop", SVC])


def start_node():
    return pkexec(["systemctl", "start", SVC])


# ── app ───────────────────────────────────────────────────────────────────

class App:
    def __init__(self, mode="normal"):
        self.status_item = Gtk.MenuItem(label="Node: checking…")
        self.status_item.set_sensitive(False)
        self.settings_win = None
        self.onboard_win = None

        self.ind = None
        if AppIndicator3:
            self.ind = AppIndicator3.Indicator.new(
                "wyrdsekai", "applications-internet",
                AppIndicator3.IndicatorCategory.APPLICATION_STATUS)
            self.ind.set_status(AppIndicator3.IndicatorStatus.ACTIVE)
            self.ind.set_title("Wyrdsekai")
            self.ind.set_menu(self._menu())

        GLib.timeout_add_seconds(4, self._refresh)
        self._refresh()

        if mode == "settings":
            self.open_settings()
        elif mode == "world":
            self.enter_world()
        else:
            self._begin_startup()

    def _menu(self):
        m = Gtk.Menu()

        def item(label, cb):
            it = Gtk.MenuItem(label=label)
            it.connect("activate", cb)
            m.append(it)
            return it

        item("Enter World", lambda *_: self.enter_world())
        m.append(Gtk.SeparatorMenuItem())
        m.append(self.status_item)
        item("Restart node", lambda *_: run_bg(restart_node, lambda *_: self._refresh()))
        item("Stop node", lambda *_: run_bg(stop_node, lambda *_: self._refresh()))
        item("Start node", lambda *_: run_bg(start_node, lambda *_: self._refresh()))

        services = Gtk.MenuItem(label="Services")
        sub = Gtk.Menu()
        for label, args in (("Restart inference", ["inference", "start"]),
                            ("Stop inference", ["inference", "stop"])):
            si = Gtk.MenuItem(label=label)
            si.connect("activate", lambda _w, a=args: run_bg(lambda: run_proc([WYRD] + a, timeout=600)))
            sub.append(si)
        services.set_submenu(sub)
        m.append(services)

        m.append(Gtk.SeparatorMenuItem())
        item("Settings…", lambda *_: self.open_settings())
        item("Open data folder", lambda *_: open_path(DATADIR))
        item("Open logs", lambda *_: subprocess.Popen(["x-terminal-emulator", "-e",
                                                       "journalctl", "-u", SVC, "-n", "200", "-f"]))
        m.append(Gtk.SeparatorMenuItem())
        item("Uninstall…", lambda *_: self.uninstall())
        item("Quit", lambda *_: Gtk.main_quit())
        m.show_all()
        return m

    # startup
    def _begin_startup(self):
        if needs_onboarding():
            self.open_onboarding()
        else:
            def go():
                if not is_running():
                    start_node()
                    for _ in range(60):
                        if is_running():
                            break
                        GLib.usleep(1500 * 1000)
                return True
            run_bg(go, lambda *_: self.enter_world())

    def _refresh(self):
        run_bg(is_running, lambda r: self.status_item.set_label(
            "Node: ● Running" if r else "Node: ○ Stopped"))
        return True

    def enter_world(self):
        open_url(APPURL)

    def open_settings(self):
        if self.settings_win is None:
            self.settings_win = SettingsWindow(self)
            self.settings_win.connect("destroy", lambda *_: setattr(self, "settings_win", None))
        self.settings_win.reload()
        self.settings_win.show_all()
        self.settings_win.present()

    def open_onboarding(self):
        if self.onboard_win is None:
            self.onboard_win = OnboardingWindow(self)
            self.onboard_win.connect("destroy", lambda *_: setattr(self, "onboard_win", None))
        self.onboard_win.show_all()
        self.onboard_win.present()

    def uninstall(self):
        d = Gtk.MessageDialog(transient_for=None, modal=True,
                              message_type=Gtk.MessageType.WARNING,
                              text="Uninstall Wyrdsekai?")
        d.format_secondary_text(
            "This stops the node and removes the program. You'll be asked to authenticate.")
        d.add_button("Cancel", Gtk.ResponseType.CANCEL)
        d.add_button("Uninstall", 100)
        d.add_button("Uninstall + delete my data", 101)
        resp = d.run()
        d.destroy()
        if resp not in (100, 101):
            return
        wipe = (resp == 101)
        if wipe:
            c = Gtk.MessageDialog(transient_for=None, modal=True,
                                  message_type=Gtk.MessageType.WARNING,
                                  text="Permanently delete your world data?")
            c.format_secondary_text(
                "Your companion, memories, bonds and settings in %s will be erased "
                "and cannot be recovered." % DATADIR)
            c.add_button("Cancel", Gtk.ResponseType.CANCEL)
            c.add_button("Delete everything", 1)
            r2 = c.run()
            c.destroy()
            if r2 != 1:
                return

        def done(code):
            m = Gtk.MessageDialog(transient_for=None, modal=True,
                                  message_type=Gtk.MessageType.INFO,
                                  buttons=Gtk.ButtonsType.OK,
                                  text="Wyrdsekai removed." if code == 0 else "Uninstall may be incomplete.")
            m.run()
            m.destroy()
            Gtk.main_quit()
        run_bg(lambda: do_uninstall(wipe), done)


class OnboardingWindow(Gtk.Window):
    def __init__(self, app):
        super().__init__(title="Welcome to Wyrdsekai")
        self.app = app
        self.set_default_size(560, 420)
        self.set_border_width(20)
        self.set_position(Gtk.WindowPosition.CENTER)

        grid = Gtk.Grid(row_spacing=12, column_spacing=12)
        self.add(grid)

        intro = Gtk.Label(xalign=0)
        intro.set_markup("<span foreground='gray'>Name your companion and choose how it thinks.\n"
                         "You can change these later from the tray icon.</span>")
        grid.attach(intro, 0, 0, 2, 1)

        def row(label, widget, r):
            l = Gtk.Label(label=label, xalign=0)
            grid.attach(l, 0, r, 1, 1)
            widget.set_hexpand(True)
            grid.attach(widget, 1, r, 1, 1)

        self.name = Gtk.Entry(text="Wyrd")
        row("Companion name", self.name, 1)

        self.lang = Gtk.ComboBoxText()
        for t in ("English (en)", "Español (es)", "日本語 (ja)"):
            self.lang.append_text(t)
        self.lang.set_active(0)
        row("Language", self.lang, 2)

        self.mode = Gtk.ComboBoxText()
        for t in ("Local model (on this machine)", "Cloud API key — hosted model", "Decide later"):
            self.mode.append_text(t)
        self.mode.set_active(0)
        self.mode.connect("changed", self._mode_changed)
        row("How it thinks", self.mode, 3)

        self.key = Gtk.Entry(visibility=False, placeholder_text="sk-ant-…", sensitive=False)
        row("API key", self.key, 4)

        self.spinner = Gtk.Spinner()
        self.status = Gtk.Label(label="", xalign=0)
        self.status.get_style_context().add_class("dim-label")
        box = Gtk.Box(spacing=8)
        box.pack_start(self.spinner, False, False, 0)
        box.pack_start(self.status, True, True, 0)
        grid.attach(box, 0, 5, 2, 1)

        self.create_btn = Gtk.Button(label="Create my companion")
        self.create_btn.get_style_context().add_class("suggested-action")
        self.create_btn.connect("clicked", self._create)
        btnbox = Gtk.Box()
        btnbox.set_halign(Gtk.Align.END)
        btnbox.pack_start(self.create_btn, False, False, 0)
        grid.attach(btnbox, 0, 6, 2, 1)

    def _mode_changed(self, *_):
        self.key.set_sensitive(self.mode.get_active() == 1)

    def _lang_code(self):
        return ("en", "es", "ja")[self.lang.get_active()]

    def _mode_code(self):
        return ("local", "cloud", "later")[self.mode.get_active()]

    def _busy(self, busy, msg=""):
        self.create_btn.set_sensitive(not busy)
        for w in (self.name, self.lang, self.mode):
            w.set_sensitive(not busy)
        self.key.set_sensitive(not busy and self.mode.get_active() == 1)
        self.status.set_text(msg)
        if busy:
            self.spinner.start()
        else:
            self.spinner.stop()

    def _create(self, *_):
        name = self.name.get_text().strip() or "Wyrd"
        lang = self._lang_code()
        mode = self._mode_code()
        key = self.key.get_text().strip()
        self._busy(True, "Step 1 of 2: applying your choices… (you'll be asked to authenticate)")

        def done(code):
            if code != 0:
                self._busy(False)
                d = Gtk.MessageDialog(transient_for=self, modal=True,
                                      message_type=Gtk.MessageType.ERROR,
                                      buttons=Gtk.ButtonsType.OK,
                                      text="Setup didn't complete")
                d.format_secondary_text("Authentication was cancelled or a step failed. You can try again.")
                d.run()
                d.destroy()
                return
            self.status.set_text("Step 2 of 2: waking %s…" % name)

            def wait_then_world():
                for _ in range(60):
                    if is_running():
                        break
                    GLib.usleep(1500 * 1000)
                return True
            run_bg(wait_then_world, lambda *_: (self.app.enter_world(), self.destroy()))

        run_bg(lambda: onboard(name, lang, mode, key), done)


class SettingsWindow(Gtk.Window):
    def __init__(self, app):
        super().__init__(title="Wyrdsekai — Settings")
        self.app = app
        self.set_default_size(840, 560)
        self.set_border_width(12)
        self.set_position(Gtk.WindowPosition.CENTER)

        vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.add(vbox)

        note = Gtk.Label(xalign=0)
        note.set_line_wrap(True)
        note.set_text("These are node settings (ports, inference, relay, household sharing). "
                      "Edit a Value and click “Save + Restart”. Your companion's voice, autonomy "
                      "and bonds live inside the world.")
        vbox.pack_start(note, False, False, 0)

        self.store = Gtk.ListStore(str, str, str)
        tv = Gtk.TreeView(model=self.store)
        c1 = Gtk.TreeViewColumn("Setting", Gtk.CellRendererText(), text=0)
        c1.set_min_width(230)
        tv.append_column(c1)
        rval = Gtk.CellRendererText()
        rval.set_property("editable", True)
        rval.connect("edited", self._on_edit)
        c2 = Gtk.TreeViewColumn("Value", rval, text=1)
        c2.set_min_width(180)
        tv.append_column(c2)
        rhelp = Gtk.CellRendererText()
        rhelp.set_property("foreground", "gray")
        rhelp.set_property("wrap-mode", Pango.WrapMode.WORD)
        rhelp.set_property("wrap-width", 340)
        tv.append_column(Gtk.TreeViewColumn("What it does", rhelp, text=2))

        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.add(tv)
        vbox.pack_start(scroll, True, True, 0)

        bar = Gtk.Box(spacing=8)
        world_btn = Gtk.Button(label="World & persona settings →")
        world_btn.connect("clicked", lambda *_: self.app.enter_world())
        bar.pack_start(world_btn, False, False, 0)
        save = Gtk.Button(label="Save + Restart")
        save.get_style_context().add_class("suggested-action")
        save.connect("clicked", self._save)
        bar.pack_end(save, False, False, 0)
        vbox.pack_start(bar, False, False, 0)

    def reload(self):
        self.store.clear()
        try:
            with open(SYSCONF) as f:
                for raw in f:
                    line = raw.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, val = line.split("=", 1)
                    key = key.strip()
                    if key:
                        self.store.append([key, val, describe(key)])
        except OSError:
            pass

    def _on_edit(self, _renderer, path, newtext):
        self.store[path][1] = newtext

    def _save(self, *_):
        pairs = ["%s=%s" % (row[0], row[1]) for row in self.store]

        def done(code):
            d = Gtk.MessageDialog(transient_for=self, modal=True,
                                  message_type=Gtk.MessageType.INFO,
                                  buttons=Gtk.ButtonsType.OK,
                                  text="Saved." if code == 0 else "Couldn't save")
            d.format_secondary_text("The node is restarting to apply your changes."
                                    if code == 0 else "Authentication was cancelled or a step failed.")
            d.run()
            d.destroy()
            if code == 0:
                self.destroy()
        run_bg(lambda: apply_settings(pairs), done)


def main():
    mode = "normal"
    if "--settings" in sys.argv:
        mode = "settings"
    elif "--world" in sys.argv:
        mode = "world"
    App(mode)
    Gtk.main()


if __name__ == "__main__":
    main()
