package shit.nilore.modules.impl.misc.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

public class AudioPlayer {
    public enum State { STOPPED, PLAYING, PAUSED, LOADING }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private volatile float volume = 0.8f;
    private volatile SongInfo currentSong;
    private volatile SourceDataLine currentLine;
    private volatile Thread playbackThread;
    private volatile boolean paused;

    // time-based progress tracking
    private volatile long playStartMs;
    private volatile long pauseStartMs;
    private volatile long totalPausedMs;

    public void play(SongInfo song, String url) {
        stop();
        this.currentSong = song;
        this.state.set(State.LOADING);
        this.paused = false;
        this.totalPausedMs = 0;
        this.playStartMs = System.currentTimeMillis();
        playbackThread = new Thread(() -> playInternal(url), "MusicPlayer-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void pause() {
        if (state.get() == State.PLAYING && currentLine != null) {
            paused = true;
            pauseStartMs = System.currentTimeMillis();
            currentLine.stop();
            state.set(State.PAUSED);
        }
    }

    public void resume() {
        if (state.get() == State.PAUSED && currentLine != null) {
            paused = false;
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            currentLine.start();
            state.set(State.PLAYING);
        }
    }

    public void stop() {
        state.set(State.STOPPED);
        paused = false;
        currentSong = null;
        if (currentLine != null) {
            try { currentLine.drain(); } catch (Exception ignored) {}
            try { currentLine.close(); } catch (Exception ignored) {}
            currentLine = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void togglePause() {
        if (state.get() == State.PLAYING) {
            pause();
        } else if (state.get() == State.PAUSED) {
            resume();
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0f, Math.min(1f, vol));
        applyVolume();
    }

    public float getVolume() { return volume; }
    public State getState() { return state.get(); }
    public SongInfo getCurrentSong() { return currentSong; }

    public float getProgress() {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return 0f;
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0f;
        long now = System.currentTimeMillis();
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        long elapsed = now - playStartMs - paused;
        return Math.max(0f, Math.min(1f, (float) elapsed / song.duration));
    }

    public long getCurrentPositionMs() {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return 0;
        long now = System.currentTimeMillis();
        State s = state.get();
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        return Math.max(0, now - playStartMs - paused);
    }

    private void playInternal(String url) {
        SourceDataLine localLine = null;
        try {
            System.out.println("[MusicPlayer] Starting playback: " + url);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<java.io.InputStream> resp = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            BufferedInputStream bis = new BufferedInputStream(resp.body());
            AudioInputStream rawStream = AudioSystem.getAudioInputStream(bis);
            AudioFormat baseFormat = rawStream.getFormat();

            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );

            AudioInputStream ais = AudioSystem.getAudioInputStream(decoded, rawStream);

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded);
            localLine = (SourceDataLine) AudioSystem.getLine(info);
            localLine.open(decoded);
            currentLine = localLine;
            applyVolume();
            localLine.start();

            playStartMs = System.currentTimeMillis();
            totalPausedMs = 0;
            state.set(State.PLAYING);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = ais.read(buffer, 0, buffer.length)) != -1) {
                if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;
                if (paused) {
                    Thread.sleep(50);
                    continue;
                }
                localLine.write(buffer, 0, bytesRead);
            }

            ais.close();
            rawStream.close();
            if (state.get() == State.PLAYING) {
                state.set(State.STOPPED);
            }
            System.out.println("[MusicPlayer] Playback started successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            if (state.get() != State.STOPPED) {
                state.set(State.STOPPED);
            }
        } finally {
            if (localLine != null) {
                try { localLine.drain(); } catch (Exception ignored) {}
                try { localLine.close(); } catch (Exception ignored) {}
                if (currentLine == localLine) currentLine = null;
            }
        }
    }

    private void applyVolume() {
        if (currentLine != null && currentLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) currentLine.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log(Math.max(volume, 0.0001)) / Math.log(10.0) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(dB, gain.getMaximum())));
        }
    }
}
