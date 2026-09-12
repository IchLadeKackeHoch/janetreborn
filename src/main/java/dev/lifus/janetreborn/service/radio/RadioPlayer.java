package dev.lifus.janetreborn.service.radio;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.JavaSoundAudioDevice;
import javazoom.jl.player.Player;

public final class RadioPlayer implements AutoCloseable {
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int READ_TIMEOUT_MILLIS = 20_000;
  private static final int RECONNECT_DELAY_MILLIS = 2_000;
  private final AtomicLong sessionIds = new AtomicLong();
  private final SessionRegistry sessions = new SessionRegistry();
  private final Object lifecycle = new Object();
  private volatile float volume = 0.5f;
  private volatile String status = "Stopped";
  private boolean closed;

  public void play(String streamUrl) {
    Objects.requireNonNull(streamUrl, "streamUrl");
    PlaybackSession session = new PlaybackSession(sessionIds.incrementAndGet(), streamUrl);
    Thread thread = new Thread(() -> playbackLoop(session), "janet-radio-player-" + session.id);
    thread.setDaemon(true);
    session.worker = thread;

    PlaybackSession previous;
    synchronized (lifecycle) {
      if (closed) return;
      previous = sessions.replace(session);
      status = "Connecting...";
    }
    cancel(previous);
    if (sessions.owns(session)) thread.start();
    else cancel(session);
  }

  public void setVolume(float volume) {
    this.volume = clamp01(volume);
  }

  public String getStatus() {
    return status;
  }

  public void stop() {
    PlaybackSession session;
    synchronized (lifecycle) {
      session = sessions.clear();
      status = "Stopped";
    }
    cancel(session);
  }

  @Override
  public void close() {
    PlaybackSession session;
    synchronized (lifecycle) {
      closed = true;
      session = sessions.clear();
      status = "Stopped";
    }
    cancel(session);
  }

  private void playbackLoop(PlaybackSession session) {
    while (sessions.owns(session)
        && !session.cancelled
        && !Thread.currentThread().isInterrupted()) {
      try {
        playOnce(session);
        updateStatus(session, "Reconnecting...");
      } catch (Exception | LinkageError throwable) {
        updateStatus(session, failureStatus(throwable));
      }
      if (!sessions.owns(session) || session.cancelled) return;
      try {
        Thread.sleep(RECONNECT_DELAY_MILLIS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void playOnce(PlaybackSession session) throws Exception {
    URLConnection connection = URI.create(session.streamUrl).toURL().openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    connection.setReadTimeout(READ_TIMEOUT_MILLIS);
    connection.setRequestProperty("User-Agent", "Janet-Reborn-RadioModule");
    connection.setRequestProperty("Icy-MetaData", "0");

    try (BufferedInputStream network =
        new BufferedInputStream(connection.getInputStream(), 64 * 1024)) {
      session.input = network;
      if (!sessions.owns(session) || session.cancelled) return;
      updateStatus(session, "Buffering...");
      Player player = new Player(network, new VolumeAudioDevice());
      session.player = player;
      try {
        if (!sessions.owns(session) || session.cancelled) return;
        updateStatus(session, "Playing");
        player.play();
      } finally {
        player.close();
        if (session.player == player) session.player = null;
      }
    } finally {
      session.input = null;
    }
  }

  static void scaleSamples(short[] samples, int offset, int length, float volume) {
    float gain = clamp01(volume);
    int end = Math.min(samples.length, offset + Math.max(0, length));
    for (int index = Math.max(0, offset); index < end; index++) {
      samples[index] = (short) Math.round(samples[index] * gain);
    }
  }

  private String failureStatus(Throwable throwable) {
    String type = throwable.getClass().getSimpleName();
    return "RadioModule error: " + (type.isEmpty() ? "playback failed" : type) + "; retrying...";
  }

  private void updateStatus(PlaybackSession session, String status) {
    if (sessions.owns(session) && !session.cancelled) this.status = status;
  }

  private static float clamp01(float value) {
    if (!Float.isFinite(value)) return 0.0f;
    return Math.max(0.0f, Math.min(1.0f, value));
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) return;
    try {
      closeable.close();
    } catch (IOException ignored) {
    }
  }

  private static void cancel(PlaybackSession session) {
    if (session == null) return;
    session.cancelled = true;
    Player player = session.player;
    if (player != null) player.close();
    closeQuietly(session.input);
    Thread worker = session.worker;
    if (worker != null) worker.interrupt();
  }

  static final class SessionRegistry {
    private PlaybackSession current;

    synchronized PlaybackSession replace(PlaybackSession next) {
      PlaybackSession previous = current;
      current = Objects.requireNonNull(next, "next");
      return previous;
    }

    synchronized PlaybackSession clear() {
      PlaybackSession previous = current;
      current = null;
      return previous;
    }

    synchronized boolean owns(PlaybackSession session) {
      return current == session;
    }
  }

  static final class PlaybackSession {
    private final long id;
    private final String streamUrl;
    private volatile boolean cancelled;
    private volatile Thread worker;
    private volatile Closeable input;
    private volatile Player player;

    PlaybackSession(long id, String streamUrl) {
      this.id = id;
      this.streamUrl = Objects.requireNonNull(streamUrl, "streamUrl");
    }
  }

  private final class VolumeAudioDevice extends JavaSoundAudioDevice {
    @Override
    protected void writeImpl(short[] samples, int offset, int length) throws JavaLayerException {
      scaleSamples(samples, offset, length, volume);
      super.writeImpl(samples, offset, length);
    }
  }
}
