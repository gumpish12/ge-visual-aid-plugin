package com.ge.gevisualaid;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

@Singleton
public class GEVisualAidPanel extends PluginPanel
{
    private final JLabel   statusLabel;
    private final JLabel   actionLabel;
    private final JLabel   itemLabel;
    private final JLabel   profitLabel;
    private final JLabel   flipsLabel;
    private final JLabel   bestLabel;
    private final JButton  resetButton;

    // Bond tracking labels
    private final JLabel   bondCountLabel;
    private final JLabel   bondTotalLabel;
    private final JLabel   bondLastLabel;
    private final JButton  resetBondsButton;

    private final JLabel[]       slotStatusLabels = new JLabel[8];
    private final JLabel[]       slotItemLabels   = new JLabel[8];
    private final JProgressBar[] slotBars         = new JProgressBar[8];

    private Runnable onReset;
    private Runnable onResetBonds;

    @Inject
    public GEVisualAidPanel()
    {
        setLayout(new BorderLayout(0, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel("GE Visual Aid");
        header.setForeground(Color.WHITE);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setBorder(new EmptyBorder(0, 0, 4, 0));
        add(header, BorderLayout.NORTH);

        JPanel centre = new JPanel();
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        centre.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel statusPanel = new JPanel(new GridLayout(3, 1, 0, 2));
        statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        statusLabel = makeLabel("Waiting...", Color.GRAY);
        actionLabel = makeLabel("Action: —", Color.WHITE);
        itemLabel   = makeLabel("Item: —", Color.WHITE);

        statusPanel.add(statusLabel);
        statusPanel.add(actionLabel);
        statusPanel.add(itemLabel);
        centre.add(statusPanel);
        centre.add(Box.createVerticalStrut(6));

        JPanel sessionPanel = new JPanel(new GridLayout(4, 1, 0, 2));
        sessionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        sessionPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        JLabel sessionHeader = makeLabel("Session", Color.LIGHT_GRAY);
        sessionHeader.setFont(sessionHeader.getFont().deriveFont(Font.BOLD));
        profitLabel = makeLabel("Profit: —", Color.WHITE);
        flipsLabel  = makeLabel("Flips: 0", Color.WHITE);
        bestLabel   = makeLabel("Best flip: —", Color.WHITE);

        sessionPanel.add(sessionHeader);
        sessionPanel.add(profitLabel);
        sessionPanel.add(flipsLabel);
        sessionPanel.add(bestLabel);
        centre.add(sessionPanel);

        resetButton = new JButton("Reset Stats");
        resetButton.setFocusPainted(false);
        resetButton.setBackground(new Color(80, 40, 40));
        resetButton.setForeground(Color.WHITE);
        resetButton.setBorder(new EmptyBorder(3, 6, 3, 6));
        resetButton.addActionListener(e -> { if (onReset != null) onReset.run(); });
        centre.add(resetButton);
        centre.add(Box.createVerticalStrut(6));

        // ---- Bond tracking panel ----------------------------------------
        JPanel bondPanel = new JPanel(new GridLayout(4, 1, 0, 2));
        bondPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bondPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        JLabel bondHeader = makeLabel("Bonds Purchased", Color.LIGHT_GRAY);
        bondHeader.setFont(bondHeader.getFont().deriveFont(Font.BOLD));
        bondCountLabel = makeLabel("Total bought: 0", Color.WHITE);
        bondTotalLabel = makeLabel("Total spent: —", Color.WHITE);
        bondLastLabel  = makeLabel("Last bond: —", Color.WHITE);

        bondPanel.add(bondHeader);
        bondPanel.add(bondCountLabel);
        bondPanel.add(bondTotalLabel);
        bondPanel.add(bondLastLabel);
        centre.add(bondPanel);

        resetBondsButton = new JButton("Reset Bond Stats");
        resetBondsButton.setFocusPainted(false);
        resetBondsButton.setBackground(new Color(40, 40, 80));
        resetBondsButton.setForeground(Color.WHITE);
        resetBondsButton.setBorder(new EmptyBorder(3, 6, 3, 6));
        resetBondsButton.addActionListener(e -> { if (onResetBonds != null) onResetBonds.run(); });
        centre.add(resetBondsButton);
        centre.add(Box.createVerticalStrut(6));
        // -----------------------------------------------------------------

        JLabel slotsHeader = makeLabel("GE Slots", Color.LIGHT_GRAY);
        slotsHeader.setFont(slotsHeader.getFont().deriveFont(Font.BOLD));
        centre.add(slotsHeader);
        centre.add(Box.createVerticalStrut(4));

        for (int i = 0; i < 8; i++)
        {
            JPanel row = new JPanel(new BorderLayout(0, 2));
            row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            row.setBorder(new EmptyBorder(4, 6, 4, 6));

            slotStatusLabels[i] = makeLabel("Slot " + (i + 1) + ": empty", Color.GRAY);
            slotItemLabels[i]   = makeLabel("", new Color(180, 180, 180));
            slotItemLabels[i].setFont(slotItemLabels[i].getFont().deriveFont(10f));

            slotBars[i] = new JProgressBar(0, 100);
            slotBars[i].setValue(0);
            slotBars[i].setStringPainted(false);
            slotBars[i].setPreferredSize(new Dimension(0, 6));
            slotBars[i].setBackground(new Color(50, 50, 50));
            slotBars[i].setForeground(Color.GRAY);
            slotBars[i].setBorderPainted(false);

            JPanel top = new JPanel(new BorderLayout());
            top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            top.add(slotStatusLabels[i], BorderLayout.WEST);
            top.add(slotItemLabels[i], BorderLayout.EAST);

            row.add(top, BorderLayout.NORTH);
            row.add(slotBars[i], BorderLayout.SOUTH);
            centre.add(row);
            centre.add(Box.createVerticalStrut(3));
        }

        JScrollPane scroll = new JScrollPane(centre);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);
    }

    public void setOnReset(Runnable r)      { this.onReset      = r; }
    public void setOnResetBonds(Runnable r) { this.onResetBonds = r; }

    /**
     * Updates the bond statistics section in the panel.
     *
     * @param count   total number of bonds purchased all-time
     * @param totalGp total GP spent on bonds all-time
     * @param lastGp  GP paid per bond on the most recent purchase (0 = never bought)
     * @param tracker BondTracker used for GP formatting
     */
    public void updateBonds(int count, long totalGp, long lastGp, BondTracker tracker)
    {
        SwingUtilities.invokeLater(() ->
        {
            bondCountLabel.setText("Total bought: " + count);
            bondTotalLabel.setText("Total spent: "  + tracker.formatGp(totalGp));
            bondLastLabel.setText("Last bond: "
                    + (lastGp > 0 ? tracker.formatGp(lastGp) : "—"));
        });
    }

    public void updateStatus(String action, String itemName,
                             boolean actionRequired, boolean dumpAlert)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (dumpAlert)
            {
                statusLabel.setText("DUMP ALERT");
                statusLabel.setForeground(new Color(255, 80, 80));
            }
            else if (actionRequired)
            {
                statusLabel.setText("Action Required");
                statusLabel.setForeground(new Color(0, 200, 100));
            }
            else
            {
                statusLabel.setText("Idle");
                statusLabel.setForeground(Color.GRAY);
            }
            actionLabel.setText("Action: " + action.replace("_", " "));
            itemLabel.setText("Item: " + (itemName.isEmpty() ? "\u2014" : itemName));
        });
    }

    public void updateSession(long totalProfit, int totalFlips,
                              long bestFlip, SessionTracker tracker)
    {
        SwingUtilities.invokeLater(() ->
        {
            Color profitColor = totalProfit >= 0
                    ? new Color(0, 200, 100) : new Color(255, 80, 80);
            profitLabel.setText("Profit: " + tracker.formatGp(totalProfit));
            profitLabel.setForeground(profitColor);
            flipsLabel.setText("Flips: " + totalFlips);
            bestLabel.setText("Best: " + tracker.formatGp(bestFlip));
        });
    }

    public void updateSlot(int i, String status, String itemName,
                           int done, int total)
    {
        if (i < 0 || i >= 8) return;
        SwingUtilities.invokeLater(() ->
        {
            Color barColor;
            Color labelColor;
            switch (status)
            {
                case "buying":
                    barColor   = new Color(100, 180, 255);
                    labelColor = new Color(100, 180, 255);
                    break;
                case "selling":
                    barColor   = new Color(255, 180, 80);
                    labelColor = new Color(255, 180, 80);
                    break;
                case "complete":
                    barColor   = new Color(80, 220, 80);
                    labelColor = new Color(80, 220, 80);
                    break;
                case "cancelled":
                    barColor   = new Color(150, 150, 150);
                    labelColor = new Color(150, 150, 150);
                    break;
                default:
                    barColor   = new Color(50, 50, 50);
                    labelColor = Color.GRAY;
                    break;
            }

            String progress = (total > 0 && !"empty".equals(status))
                    ? " " + done + "/" + total
                    : "";

            slotStatusLabels[i].setText("Slot " + (i + 1) + ": " + status + progress);
            slotStatusLabels[i].setForeground(labelColor);
            slotItemLabels[i].setText(itemName);

            slotBars[i].setForeground(barColor);
            if (total > 0)
            {
                slotBars[i].setMaximum(total);
                slotBars[i].setValue(done);
            }
            else
            {
                slotBars[i].setValue(0);
            }
        });
    }

    public void setDisconnected()
    {
        SwingUtilities.invokeLater(() ->
        {
            statusLabel.setText("Copilot not found");
            statusLabel.setForeground(Color.GRAY);
            actionLabel.setText("Action: \u2014");
            itemLabel.setText("Item: \u2014");
        });
    }

    private JLabel makeLabel(String text, Color color)
    {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        return l;
    }
}