package com.ge.gevisualaid;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;

@Slf4j
@Singleton
public class SoundAlert
{
    @Inject
    private GEVisualAidConfig config;

    @Inject
    private AudioPlayer audioPlayer;

    private long lastPlayedTime = 0;

    public void playAction()
    {
        if (!config.soundEnabled() || !config.soundOnAction()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayedTime < config.soundCooldownSeconds() * 1000L) return;
        lastPlayedTime = now;
        play(880, 180);
    }

    public void playDumpAlert()
    {
        if (!config.soundEnabled() || !config.soundOnDumpAlert()) return;
        lastPlayedTime = System.currentTimeMillis();
        new Thread(() ->
        {
            play(1300, 120);
            sleep(150);
            play(1300, 120);
            sleep(150);
            play(1300, 120);
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
            play(660, 120);
            sleep(140);
            play(880, 200);
        }, "ge-visual-aid-sound").start();
    }

    private void play(int hz, int durationMs)
    {
        try
        {
            InputStream wav = generateWav(hz, durationMs, config.soundVolume());
            audioPlayer.play(wav, 1.0f);
        }
        catch (Exception e)
        {
            log.warn("GEVisualAid sound error: {}", e.getMessage());
        }
    }

    private void sleep(int ms)
    {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private InputStream generateWav(int hz, int durationMs, int volume) throws Exception
    {
        int sampleRate = 44100;
        int samples    = sampleRate * durationMs / 1000;
        double vol     = volume / 100.0;

        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++)
        {
            double angle = 2.0 * Math.PI * i * hz / sampleRate;
            double fade  = Math.min(1.0, Math.min(i, samples - i) / (sampleRate * 0.01));
            pcm[i] = (byte)(Math.sin(angle) * 127 * vol * fade);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        int dataSize   = samples;
        int totalSize  = 36 + dataSize;

        // RIFF header
        dos.writeBytes("RIFF");
        writeLittleInt(dos, totalSize);
        dos.writeBytes("WAVE");

        // fmt chunk
        dos.writeBytes("fmt ");
        writeLittleInt(dos, 16);
        writeLittleShort(dos, (short) 1);       // PCM
        writeLittleShort(dos, (short) 1);       // mono
        writeLittleInt(dos, sampleRate);
        writeLittleInt(dos, sampleRate);        // byte rate (8-bit mono)
        writeLittleShort(dos, (short) 1);       // block align
        writeLittleShort(dos, (short) 8);       // bits per sample

        // data chunk
        dos.writeBytes("data");
        writeLittleInt(dos, dataSize);
        dos.write(pcm);
        dos.flush();

        return new ByteArrayInputStream(out.toByteArray());
    }

    private void writeLittleInt(DataOutputStream dos, int v) throws Exception
    {
        dos.write(v & 0xFF);
        dos.write((v >> 8) & 0xFF);
        dos.write((v >> 16) & 0xFF);
        dos.write((v >> 24) & 0xFF);
    }

    private void writeLittleShort(DataOutputStream dos, short v) throws Exception
    {
        dos.write(v & 0xFF);
        dos.write((v >> 8) & 0xFF);
    }
}