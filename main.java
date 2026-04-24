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
            sp.setBorder(BorderFactory.createLineBorder(theme.bg1, 1));
            sp.getVerticalScrollBar().setUnitIncrement(16);
            add(sp, BorderLayout.CENTER);
        }
        void banner() {
            println("T12", "HedgeBet / lama terminal  |  mode=SIM  |  chain=EVM");
            println("T12", "try: help | mk 100 0.5 2 10 20 | bet up 250 | settle 101.25 | oracle build");
            println("T12", "");
        }
        void println(String t, String msg) {
            String line = (t==null?"":t) + " " + (msg==null?"":msg);
            last.addLast(line);
            while (last.size()>60) last.removeFirst();
            out.append(line + "\n");
            out.setCaretPosition(out.getDocument().getLength());
        }
        void clear(){ out.setText(""); last.clear(); }
        String last(){ return last.peekLast()==null ? "" : last.peekLast(); }
    }

    static final class StatusLine extends JPanel {
        private final JLabel left = new JLabel();
        private final JLabel right = new JLabel();
        StatusLine(Theme theme) {
            super(new BorderLayout());
            setBackground(theme.bg1);
            left.setFont(theme.fontSm); right.setFont(theme.fontSm);
            left.setForeground(theme.fg1); right.setForeground(theme.fg1);
            left.setText("HedgeBet / LamaXII"); right.setText("booting...");
            add(left, BorderLayout.WEST); add(right, BorderLayout.EAST);
        }
        void setRight(String s){ SwingUtilities.invokeLater(() -> right.setText(s)); }
    }

    static final class CommandBar extends JPanel {
        private final JTextField in = new JTextField();
        private final Deque<String> hist = new ArrayDeque<>();
        private int histCur=-1;
        private final java.util.function.Consumer<String> sink;
        CommandBar(Theme theme, java.util.function.Consumer<String> sink) {
            super(new BorderLayout(10,10));
            this.sink = sink;
            setBackground(theme.bg0);
            JLabel p = new JLabel("CMD");
            p.setFont(theme.fontMd); p.setForeground(theme.amber);
            in.setFont(theme.fontMono);
            in.setBackground(theme.bg1); in.setForeground(theme.fg0); in.setCaretColor(theme.fg0);
            in.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.bg1,1), new EmptyBorder(8,10,8,10)));
            add(p, BorderLayout.WEST); add(in, BorderLayout.CENTER);
            in.addActionListener(e -> run());
            in.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e){
                    if (e.getKeyCode()==KeyEvent.VK_UP){ historyUp(); e.consume(); }
                    else if (e.getKeyCode()==KeyEvent.VK_DOWN){ historyDown(); e.consume(); }
                }
            });
        }
        void focus(){ SwingUtilities.invokeLater(() -> in.requestFocusInWindow()); }
        private void run(){
            String raw = in.getText()==null?"":in.getText().trim();
            if (raw.isBlank()) return;
            hist.addLast(raw); while (hist.size()>120) hist.removeFirst();
            histCur=-1; in.setText("");
            sink.accept(raw);
        }
        private void historyUp(){
            if (hist.isEmpty()) return;
            if (histCur<0) histCur=hist.size()-1;
            else histCur=Math.max(0, histCur-1);
            in.setText(new ArrayList<>(hist).get(histCur));
        }
        private void historyDown(){
            if (hist.isEmpty()||histCur<0) return;
            histCur++;
            if (histCur>=hist.size()){ histCur=-1; in.setText(""); return; }
            in.setText(new ArrayList<>(hist).get(histCur));
        }
    }

    static final class WatchPanel extends JPanel {
        private final State state;
        private final MarketSim sim;
        private final Tape tape;
        private final JTable table;
        private boolean dense;
        WatchPanel(Theme theme, State state, MarketSim sim, Tape tape) {
            super(new BorderLayout());
            this.state=state; this.sim=sim; this.tape=tape;
            setPreferredSize(new Dimension(440,620));
            setBackground(theme.bg0);
            table = new JTable(new WatchModel(state, sim));
            table.setFont(theme.fontSm);
            table.setBackground(theme.bg0);
            table.setForeground(theme.fg0);
            table.setRowHeight(20);
            table.setGridColor(theme.bg1);
            table.getTableHeader().setFont(theme.fontSm);
            table.getTableHeader().setBackground(theme.bg1);
            table.getTableHeader().setForeground(theme.fg1);
            JScrollPane sp = new JScrollPane(table);
            sp.setBorder(BorderFactory.createLineBorder(theme.bg1,1));
            add(title(theme,"WATCHLIST","dbl-click -> active symbol"), BorderLayout.NORTH);
            add(sp, BorderLayout.CENTER);
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e){
                    if (e.getClickCount()>=2){
                        int r=table.getSelectedRow();
                        if (r>=0){
                            String sym=(String)table.getModel().getValueAt(r,0);
                            state.activeSymbol=sym;
                            tape.add("WATCH","active symbol="+sym);
                        }
                    }
                }
            });
        }
        void refresh(){ SwingUtilities.invokeLater(() -> ((WatchModel)table.getModel()).fireTableDataChanged()); }
        void toggleDensity(){ dense=!dense; table.setRowHeight(dense?18:20); }
    }

    static final class MarketPanel extends JPanel {
        private final State state;
        private final MarketSim sim;
        private final OracleToy oracle;
        private final Tape tape;
        private final JTextArea box = new JTextArea();
        private final JTextField stake = new JTextField("250.00");
        private boolean dense;
        MarketPanel(Theme theme, State state, MarketSim sim, OracleToy oracle, Tape tape) {
            super(new BorderLayout(10,10));
            this.state=state; this.sim=sim; this.oracle=oracle; this.tape=tape;
            setPreferredSize(new Dimension(440,620));
            setBackground(theme.bg0);
            setBorder(new EmptyBorder(10,10,10,10));
            add(title(theme,"MARKET","sim + payload"), BorderLayout.NORTH);
            box.setFont(theme.fontSm);
            box.setEditable(false);
            box.setBackground(theme.bg0);
            box.setForeground(theme.fg0);
            box.setBorder(BorderFactory.createLineBorder(theme.bg1,1));
            box.setRows(22);
            add(new JScrollPane(box), BorderLayout.CENTER);
            add(controls(theme), BorderLayout.SOUTH);
            refresh();
        }
        void refresh(){ SwingUtilities.invokeLater(() -> box.setText(render())); }
        void toggleDensity(){ dense=!dense; box.setRows(dense?16:22); }
        private JPanel controls(Theme theme){
            JPanel p=new JPanel(new GridBagLayout());
            p.setBackground(theme.bg0);
            GridBagConstraints g=new GridBagConstraints();
            g.insets=new Insets(4,4,4,4);
            g.fill=GridBagConstraints.HORIZONTAL;
            JButton mk=btn("NEW", theme.cyan), up=btn("UP", theme.lime), dn=btn("DOWN", theme.red), fl=btn("FLAT", theme.amber);
            JButton set=btn("SETTLE", theme.blue), or=btn("ORACLE JSON", theme.amber);
            stake.setFont(theme.fontMono);
            stake.setBackground(theme.bg1); stake.setForeground(theme.fg0); stake.setCaretColor(theme.fg0);
            stake.setBorder(BorderFactory.createLineBorder(theme.bg1,1));
            g.gridx=0; g.gridy=0; p.add(mk,g);
            g.gridx=1; g.gridy=0; p.add(lbl(theme,"STAKE"),g);
            g.gridx=2; g.gridy=0; p.add(stake,g);
            g.gridx=0; g.gridy=1; p.add(up,g);
            g.gridx=1; g.gridy=1; p.add(dn,g);
            g.gridx=2; g.gridy=1; p.add(fl,g);
            g.gridx=0; g.gridy=2; g.gridwidth=3; p.add(set,g);
            g.gridx=0; g.gridy=3; g.gridwidth=3; p.add(or,g);
            mk.addActionListener(e -> {
                String sym=state.activeSymbol;
                long strike=sim.quote(sym).priceE8;
                long band=Math.max(Fmt.e8("0.25"), strike/200);
                MarketSim.Market m=sim.mk(sym, strike, band, 2, 10, 20);
                state.activeMarketId=m.id;
                tape.add("MKT","created #"+m.id+" "+sym);
                refresh();
            });
            up.addActionListener(e -> bet(MarketSim.Bucket.UP));
            dn.addActionListener(e -> bet(MarketSim.Bucket.DOWN));
            fl.addActionListener(e -> bet(MarketSim.Bucket.FLAT));
            set.addActionListener(e -> {
                MarketSim.Market m=sim.get(state.activeMarketId);
                if (m==null){ tape.add("SET","no active market"); return; }
                long pz=sim.quote(m.symbol).priceE8;
                MarketSim.Settle s=sim.settle(m.id, pz);
                if (s==null) tape.add("SET","market not closable yet");
                else tape.add("SET","settled #"+m.id+" final="+Fmt.money(pz)+" winner="+s.winner);
                refresh();
            });
            or.addActionListener(e -> {
                MarketSim.Market m=sim.get(state.activeMarketId);
                if (m==null||m.settle==null){ tape.add("ORACLE","need settled market"); return; }
                OracleToy.Payload pl=oracle.settle(m.id, m.symbol, m.settle.priceE8, m.lockAt, m.closeAt);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(pl.json), null);
                tape.add("ORACLE","copied JSON");
            });
            return p;
        }
        private void bet(MarketSim.Bucket b){
            MarketSim.Market m=sim.get(state.activeMarketId);
            if (m==null){ tape.add("BET","no active market"); return; }
            long amt=Fmt.cents(stake.getText());
            if (amt<=0){ tape.add("BET","bad amount"); return; }
            if (!sim.bet(m.id,"YOU",b,amt)){ tape.add("BET","market not open"); return; }
            tape.add("BET","YOU "+b+" "+Fmt.money2(amt));
            refresh();
        }
        private String render(){
            StringBuilder sb=new StringBuilder();
            String sym=state.activeSymbol;
            MarketSim.Quote q=sim.quote(sym);
            MarketSim.Market m=sim.get(state.activeMarketId);
            sb.append("ACTIVE SYMBOL: ").append(sym).append("\n");
            sb.append("ACTIVE MARKET: ").append(m==null?"—":"#"+m.id).append("\n\n");
            sb.append("QUOTE\n");
            sb.append("  last=").append(Fmt.money(q.priceE8)).append("  chg%=").append(q.pct()).append("\n");
            sb.append("  spark=").append(q.spark).append("\n\n");
            if (m==null){
                sb.append("TIP\n  - click NEW or run: mk 100 0.5 2 10 20\n  - then bet: bet up|down|flat 250\n");
                return sb.toString();
            }
            sb.append("MARKET\n");
            sb.append("  symbol=").append(m.symbol).append("\n");
            sb.append("  strike=").append(Fmt.money(m.strikeE8)).append("\n");
            sb.append("  band=").append(Fmt.money(m.bandE8)).append("\n");
            sb.append("  openAt=").append(Fmt.epoch(m.openAt)).append("\n");
            sb.append("  lockAt=").append(Fmt.epoch(m.lockAt)).append("\n");
            sb.append("  closeAt=").append(Fmt.epoch(m.closeAt)).append("\n\n");
            sb.append("POOLS\n");
            sb.append("  UP=").append(Fmt.money2(m.poolUp)).append("\n");
            sb.append("  DOWN=").append(Fmt.money2(m.poolDown)).append("\n");
            sb.append("  FLAT=").append(Fmt.money2(m.poolFlat)).append("\n");
            sb.append("  TOTAL=").append(Fmt.money2(m.poolTotal)).append("\n\n");
            sb.append("BETS\n");
            if (m.bets.isEmpty()) sb.append("  (none)\n");
            for (MarketSim.Bet b : m.bets) sb.append("  ").append(Norm.pad(b.who,6)).append(" ").append(Norm.pad(b.bucket.name(),4)).append(" ").append(Fmt.money2(b.amount)).append("\n");
            sb.append("\nSTATE\n  phase=").append(m.phase()).append("\n");
            if (m.settle!=null) sb.append("  final=").append(Fmt.money(m.settle.priceE8)).append(" winner=").append(m.settle.winner).append("\n");
            else sb.append("  settle: after close\n");
            return sb.toString();
        }
        private static JButton btn(String t, Color fg){
            JButton b=new JButton(t);
            b.setFocusPainted(false);
            b.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            b.setBackground(new Color(18,24,32));
            b.setForeground(fg);
            b.setBorder(BorderFactory.createLineBorder(new Color(28,36,48),1));
            return b;
        }
        private static JLabel lbl(Theme theme, String t){ JLabel l=new JLabel(t); l.setFont(theme.fontSm); l.setForeground(theme.fg1); return l; }
    }

    static final class HelpPanel extends JPanel {
        HelpPanel(Theme theme) {
            super(new BorderLayout());
            setPreferredSize(new Dimension(440,620));
            setBackground(theme.bg0);
            JTextArea a=new JTextArea();
            a.setFont(theme.fontSm);
            a.setBackground(theme.bg0);
            a.setForeground(theme.fg0);
            a.setEditable(false);
            a.setBorder(new EmptyBorder(10,10,10,10));
            a.setText("HedgeBet quick guide\\n-------------------\\nhelp | clear\\nwatch list | watch add LAMA/USD | watch del ETH/USD\\nsym LAMA/USD\\nmk 100 0.5 2 10 20\\nbet up 250 | bet down 250 | bet flat 250\\nsettle 101.25\\noracle id | oracle build\\n");
            add(new JScrollPane(a), BorderLayout.CENTER);
        }
    }

    private static JPanel title(Theme theme, String left, String right){
        JPanel p=new JPanel(new BorderLayout());
        p.setBackground(theme.bg1);
        JLabel l=new JLabel(" "+left);
        l.setFont(theme.fontMd);
        l.setForeground(theme.cyan);
        JLabel r=new JLabel(" "+right+" ");
        r.setFont(theme.fontSm);
        r.setForeground(theme.dim);
        p.add(l, BorderLayout.WEST);
        p.add(r, BorderLayout.EAST);
        return p;
    }

    static final class State {
        List<String> watch = new ArrayList<>();
        String activeSymbol = "LAMA/USD";
        int activeMarketId = 0;
        void copyFrom(State o){ watch=new ArrayList<>(o.watch); activeSymbol=o.activeSymbol; activeMarketId=o.activeMarketId; }
    }

    static final class Persist {
        private final Path file = Path.of(System.getProperty("user.home"), ".hedgebet_state.properties");
        void save(State s){
            try{
                Properties p=new Properties();
                p.setProperty("activeSymbol", s.activeSymbol==null?"":s.activeSymbol);
                p.setProperty("activeMarketId", Integer.toString(s.activeMarketId));
                p.setProperty("watch", String.join(",", s.watch));
                try (OutputStream os=Files.newOutputStream(file)){ p.store(os, "HedgeBet"); }
            } catch (Exception ignored){}
        }
        State load(){
            try{
                if (!Files.exists(file)) return null;
                Properties p=new Properties();
                try (InputStream is=Files.newInputStream(file)){ p.load(is); }
                State s=new State();
                s.activeSymbol=p.getProperty("activeSymbol","LAMA/USD");
                s.activeMarketId=Integer.parseInt(p.getProperty("activeMarketId","0"));
                String w=p.getProperty("watch","");
                if (!w.isBlank()) s.watch.addAll(Arrays.asList(w.split(",")));
                return s;
            } catch (Exception e){ return null; }
        }
    }

    static final class TapeItem { final String t, tag, msg; TapeItem(String t,String tag,String msg){this.t=t;this.tag=tag;this.msg=msg;} }
    static final class Tape {
        private final ConcurrentLinkedQueue<TapeItem> q=new ConcurrentLinkedQueue<>();
        private final Deque<TapeItem> ring=new ArrayDeque<>();
        private final int cap;
        Tape(int cap){this.cap=cap;}
        void add(String tag,String msg){
            TapeItem it=new TapeItem(clock(), tag, msg);
            q.add(it); ring.addLast(it);
            while (ring.size()>cap) ring.removeFirst();
        }
        TapeItem poll(){ return q.poll(); }
    }

    static final class CmdCtx { final List<String> args; CmdCtx(List<String> args){this.args=args;} }
    interface CmdFn { CmdResult run(CmdCtx c); }
    static final class Cmd { final String help; final CmdFn fn; Cmd(String help, CmdFn fn){this.help=help; this.fn=fn;} }
    static final class CmdResult {
        final String tag, toast; final List<String> lines; final Runnable mutate;
        CmdResult(String tag,String toast,List<String> lines,Runnable mutate){this.tag=tag;this.toast=toast;this.lines=lines;this.mutate=mutate;}
        static CmdResult ok(String tag,String toast){ return new CmdResult(tag, toast, null, null); }
        static CmdResult err(String tag,String toast){ return new CmdResult(tag, toast, List.of("! "+toast), null); }
        static CmdResult lines(String tag,String toast,List<String> lines){ return new CmdResult(tag, toast, lines, null); }
        static CmdResult mutate(String tag,String toast,Runnable r){ return new CmdResult(tag, toast, null, r); }
    }
    static final class CmdEngine {
        private final Map<String, Cmd> map=new LinkedHashMap<>();
        void reg(String name,String help,CmdFn fn){ map.put(name, new Cmd(help, fn)); }
        CmdResult exec(String raw){
            List<String> a=Norm.split(raw);
            if (a.isEmpty()) return null;
            Cmd c=map.get(a.get(0).toLowerCase(Locale.ROOT));
            if (c==null) return CmdResult.err("CMD","unknown: "+a.get(0));
            try{ return c.fn.run(new CmdCtx(a)); } catch (Exception e){ return CmdResult.err("CMD","error: "+e.getMessage()); }
        }
    }

    static final class WatchModel extends javax.swing.table.AbstractTableModel {
        private final State state; private final MarketSim sim;
        private final String[] cols={"SYMBOL","LAST","CHG%","SPARK"};
        WatchModel(State state, MarketSim sim){ this.state=state; this.sim=sim; }
        @Override public int getRowCount(){ return state.watch.size(); }
        @Override public int getColumnCount(){ return cols.length; }
        @Override public String getColumnName(int c){ return cols[c]; }
        @Override public Object getValueAt(int r,int c){
            String sym=state.watch.get(r);
            MarketSim.Quote q=sim.quote(sym);
            return switch (c){
                case 0 -> sym;
                case 1 -> Fmt.money(q.priceE8);
                case 2 -> q.pct();
                case 3 -> q.spark;
                default -> "";
            };
        }
    }

    static final class MarketSim {
        enum Bucket {UP, DOWN, FLAT}
        static final class Quote {
            long priceE8, prevE8; String spark;
            String pct(){ if (prevE8<=0) return "0.00%"; double p=((double)priceE8-(double)prevE8)/(double)prevE8*100.0; return String.format(Locale.ROOT,"%+.2f%%",p); }
        }
        static final class Bet { final String who; final Bucket bucket; final long amount; Bet(String who,Bucket bucket,long amount){this.who=who;this.bucket=bucket;this.amount=amount;} }
        static final class Settle { final long priceE8; final Bucket winner; final long winningPool; Settle(long priceE8,Bucket winner,long winningPool){this.priceE8=priceE8;this.winner=winner;this.winningPool=winningPool;} }
        static final class Market {
            final int id; final String symbol; final long strikeE8, bandE8; final long openAt, lockAt, closeAt;
            long poolUp, poolDown, poolFlat, poolTotal; final List<Bet> bets=new ArrayList<>(); Settle settle;
            Market(int id,String symbol,long strikeE8,long bandE8,long openAt,long lockAt,long closeAt){ this.id=id;this.symbol=symbol;this.strikeE8=strikeE8;this.bandE8=bandE8;this.openAt=openAt;this.lockAt=lockAt;this.closeAt=closeAt; }
            String phase(){ long now=System.currentTimeMillis()/1000; if (settle!=null) return "SETTLED"; if (now<openAt) return "PREOPEN"; if (now<lockAt) return "OPEN"; if (now<closeAt) return "LOCKED"; return "CLOSEWAIT"; }
        }
        private final SecureRandom rng;
        private final Map<String, Quote> quotes=new HashMap<>();
        private final Map<Integer, Market> markets=new HashMap<>();
        private int nextId;
        MarketSim(SecureRandom rng){ this.rng=rng; }
        Quote quote(String sym){
            return quotes.computeIfAbsent(sym, s -> {
                Quote q=new Quote();
                q.priceE8=Fmt.e8("100.00")+Math.abs(rng.nextLong()%Fmt.e8("25.00"));
                q.prevE8=q.priceE8;
                q.spark=Spark.seed(rng, 24);
                return q;
            });
        }
        void step(){
            for (Quote q : quotes.values()){
                q.prevE8=q.priceE8;
                long drift=(long)(rng.nextGaussian()*(double)Fmt.e8("0.20"));
                long shock=(rng.nextInt(1000)<5)?(long)(rng.nextGaussian()*(double)Fmt.e8("2.50")):0;
                q.priceE8=Math.max(1, q.priceE8+drift+shock);
                q.spark=Spark.roll(q.spark, q.priceE8, q.prevE8);
            }
        }
        Market mk(String symbol,long strikeE8,long bandE8,int openSec,int lockSec,int closeSec){
            long now=System.currentTimeMillis()/1000;
            Market m=new Market(++nextId, symbol, strikeE8, bandE8, now+openSec, now+lockSec, now+closeSec);
            markets.put(m.id, m);
            return m;
        }
        Market get(int id){ return markets.get(id); }
        boolean bet(int id,String who,Bucket b,long amount){
            Market m=markets.get(id);
            if (m==null) return false;
            long now=System.currentTimeMillis()/1000;
            if (now<m.openAt||now>=m.lockAt) return false;
            m.bets.add(new Bet(who,b,amount));
            if (b==Bucket.UP) m.poolUp+=amount;
            else if (b==Bucket.DOWN) m.poolDown+=amount;
            else m.poolFlat+=amount;
            m.poolTotal+=amount;
            return true;
        }
        Settle settle(int id,long priceE8){
            Market m=markets.get(id);
            if (m==null) return null;
            long now=System.currentTimeMillis()/1000;
            if (now<m.closeAt) return null;
            long delta=Math.abs(priceE8-m.strikeE8);
            Bucket win=(delta<=m.bandE8)?Bucket.FLAT:(priceE8>m.strikeE8?Bucket.UP:Bucket.DOWN);
            long wp=(win==Bucket.UP)?m.poolUp:(win==Bucket.DOWN?m.poolDown:m.poolFlat);
            m.settle=new Settle(priceE8, win, wp);
            return m.settle;
        }
    }

    static final class OracleToy {
        private final SecureRandom rng;
        private final byte[] key=new byte[32];
        private long nonce;
        OracleToy(SecureRandom rng){ this.rng=rng; rng.nextBytes(key); nonce=1000+Math.abs(rng.nextInt(9000)); }
        String keyHint(){ byte[] h=Hash.sha3(key); return "0x"+Hex.hex(Arrays.copyOfRange(h,0,8))+"…"; }
        Payload settle(int marketId,String symbol,long priceE8,long lockAt,long closeAt){
            long n=++nonce;
            byte[] meta=new byte[32]; rng.nextBytes(meta);
            byte[] symH=Hash.sha3(symbol.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            write(out,"LMX_MARKET_TYPEHASH".getBytes(StandardCharsets.UTF_8));
            write(out,Bytes.u256(marketId));
            write(out,symH);
            write(out,Bytes.u64(priceE8));
            write(out,Bytes.u64(lockAt));
            write(out,Bytes.u64(closeAt));
            write(out,Bytes.u256(n));
            write(out,meta);
            byte[] digest=Hash.sha3(out.toByteArray());
            byte[] r=Hash.sha3(Bytes.concat(key,digest));
            byte[] s=Hash.sha3(Bytes.concat(digest,key));
            byte v=(byte)(27+(r[0]&1));
            byte[] sig=Bytes.concat(Arrays.copyOf(r,32), Arrays.copyOf(s,32), new byte[]{v});
            String json="{\\n"+
                "  \\\"marketId\\\": "+marketId+",\\n"+
                "  \\\"symbolHash\\\": \\\"0x"+Hex.hex(symH)+"\\\",\\n"+
                "  \\\"priceE8\\\": "+priceE8+",\\n"+
                "  \\\"lockAt\\\": "+lockAt+",\\n"+
                "  \\\"closeAt\\\": "+closeAt+",\\n"+
                "  \\\"oracleNonce\\\": "+n+",\\n"+
                "  \\\"meta\\\": \\\"0x"+Hex.hex(meta)+"\\\",\\n"+
                "  \\\"digest\\\": \\\"0x"+Hex.hex(digest)+"\\\",\\n"+
                "  \\\"sig\\\": \\\"0x"+Hex.hex(sig)+"\\\"\\n"+
                "}\\n";
            return new Payload(n, "0x"+Hex.hex(meta), "0x"+Hex.hex(digest), "0x"+Hex.hex(sig), json);
        }
        private static void write(ByteArrayOutputStream out, byte[] b){ try{ out.write(b); } catch (IOException ignored){} }
        static final class Payload {
            final long oracleNonce; final String metaHex,digestHex,sigHex,json;
            Payload(long oracleNonce,String metaHex,String digestHex,String sigHex,String json){this.oracleNonce=oracleNonce;this.metaHex=metaHex;this.digestHex=digestHex;this.sigHex=sigHex;this.json=json;}
        }
    }

    static final class Fmt {
        static long e8(String s){
            try{
                String x=s.trim(); if (x.isBlank()) return 0;
                boolean neg=x.startsWith("-"); if (neg) x=x.substring(1);
                String[] p=x.split("\\\\.");
                String a=p[0].isEmpty()?"0":p[0];
                String b=(p.length>1)?p[1]:"";
                if (b.length()>8) b=b.substring(0,8);
                while (b.length()<8) b+="0";
                long hi=Long.parseLong(a);
                long lo=b.isEmpty()?0:Long.parseLong(b);
                long v=hi*100_000_000L+lo;
                return neg?-v:v;
            } catch (Exception e){ return 0; }
        }
        static long cents(String s){
            try{
                String x=(s==null?"":s).trim().replace(",",""); if (x.isBlank()) return 0;
                boolean neg=x.startsWith("-"); if (neg) x=x.substring(1);
                String[] p=x.split("\\\\.");
                String a=p[0].isEmpty()?"0":p[0];
                String b=(p.length>1)?p[1]:"";
                if (b.length()>2) b=b.substring(0,2);
                while (b.length()<2) b+="0";
                long hi=Long.parseLong(a);
                long lo=b.isEmpty()?0:Long.parseLong(b);
                long v=hi*100L+lo;
                return neg?-v:v;
            } catch (Exception e){ return 0; }
        }
        static String money(long e8){ return String.format(Locale.ROOT,"%.2f",(double)e8/1e8); }
        static String money2(long cents){ return String.format(Locale.ROOT,"%.2f",(double)cents/100.0); }
        static String epoch(long sec){ return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(sec)); }
    }

    static final class Norm {
        static String symbol(String s){
            if (s==null) return "";
            String x=s.trim().toUpperCase(Locale.ROOT).replaceAll("\\\\s+","");
            if (!x.contains("/")) return "";
            if (x.length()<5||x.length()>18) return "";
            return x;
        }
        static int i(String s){ try{ return Integer.parseInt(s.trim()); } catch (Exception e){ return 0; } }
        static List<String> split(String raw){
            List<String> out=new ArrayList<>();
            if (raw==null) return out;
            String s=raw.trim();
            if (s.isEmpty()) return out;
            StringBuilder cur=new StringBuilder();
            boolean inQ=false;
            for (int i=0;i<s.length();i++){
                char c=s.charAt(i);
                if (c=='\"'){ inQ=!inQ; continue; }
                if (!inQ && Character.isWhitespace(c)){
                    if (!cur.isEmpty()){ out.add(cur.toString()); cur.setLength(0); }
                } else cur.append(c);
            }
            if (!cur.isEmpty()) out.add(cur.toString());
            return out;
        }
        static String pad(String s,int w){ if (s.length()>=w) return s; StringBuilder b=new StringBuilder(s); while (b.length()<w) b.append(' '); return b.toString(); }
    }

    static final class Spark {
        private static final char[] blocks={'▁','▂','▃','▄','▅','▆','▇','█'};
        static String seed(SecureRandom rng,int n){ StringBuilder b=new StringBuilder(); for(int i=0;i<n;i++) b.append(blocks[rng.nextInt(blocks.length)]); return b.toString(); }
        static String roll(String prev,long now,long prior){
            if (prior<=0) return seed(new SecureRandom(),24);
            double p=((double)now-(double)prior)/(double)prior;
            double x=Math.max(-0.04, Math.min(0.04, p));
            double t=(x+0.04)/0.08;
            int idx=(int)Math.round(t*(blocks.length-1));
            idx=Math.max(0, Math.min(blocks.length-1, idx));
            String s=(prev==null||prev.length()<2)?seed(new SecureRandom(),24):prev;
            return s.substring(1)+blocks[idx];
        }
    }

    static final class Bytes {
        static byte[] concat(byte[]... a){
            int n=0; for (byte[] b : a) n+=(b==null?0:b.length);
            byte[] out=new byte[n]; int p=0;
            for (byte[] b : a){ if (b==null) continue; System.arraycopy(b,0,out,p,b.length); p+=b.length; }
            return out;
        }
        static byte[] u64(long x){
            byte[] out=new byte[8]; long v=x;
            for (int i=7;i>=0;i--){ out[i]=(byte)(v&0xFF); v>>>=8; }
            return out;
        }
        static byte[] u256(long x){
            byte[] out=new byte[32]; long v=x;
            for (int i=31;i>=24;i--){ out[i]=(byte)(v&0xFF); v>>>=8; }
            return out;
        }
    }

    static final class Hash {
        static byte[] sha3(byte[] input){
            try{ return MessageDigest.getInstance("SHA3-256").digest(input); }
            catch (Exception e){
                try{ return MessageDigest.getInstance("SHA-256").digest(input); }
                catch (Exception ex){ throw new RuntimeException("no digest provider"); }
            }
        }
    }

    static final class Hex {
        private static final char[] HEX="0123456789abcdef".toCharArray();
        static String hex(byte[] b){
            char[] out=new char[b.length*2];
            for (int i=0;i<b.length;i++){
                int v=b[i]&0xFF;
                out[i*2]=HEX[v>>>4];
                out[i*2+1]=HEX[v&0x0F];
            }
            return new String(out);
        }
    }

}
