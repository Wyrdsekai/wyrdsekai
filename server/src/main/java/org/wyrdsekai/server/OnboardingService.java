package org.wyrdsekai.server;

import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.util.List;

/**
 * First-run onboarding service (§65).
 * Provides guided introduction hints for new players.
 */
public class OnboardingService {

    public enum OnboardingStage {
        WELCOME,
        EXPLORE,
        INTERACT,
        COMPLETE
    }

    /** Get onboarding hints for a given stage. */
    public List<Hint> hintsForStage(OnboardingStage stage) {
        return hintsForStage(stage, "en");
    }

    /** Get onboarding hints for a given stage and locale. */
    public List<Hint> hintsForStage(OnboardingStage stage, String locale) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        return switch (stage) {
            case WELCOME -> List.of(
                new Hint(catalog.get("onboard.look_around"), "explore_room", "look"),
                new Hint(catalog.get("onboard.say_hello"), "greet", "say:hello"),
                new Hint(catalog.get("onboard.check_exits"), "check_exits", "look")
            );
            case EXPLORE -> List.of(
                new Hint(catalog.get("onboard.go_terminal"), "navigate_terminal", "go:north"),
                new Hint(catalog.get("onboard.go_docks"), "navigate_docks", "go:east"),
                new Hint(catalog.get("onboard.examine_crystal"), "examine_crystal", "use:crystal"),
                new Hint(catalog.get("onboard.talk_companion"), "talk_companion", "say:Who are you?"),
                new Hint(catalog.get("onboard.equip_mode"), "equip_item", "say:equip focused mode")
            );
            case INTERACT -> List.of(
                new Hint(catalog.get("onboard.go_bridge"), "navigate_bridge", "go:up"),
                new Hint(catalog.get("onboard.go_boiler"), "navigate_boiler", "go:down"),
                new Hint(catalog.get("onboard.ask_world"), "ask_world", "say:Tell me about this world"),
                new Hint(catalog.get("onboard.go_library"), "navigate_library", "go:southeast"),
                new Hint(catalog.get("onboard.use_draught"), "use_item", "say:use restoring draught")
            );
            case COMPLETE -> List.of(
                new Hint(catalog.get("onboard.browse_library"), "browse_library", "say:catalog"),
                new Hint(catalog.get("onboard.go_counting"), "navigate_counting", "go:south"),
                new Hint(catalog.get("onboard.go_trading"), "navigate_trading", "go:east then north"),
                new Hint(catalog.get("onboard.explore_freely"), "free_explore", "look")
            );
        };
    }

    /** Get the welcome message for a first-time player. */
    public String welcomeMessage(String playerName) {
        return welcomeMessage(playerName, "en");
    }

    /** Get the welcome message for a first-time player in the given locale. */
    public String welcomeMessage(String playerName, String locale) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        return catalog.get("onboard.welcome", playerName);
    }

    /** Determine next stage based on rooms visited. */
    public OnboardingStage nextStage(int roomsVisited, int interactionCount) {
        if (roomsVisited <= 1 && interactionCount < 3) return OnboardingStage.WELCOME;
        if (roomsVisited <= 3) return OnboardingStage.EXPLORE;
        if (roomsVisited <= 6) return OnboardingStage.INTERACT;
        return OnboardingStage.COMPLETE;
    }
}
