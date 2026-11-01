package one.autremel.fearsome_sentinel;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Installer {
    private final Path modsDir;
    private final List<Pending> downloads = new ArrayList<>();
    private final Set<Path> obsolete = new LinkedHashSet<>();

    Installer(Path modsDir) {
        this.modsDir = modsDir;
    }

    Path download(Remote.RemoteFile file) throws IOException, InterruptedException {
        Files.createDirectories(modsDir);
        var temp = modsDir.resolve(file.fileName() + ".download");
        Remote.download(file, temp);
        downloads.add(new Pending(temp, modsDir.resolve(file.fileName())));
        return temp;
    }

    void replace(ModContainer container) {
        var origin = container.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) return;
        for (Path path : origin.getPaths()) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) obsolete.add(path);
        }
    }

    void discard() {
        for (var pending : downloads) {
            try {
                Files.deleteIfExists(pending.temp());
            } catch (IOException e) {
                FearsomeSentinel.LOGGER.warn("Couldn't delete {}", pending.temp(), e);
            }
        }
    }

    void commit() throws IOException {
        var deferred = new ArrayList<String>();
        var locked = new HashSet<Path>();

        for (var path : obsolete) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                locked.add(path);
                deferred.addAll(List.of(Swapper.DELETE, path.toString()));
            }
        }

        for (var pending : downloads) {
            if (locked.contains(pending.target())) {
                deferred.addAll(List.of(Swapper.MOVE, pending.temp().toString(), pending.target().toString()));
            } else {
                Files.move(pending.temp(), pending.target(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (!deferred.isEmpty()) launchSwapper(deferred);
    }

    private void launchSwapper(List<String> operations) throws IOException {
        var loader = FabricLoader.getInstance();
        var java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("Couldn't locate the java executable"));
        var classpath = loader.getModContainer(FearsomeSentinel.ID).orElseThrow().getOrigin().getPaths().getFirst();

        var command = new ArrayList<>(List.of(java, "-cp", classpath.toString(), Swapper.class.getName(),
                String.valueOf(ProcessHandle.current().pid())));
        command.addAll(operations);

        var log = loader.getGameDir().resolve("logs").resolve("fearsome-sentinel.log");
        Files.createDirectories(log.getParent());
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private record Pending(Path temp, Path target) {
    }
}
