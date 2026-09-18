using System.Diagnostics;
using System.Text.Json;

namespace Wyrdsekai.Tray;

/// <summary>
/// The Windows brainstem: the watcher outside the JVM. Linux has a systemd unit and mac a
/// LaunchDaemon; on Windows the node is a user process and the tray is the thing that is
/// always there beside it, so the tray carries the same loop and writes the same files:
/// <list type="number">
///   <item>touches <c>brainstem/heartbeat</c> every tick so the server can see it is watched;</item>
///   <item>asks <c>/health</c> for status UP; six misses in a row is a hang;</item>
///   <item>before restarting, copies the record to <c>backups/brainstem.world.db.&lt;ts&gt;.bak</c>;</item>
///   <item>restarts through <c>wyrd restart</c>, never by killing the process;</item>
///   <item>waits out a grace period after any start so a slow boot is not a hang;</item>
///   <item>appends every decision to <c>brainstem/events.jsonl</c>, which the server turns into marks.</item>
/// </list>
/// A node the user stopped on purpose is not restarted: the tray only acts on a node it
/// saw answering since the last start it knows about.
/// </summary>
internal sealed class Brainstem
{
    private const int MissesBeforeRestart = 6;          // × the tray's 4 s poll ≈ 24 s
    private static readonly TimeSpan Grace = TimeSpan.FromSeconds(180);
    private static readonly TimeSpan RestartTimeout = TimeSpan.FromMinutes(5);

    private readonly string _dir = Path.Combine(NodeController.DataDir, "brainstem");
    private int _misses;
    private bool _sawUp;
    private bool _hang;
    private bool _restarting;
    private DateTime _activeSince = DateTime.UtcNow;
    private DateTime? _downSince;

    /// <summary>Call after any start the tray itself issued, so the grace period restarts.</summary>
    public void NoteStarted()
    {
        _activeSince = DateTime.UtcNow;
        _misses = 0;
        _sawUp = false;
    }

    /// <summary>One pulse. Never throws; the tray's poll must not die on a file error.</summary>
    public async Task TickAsync(bool healthy)
    {
        // The poll timer fires again while a restart is still running. Only the tick that
        // started the restart may clear the flag; the first live test cleared it from a tick
        // that had merely returned early, and the node was snapshotted and restarted twice.
        var mine = false;
        try
        {
            Directory.CreateDirectory(_dir);
            File.SetLastWriteTimeUtc(Touch(Path.Combine(_dir, "heartbeat")), DateTime.UtcNow);

            if (healthy)
            {
                if (_hang && _downSince is { } since)
                {
                    Event("recovered", $"after {(int)(DateTime.UtcNow - since).TotalSeconds} s without an answer", null);
                }
                _hang = false;
                _downSince = null;
                _misses = 0;
                _sawUp = true;
                return;
            }

            if (_restarting) return;
            if (!_sawUp) return;                                   // stopped on purpose, or never started
            if (DateTime.UtcNow - _activeSince < Grace) return;    // still booting
            _downSince ??= DateTime.UtcNow;
            _misses++;
            if (_misses < MissesBeforeRestart) return;

            _hang = true;
            _restarting = true;
            mine = true;
            var snap = Snapshot();
            var seconds = _misses * 4;
            var code = await NodeController.RunWyrdAsync(RestartTimeout, "restart");
            if (code == 0)
            {
                Event("restarted", $"the server was up but did not answer for {seconds} s; restarted after a snapshot", snap);
                NoteStarted();
                _sawUp = true;   // so a second hang after this restart is also caught
            }
            else
            {
                Event("restart-failed", $"the server did not answer for {seconds} s and the restart command failed", snap);
                _misses = 0;     // count again before trying once more
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"brainstem tick: {ex}");
        }
        finally
        {
            if (mine) _restarting = false;
        }
    }

    private static string Touch(string path)
    {
        if (!File.Exists(path)) File.WriteAllText(path, "");
        return path;
    }

    /// <summary>
    /// A copy of the record before the restart. sqlite3 is not on Windows; the copy is the
    /// database file with its write-ahead log beside it, which SQLite reads back as one.
    /// </summary>
    private static string? Snapshot()
    {
        try
        {
            var db = Path.Combine(NodeController.DataDir, "world.db");
            if (!File.Exists(db)) return null;
            var backups = Path.Combine(NodeController.DataDir, "backups");
            Directory.CreateDirectory(backups);
            var stamp = DateTime.UtcNow.ToString("yyyyMMdd-HHmmss");
            var dest = Path.Combine(backups, $"brainstem.world.db.{stamp}.bak");
            File.Copy(db, dest, overwrite: true);
            if (File.Exists(db + "-wal")) File.Copy(db + "-wal", dest + "-wal", overwrite: true);
            return dest;
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"brainstem snapshot: {ex}");
            return null;
        }
    }

    private void Event(string name, string reason, string? snapshot)
    {
        var line = JsonSerializer.Serialize(new
        {
            ts = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"),
            @event = name,
            reason,
            snapshot = snapshot ?? "none",
        });
        File.AppendAllText(Path.Combine(_dir, "events.jsonl"), line + "\n");
    }
}
