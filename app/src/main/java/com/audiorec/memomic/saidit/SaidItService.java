package com.audiorec.memomic.saidit;



import static com.audiorec.memomic.saidit.SaidIt.AUDIO_MEMORY_ENABLED_KEY;
import static com.audiorec.memomic.saidit.SaidIt.AUDIO_MEMORY_SIZE_KEY;
import static com.audiorec.memomic.saidit.SaidIt.PACKAGE_NAME;
import static com.audiorec.memomic.saidit.SaidIt.SAMPLE_RATE_KEY;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.audiorec.memomic.R;
import com.audiorec.memomic.pcm.WavAudioFormat;
import com.audiorec.memomic.pcm.WavFileWriter;

import java.io.File;
import java.io.IOException;



public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final String YOUR_NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;


    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread

    @Override
    public void onCreate() {

        Log.d(TAG, "Reading native sample rate");

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate (AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }

    }

    @Override
    public void onDestroy() {
        stopRecording(null, "");
        innerStopListening();
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();

        innerStartListening();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
    }

    int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    private void innerStartListening() {
        switch(state) {
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");

                audioRecord = new AudioRecord(
                       MediaRecorder.AudioSource.MIC,
                       SAMPLE_RATE,
                       AudioFormat.CHANNEL_IN_MONO,
                       AudioFormat.ENCODING_PCM_16BIT,
                       AudioMemory.CHUNK_SIZE);

                if(audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    return;
                }

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioMemory.allocate(memorySize);

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });


    }

    private void innerStopListening() {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null)
                    audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });

    }

    public void dumpRecording(final float memorySeconds, final WavFileReceiver wavFileReceiver, String newFileName) {
        if(state != STATE_LISTENING) throw new IllegalStateException("Not listening!");

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int)(memorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                long millis  = System.currentTimeMillis() - 1000 * useBytes / FILL_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".wav";
                if(!newFileName.equals("")){
                    filename = newFileName + ".wav";
                }

                File storageDir;
                if(isExternalStorageWritable()){
                    // Use public storage directory for Android 11+ (min SDK 30)
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }

                if(!storageDir.exists()){
                    storageDir.mkdir();
                }
                File file = new File(storageDir, filename);

                // Create the file if it doesn't exist
                if (!file.exists()) {
                    try {
                        if (!file.createNewFile()) {
                            // Handle file creation failure
                            throw new IOException("Failed to create file");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        // Handle IOException
                        showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    }
                }
                final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try (WavFileWriter writer = new WavFileWriter(format, file)) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                writer.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        // Handle error during file writing
                        showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
                        Log.e(TAG, "Error during writing history into " + file.getAbsolutePath(), e);
                    }
                    if (wavFileReceiver != null) {
                        wavFileReceiver.fileReady(file, writer.getTotalSampleBytesWritten() * getBytesToSeconds());
                    }
                } catch (IOException e) {
                    // Handle error during file creation or closing writer
                    showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    Log.e(TAG, "Can't create file " + file.getAbsolutePath(), e);
                }
            }
        });

    }
    private static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }
    private void showToast(String message) {
        Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
    }

    public void startRecording(final float prependedMemorySeconds) {
        switch(state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int)(prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                long millis  = System.currentTimeMillis() - 1000 * useBytes / FILL_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".wav";

                File storageDir;
                if(isExternalStorageWritable()){
                    // Use public storage directory for Android 11+ (min SDK 30)
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }
                final String storagePath = storageDir.getAbsolutePath();

                String path = storagePath + "/" + filename;

                wavFile = new File(path);
                try {
                    wavFile.createNewFile();
                } catch (IOException e) {
                    filename = filename.replace(':', '.');
                    path = storagePath + "/" + filename;
                    wavFile = new File(path);
                }
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + path;
                    Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, errorMessage, e);
                    return;
                }

                final String finalPath = path;

                if(skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                wavFileWriter.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        final String errorMessage = getString(R.string.error_during_writing_history_into) + finalPath;
                        Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, errorMessage, e);
                        stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
                    }
                }
            }
        });

    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
    }

    public interface WavFileReceiver {
        public void fileReady(File file, float runtime);
    }

    public void stopRecording(final WavFileReceiver wavFileReceiver, String newFileName) {
        switch(state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                try {
                    wavFileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "CLOSING ERROR", e);
                }
                if(wavFileReceiver != null) {
                    wavFileReceiver.fileReady(wavFile, wavFileWriter.getTotalSampleBytesWritten() * getBytesToSeconds());
                }
                wavFileWriter = null;
            }
        });

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }

        stopForeground(true);
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
//            Log.d(TAG, "READING " + count + " B");
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AUDIO RECORD ERROR - BAD VALUE");
                return 0;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AUDIO RECORD ERROR - INVALID OPERATION");
                return 0;
            }
            if (read == AudioRecord.ERROR) {
                Log.e(TAG, "AUDIO RECORD ERROR - UNKNOWN ERROR");
                return 0;
            }
            if (wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            if (read == count) {
                // We've filled the buffer, so let's read again.
                audioHandler.post(audioReader);
            } else {
                // It seems we've read everything!
                //
                // Estimate how long do we have until audioRecord fills up completely and post the callback 1 second before that
                // (but not earlier than half the buffer and no later than 90% of the buffer).
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float)SAMPLE_RATE;
                float delaySeconds = bufferSizeInSeconds - 1;
                delaySeconds = Math.max(delaySeconds, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader, (long)(delaySeconds * 1000));
            }
            return read;
        }
    };
    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                final String errorMessage = getString(R.string.error_during_recording_into) + wavFile.getName();
                Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
            }
        }
    };

    public interface StateCallback {
        public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        // Note that we may not run this for quite a while, if audioReader decides to read a lot of audio!
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);
                
                int recorded = 0;
                if(wavFileWriter != null) {
                    recorded += wavFileWriter.getTotalSampleBytesWritten();
                    recorded += stats.estimation;
                }
                final float bytesToSeconds = getBytesToSeconds();
                final int finalRecorded = recorded;
                sourceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(listeningEnabled, recording,
                                (stats.overwriting ? stats.total : stats.filled + stats.estimation) * bytesToSeconds,
                                stats.total * bytesToSeconds,
                                finalRecorded * bytesToSeconds);
                    }
                });
            }
        });
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_STICKY;
    }

    // Workaround for bug where recent app removal caused service to stop
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT| PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, YOUR_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.recording))
                .setSmallIcon(R.mipmap.ic_stat_notify_recording)
                .setTicker(getString(R.string.recording))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true); // Ensure notification is ongoing

        // Create the notification channel
        NotificationChannel channel = new NotificationChannel(
                YOUR_NOTIFICATION_CHANNEL_ID,
                "Recording Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        return notificationBuilder.build();
    }

}
