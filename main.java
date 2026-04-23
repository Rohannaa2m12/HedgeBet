import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HedgeBet {
    private static final String APP_NAME = "HedgeBet";
    private static final int W = 1280;
    private static final int H = 760;
    private static final int TICK_MS = 250;
    private static final int AUTOSAVE_SEC = 6;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            new TerminalApp().start();
        });
    }

    static final class TerminalApp {
        private final Theme theme = Theme.bloomLama();
        private final SecureRandom rng = new SecureRandom();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        private final Tape tape = new Tape(1400);
        private final State state = new State();
        private final MarketSim sim = new MarketSim(rng);
        private final CmdEngine cmd = new CmdEngine();
        private final OracleToy oracle = new OracleToy(rng);
        private final Persist persist = new Persist();

        private Terminal terminal;
        private WatchPanel watch;
        private MarketPanel market;
        private StatusLine status;
        private CommandBar commandBar;

        void start() {
            State loaded = persist.load();
            if (loaded != null) state.copyFrom(loaded);
            if (state.watch.isEmpty()) state.watch.addAll(List.of("LAMA/USD","ETH/USD","BTC/USD","LLL/ETH","XII/BASE"));
            if (state.activeSymbol == null || state.activeSymbol.isBlank()) state.activeSymbol = "LAMA/USD";
            for (String s : state.watch) sim.quote(s);
            buildCommands();
            buildUi();
            bootTape();
            bootLoops();
        }

        private void buildUi() {
            JFrame frame = new JFrame(APP_NAME + " — Lama Terminal XII");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout(10,10));
            root.setBackground(theme.bg0);
            root.setBorder(new EmptyBorder(10,10,10,10));
            status = new StatusLine(theme);
            terminal = new Terminal(theme);
            commandBar = new CommandBar(theme, raw -> onResult(cmd.exec(raw)));
            JTabbedPane tabs = new JTabbedPane();
            tabs.setFont(theme.fontSm);
            tabs.setBackground(theme.bg0);
            tabs.setForeground(theme.fg1);
            watch = new WatchPanel(theme, state, sim, tape);
            market = new MarketPanel(theme, state, sim, oracle, tape);
            tabs.addTab("WATCH", watch);
            tabs.addTab("MARKET", market);
            tabs.addTab("HELP", new HelpPanel(theme));
            JPanel center = new JPanel(new BorderLayout(10,10));
            center.setBackground(theme.bg0);
            center.add(terminal, BorderLayout.CENTER);
            center.add(tabs, BorderLayout.EAST);
            root.add(status, BorderLayout.NORTH);
            root.add(center, BorderLayout.CENTER);
            root.add(commandBar, BorderLayout.SOUTH);
            frame.setContentPane(root);
            frame.setSize(W, H);
            frame.setLocationRelativeTo(null);
            bindKeys(root);
            frame.setVisible(true);
            terminal.banner();
            terminal.println(clock(), "HedgeBet online  |  type 'help'  |  Ctrl/Cmd+K focus cmd");
        }

        private void bindKeys(JComponent root) {
            int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_K, mod), () -> commandBar.focus());
            bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_T, mod), () -> { watch.toggleDensity(); market.toggleDensity(); tape.add("UI","density toggled"); });
            bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_C, mod | InputEvent.SHIFT_DOWN_MASK), () -> {
                String last = terminal.last();
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(last), null);
                tape.add("CLIP","copied last line");
            });
        }

        private void bind(JComponent root, KeyStroke ks, Runnable r) {
            String name = "hk_" + ks.toString();
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
