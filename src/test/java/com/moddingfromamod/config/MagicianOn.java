package com.moddingfromamod.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * {@code @ExtendWith(MagicianOn.class)}: run each test with Magician mode ON, as a player who turned it on would
 *. For a test that drives a fake {@code claude} or {@code muse}: with Magician off (a plain unit-test JVM has
 * no plug-in and no player setting) no AI program may start, which is the point of {@link MagicianOffTest}.
 */
public final class MagicianOn implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ModdySettings.overridesForTest(List.of(new SettingsOverride() {
            @Override
            public Set<String> keys() {
                return Set.of(SettingsCatalog.MAGICIAN);
            }

            @Override
            public Optional<String> value(String key) {
                return SettingsCatalog.MAGICIAN.equals(key) ? Optional.of("on") : Optional.empty();
            }
        }));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ModdySettings.overridesForTest(null);
    }
}
