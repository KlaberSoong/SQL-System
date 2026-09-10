package cli;

import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;
import storage.BufferPool;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * cmd 风格图形界面：黑底、等宽字体、绿色文字，整体观感类似 Windows 命令提示符。
 * 用单一可编辑文本区模拟终端——提示符 {@code MiniDB>}、用户输入与执行结果在同一片区域
 * 交错显示：回车后结果紧接输入行下方，空一行后再出现新的 {@code MiniDB>} 提示符。
 * 支持上下方向键翻命令历史；多行 SQL（括号/引号未闭合）以 {@code ...>} 续行。
 */
public class CmdWindow {
    private final StorageEngine storage;
    private final CatalogManager catalogManager;
    private final Catalog catalog;

    private JFrame frame;
    private JTextArea terminal;
    private JLabel statusBar;
    private JTextArea logArea;

    /** 当前可编辑行（提示符之后）的起始位置；其左侧为只读保护区。 */
    private int promptPos = 0;

    /** 命令历史与当前浏览位置（用于上下方向键回显历史命令）。 */
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;

    /** 尚未闭合（括号/引号未配对）的多行 SQL 缓冲。 */
    private final StringBuilder pending = new StringBuilder();

    private static final String PROMPT = "MiniDB> ";
    private static final String CONTINUE_PROMPT = "  ...> ";

    private static final Color BG = Color.BLACK;
    private static final Color FG = Color.WHITE;
    // 用逻辑字体 "Monospaced"（而非物理字体 "Consolas"）：物理字体缺少 CJK 字形时不会回退，
    // 中文会显示成乱码/方框；逻辑字体会在 Windows 上自动回退（拉丁用 Consolas，中文用宋体）。
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    public CmdWindow(StorageEngine storage, CatalogManager catalogManager, Catalog catalog) {
        this.storage = storage;
        this.catalogManager = catalogManager;
        this.catalog = catalog;
    }

    /** 在 EDT 上构建并显示窗口。 */
    public void show() {
        frame = new JFrame("MiniDB");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(780, 520);
        frame.setLocationRelativeTo(null);

        terminal = new JTextArea();
        terminal.setBackground(BG);
        terminal.setForeground(FG);
        terminal.setCaretColor(FG);
        terminal.setFont(MONO);
        terminal.setLineWrap(false);
        terminal.setText(PROMPT);
        promptPos = terminal.getDocument().getLength();

        // 保护提示符及其上方的历史输出：任何插入/删除/替换都不得越过 promptPos。
        ((AbstractDocument) terminal.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                    throws BadLocationException {
                if (offset >= promptPos) {
                    super.insertString(fb, offset, string, attr);
                }
            }

            @Override
            public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
                if (offset >= promptPos) {
                    super.remove(fb, offset, length);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                    throws BadLocationException {
                if (offset >= promptPos) {
                    super.replace(fb, offset, length, text, attrs);
                }
            }
        });

        // 光标不得落到保护区内（例如用鼠标点历史输出时弹回提示符后）。
        terminal.addCaretListener(e -> {
            if (e.getDot() < promptPos) {
                terminal.setCaretPosition(promptPos);
            }
        });

        terminal.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ENTER) {
                    e.consume();
                    submitCurrentLine();
                } else if (code == KeyEvent.VK_UP) {
                    e.consume();
                    recallHistory(-1);
                } else if (code == KeyEvent.VK_DOWN) {
                    e.consume();
                    recallHistory(1);
                } else if (code == KeyEvent.VK_BACK_SPACE) {
                    if (terminal.getCaretPosition() <= promptPos || terminal.getSelectionStart() < promptPos) {
                        e.consume();
                    }
                } else if (code == KeyEvent.VK_DELETE) {
                    if (terminal.getSelectionStart() < promptPos) {
                        e.consume();
                    }
                } else if (code == KeyEvent.VK_LEFT) {
                    if (terminal.getCaretPosition() <= promptPos) {
                        e.consume();
                    }
                } else if (code == KeyEvent.VK_HOME) {
                    e.consume();
                    terminal.setCaretPosition(promptPos);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(terminal);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);

        frame.setLayout(new BorderLayout());
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(buildStatusPanel(), BorderLayout.SOUTH);

        // 把缓冲池事件（策略切换 / 淘汰）重定向到界面底部日志区，并同步刷新状态栏。
        BufferPool.setLogSink(msg -> SwingUtilities.invokeLater(() -> {
            appendLog(msg);
            refreshStatus();
        }));

        frame.setVisible(true);
        terminal.requestFocusInWindow();
        terminal.setCaretPosition(promptPos);
        refreshStatus();
    }

    /** 回车提交当前行；结果紧接输入行下方，空一行后再出现新提示符。 */
    private void submitCurrentLine() {
        String line = terminal.getText().substring(promptPos).trim();

        if (line.isEmpty()) {
            // 空行取消尚未完成的多行语句，避免一直卡在续行状态。
            if (pending.length() > 0) {
                pending.setLength(0);
                terminal.append("\n" + CONTINUE_PROMPT + "(已取消)");
            }
            appendNewPrompt();
            return;
        }

        if (pending.length() == 0 && ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line))) {
            terminal.append("\nBye.\n");
            frame.dispose();
            System.exit(0);
            return;
        }

        pending.append(line).append(' ');

        // 语句尚未闭合则继续等待后续行，不提交。
        if (!isComplete(pending.toString())) {
            appendContinuationPrompt();
            return;
        }

        String sql = pending.toString().trim();
        pending.setLength(0);
        history.add(sql);
        historyPos = history.size();

        // 结果紧接输入行下方，随后空一行，再输出新提示符。
        String result = Main.executeAndFormat(sql, storage, catalogManager, catalog);
        terminal.append("\n" + result);
        refreshStatus();
        terminal.append("\n\n");
        appendNewPrompt();
    }

    /** 在末尾追加新提示符，并把可编辑位置推进到提示符之后。 */
    private void appendNewPrompt() {
        terminal.append(PROMPT);
        promptPos = terminal.getDocument().getLength();
        terminal.setCaretPosition(promptPos);
    }

    /** 多行语句续行：换行后追加续行提示符 {@code ...>}。 */
    private void appendContinuationPrompt() {
        terminal.append("\n" + CONTINUE_PROMPT);
        promptPos = terminal.getDocument().getLength();
        terminal.setCaretPosition(promptPos);
    }

    /** 语句是否已闭合：括号配对且当前不在字符串字面量内部。 */
    private boolean isComplete(String sql) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }

    /** 按上下方向键从历史中回显命令（delta 为 -1 向上、1 向下）。 */
    private void recallHistory(int delta) {
        if (history.isEmpty()) {
            return;
        }
        historyPos += delta;
        if (historyPos < 0) {
            historyPos = 0;
        }
        if (historyPos >= history.size()) {
            historyPos = history.size();
            replaceInput("");
            return;
        }
        replaceInput(history.get(historyPos));
    }

    /** 用 {@code text} 替换当前可编辑行（提示符之后到末尾的内容）。 */
    private void replaceInput(String text) {
        int len = terminal.getDocument().getLength();
        terminal.replaceRange(text, promptPos, len);
        terminal.setCaretPosition(terminal.getDocument().getLength());
    }

    /** 构建底部状态面板：上方状态栏（策略/命中率），下方缓冲池事件日志区。 */
    private JPanel buildStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);

        statusBar = new JLabel(" ");
        statusBar.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statusBar.setForeground(new Color(0, 200, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(statusBar, BorderLayout.NORTH);

        logArea = new JTextArea(4, 40);
        logArea.setEditable(false);
        logArea.setBackground(BG);
        logArea.setForeground(new Color(170, 170, 170));
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.getViewport().setBackground(BG);
        panel.add(logScroll, BorderLayout.CENTER);

        return panel;
    }

    /** 追加一条缓冲池事件到日志区。 */
    private void appendLog(String msg) {
        logArea.append(msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** 读取各表缓冲池的命中统计与当前策略，刷新状态栏。 */
    private void refreshStatus() {
        int[] stats = storage.bufferPoolStats();
        int total = stats[0] + stats[1];
        double rate = total == 0 ? 0.0 : stats[0] * 100.0 / total;

        StringBuilder sb = new StringBuilder();
        for (BufferPool.Strategy s : storage.activeStrategies()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(s.name());
        }
        String strategy = sb.length() == 0 ? "—" : sb.toString();

        statusBar.setText(String.format("策略 %-6s | 命中 %d / 未命中 %d | 命中率 %.1f%%",
                strategy, stats[0], stats[1], rate));
    }
}
