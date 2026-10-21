/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Barnabas Toh, a1869720
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int QUEUE_CAPACITY = 8; // Bounded queue size per pipeline stage

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<PreparedFrame> preparedFrames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<Thread> pipelineThreads = new ArrayList<>();
    
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    
    private volatile boolean playbackOpen; // True when playback is happening.
    private volatile boolean pipelineRunning;
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    
    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private volatile long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>();
    }

    public void setFrameProcessors(List<FrameProcessor> processors) {
        this.frameProcessors.clear();
        if (processors != null) {
            this.frameProcessors.addAll(processors);
        }
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // Stops background threads before touching grabber or clearing queues
            stopPipelineThreads();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startPipelineThreads();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            startPipelineThreads();
            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrames.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // multi threaded implementation

    private void startPipelineThreads() {
        pipelineRunning = true;
        int numProcessors = frameProcessors.size();

        // Create queues connecting: Decoder -> Processor 0 -> ... -> Processor N-1 -> Converter
        List<BlockingQueue<RawVideoFrame>> stageQueues = new ArrayList<>(numProcessors + 1);
        for (int i = 0; i <= numProcessors; i++) {
            stageQueues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        }

        // 1. Decoder Thread (Stage 0)
        BlockingQueue<RawVideoFrame> decoderOut = stageQueues.get(0);
        Thread decoderThread = new Thread(() -> runDecoderStage(decoderOut), "Pipeline-Decoder");
        decoderThread.setDaemon(true);
        pipelineThreads.add(decoderThread);

        // 2. FrameProcessor Threads (Stages 1 .. N)
        for (int i = 0; i < numProcessors; i++) {
            final FrameProcessor processor = frameProcessors.get(i);
            final BlockingQueue<RawVideoFrame> inQueue = stageQueues.get(i);
            final BlockingQueue<RawVideoFrame> outQueue = stageQueues.get(i + 1);

            Thread processorThread = new Thread(
                    () -> runProcessorStage(processor, inQueue, outQueue),
                    "Pipeline-Processor-" + i);
            processorThread.setDaemon(true);
            pipelineThreads.add(processorThread);
        }

        // 3. Converter Thread (Final Stage -> preparedFrames)
        BlockingQueue<RawVideoFrame> converterIn = stageQueues.get(numProcessors);
        Thread converterThread = new Thread(() -> runConverterStage(converterIn), "Pipeline-Converter");
        converterThread.setDaemon(true);
        pipelineThreads.add(converterThread);

        for (Thread t : pipelineThreads) {
            t.start();
        }

    }

    private void stopPipelineThreads() {
        pipelineRunning = false;
        for (Thread t : pipelineThreads) {
            t.interrupt();
        }
        for (Thread t : pipelineThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pipelineThreads.clear();
        preparedFrames.clear();
        pendingAudio.clear();
    }

    private void runDecoderStage(BlockingQueue<RawVideoFrame> outQueue) {
        try {
            while (pipelineRunning && !Thread.currentThread().isInterrupted()) {
                Frame grabbed = grabFrame();
                if (grabbed == null) {
                    outQueue.put(RawVideoFrame.endOfStream());
                    return;
                }

                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && grabbed.samples != null) {
                    queueAudio(timestampUs, grabbed);
                }

                if (grabbed.image == null) {
                    continue;
                }

                if (!frameProcessorsInitialised) {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            grabbed.imageWidth,
                            grabbed.imageHeight,
                            grabbed.imageDepth,
                            grabbed.imageChannels,
                            grabbed.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                }

                if (firstTimestampUs == NO_SEEK_REQUEST) {
                    firstTimestampUs = timestampUs;
                }

                int frameNumber = grabber.getFrameNumber();
                InfoFrame info = new InfoFrame(frameNumber, timestampUs);

                // Clone the Frame so FFmpegFrameGrabber does not overwrite the buffer on the next grab()
                Frame clonedFrame = grabbed.clone();
                outQueue.put(new RawVideoFrame(clonedFrame, info, timestampUs, false));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (pipelineRunning) {
                Platform.runLater(() -> handlePlaybackError(e));
            }
        }
    }

    private void runProcessorStage(
            FrameProcessor processor,
            BlockingQueue<RawVideoFrame> inQueue,
            BlockingQueue<RawVideoFrame> outQueue) {
        try {
            while (pipelineRunning && !Thread.currentThread().isInterrupted()) {
                RawVideoFrame raw = inQueue.take();
                if (raw.isEndOfStream()) {
                    outQueue.put(raw);
                    return;
                }

                processor.process(raw.frame(), raw.info());
                outQueue.put(raw);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (pipelineRunning) {
                Platform.runLater(() -> handlePlaybackError(e));
            }
        }
    }

    private void runConverterStage(BlockingQueue<RawVideoFrame> inQueue) {
        try {
            while (pipelineRunning && !Thread.currentThread().isInterrupted()) {
                RawVideoFrame raw = inQueue.take();
                if (raw.isEndOfStream()) {
                    preparedFrames.put(PreparedFrame.endOfStream());
                    return;
                }

                Image image;
                try {
                    image = converter.convert(raw.frame());
                } finally {
                    // Always release the cloned native frame memory
                    raw.frame().close();
                }

                long relativeTimestampUs = Math.max(0, raw.timestampUs() - firstTimestampUs);
                long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;

                preparedFrames.put(new PreparedFrame(
                        image,
                        raw.info().frameNumber(),
                        raw.timestampUs(),
                        logicalTimestampUs,
                        relativeTimestampUs,
                        System.nanoTime(),
                        false));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (pipelineRunning) {
                Platform.runLater(() -> handlePlaybackError(e));
            }
        }
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        PreparedFrame nextFrame = preparedFrames.peek();

        // Check if the stream has finished and the final frame has been displayed
        if (nextFrame != null && nextFrame.isEndOfStream()) {
            preparedFrames.poll();
            finishPlayback();
            return;
        }

        // Anchor the master playback clock when the first frame arrives in the queue
        if (nextFrame != null && playbackStartNs == 0) {
            playbackStartNs = now;
            if (pauseRequested) {
                pauseStartedNs = now;
                // Render the first frame on screen when opening or seeking while paused
                displayPreparedFrame(preparedFrames.poll(), now, now);
                return;
            }
        }

        // Task (1): Keep audio flowing if sound is enabled
        writeDueAudio(now);

        // Do not advance video frames if paused or if the playback clock hasn't started yet
        if (pauseRequested || playbackStartNs == 0) {
            return;
        }

        // Task (2): Display the next prepared frame if its target timestamp is due
        if (nextFrame != null) {
            long targetTimeNs = playbackStartNs + nextFrame.relativeTimestampUs() * 1_000L;
            if (targetTimeNs <= now) {
                displayPreparedFrame(preparedFrames.poll(), targetTimeNs, now);
            }
        }
    }

    private void displayPreparedFrame(PreparedFrame frame, long targetTimeNs, long now) {
        if (frame == null) {
            return;
        }

        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - now) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.poll();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        // Stop and join all background pipeline threads before closing grabber/converter/audio
        stopPipelineThreads();

        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
            }
            try {
                grabber.close();
            } catch (Exception e) {
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record RawVideoFrame(
            Frame frame,
            InfoFrame info,
            long timestampUs,
            boolean isEndOfStream) {

        static RawVideoFrame endOfStream() {
            return new RawVideoFrame(null, null, 0L, true);
        }
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long relativeTimestampUs,
            long preparedAtNs,
            boolean isEndOfStream) {

        static PreparedFrame endOfStream() {
            return new PreparedFrame(null, 0, 0L, 0L, 0L, 0L, true);
        }
    }

    private static class PendingAudio {

        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
