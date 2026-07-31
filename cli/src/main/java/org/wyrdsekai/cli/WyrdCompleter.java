package org.wyrdsekai.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;

/**
 * JLine tab completer with dynamic candidates from current room state.
 * Completes commands, directions, and object names contextually.
 */
public class WyrdCompleter implements Completer {

    private static final List<String> SLASH_COMMANDS = List.of(
        "/help", "/login", "/register", "/logout", "/whoami", "/inventory", "/quit"
    );

    private static final List<String> VERBS = List.of(
        "look", "go", "take", "drop", "use", "say", "inventory"
    );

    private final Renderer renderer;

    public WyrdCompleter(Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        var word = line.word();
        var wordIndex = line.wordIndex();
        var words = line.words();

        if (wordIndex == 0) {
            // First word: complete slash commands, verbs, and directions
            for (var cmd : SLASH_COMMANDS) {
                candidates.add(new Candidate(cmd));
            }
            for (var verb : VERBS) {
                candidates.add(new Candidate(verb));
            }
            addDirectionCandidates(candidates);
        } else if (wordIndex == 1 && words.size() > 0) {
            var firstWord = words.get(0).toLowerCase();
            switch (firstWord) {
                case "go" -> addDirectionCandidates(candidates);
                case "take", "get" -> addTakeableObjectCandidates(candidates);
                case "drop" -> addInventoryCandidates(candidates);
                case "use" -> addObjectCandidates(candidates);
            }
        } else if (wordIndex >= 2 && words.size() > 0) {
            var firstWord = words.get(0).toLowerCase();
            // "use <object> on <target>" — suggest "on" keyword
            if (firstWord.equals("use") && wordIndex == 2) {
                candidates.add(new Candidate("on"));
            }
        }
    }

    private void addDirectionCandidates(List<Candidate> candidates) {
        var room = renderer.getCurrentRoom();
        if (room != null && room.exits() != null) {
            for (var exit : room.exits()) {
                candidates.add(new Candidate(exit.direction(), exit.direction(),
                    null, exit.label(), null, null, true));
            }
        }
    }

    private void addObjectCandidates(List<Candidate> candidates) {
        var room = renderer.getCurrentRoom();
        if (room != null && room.objects() != null) {
            for (var obj : room.objects()) {
                candidates.add(new Candidate(obj.name(), obj.name(),
                    null, obj.description(), null, null, true));
            }
        }
    }

    private void addInventoryCandidates(List<Candidate> candidates) {
        var inventory = renderer.getLastInventory();
        if (inventory != null) {
            for (var obj : inventory) {
                candidates.add(new Candidate(obj.name(), obj.name(),
                    null, obj.description(), null, null, true));
            }
        }
    }

    private void addTakeableObjectCandidates(List<Candidate> candidates) {
        var room = renderer.getCurrentRoom();
        if (room != null && room.objects() != null) {
            for (var obj : room.objects()) {
                if (obj.takeable()) {
                    candidates.add(new Candidate(obj.name(), obj.name(),
                        null, obj.description(), null, null, true));
                }
            }
        }
    }
}
