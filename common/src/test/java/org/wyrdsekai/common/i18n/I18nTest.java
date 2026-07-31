package org.wyrdsekai.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {

    @AfterEach void cleanup() {
        I18n.clear();
        I18n.clearCaches();
    }

    // ── MessageCatalog / PropertyCatalog ──

    @Test void english_catalog_returns_known_key() {
        var catalog = new PropertyCatalog(Locale.ENGLISH);
        assertThat(catalog.get("vitality.energy.exhausted")).isEqualTo("exhausted");
    }

    @Test void english_catalog_returns_key_for_unknown() {
        var catalog = new PropertyCatalog(Locale.ENGLISH);
        assertThat(catalog.get("nonexistent.key")).isEqualTo("nonexistent.key");
    }

    @Test void english_catalog_formats_args() {
        var catalog = new PropertyCatalog(Locale.ENGLISH);
        assertThat(catalog.get("economy.ledger.accounts", 42)).isEqualTo("Accounts: 42");
    }

    @Test void spanish_catalog_returns_translated() {
        var catalog = new PropertyCatalog(Locale.forLanguageTag("es"));
        assertThat(catalog.get("vitality.energy.exhausted")).isEqualTo("agotado");
    }

    @Test void japanese_catalog_returns_translated() {
        var catalog = new PropertyCatalog(Locale.JAPANESE);
        assertThat(catalog.get("vitality.energy.exhausted")).isNotEqualTo("exhausted");
        assertThat(catalog.hasKey("vitality.energy.exhausted")).isTrue();
    }

    @Test void catalog_hasKey_returns_false_for_unknown() {
        var catalog = new PropertyCatalog(Locale.ENGLISH);
        assertThat(catalog.hasKey("nonexistent.key")).isFalse();
    }

    @Test void catalog_locale_matches() {
        var catalog = new PropertyCatalog(Locale.forLanguageTag("es"));
        assertThat(catalog.getLocale().getLanguage()).isEqualTo("es");
    }

    // ── I18n static accessor ──

    @Test void i18n_defaults_to_english() {
        assertThat(I18n.getLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(I18n.get("vitality.energy.tired")).isEqualTo("tired");
    }

    @Test void i18n_switches_locale() {
        I18n.setLocale(Locale.forLanguageTag("es"));
        assertThat(I18n.get("vitality.energy.tired")).isEqualTo("cansado");
    }

    @Test void i18n_format_args() {
        assertThat(I18n.get("economy.ledger.transactions", 7)).isEqualTo("Transactions: 7");
    }

    @Test void i18n_clear_resets_to_english() {
        I18n.setLocale(Locale.JAPANESE);
        I18n.clear();
        assertThat(I18n.getLocale()).isEqualTo(Locale.ENGLISH);
    }

    // ── I18nContext ──

    @Test void context_of_creates_from_lang_tag() {
        var ctx = I18nContext.of("es");
        assertThat(ctx.locale().getLanguage()).isEqualTo("es");
        assertThat(ctx.catalog().get("vitality.energy.exhausted")).isEqualTo("agotado");
    }

    @Test void context_default_is_english() {
        var ctx = I18nContext.defaultContext();
        assertThat(ctx.isDefault()).isTrue();
        assertThat(ctx.langTag()).isEqualTo("en");
    }

    @Test void context_non_english_is_not_default() {
        var ctx = I18nContext.of("ja");
        assertThat(ctx.isDefault()).isFalse();
    }

    // ── Translatable ──

    @Test void translatable_lambda_works() {
        Translatable t = catalog -> catalog.get("onboarding.welcome");
        var enCatalog = new PropertyCatalog(Locale.ENGLISH);
        var esCatalog = new PropertyCatalog(Locale.forLanguageTag("es"));
        assertThat(t.translate(enCatalog)).isEqualTo("Welcome to Wyrdsekai!");
        assertThat(t.translate(esCatalog)).contains("Bienvenido");
    }
}
