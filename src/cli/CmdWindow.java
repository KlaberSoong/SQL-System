package cli;

import engine.CatalogManager;
import engine.StorageEngine;
import sql_compiler.Catalog;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * cmd 风格图形界面：黑底、等宽字体、绿色文字与 {@code MiniDB>} 提示符，整体观感类似
 * Windows 命令提示符。上方为只读的滚动输出区，下方为单行输入框（支持上下方向键翻命令
 * 历史）。输入 {@code quit} / {@code exit} 或直接关闭窗口退出。
 */
public class CmdWindow {
    private final StorageEngine storage;
    private final CatalogManager catalogManager;
    private final Catalog catalog;

    private JFrame frame;
    private JTextArea output;
    private JTextField input;

    /** 命令历史与当前浏览位置（用于上下方向键回显历史命令）。 */
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;

    private static final Color BG = Color.BLACK;
    private static final Color FG = new Color(0x3f, 0xf2, 0x3f); // 经典终端绿
    private static final Color FG_INPUT = new Color(0xf0, 0xf0, 0xf0);
    // 用逻辑字体 "Monospaced"（而非物理字体 "Consolas"）：物理字体缺少 CJK 字形时不会回退，
    // 中文会显示成乱码/方框；逻辑字体会在 Windows 上自动回退（拉丁用 Consolas，中文用宋体）。
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 14);

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

        output = new JTextArea();
        output.setEditable(false);
        output.setBackground(BG);
        output.setForeground(FG);
        output.setFont(MONO);
        output.setCaretColor(FG);
        output.setLineWrap(false);

        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);

        JLabel prompt = new JLabel("MiniDB> ");
        prompt.setForeground(FG);
        prompt.setBackground(BG);
        prompt.setOpaque(true);
        prompt.setFont(MONO_BOLD);

        input = new JTextField();
        input.setBackground(BG);
        input.setForeground(FG_INPUT);
        input.setCaretColor(FG_INPUT);
        input.setFont(MONO);
        input.setBorder(BorderFactory.createEmptyBorder());
        input.addActionListener(e -> onSubmit());
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    recall(-1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    recall(1);
                    e.consume();
                }
            }
        });

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBackground(BG);
        inputPanel.setBorder(new EmptyBorder(4, 6, 6, 6));
        inputPanel.add(prompt, BorderLayout.WEST);
        inputPanel.add(input, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(inputPanel, BorderLayout.SOUTH);

        appendLine("MiniDB (Java) — cmd 风格界面");
        appendLine("输入 SQL 语句后回车执行；输入 quit / exit 退出；↑↓ 翻历史命令。");
        appendLine("");

        frame.setVisible(true);
        input.requestFocusInWindow();
    }

    /** 回车提交一条命令。 */
    private void onSubmit() {
        String line = input.getText().trim();
        input.setText("");
        if (line.isEmpty()) {
            return;
        }
        history.add(line);
        historyPos = history.size();
        appendLine("MiniDB> " + line);

        if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
            appendLine("Bye.");
            frame.dispose();
            System.exit(0);
            return;
        }

        appendLine(Main.executeAndFormat(line, storage, catalogManager, catalog));
        appendLine("");
    }

    /** 按上下方向键从历史中回显命令（delta 为 -1 向上、1 向下）。 */
    private void recall(int delta) {
        if (history.isEmpty()) {
            return;
        }
        historyPos += delta;
        if (historyPos < 0) {
            historyPos = 0;
        }
        if (historyPos >= history.size()) {
            historyPos = history.size();
            input.setText("");
            return;
        }
        input.setText(history.get(historyPos));
        input.setCaretPosition(input.getText().length());
    }

    /** 向输出区追加一行并滚动到底部。 */
    private void appendLine(String s) {
        output.append(s);
        output.append("\n");
        output.setCaretPosition(output.getDocument().getLength());
    }
}
