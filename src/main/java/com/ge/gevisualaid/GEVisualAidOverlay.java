package com.ge.gevisualaid;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

@Singleton
public class GEVisualAidOverlay extends Overlay
{
    @Inject private GEVisualAidConfig config;
    @Inject private Client client;

    private Rectangle highlightBounds = null;
    private String    actionType      = "normal";

    @Inject
    public GEVisualAidOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setHighlight(Rectangle bounds, String actionType)
    {
        this.highlightBounds = bounds;
        this.actionType      = actionType;
    }

    public void clearHighlight()
    {
        this.highlightBounds = null;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.overlayEnabled() || highlightBounds == null) return null;

        Color base;
        switch (actionType)
        {
            case "dump":
            case "abort":
                base = config.overlayColorDump();
                break;
            case "modify":
                base = config.overlayColorModify();
                break;
            case "collect":
                base = config.overlayColorCollect();
                break;
            default:
                base = config.overlayColorNormal();
                break;
        }

        int alpha = base.getAlpha();
        if (config.overlayPulse())
        {
            long period = config.overlayPulseSpeed().getPeriodMs();
            double phase = (System.currentTimeMillis() % period) / (double) period;
            double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * phase);
            alpha = (int)(40 + pulse * (base.getAlpha() - 40));
        }

        int t = config.overlayBorderThickness();
        Color border = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        Color fill   = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha / 5);

        g.setColor(fill);
        g.fillRect(highlightBounds.x + t, highlightBounds.y + t,
                highlightBounds.width - t * 2, highlightBounds.height - t * 2);

        g.setColor(border);
        g.setStroke(new BasicStroke(t));
        g.drawRect(highlightBounds.x + t, highlightBounds.y + t,
                highlightBounds.width - t * 2, highlightBounds.height - t * 2);

        return null;
    }
}