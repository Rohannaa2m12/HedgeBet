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
            root.getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent e){ r.run(); }});
        }

        private void bootTape() {
            tape.add("BOOT","feed: local-sim (no network)");
            tape.add("BOOT","oracle: payload builder (mock signature)");
            tape.add("BOOT","hotkeys: Ctrl/Cmd+K focus, Ctrl/Cmd+T density, Ctrl/Cmd+Shift+C copy");
        }

        private void bootLoops() {
            scheduler.scheduleAtFixedRate(() -> { sim.step(); watch.refresh(); market.refresh(); status.setRight("chain=EVM | now=" + clock()); }, 200, TICK_MS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(() -> persist.save(state), AUTOSAVE_SEC, AUTOSAVE_SEC, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(() -> { TapeItem it; while ((it=tape.poll())!=null) terminal.println(it.t, "["+it.tag+"] "+it.msg); }, 120, 120, TimeUnit.MILLISECONDS);
        }

        private void onResult(CmdResult r) {
            if (r == null) return;
            if (r.toast != null && !r.toast.isBlank()) tape.add(r.tag, r.toast);
            if (r.lines != null) for (String l : r.lines) tape.add(r.tag, l);
            if (r.mutate != null) r.mutate.run();
            watch.refresh();
            market.refresh();
        }

        private void buildCommands() {
            cmd.reg("help", "help", c -> CmdResult.lines("HELP","help", helpLines()));
            cmd.reg("clear","clear", c -> { terminal.clear(); return CmdResult.ok("TERM","cleared"); });
            cmd.reg("watch","watch add|del|list", this::doWatch);
            cmd.reg("sym","sym LAMA/USD", c -> {
                if (c.args.size()<2) return CmdResult.err("SYMBOL","usage: sym <AAA/BBB>");
                String s = Norm.symbol(c.args.get(1));
                if (s.isBlank()) return CmdResult.err("SYMBOL","bad symbol");
                return CmdResult.mutate("SYMBOL","active="+s, () -> state.activeSymbol = s);
            });
            cmd.reg("mk","mk <strike> <band> <openSec> <lockSec> <closeSec>", this::doMk);
            cmd.reg("bet","bet up|down|flat <amt>", this::doBet);
            cmd.reg("settle","settle <price>", this::doSettle);
            cmd.reg("oracle","oracle id|build", this::doOracle);
        }

        private CmdResult doWatch(CmdCtx c) {
            if (c.args.size()<2 || "list".equalsIgnoreCase(c.args.get(1))) {
                List<String> out = new ArrayList<>();
                int i=1;
                for (String s : state.watch) {
                    MarketSim.Quote q = sim.quote(s);
                    out.add(String.format(Locale.ROOT, "%2d  %-12s  %10s  %8s  %s", i++, s, Fmt.money(q.priceE8), q.pct(), q.spark));
                }
                return CmdResult.lines("WATCH","watchlist", out);
            }
            if (c.args.size()>=3 && "add".equalsIgnoreCase(c.args.get(1))) {
                String s = Norm.symbol(c.args.get(2));
                if (s.isBlank()) return CmdResult.err("WATCH","bad symbol");
                return CmdResult.mutate("WATCH","added "+s, () -> { if (!state.watch.contains(s)) state.watch.add(s); sim.quote(s); });
            }
            if (c.args.size()>=3 && "del".equalsIgnoreCase(c.args.get(1))) {
                String s = Norm.symbol(c.args.get(2));
                return CmdResult.mutate("WATCH","removed "+s, () -> state.watch.remove(s));
            }
            return CmdResult.err("WATCH","usage: watch add|del|list");
        }

        private CmdResult doMk(CmdCtx c) {
            if (c.args.size()<6) return CmdResult.err("MKT","usage: mk <strike> <band> <openSec> <lockSec> <closeSec>");
            long strike = Fmt.e8(c.args.get(1));
            long band = Fmt.e8(c.args.get(2));
            int open = Norm.i(c.args.get(3));
            int lock = Norm.i(c.args.get(4));
            int close = Norm.i(c.args.get(5));
            if (strike<=0 || band<=0) return CmdResult.err("MKT","strike/band must be >0");
            if (!(open<lock && lock<close)) return CmdResult.err("MKT","need open<lock<close");
            MarketSim.Market m = sim.mk(state.activeSymbol, strike, band, open, lock, close);
            return CmdResult.mutate("MKT","created #"+m.id+" "+m.symbol, () -> state.activeMarketId = m.id);
        }

        private CmdResult doBet(CmdCtx c) {
            if (c.args.size()<3) return CmdResult.err("BET","usage: bet up|down|flat <amt>");
            MarketSim.Market m = sim.get(state.activeMarketId);
            if (m==null) return CmdResult.err("BET","no active market");
            MarketSim.Bucket b = switch (c.args.get(1).toLowerCase(Locale.ROOT)) {
                case "up" -> MarketSim.Bucket.UP;
                case "down" -> MarketSim.Bucket.DOWN;
                case "flat" -> MarketSim.Bucket.FLAT;
                default -> null;
            };
            if (b==null) return CmdResult.err("BET","bucket must be up/down/flat");
            long amt = Fmt.cents(c.args.get(2));
            if (amt<=0) return CmdResult.err("BET","amount must be >0");
            if (!sim.bet(m.id,"YOU",b,amt)) return CmdResult.err("BET","market not open");
            return CmdResult.ok("BET","YOU "+b+" "+Fmt.money2(amt));
        }

        private CmdResult doSettle(CmdCtx c) {
            if (c.args.size()<2) return CmdResult.err("SET","usage: settle <price>");
            MarketSim.Market m = sim.get(state.activeMarketId);
            if (m==null) return CmdResult.err("SET","no active market");
            long p = Fmt.e8(c.args.get(1));
            MarketSim.Settle s = sim.settle(m.id, p);
            if (s==null) return CmdResult.err("SET","market not closable yet");
            return CmdResult.lines("SET","settled", List.of("market#"+m.id+" final="+Fmt.money(p)+" winner="+s.winner, "hint: oracle build"));
        }

        private CmdResult doOracle(CmdCtx c) {
            if (c.args.size()<2 || "id".equalsIgnoreCase(c.args.get(1))) {
                return CmdResult.lines("ORACLE","id", List.of("oracleKey="+oracle.keyHint(), "note: local mock; mainnet signs with your real key"));
            }
            if ("build".equalsIgnoreCase(c.args.get(1))) {
                MarketSim.Market m = sim.get(state.activeMarketId);
                if (m==null || m.settle==null) return CmdResult.err("ORACLE","need settled market");
                OracleToy.Payload pl = oracle.settle(m.id, m.symbol, m.settle.priceE8, m.lockAt, m.closeAt);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(pl.json), null);
                return CmdResult.lines("ORACLE","copied JSON", List.of("digest="+pl.digestHex, "oracleNonce="+pl.oracleNonce, "meta="+pl.metaHex, "sig="+pl.sigHex, "", pl.json));
            }
            return CmdResult.err("ORACLE","usage: oracle id|build");
        }

        private List<String> helpLines() {
            return List.of(
                "help | clear",
                "watch list | watch add LAMA/USD | watch del ETH/USD",
                "sym LAMA/USD",
                "mk 100 0.5 2 10 20",
                "bet up 250 | bet down 250 | bet flat 250",
                "settle 101.25",
                "oracle id | oracle build",
                "",
                "Hotkeys: Ctrl/Cmd+K focus cmd, Ctrl/Cmd+T density, Ctrl/Cmd+Shift+C copy last"
            );
        }
    }

    private static String clock() {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    static final class Theme {
        final Color bg0, bg1, fg0, fg1, dim, lime, amber, red, cyan, blue;
        final Font fontSm, fontMd, fontMono;
        Theme(Color bg0, Color bg1, Color fg0, Color fg1, Color dim, Color lime, Color amber, Color red, Color cyan, Color blue, Font fontSm, Font fontMd, Font fontMono) {
            this.bg0=bg0; this.bg1=bg1; this.fg0=fg0; this.fg1=fg1; this.dim=dim;
            this.lime=lime; this.amber=amber; this.red=red; this.cyan=cyan; this.blue=blue;
            this.fontSm=fontSm; this.fontMd=fontMd; this.fontMono=fontMono;
        }
        static Theme bloomLama() {
            Color bg0=new Color(11,14,18), bg1=new Color(16,20,26);
            Color fg0=new Color(222,228,236), fg1=new Color(180,190,202), dim=new Color(124,136,152);
            Color lime=new Color(70,220,160), amber=new Color(255,190,80), red=new Color(255,96,96);
            Color cyan=new Color(90,205,255), blue=new Color(120,140,255);
            Font mono=new Font(Font.MONOSPACED, Font.PLAIN, 13);
            return new Theme(bg0,bg1,fg0,fg1,dim,lime,amber,red,cyan,blue, mono.deriveFont(12f), mono.deriveFont(Font.BOLD, 13f), mono);
        }
    }

    static final class Terminal extends JPanel {
        private final JTextArea out = new JTextArea();
        private final Deque<String> last = new ArrayDeque<>();
        Terminal(Theme theme) {
            super(new BorderLayout());
            setBackground(theme.bg0);
            out.setEditable(false);
            out.setLineWrap(true);
            out.setWrapStyleWord(true);
            out.setFont(theme.fontMono);
            out.setBackground(theme.bg0);
            out.setForeground(theme.fg0);
            out.setCaretColor(theme.fg0);
            out.setBorder(new EmptyBorder(10,10,10,10));
            JScrollPane sp = new JScrollPane(out);
