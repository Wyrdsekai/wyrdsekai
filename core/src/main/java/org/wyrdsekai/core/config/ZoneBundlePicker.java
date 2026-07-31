package org.wyrdsekai.core.config;

/**
 * Standalone main for {@code wyrd config init} — picks a random
 * (zoneName, theme) bundle and prints it as {@code zoneName:theme}.
 * Bash splits on the colon. Done as a tiny main rather than {@code java -e}
 * (JShell-only) so the same artifact ships everywhere.
 */
public final class ZoneBundlePicker {

    private ZoneBundlePicker() {}

    public static void main(String[] args) {
        // `--theme <theme>` → print just a random name matching that theme, so
        // `wyrd config init` can re-roll the suggested name after a theme pick.
        if (args.length >= 2 && "--theme".equals(args[0])) {
            System.out.println(ZoneNameGenerator.randomNameForTheme(args[1]));
            return;
        }
        var seed = args.length > 0 ? args[0] : null;
        var bundle = (seed != null && !seed.isEmpty())
            ? ZoneNameGenerator.bundleForSeed(seed)
            : ZoneNameGenerator.randomBundle();
        System.out.println(bundle.zoneName() + ":" + bundle.theme());
    }
}
