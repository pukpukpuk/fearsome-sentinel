package one.autremel.fearsome_sentinel;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

final class SentinelWindow {
    private static final String TITLE = "Fearsome Sentinel";

    private final JDialog dialog;
    private final JLabel status;

    private SentinelWindow() {
        status = new JLabel(" ");
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        content.add(status, BorderLayout.CENTER);
        content.add(bar, BorderLayout.SOUTH);

        dialog = new JDialog((Frame) null, TITLE);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(420, 0));
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    static boolean isSupported() {
        return !GraphicsEnvironment.isHeadless()
                && !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    static boolean ask(String message, String accept, String decline) throws Exception {
        return onEdt(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                FearsomeSentinel.LOGGER.warn("Couldn't apply the system look and feel", e);
            }
            JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION,
                    null, new Object[]{accept, decline}, accept);
            show(pane);
            return accept.equals(pane.getValue());
        });
    }

    static void inform(String message, int type) {
        try {
            onEdt(() -> {
                show(new JOptionPane(message, type));
                return null;
            });
        } catch (Exception e) {
            FearsomeSentinel.LOGGER.error("Couldn't show a message: {}", message, e);
        }
    }

    static SentinelWindow openProgress() throws Exception {
        return onEdt(SentinelWindow::new);
    }

    void setStatus(String text) {
        FearsomeSentinel.LOGGER.info(text);
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    void close() {
        SwingUtilities.invokeLater(dialog::dispose);
    }

    private static void show(JOptionPane pane) {
        JDialog dialog = pane.createDialog(TITLE);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
        dialog.dispose();
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
