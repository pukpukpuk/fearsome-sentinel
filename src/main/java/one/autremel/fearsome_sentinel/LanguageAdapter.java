package one.autremel.fearsome_sentinel;

import net.fabricmc.loader.api.ModContainer;

public final class LanguageAdapter implements net.fabricmc.loader.api.LanguageAdapter {
    static {
        FearsomeSentinel.run();
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) {
        throw new UnsupportedOperationException();
    }
}
