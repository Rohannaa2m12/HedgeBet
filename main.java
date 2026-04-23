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
