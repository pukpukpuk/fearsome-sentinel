package one.autremel.fearsome_sentinel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Swapper {
    static final String DELETE = "delete";
    static final String MOVE = "move";

    private static final int ATTEMPTS = 50;
    private static final long RETRY_DELAY_MS = 200;

    public static void main(String[] args) throws InterruptedException {
        ProcessHandle.of(Long.parseLong(args[0])).ifPresent(game -> game.onExit().join());

        for (int i = 1; i < args.length; ) {
            if (args[i].equals(DELETE)) {
                Path path = Path.of(args[i + 1]);
                retry("delete " + path, () -> Files.deleteIfExists(path));
                i += 2;
            } else {
                Path from = Path.of(args[i + 1]);
                Path to = Path.of(args[i + 2]);
                retry("move " + from + " -> " + to, () -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
                i += 3;
            }
        }
    }

    private static void retry(String description, IoAction action) throws InterruptedException {
        var attempt = 0;
        while (true) {
            try {
                action.run();
                System.out.println("[Fearsome Sentinel] " + description);
                return;
            } catch (IOException e) {
                if (attempt++ > ATTEMPTS) {
                    System.out.println("[Fearsome Sentinel] Failed to " + description);
                    e.printStackTrace(System.out);
                    return;
                }
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
