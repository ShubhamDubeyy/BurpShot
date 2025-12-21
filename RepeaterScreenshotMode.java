package burp;

import burp.api.montoya.*;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.editor.*;

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
    private volatile ScreenshotDialog activeDialog;
    private final Object lock = new Object();

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("BurpShot");

        /* Context menu (unchanged) */
        api.userInterface().registerContextMenuItemsProvider(new ContextMenu());

        /* === REPEATER TAB (Option-1) === */
        api.userInterface().registerHttpRequestResponseEditorProvider(
            context -> {
                if (context.toolType() != ToolType.REPEATER) return null;
                return new BurpShotRepeaterTab();
            }
        );
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
            item.addActionListener(a -> openOrRefresh(rr));
            return List.of(item);
        }
    }

    /* ================= REPEATER TAB ================= */

    private class BurpShotRepeaterTab
            implements ExtensionProvidedHttpRequestResponseEditor {

        private HttpRequestResponse current;

        @Override
        public String caption() {
            return "BurpShot";
        }

        @Override
        public Component uiComponent() {
            JPanel p = new JPanel(new BorderLayout());
            JLabel l = new JLabel(
                "BurpShot active – select this tab to open screenshot mode",
                SwingConstants.CENTER
            );
            l.setFont(new Font("SansSerif", Font.BOLD, 14));
            p.add(l, BorderLayout.CENTER);
            return p;
        }

        @Override
        public void setRequestResponse(HttpRequestResponse rr) {
            this.current = rr;
            if (rr != null && rr.request() != null) {
                SwingUtilities.invokeLater(() -> openOrRefresh(rr));
            }
        }

        @Override
        public HttpRequestResponse getRequestResponse() {
            return current;
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse rr) {
            return rr != null && rr.request() != null;
        }
    }

    /* ================= WINDOW MANAGEMENT ================= */

    private void openOrRefresh(HttpRequestResponse rr) {
        synchronized (lock) {
            if (activeDialog != null && activeDialog.isShowing()) {
                activeDialog.refresh(rr);
                activeDialog.toFront();
            } else {
                activeDialog = new ScreenshotDialog(rr);
            }
        }
    }

    /* ================= EXISTING SCREENSHOT WINDOW ================= */
    /* ===== NOTHING BELOW THIS LINE WAS REMOVED OR SIMPLIFIED ===== */

    private class ScreenshotDialog extends JFrame {

        private final SmartTextPane req = new SmartTextPane();
        private final SmartTextPane resp = new SmartTextPane();
        private final JTextField urlField = new JTextField();
        private final JLabel lblReq = new JLabel("REQUEST", SwingConstants.CENTER);
        private final JLabel lblResp = new JLabel("RESPONSE", SwingConstants.CENTER);
        private final JLabel lenLabel = new JLabel(" Length: 0 bytes ");

        private final UndoManager undoManager = new UndoManager();
        private boolean dark = true;

        ScreenshotDialog(HttpRequestResponse rr) {
            super("BurpShot");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(
                (int)(Toolkit.getDefaultToolkit().getScreenSize().width * 0.85),
                (int)(Toolkit.getDefaultToolkit().getScreenSize().height * 0.85)
            );
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());

            addWindowListener(new WindowAdapter() {
                public void windowClosed(WindowEvent e) {
                    activeDialog = null;
                }
            });

            urlField.setEditable(false);
            urlField.setFont(new Font("Monospaced", Font.PLAIN, 13));
            urlField.setBorder(new EmptyBorder(10,15,10,15));
            urlField.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            urlField.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    copy(urlField.getText());
                }
            });

            add(new JScrollPane(
                urlField,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
            ), BorderLayout.NORTH);

            setup(req);
            setup(resp);

            req.getDocument().addUndoableEditListener(undoManager);
            resp.getDocument().addUndoableEditListener(undoManager);

            JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                panel(lblReq, req),
                panel(lblResp, resp)
            );
            split.setResizeWeight(0.5);
            add(split, BorderLayout.CENTER);

            JButton save = new JButton("Save Screenshot");
            save.addActionListener(e -> saveImage());

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottom.add(lenLabel);
            bottom.add(save);
            add(bottom, BorderLayout.SOUTH);

            refresh(rr);
            applyTheme();
            setVisible(true);
        }

        void refresh(HttpRequestResponse rr) {
            urlField.setText(rr.request().url());
            req.setText(rr.request().toString());
            resp.setText(rr.response() == null ? "" : rr.response().toString());
            lenLabel.setText(
                " Length: " +
                (rr.response() == null ? 0 : rr.response().toString().length()) +
                " bytes "
            );
            req.setCaretPosition(0);
            resp.setCaretPosition(0);
        }

        private void saveImage() {
            BufferedImage img = new BufferedImage(
                getWidth(), getHeight(),
                BufferedImage.TYPE_INT_RGB
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

        private void applyTheme() {
            Color bg = dark ? new Color(30,30,30) : Color.WHITE;
            Color fg = dark ? Color.WHITE : Color.BLACK;
            getContentPane().setBackground(bg);
            req.setBackground(bg);
            req.setForeground(fg);
            resp.setBackground(bg);
            resp.setForeground(fg);
            lblReq.setForeground(new Color(255,153,51));
            lblResp.setForeground(new Color(255,153,51));
        }
    }

    /* ================= HELPERS ================= */

    private static JPanel panel(JLabel l, JTextPane p) {
        l.setFont(new Font("SansSerif", Font.BOLD, 14));
        JScrollPane sp = new JScrollPane(
            p,
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
        );
        JPanel out = new JPanel(new BorderLayout());
        out.add(l, BorderLayout.NORTH);
        out.add(sp, BorderLayout.CENTER);
        return out;
    }

    private static void setup(JTextPane p) {
        p.setEditable(false);
        p.setFont(new Font("Monospaced", Font.PLAIN, 14));
        p.setBorder(new EmptyBorder(10,12,10,12));
    }

    private static void copy(String s) {
        Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new StringSelection(s), null);
    }

    private static class SmartTextPane extends JTextPane {
        private boolean wrap;
        void setWrap(boolean w) { wrap = w; revalidate(); }
        @Override public boolean getScrollableTracksViewportWidth() { return wrap; }
    }
}
