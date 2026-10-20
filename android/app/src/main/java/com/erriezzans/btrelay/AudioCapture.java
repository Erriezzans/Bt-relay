package com.erriezzans.btrelay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;

/**
 * Records what this app plays (the remote-browser WebView), shrinks it to AMR
 * (about 1.5 KB per second) and keeps it in 10-second clips for the Nokia to fetch.
 */
class AudioCapture {

    static final int CLIP_FRAMES = 500;      // 500 frames x 20 ms = 10 seconds per clip
    static final int MAX_CLIPS = 6;           // keep at most one minute
    static final int SILENCE_PEAK = 200;      // clips quieter than this are dropped
    private static final byte[] AMR_HEADER = {'#', '!', 'A', 'M', 'R', '\n'};

    private static final LinkedList<byte[]> clips = new LinkedList<byte[]>();
    private static MediaProjection projection;
    private static volatile boolean running = false;
    private static volatile String lastError = "";
    static volatile RemoteBrowser.Log logger;

    static boolean isRunning() {
        return running;
    }

    static String lastError() {
        return lastError;
    }

    static synchronized void start(final MediaProjection mp) {
        stop();
        projection = mp;
        running = true;
        lastError = "";
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                synchronized (AudioCapture.class) {
                    if (projection == mp) {
                        running = false;
                    }
                }
            }
        }, null);
        new Thread(new Runnable() {
            public void run() {
                capture(mp);
            }
        }).start();
    }

    static synchronized void stop() {
        running = false;
        MediaProjection old = projection;
        projection = null;
        if (old != null) {
            try {
                old.stop();
            } catch (Exception ignored) {
            }
        }
        synchronized (clips) {
            clips.clear();
            clips.notifyAll();
        }
    }

    /** Waits up to waitMs for the next clip; null if none came. */
    static byte[] nextClip(long waitMs) throws InterruptedException {
        synchronized (clips) {
            long end = System.currentTimeMillis() + waitMs;
            while (clips.isEmpty() && running) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                clips.wait(left);
            }
            return clips.isEmpty() ? null : clips.removeFirst();
        }
    }

    private static void addClip(byte[] c) {
        synchronized (clips) {
            clips.add(c);
            while (clips.size() > MAX_CLIPS) {
                clips.removeFirst();
            }
            clips.notifyAll();
        }
    }

    private static void log(String m) {
        RemoteBrowser.Log l = logger;
        if (l != null) {
            l.log(m);
        }
    }

    private static void capture(MediaProjection mp) {
        AudioRecord rec = null;
        MediaCodec codec = null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, 7680) * 4)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();

            MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1);
            mf.setInteger(MediaFormat.KEY_BIT_RATE, 12200);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB);
            codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            rec.startRecording();
            log("Sound capture running");

            short[] in = new short[1920];            // 20 ms of 48 kHz stereo
            byte[] pcm = new byte[320];              // 20 ms of 8 kHz mono, 16-bit
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteArrayOutputStream clip = new ByteArrayOutputStream();
            clip.write(AMR_HEADER, 0, AMR_HEADER.length);
            int frames = 0;
            int peak = 0;
            long pts = 0;

            while (running) {
                int n = 0;
                while (n < in.length && running) {
                    int r = rec.read(in, n, in.length - n);
                    if (r <= 0) {
                        throw new IllegalStateException("AudioRecord.read returned " + r);
                    }
                    n += r;
                }
                if (!running) {
                    break;
                }
                // 48 kHz stereo -> 8 kHz mono: average each group of 6 stereo frames
                for (int i = 0; i < 160; i++) {
                    int sum = 0;
                    for (int k = 0; k < 12; k++) {
                        sum += in[i * 12 + k];
                    }
                    int v = sum / 12;
                    int a = Math.abs(v);
                    if (a > peak) {
                        peak = a;
                    }
                    pcm[i * 2] = (byte) (v & 0xFF);
                    pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                int ii = codec.dequeueInputBuffer(20000);
                if (ii >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(ii);
                    ib.clear();
                    ib.put(pcm, 0, 320);
                    codec.queueInputBuffer(ii, 0, 320, pts, 0);
                    pts += 20000;
                }
                int oi;
                while ((oi = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        byte[] f = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(f);
                        if (f.length == 31) {
                            clip.write(0x3C);        // frame came without its header byte (12.2 kbit/s)
                        }
                        clip.write(f, 0, f.length);
                        frames++;
                    }
                    codec.releaseOutputBuffer(oi, false);
                }
                if (frames >= CLIP_FRAMES) {
                    if (peak > SILENCE_PEAK) {
                        addClip(clip.toByteArray());
                    }
                    clip.reset();
                    clip.write(AMR_HEADER, 0, AMR_HEADER.length);
                    frames = 0;
                    peak = 0;
                }
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            log("Sound capture error: " + t);
        } finally {
            running = false;
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            synchronized (clips) {
                clips.notifyAll();
            }
            log("Sound capture stopped");
        }
    }
}
