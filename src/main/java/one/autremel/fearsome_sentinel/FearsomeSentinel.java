package one.autremel.fearsome_sentinel;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public final class FearsomeSentinel {
    public static final String ID = "fearsome-sentinel";
    static final Logger LOGGER = LoggerFactory.getLogger("Fearsome Sentinel");

    private static final String FEARSOME_ID = "fearsome";
    private static final Set<String> BUILTIN_IDS = Set.of("fabricloader", "minecraft", "java");
    private static final boolean IS_RUSSIAN = Locale.getDefault().getLanguage().equals("ru");

    static void run() {
        var loader = FabricLoader.getInstance();
        var installed = loader.getModContainer(FEARSOME_ID);

        Remote.Release release;
        try {
            Optional<Remote.Release> latest = Remote.latestFearsome();
            if (latest.isEmpty()) return;
            release = latest.get();
        } catch (Exception e) {
            LOGGER.warn("Couldn't check for Fearsome updates", e);
            return;
        }

        var installedVersion = installed.map(container -> container.getMetadata().getVersion()).orElse(null);
        if (installedVersion != null && installedVersion.compareTo(release.version()) >= 0) return;

        var latestVersion = release.version().getFriendlyString();
        var doneMessage = tr(
                "Fearsome %s установлен. Игра закроется, запустите её снова.",
                "Fearsome %s has been installed. The game will close, please launch it again."
        ).formatted(latestVersion);

        if (!SentinelWindow.isSupported()) {
            LOGGER.info("Installing Fearsome {} without a window", latestVersion);
            try {
                install(loader, installed, release, LOGGER::info);
            } catch (Exception e) {
                LOGGER.error("Failed to install Fearsome", e);
                return;
            }
            throw new IllegalStateException(doneMessage);
        }

        var updateQuestion = installedVersion == null
                ? tr("Мод Fearsome не установлен.\nСкачать и установить версию %s?",
                "Fearsome is not installed.\nDownload and install version %s?").formatted(latestVersion)
                : tr("Вышла новая версия Fearsome: %s (установлена %s).\nОбновить?",
                "A new version of Fearsome is available: %s (installed: %s).\nUpdate?")
                .formatted(latestVersion, installedVersion.getFriendlyString());

        try {
            if (!SentinelWindow.ask(updateQuestion, tr("Установить", "Install"), tr("Пропустить", "Skip"))) return;
            var progress = SentinelWindow.openProgress();
            try {
                install(loader, installed, release, progress::setStatus);
            } finally {
                progress.close();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to install Fearsome", e);
            var reason = e instanceof SentinelException ? e.getMessage() : e.toString();
            SentinelWindow.inform(tr(
                    "Не удалось установить Fearsome:\n%s\n\nИгра запустится без изменений.",
                    "Failed to install Fearsome:\n%s\n\nThe game will launch unchanged."
            ).formatted(reason), JOptionPane.ERROR_MESSAGE);
            return;
        }

        SentinelWindow.inform(doneMessage, JOptionPane.INFORMATION_MESSAGE);
        System.exit(0);
    }

    static String tr(String ru, String en) {
        return IS_RUSSIAN ? ru : en;
    }

    private static void install(
            FabricLoader loader,
            Optional<ModContainer> installed,
            Remote.Release release,
            Consumer<String> status
    ) throws Exception {
        var installer = new Installer(loader.getGameDir().resolve("mods"));
        try {
            status.accept(tr("Загрузка %s…", "Downloading %s…").formatted(release.file().fileName()));
            var jar = installer.download(release.file());
            installed.ifPresent(installer::replace);

            var metadata = Remote.readJar(jar);
            for (var dependency : metadata.depends().entrySet()) {
                var id = dependency.getKey();
                var requirement = dependency.getValue();

                var nested = metadata.nested().get(id);
                if (nested != null && requirement.matches(nested)) continue;

                var provider = loader.getModContainer(id)
                        .filter(container -> !isNestedIn(container, installed));
                if (provider.isPresent() && requirement.matches(provider.get().getMetadata().getVersion())) continue;

                var description = id + " " + requirement;
                if (BUILTIN_IDS.contains(id)) {
                    throw new SentinelException(tr("Fearsome %s требует %s", "Fearsome %s requires %s")
                            .formatted(release.version().getFriendlyString(), description));
                }

                status.accept(tr("Поиск %s…", "Looking up %s…").formatted(description));
                var file = Remote.findOnModrinth(id, requirement, loader.getRawGameVersion())
                        .orElseThrow(() -> new SentinelException(
                                tr("Не найдена подходящая версия %s", "No matching version of %s found").formatted(description)));

                status.accept(tr("Загрузка %s…", "Downloading %s…").formatted(file.fileName()));
                installer.download(file);
                provider.ifPresent(installer::replace);
            }

            status.accept(tr("Установка…", "Installing…"));
            installer.commit();
        } catch (Exception e) {
            installer.discard();
            throw e;
        }
    }

    private static boolean isNestedIn(ModContainer container, Optional<ModContainer> parent) {
        if (parent.isEmpty()) return false;
        for (var current = container.getContainingMod(); current.isPresent(); current = current.get().getContainingMod()) {
            if (current.get() == parent.get()) return true;
        }
        return false;
    }
}
