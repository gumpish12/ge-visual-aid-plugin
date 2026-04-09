package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.*;

@Slf4j
@Singleton
public class SoundAlert
{
    @Inject
    private GEVisualAidConfig config;

    private long lastPlayedTime = 0;

    public void playAction()
    {
        if (!config.soundEnabled() || !config.soundOnAction()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayedTime < config.soundCooldownSeconds() * 1000L) return;
        lastPlayedTime = now;
        new Thread(() -> playTone(880, 180, config.soundVolume()),
                "ge-visual-aid-sound").start();
    }

    public void playDumpAlert()
    {
        if (!config.soundEnabled() || !config.soundOnDumpAlert()) return;
        lastPlayedTime = System.currentTimeMillis();
        new Thread(() ->
        {
            for (int i = 0; i < 3; i++)
            {
                playTone(1300, 120, config.soundVolume());
                try { Thread.sleep(130); } catch (InterruptedException ignored) {}
            }
        }, "ge-visual-aid-sound").start();
    }

    public void playOfferComplete()
    {
        if (!config.soundEnabled() || !config.soundOnOfferComplete()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayedTime < config.soundCooldownSeconds() * 1000L) return;
        lastPlayedTime = now;
        new Thread(() ->
        {
            playTone(660, 120, config.soundVolume());
            try { Thread.sleep(130); } catch (InterruptedException ignored) {}
            playTone(880, 200, config.soundVolume());
        }, "ge-visual-aid-sound").start();
    }

    private void playTone(int hz, int durationMs, int volume)
    {
        try
        {
            float sampleRate = 44100f;
            int samples = (int)(sampleRate * durationMs / 1000);
            byte[] buf = new byte[samples];
            double vol = volume / 100.0;
            for (int i = 0; i < samples; i++)
            {
                double angle = 2.0 * Math.PI * i * hz / sampleRate;
                double fade  = Math.min(1.0, Math.min(i, samples - i) / (sampleRate * 0.01));
                buf[i] = (byte)(Math.sin(angle) * 127 * vol * fade);
            }
            AudioFormat format = new AudioFormat(sampleRate, 8, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
            line.close();
        }
        catch (Exception e) { log.warn("Sound error: {}", e.getMessage()); }
    }
}