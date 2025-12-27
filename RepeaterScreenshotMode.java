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
    private static File lastSavedDirectory = null;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("BurpShot");
        api.userInterface().registerContextMenuItemsProvider(new ContextMenu());
    }

    private static Font fira(float size) {
        for (Font f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
            if (f.getName().equalsIgnoreCase("Fira Code Retina")) return f.deriveFont(size);
        }
        return new Font("Monospaced", Font.BOLD, (int) size);
    }

    private class ContextMenu implements ContextMenuItemsProvider {
        @Override
        public List<Component> provideMenuItems(ContextMenuEvent e) {
            if (!e.isFromTool(ToolType.REPEATER) || e.messageEditorRequestResponse().isEmpty()) return null;
            HttpRequestResponse rr = e.messageEditorRequestResponse().get().requestResponse();
            JMenuItem item = new JMenuItem("Open BurpShot");
            item.addActionListener(a -> SwingUtilities.invokeLater(() -> new ScreenshotDialog(rr)));
            return List.of(item);
        }
    }

    private class ScreenshotDialog extends JFrame {
        private final AutoWrapPane req = new AutoWrapPane();
        private final AutoWrapPane resp = new AutoWrapPane();
        private final JTextField urlField = new JTextField();
        private final JLabel lenLabel = new JLabel(" Length: 0 bytes ");
        private final UndoManager undoManager = new UndoManager();
        private final List<SearchResult> findMatches = new ArrayList<>();
        private int findIndex = -1;
        private boolean isDark = true;

        ScreenshotDialog(HttpRequestResponse rr) {
            super("BurpShot");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize((int)(Toolkit.getDefaultToolkit().getScreenSize().width * 0.85),
                    (int)(Toolkit.getDefaultToolkit().getScreenSize().height * 0.85));
            setLocationRelativeTo(null);

            JPanel mainPanel = new JPanel(new BorderLayout());
            mainPanel.setBackground(new Color(30,30,30));
            setContentPane(mainPanel);

            urlField.setEditable(false);
            urlField.setFont(fira(15f));
            urlField.setBorder(new EmptyBorder(12,15,12,15));
            urlField.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { copyToClipboard(urlField.getText()); }
            });
            add(urlField, BorderLayout.NORTH);

            setupPane(req); setupPane(resp);
            req.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));
            resp.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

            // High-visibility titles
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createPanel("REQUEST", req), createPanel("RESPONSE", resp));
            split.setResizeWeight(0.5); split.setDividerSize(2); split.setBorder(null);
            add(split, BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setBackground(new Color(35,35,35));
            JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,15,12)); statusPanel.setOpaque(false);
            styleStatusLabel(lenLabel); statusPanel.add(lenLabel);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT,12,8)); actions.setOpaque(false);
            JButton btnFind = new JButton("Find"), btnAuto = new JButton("Auto-Redact"), btnUndo = new JButton("Undo"),
                    btnCopyReq = new JButton("Copy Req"), btnCopyResp = new JButton("Copy Resp"), btnTheme = new JButton("Theme"),
                    btnSave = new JButton("Save Image");

            for (JButton b : List.of(btnFind, btnAuto, btnUndo, btnCopyReq, btnCopyResp, btnTheme, btnSave)) {
                b.setFont(fira(14f)); actions.add(b);
            }

            btnFind.addActionListener(e -> showFindDialog());
            btnAuto.addActionListener(e -> req.setText(req.getText().replaceAll("(?i)(Cookie|Authorization):\\s*[^\\r\\n]+", "$1: [REDACTED]")));
            btnUndo.addActionListener(e -> { if (undoManager.canUndo()) undoManager.undo(); });
            btnCopyReq.addActionListener(e -> copyToClipboard(req.getText()));
            btnCopyResp.addActionListener(e -> copyToClipboard(resp.getText()));
            btnTheme.addActionListener(e -> { isDark = !isDark; applyTheme(); });
            btnSave.addActionListener(e -> saveImg());

            bottom.add(statusPanel, BorderLayout.WEST); bottom.add(actions, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);

            setupContext(req); setupContext(resp);
            refresh(rr); applyTheme();
            setVisible(true);
        }

        private String formatMessage(String raw) {
            if (raw == null || raw.isEmpty()) return "";
            int splitIdx = raw.indexOf("\r\n\r\n");
            if (splitIdx == -1) return raw;
            String headers = raw.substring(0, splitIdx);
            String body = raw.substring(splitIdx + 4);
            String lowHeaders = headers.toLowerCase();
            if (lowHeaders.contains("text/html") || body.trim().startsWith("<!doctype") || body.trim().startsWith("<html")) 
                return headers + "\r\n\r\n" + prettyPrintHtml(body);
            if (body.contains("{") || body.contains("[") || lowHeaders.contains("json")) 
                return headers + "\r\n\r\n" + prettyPrintJson(body);
            return raw;
        }

        private String prettyPrintHtml(String html) {
            StringBuilder result = new StringBuilder();
            int indent = 0;
            String normalized = html.replaceAll(">\\s+<", "><").trim();
            StringTokenizer st = new StringTokenizer(normalized, "<>", true);
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                if (token.equals("<")) {
                    String next = st.nextToken();
                    if (next.startsWith("/")) { indent--; result.append("\n").append("  ".repeat(Math.max(0, indent))).append("<").append(next); }
                    else if (next.endsWith("/")) { result.append("\n").append("  ".repeat(Math.max(0, indent))).append("<").append(next); }
                    else {
                        result.append("\n").append("  ".repeat(Math.max(0, indent))).append("<").append(next);
                        if (!next.toLowerCase().startsWith("meta") && !next.toLowerCase().startsWith("link") && !next.toLowerCase().startsWith("br")) indent++;
                    }
                } else if (token.equals(">")) { result.append(">"); }
                else result.append(token.trim());
            }
            return result.toString().trim();
        }

        private String prettyPrintJson(String input) {
            int jsonStart = -1;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i); if (c == '{' || c == '[') { jsonStart = i; break; }
            }
            if (jsonStart == -1) return input;
            String prefix = input.substring(0, jsonStart);
            String jsonPart = input.substring(jsonStart);
            StringBuilder formatted = new StringBuilder(prefix);
            int indent = 0; boolean quotes = false;
            for (int i = 0; i < jsonPart.length(); i++) {
                char c = jsonPart.charAt(i);
                if (c == '\\' && i + 1 < jsonPart.length()) { formatted.append(c).append(jsonPart.charAt(i+1)); i++; continue; }
                switch (c) {
                    case '"': quotes = !quotes; formatted.append(c); break;
                    case '{': case '[': formatted.append(c); if (!quotes) { indent++; formatted.append("\n").append("  ".repeat(indent)); } break;
                    case '}': case ']': if (!quotes) { indent--; formatted.append("\n").append("  ".repeat(Math.max(0, indent))); } formatted.append(c); break;
                    case ',': formatted.append(c); if (!quotes) formatted.append("\n").append("  ".repeat(indent)); break;
                    case ':': formatted.append(c); if (!quotes) formatted.append(" "); break;
                    default: formatted.append(c);
                }
            }
            return formatted.toString().trim();
        }

        private void showFindDialog() {
            JDialog d = new JDialog(this, "Find", false); d.setUndecorated(true); d.setSize(520, 90); d.setLocationRelativeTo(this);
            d.getRootPane().setBorder(BorderFactory.createLineBorder(new Color(255,102,0), 2));
            JPanel p = new JPanel(new BorderLayout(15,0)); p.setBackground(new Color(45,45,45)); p.setBorder(new EmptyBorder(16,20,16,20));
            JTextField f = new JTextField(); f.setFont(fira(18f)); f.setBackground(new Color(45,45,45)); f.setForeground(Color.WHITE); f.setCaretColor(new Color(255,102,0)); f.setBorder(null);
            JLabel counter = new JLabel("0/0"); counter.setFont(fira(16f)); counter.setForeground(new Color(180,180,180));
            JButton next = new JButton("▶"); next.setFont(fira(20f)); next.setForeground(new Color(255,102,0)); next.setBorderPainted(false); next.setContentAreaFilled(false);
            JButton close = new JButton("×"); close.setFont(fira(22f)); close.setForeground(Color.LIGHT_GRAY); close.setBorderPainted(false); close.setContentAreaFilled(false);
            close.addActionListener(e -> d.dispose());
            f.addKeyListener(new KeyAdapter() {
                public void keyReleased(KeyEvent e) {
                    req.getHighlighter().removeAllHighlights(); resp.getHighlighter().removeAllHighlights();
                    findMatches.clear(); searchPane(req, f.getText()); searchPane(resp, f.getText());
                    if (!findMatches.isEmpty()) { findIndex = 0; applyFindHighlight(); counter.setText("1/" + findMatches.size()); } else counter.setText("0/0");
                }
            });
            next.addActionListener(e -> { if (!findMatches.isEmpty()) { findIndex = (findIndex + 1) % findMatches.size(); applyFindHighlight(); counter.setText((findIndex + 1) + "/" + findMatches.size()); } });
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT,15,0)); east.setOpaque(false); east.add(counter); east.add(next); east.add(close);
            p.add(f, BorderLayout.CENTER); p.add(east, BorderLayout.EAST); d.add(p); d.setVisible(true); f.requestFocusInWindow();
        }

        private void searchPane(JTextPane pane, String q) {
            if (q.isEmpty()) return;
            try { String t = pane.getDocument().getText(0, pane.getDocument().getLength());
                Matcher m = Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE).matcher(t);
                while (m.find()) findMatches.add(new SearchResult(pane, m.start(), m.end()));
            } catch (Exception ignored) {}
        }

        private void applyFindHighlight() {
            req.getHighlighter().removeAllHighlights(); resp.getHighlighter().removeAllHighlights();
            try { SearchResult r = findMatches.get(findIndex); r.pane.getHighlighter().addHighlight(r.start, r.end, new DefaultHighlighter.DefaultHighlightPainter(new Color(255,102,0,150))); r.pane.scrollRectToVisible(r.pane.modelToView2D(r.start).getBounds()); } catch (Exception ignored) {}
        }

        private void applyTheme() {
            Color bg = isDark ? new Color(30,30,30) : Color.WHITE; Color fg = isDark ? Color.WHITE : Color.BLACK;
            getContentPane().setBackground(bg); req.setBackground(bg); req.setForeground(fg); resp.setBackground(bg); resp.setForeground(fg);
            urlField.setBackground(isDark ? new Color(20,20,20) : new Color(245,245,245)); urlField.setForeground(fg);
        }

        private void setupPane(JTextPane p) { p.setEditable(true); p.setFont(fira(15f)); p.setBorder(new EmptyBorder(10,15,10,15)); }
        
        private JPanel createPanel(String title, JTextPane p) {
            JPanel pan = new JPanel(new BorderLayout()); pan.setOpaque(false);
            JLabel l = new JLabel(title); 
            l.setHorizontalAlignment(SwingConstants.CENTER); 
            l.setFont(fira(16f).deriveFont(Font.BOLD)); // Bolder title font
            l.setForeground(new Color(255, 102, 0)); // Bright high-contrast orange
            l.setBorder(new EmptyBorder(12,0,12,0));
            JScrollPane scroll = new JScrollPane(p); scroll.setBorder(null); scroll.getViewport().setBackground(new Color(30,30,30));
            pan.add(l, BorderLayout.NORTH); pan.add(scroll, BorderLayout.CENTER); return pan;
        }

        private void setupContext(JTextPane p) {
            JPopupMenu menu = new JPopupMenu(); JMenuItem h = new JMenuItem("Highlight Selection"), r = new JMenuItem("Redact Selection"), rm = new JMenuItem("Remove Selection");
            h.addActionListener(e -> { SimpleAttributeSet sas = new SimpleAttributeSet(); StyleConstants.setBackground(sas, new Color(255,102,0,150)); p.getStyledDocument().setCharacterAttributes(p.getSelectionStart(), p.getSelectionEnd() - p.getSelectionStart(), sas, false); });
            r.addActionListener(e -> { SimpleAttributeSet sas = new SimpleAttributeSet(); StyleConstants.setBackground(sas, Color.BLACK); StyleConstants.setForeground(sas, Color.BLACK); p.getStyledDocument().setCharacterAttributes(p.getSelectionStart(), p.getSelectionEnd() - p.getSelectionStart(), sas, false); });
            rm.addActionListener(e -> { try { p.getDocument().remove(p.getSelectionStart(), p.getSelectionEnd() - p.getSelectionStart()); } catch (Exception ignored) {} });
            menu.add(h); menu.add(r); menu.add(rm);
            p.addMouseListener(new MouseAdapter() { public void mouseReleased(MouseEvent e) { if (p.getSelectionStart() != p.getSelectionEnd()) menu.show(p, e.getX(), e.getY()); } });
        }

        private void styleStatusLabel(JLabel l) { l.setOpaque(true); l.setBackground(new Color(45,45,45)); l.setForeground(new Color(255,102,0)); l.setFont(fira(12f)); l.setBorder(BorderFactory.createLineBorder(new Color(65,65,65))); }
        
        private void saveImg() {
            BufferedImage img = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB); 
            paint(img.getGraphics());
            JFileChooser fc = new JFileChooser();
            if (lastSavedDirectory != null) fc.setCurrentDirectory(lastSavedDirectory);
            fc.setSelectedFile(new File("BurpShot_" + System.currentTimeMillis() + ".png"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selected = fc.getSelectedFile();
                lastSavedDirectory = selected.getParentFile();
                try { ImageIO.write(img, "png", selected); } catch (Exception ignored) {}
            }
        }

        void refresh(HttpRequestResponse rr) {
            urlField.setText(rr.request().url());
            req.setText(formatMessage(rr.request().toString()));
            if (rr.response() != null) { resp.setText(formatMessage(rr.response().toString())); lenLabel.setText(" Length: " + rr.response().toString().length() + " bytes "); }
        }
    }

    private static class SearchResult { JTextPane pane; int start, end; SearchResult(JTextPane p, int s, int e) { pane = p; start = s; end = e; } }

    private static class AutoWrapPane extends JTextPane {
        public AutoWrapPane() {
            setEditorKit(new StyledEditorKit() {
                @Override
                public ViewFactory getViewFactory() {
                    return new ViewFactory() {
                        @Override
                        public View create(Element elem) {
                            String kind = elem.getName();
                            if (AbstractDocument.ContentElementName.equals(kind)) return new ForcedWrapLabel(elem);
                            else if (AbstractDocument.ParagraphElementName.equals(kind)) return new ParagraphView(elem);
                            else if (AbstractDocument.SectionElementName.equals(kind)) return new BoxView(elem, View.Y_AXIS);
                            return new LabelView(elem);
                        }
                    };
                }
            });
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        private static class ForcedWrapLabel extends LabelView {
            public ForcedWrapLabel(Element e) { super(e); }
            @Override public float getMinimumSpan(int axis) { return (axis == View.X_AXIS) ? 0 : super.getMinimumSpan(axis); }
            @Override public int getBreakWeight(int axis, float pos, float len) {
                return (axis == View.X_AXIS) ? ExcellentBreakWeight : super.getBreakWeight(axis, pos, len);
            }
        }
    }

    private static void copyToClipboard(String s) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null); }
}
