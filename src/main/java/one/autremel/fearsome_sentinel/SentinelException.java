package one.autremel.fearsome_sentinel;

public class SentinelException extends Exception {
    SentinelException(String message) {
        super(message);
    }
}
