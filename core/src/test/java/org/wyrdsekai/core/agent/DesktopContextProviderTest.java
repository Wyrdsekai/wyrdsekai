package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DesktopContextProvider} — active window categorization and context.
 */
class DesktopContextProviderTest {

    private DesktopContextProvider provider;

    @BeforeEach
    void setUp() {
        DesktopContextProvider.init();
        provider = DesktopContextProvider.get();
    }

    @AfterEach
    void tearDown() {
        DesktopContextProvider.reset();
    }

    @Test
    void categorize_coding_apps() {
        assertThat(provider.categorize("Visual Studio Code - main.java")).isEqualTo("coding");
        assertThat(provider.categorize("VIM - ~/.bashrc")).isEqualTo("coding");
        assertThat(provider.categorize("NeoVim")).isEqualTo("coding");
        assertThat(provider.categorize("IntelliJ IDEA - wyrdsekai")).isEqualTo("coding");
        assertThat(provider.categorize("Cursor - project")).isEqualTo("coding");
        assertThat(provider.categorize("Android Studio - app")).isEqualTo("coding");
    }

    @Test
    void categorize_browsing_apps() {
        assertThat(provider.categorize("Mozilla Firefox - Google")).isEqualTo("browsing");
        assertThat(provider.categorize("Google Chrome - GitHub")).isEqualTo("browsing");
        assertThat(provider.categorize("Brave Browser")).isEqualTo("browsing");
        assertThat(provider.categorize("Microsoft Edge")).isEqualTo("browsing");
    }

    @Test
    void categorize_meeting_apps() {
        assertThat(provider.categorize("Zoom Meeting")).isEqualTo("meeting");
        assertThat(provider.categorize("Google Meet")).isEqualTo("meeting");
        assertThat(provider.categorize("Microsoft Teams")).isEqualTo("meeting");
        assertThat(provider.categorize("Discord - #general")).isEqualTo("meeting");
    }

    @Test
    void unknown_app_returns_other() {
        assertThat(provider.categorize("My Custom App")).isEqualTo("other");
        assertThat(provider.categorize("Calculator")).isEqualTo("other");
        assertThat(provider.categorize(null)).isEqualTo("other");
        assertThat(provider.categorize("")).isEqualTo("other");
    }

    @Test
    void categorize_terminal_apps() {
        assertThat(provider.categorize("Alacritty")).isEqualTo("terminal");
        assertThat(provider.categorize("GNOME Terminal")).isEqualTo("terminal");
        assertThat(provider.categorize("kitty - ~/src")).isEqualTo("terminal");
        assertThat(provider.categorize("WezTerm")).isEqualTo("terminal");
    }

    @Test
    void categorize_communication_apps() {
        assertThat(provider.categorize("Slack - wyrdsekai")).isEqualTo("communication");
        assertThat(provider.categorize("Signal")).isEqualTo("communication");
        assertThat(provider.categorize("Thunderbird Mail")).isEqualTo("communication");
    }

    @Test
    void context_respects_permissions() {
        ContextAccessManager.init();
        var accessMgr = ContextAccessManager.get();

        // No permission — no context
        var ctx = provider.getContext("agent-ma", accessMgr);
        assertThat(ctx).isEmpty();

        // Grant permission — context returned (xdotool may not be available in test, so
        // we just test the no-permission path works correctly)
        accessMgr.grant("agent-ma", "active_window", "vscode", "did:key:operator");
        // The actual getActiveWindowTitle will fail in CI, but the permission check path works
        // Real integration testing requires a desktop environment

        ContextAccessManager.reset();
    }
}
