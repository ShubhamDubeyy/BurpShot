package burp;

import burp.api.montoya.*;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.regex.*;

public class RepeaterScreenshotMode implements BurpExtension {

    private MontoyaApi api;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("BurpShot");
        api.userInterface().registerContextMenuItemsProvider(new ContextMenu());
    }

    /* ================= FONT HELPER ================= */

    private static Font fira(float size) {
        for (Font f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
            if (f.getName().equalsIgnoreCase("Fira Code Retina")) {
                return f.deriveFont(size);
            }
        }
        return new Font("Monospaced", Font.PLAIN, (int) size);
    }

    /* ================= CONTEXT MENU ================= */

    private class ContextMenu implements ContextMenuItemsProvider {
        @Override
        public List<Component> provideMenuItems(ContextMenuEvent e) {
            if (!e.isFromTool(ToolType.REPEATER) || e.messageEditorRequestResponse().isEmpty())
                return null;

            HttpRequestResponse rr =
                e.messageEditorRequestResponse().get().requestResponse();

            JMenuItem item = new JMenuItem("Open BurpShot");
            item.addActionListener(a ->
                SwingUtilities.invokeLater(() -> new ScreenshotDialog(rr))
            );
            return List.of(item);
        }
    }

    /* ================= MAIN WINDOW ================= */

    private class ScreenshotDialog extends JFrame {

        private final SmartTextPane req = new SmartTextPane();
        private final SmartTextPane resp = new SmartTextPane();
        private final JLabel lblReq = new JLabel("REQUEST");
        private final JLabel lblResp = new JLabel("RESPONSE");
        private final JLabel lenLabel = new JLabel(" Length: 0 bytes ");
        private final JTextField urlField = new JTextField();

        private final UndoManager undoManager = new UndoManager();
        private final List<SearchResult> findMatches = new ArrayList<>();
        private int findIndex = -1;
        private boolean isDark = true;

        ScreenshotDialog(HttpRequestResponse rr) {
            super("BurpShot");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(
                (int)(Toolkit.getDefaultToolkit().getScreenSize().width * 0.85),
                (int)(Toolkit.getDefaultToolkit().getScreenSize().height * 0.85)
            );
            setLocationRelativeTo(null);

            JPanel mainPanel = new JPanel(new BorderLayout());
            mainPanel.setBackground(new Color(30,30,30));
            setContentPane(mainPanel);

            /* URL BAR */
            urlField.setEditable(false);
            urlField.setFont(fira(15f));
            urlField.setBorder(new EmptyBorder(12,15,12,15));
            urlField.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            urlField.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    copyToClipboard(urlField.getText());
                }
            });
            add(urlField, BorderLayout.NORTH);

            setupPane(req);
            setupPane(resp);

            // Corrected Undo Listening
            req.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));
            resp.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

            JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createPanel(lblReq, req),
                createPanel(lblResp, resp)
            );
            split.setResizeWeight(0.5);
            split.setDividerSize(2);
            split.setBorder(null);
            split.setBackground(new Color(30,30,30));
            add(split, BorderLayout.CENTER);

            /* BOTTOM BAR */
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setBackground(new Color(35,35,35));

            JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,15,12));
            statusPanel.setOpaque(false);
            styleStatusLabel(lenLabel);
            statusPanel.add(lenLabel);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT,12,8));
            actions.setOpaque(false);

            JButton btnFind = new JButton("Find");
            JButton btnAuto = new JButton("Auto-Redact");
            JButton btnUndo = new JButton("Undo"); // Added Undo Button
            JButton btnCopyReq = new JButton("Copy Req");
            JButton btnCopyResp = new JButton("Copy Resp");
            JButton btnTheme = new JButton("Theme");
            JButton btnSave = new JButton("Save Image");

            JCheckBox chkWrap = new JCheckBox("WRAP");
            chkWrap.setFont(fira(15f));
            chkWrap.setForeground(new Color(255,153,51));
            chkWrap.setOpaque(false);

            for (JButton b : List.of(btnFind, btnAuto, btnUndo, btnCopyReq, btnCopyResp, btnTheme, btnSave)) {
                b.setFont(fira(14f));
            }

            btnFind.addActionListener(e -> showFindDialog());
            btnAuto.addActionListener(e ->
                req.setText(req.getText().replaceAll(
                    "(?i)(Cookie|Authorization):\\s*[^\\r\\n]+",
                    "$1: [REDACTED]"
                ))
            );
            
            // Undo Logic
            btnUndo.addActionListener(e -> {
                if (undoManager.canUndo()) undoManager.undo();
            });

            btnCopyReq.addActionListener(e -> copyToClipboard(req.getText()));
            btnCopyResp.addActionListener(e -> copyToClipboard(resp.getText()));
            chkWrap.addActionListener(e -> {
                req.setWrap(chkWrap.isSelected());
                resp.setWrap(chkWrap.isSelected());
            });
            btnTheme.addActionListener(e -> {
                isDark = !isDark;
                applyTheme();
            });
            btnSave.addActionListener(e -> saveImg());

            actions.add(btnFind);
            actions.add(btnAuto);
            actions.add(btnUndo); // Added to UI
            actions.add(btnCopyReq);
            actions.add(btnCopyResp);
            actions.add(chkWrap);
            actions.add(btnTheme);
            actions.add(btnSave);

            bottom.add(statusPanel, BorderLayout.WEST);
            bottom.add(actions, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);

            setupContext(req);
            setupContext(resp);

            refresh(rr);
            applyTheme();
            setVisible(true);
        }

        /* ================= FIND ================= */

        private void showFindDialog() {
            JDialog d = new JDialog(this, "Find", false);
            d.setUndecorated(true);
            d.setSize(520, 90);
            d.setLocationRelativeTo(this);
            d.getRootPane().setBorder(
                BorderFactory.createLineBorder(new Color(255,153,51), 2)
            );

            JPanel p = new JPanel(new BorderLayout(15,0));
            p.setBackground(new Color(45,45,45));
            p.setBorder(new EmptyBorder(16,20,16,20));

            JTextField f = new JTextField();
            f.setFont(fira(18f));
            f.setBackground(new Color(45,45,45));
            f.setForeground(Color.WHITE);
            f.setCaretColor(new Color(255,153,51));
            f.setBorder(null);

            JLabel counter = new JLabel("0/0");
            counter.setFont(fira(16f));
            counter.setForeground(new Color(180,180,180));

            JButton next = new JButton("▶");
            next.setFont(fira(20f));
            next.setForeground(new Color(255,153,51));
            next.setBorderPainted(false);
            next.setContentAreaFilled(false);

            JButton close = new JButton("×");
            close.setFont(fira(22f));
            close.setForeground(Color.LIGHT_GRAY);
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.addActionListener(e -> d.dispose());

            f.addKeyListener(new KeyAdapter() {
                public void keyReleased(KeyEvent e) {
                    req.getHighlighter().removeAllHighlights();
                    resp.getHighlighter().removeAllHighlights();
                    findMatches.clear();

                    searchPane(req, f.getText());
                    searchPane(resp, f.getText());

                    if (!findMatches.isEmpty()) {
                        findIndex = 0;
                        applyFindHighlight();
                        counter.setText("1/" + findMatches.size());
                    } else counter.setText("0/0");
                }
            });

            next.addActionListener(e -> {
                if (!findMatches.isEmpty()) {
                    findIndex = (findIndex + 1) % findMatches.size();
                    applyFindHighlight();
                    counter.setText((findIndex + 1) + "/" + findMatches.size());
                }
            });

            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT,15,0));
            east.setOpaque(false);
            east.add(counter);
            east.add(next);
            east.add(close);

            p.add(f, BorderLayout.CENTER);
            p.add(east, BorderLayout.EAST);
            d.add(p);
            d.setVisible(true);
            f.requestFocusInWindow();
        }

        private void searchPane(JTextPane pane, String q) {
            if (q.isEmpty()) return;
            try {
                String t = pane.getDocument().getText(0, pane.getDocument().getLength());
                Matcher m = Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE).matcher(t);
                while (m.find()) findMatches.add(new SearchResult(pane, m.start(), m.end()));
            } catch (Exception ignored) {}
        }

        private void applyFindHighlight() {
            req.getHighlighter().removeAllHighlights();
            resp.getHighlighter().removeAllHighlights();
            try {
                SearchResult r = findMatches.get(findIndex);
                r.pane.getHighlighter().addHighlight(
                    r.start, r.end,
                    new DefaultHighlighter.DefaultHighlightPainter(
                        new Color(255,153,51,150)
                    )
                );
                r.pane.scrollRectToVisible(r.pane.modelToView2D(r.start).getBounds());
            } catch (Exception ignored) {}
        }

        /* ================= UTIL ================= */

        void refresh(HttpRequestResponse rr) {
            urlField.setText(rr.request().url());
            req.setText(rr.request().toString());
            resp.setText(rr.response() == null ? "" : rr.response().toString());
            lenLabel.setText(
                " Length: " +
                (rr.response() == null ? 0 : rr.response().toString().length()) +
                " bytes "
            );
        }

        private void applyTheme() {
            Color bg = isDark ? new Color(30,30,30) : Color.WHITE;
            Color fg = isDark ? Color.WHITE : Color.BLACK;
            getContentPane().setBackground(bg);
            req.setBackground(bg);
            req.setForeground(fg);
            resp.setBackground(bg);
            resp.setForeground(fg);
            urlField.setBackground(isDark ? new Color(20,20,20) : new Color(245,245,245));
            urlField.setForeground(fg);
            lblReq.setForeground(new Color(255,153,51));
            lblResp.setForeground(new Color(255,153,51));
        }

        private void setupPane(JTextPane p) {
            p.setEditable(true); // Must be true for Undo and selection changes to work properly
            p.setFont(fira(15f));
            p.setBorder(new EmptyBorder(10,15,10,15));
        }

        private JPanel createPanel(JLabel l, JTextPane p) {
            JPanel pan = new JPanel(new BorderLayout());
            pan.setOpaque(false);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setFont(fira(15f));
            l.setBorder(new EmptyBorder(10,0,10,0));
            JScrollPane scroll = new JScrollPane(p);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(isDark ? new Color(30,30,30) : Color.WHITE);
            pan.add(l, BorderLayout.NORTH);
            pan.add(scroll, BorderLayout.CENTER);
            return pan;
        }

        private void setupContext(JTextPane p) {
            JPopupMenu menu = new JPopupMenu();

            JMenuItem h = new JMenuItem("Highlight Selection");
            JMenuItem r = new JMenuItem("Redact Selection");
            JMenuItem rm = new JMenuItem("Remove Selection");

            h.addActionListener(e -> {
                SimpleAttributeSet sas = new SimpleAttributeSet();
                StyleConstants.setBackground(sas, new Color(255,153,51,150));
                p.getStyledDocument().setCharacterAttributes(
                    p.getSelectionStart(),
                    p.getSelectionEnd() - p.getSelectionStart(),
                    sas, false
                );
            });

            r.addActionListener(e -> {
                SimpleAttributeSet sas = new SimpleAttributeSet();
                StyleConstants.setBackground(sas, Color.BLACK);
                StyleConstants.setForeground(sas, Color.BLACK);
                p.getStyledDocument().setCharacterAttributes(
                    p.getSelectionStart(),
                    p.getSelectionEnd() - p.getSelectionStart(),
                    sas, false
                );
            });

            rm.addActionListener(e -> {
                try {
                    p.getDocument().remove(
                        p.getSelectionStart(),
                        p.getSelectionEnd() - p.getSelectionStart()
                    );
                } catch (Exception ignored) {}
            });

            menu.add(h);
            menu.add(r);
            menu.add(rm);

            p.addMouseListener(new MouseAdapter() {
                public void mouseReleased(MouseEvent e) {
                    if (p.getSelectionStart() != p.getSelectionEnd())
                        menu.show(p, e.getX(), e.getY());
                }
            });
        }

        private void styleStatusLabel(JLabel l) {
            l.setOpaque(true);
            l.setBackground(new Color(45,45,45));
            l.setForeground(new Color(255,153,51));
            l.setFont(fira(12f));
            l.setBorder(BorderFactory.createLineBorder(new Color(65,65,65)));
        }

        private void saveImg() {
            BufferedImage img = new BufferedImage(
                getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB
            );
            paint(img.getGraphics());
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("BurpShot_" + System.currentTimeMillis() + ".png"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    ImageIO.write(img, "png", fc.getSelectedFile());
                } catch (Exception ignored) {}
            }
        }
    }

    /* ================= SUPPORT ================= */

    private static class SearchResult {
        JTextPane pane;
        int start, end;
        SearchResult(JTextPane p, int s, int e) {
            pane = p; start = s; end = e;
        }
    }

    private static class SmartTextPane extends JTextPane {
        private boolean wrap;
        void setWrap(boolean w) { wrap = w; revalidate(); }
        @Override public boolean getScrollableTracksViewportWidth() { return wrap; }
    }

    private static void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new StringSelection(s), null);
    }
}
