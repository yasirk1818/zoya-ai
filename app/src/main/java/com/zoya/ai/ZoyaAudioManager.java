package com.zoya.ai;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZoyaAudioManager {

    private static final String TAG = "ZoyaAudio";

    // Recording config
    private static final int RECORD_SAMPLE_RATE = 16000;
    private static final int RECORD_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORD_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_CHUNK_SIZE = 4096;

    // Playback config
    private static final int PLAYBACK_SAMPLE_RATE = 24000;
    private static final int PLAYBACK_CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int PLAYBACK_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread recordingThread;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isMuted = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<byte[]> playbackQueue = new ConcurrentLinkedQueue<>();
    private Thread playbackThread;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    private AudioDataCallback audioCallback;

    public interface AudioDataCallback {
        void onAudioData(byte[] data);
    }

    public void setAudioCallback(AudioDataCallback callback) {
        this.audioCallback = callback;
    }

    @SuppressWarnings("MissingPermission")
    public void startRecording() {
        if (isRecording.get()) return;

        int bufferSize = Math.max(
                AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE, RECORD_CHANNEL, RECORD_ENCODING),
                RECORD_CHUNK_SIZE * 2
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORD_SAMPLE_RATE,
                RECORD_CHANNEL,
                RECORD_ENCODING,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            return;
        }

        isRecording.set(true);
        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[RECORD_CHUNK_SIZE];
            while (isRecording.get()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && !isMuted.get() && audioCallback != null) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    audioCallback.onAudioData(chunk);
                }
            }
        }, "AudioRecordThread");
        recordingThread.start();
    }

    public void stopRecording() {
        isRecording.set(false);
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord: " + e.getMessage());
            }
            audioRecord = null;
        }
    }

    public void startPlayback() {
        if (isPlaying.get()) return;

        int bufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE, PLAYBACK_CHANNEL, PLAYBACK_ENCODING);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(PLAYBACK_CHANNEL)
                        .setEncoding(PLAYBACK_ENCODING)
                        .build())
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
        isPlaying.set(true);

        playbackThread = new Thread(() -> {
            while (isPlaying.get()) {
                byte[] data = playbackQueue.poll();
                if (data != null) {
                    audioTrack.write(data, 0, data.length);
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "AudioPlaybackThread");
        playbackThread.start();
    }

    public void stopPlayback() {
        isPlaying.set(false);
        playbackQueue.clear();
        if (playbackThread != null) {
            try {
                playbackThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioTrack: " + e.getMessage());
            }
            audioTrack = null;
        }
    }

    public void enqueueAudio(byte[] data) {
        playbackQueue.add(data);
    }

    public void clearPlaybackQueue() {
        playbackQueue.clear();
    }

    public void setMuted(boolean muted) {
        isMuted.set(muted);
    }

    public boolean isMuted() {
        return isMuted.get();
    }

    public void release() {
        stopRecording();
        stopPlayback();
    }
}
