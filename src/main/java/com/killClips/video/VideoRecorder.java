package com.killClips.video;

import com.killClips.KillClipsConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Core recording engine: maintains a rolling circular buffer of JPEG-compressed frames
// in memory and, on demand, extracts a time-windowed slice into an AVI container.
@Slf4j
@Singleton
public class VideoRecorder
{
    // Absolute upper bounds
    private static final int CEILING_DURATION_SEC = 60;
    private static final int CEILING_FPS = 30;
    private static final int RING_CAPACITY = CEILING_DURATION_SEC * CEILING_FPS;

    // Post-event capture: how long to keep recording after the triggering event
    private static final int DEFAULT_POST_EVENT_MS = 4000;

    // Encoding pipeline backpressure
    private static final int ENCODE_CONCURRENCY_LIMIT = 4;

    // How often we re-evaluate whether login/bank-pin widgets are visible
    private static final long SENSITIVE_RECHECK_MS = 500;

    // Absolute maximum output resolution
    private static final int ABS_CAP_W = 1920;
    private static final int ABS_CAP_H = 1080;

    // Frame deduplication: reuse previous JPEG when the new encode is byte-identical.
    // Only catches truly static frames (menus, afk in empty room) — never collapses real motion.
    private volatile byte[] previousFrameJpeg = null;
    private static final int DEDUP_SIZE_TOLERANCE = 2048;

    // Box-blur kernel radius for sensitive-content redaction
    private static final int REDACT_RADIUS = 15;

    // -- Injected dependencies --
    private final DrawManager drawMgr;
    private final KillClipsConfig cfg;
    private final Client gameClient;
    private final ChatMessageManager chatMgr;

    // -- Thread pools --
    private final ScheduledExecutorService timer;
    private final ExecutorService encoderPool;
    private final ExecutorService serializerPool;

    // -- Ring buffer --
    private final byte[][] ringJpeg = new byte[RING_CAPACITY][];
    private final long[] ringTs = new long[RING_CAPACITY];
    private final boolean[] ringBlurFlag = new boolean[RING_CAPACITY];
    private final AtomicInteger ringHead = new AtomicInteger(0);
    private final AtomicInteger ringSize = new AtomicInteger(0);
    private final Object ringLock = new Object();

    // -- State flags --
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean postEventActive = new AtomicBoolean(false);
    private final AtomicInteger inflightEncodes = new AtomicInteger(0);

    // -- Sensitive content cache --
    private final AtomicBoolean cachedSensitive = new AtomicBoolean(false);
    private final AtomicLong sensitiveCheckedAt = new AtomicLong(0);

    // -- Async GPU capture --
    private AsyncFrameCapture gpuCapture;
    private volatile int activeFps = 0;
    private volatile float activeJpegQuality = 0.5f;
    private volatile boolean noGpu = false;
    private volatile boolean gpuMsgSent = false;

    @Inject
    public VideoRecorder(DrawManager drawMgr, KillClipsConfig cfg, Client gameClient, ChatMessageManager chatMgr)
    {
        this.drawMgr = drawMgr;
        this.cfg = cfg;
        this.gameClient = gameClient;
        this.chatMgr = chatMgr;

        this.timer = Executors.newScheduledThreadPool(1, r ->
        {
            Thread th = new Thread(r, "KillClips-Scheduler");
            th.setDaemon(true);
            return th;
        });
        this.encoderPool = Executors.newFixedThreadPool(2, r ->
        {
            Thread th = new Thread(r, "KillClips-Encoder");
            th.setDaemon(true);
            return th;
        });
        this.serializerPool = Executors.newSingleThreadExecutor(r ->
        {
            Thread th = new Thread(r, "KillClips-Serializer");
            th.setDaemon(true);
            return th;
        });

        log.debug("Recorder ready (ring capacity={})", computeEffectiveCapacity());
    }

    // ------------------------------------------------------------------
    //  Effective config helpers
    // ------------------------------------------------------------------

    private int computeEffectiveCapacity()
    {
        int sec = clampDuration();
        int fps = cfg.videoFps().getFps();
        return sec * fps;
    }

    private int computeEffectiveDurationMs()
    {
        return clampDuration() * 1000;
    }

    private int clampDuration()
    {
        return Math.max(10, Math.min(CEILING_DURATION_SEC, cfg.recordingDuration()));
    }

    // ------------------------------------------------------------------
    //  Package-level API consumed by AsyncFrameCapture
    // ------------------------------------------------------------------

    boolean isCurrentlyRecording()
    {
        return recording.get();
    }

    int getCaptureFps()
    {
        return activeFps;
    }

    boolean canAcceptFrame()
    {
        return inflightEncodes.get() < ENCODE_CONCURRENCY_LIMIT;
    }

    // Accepts raw RGBA pixels from the PBO readback and schedules encoding
    void submitCapturedFrame(ByteBuffer rgba, int w, int h)
    {
        long now = System.currentTimeMillis();
        boolean redact = fetchSensitiveFlag(now);

        inflightEncodes.incrementAndGet();
        encoderPool.submit(() ->
        {
            try
            {
                processRawFrame(rgba, w, h, now, redact);
            }
            catch (Exception ex)
            {
                log.error("Frame encoding failed", ex);
            }
            finally
            {
                inflightEncodes.decrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------
    //  Sensitive content detection
    // ------------------------------------------------------------------

    private boolean querySensitiveWidgets()
    {
        try
        {
            GameState gs = gameClient.getGameState();
            if (gs == GameState.LOGIN_SCREEN
                || gs == GameState.LOGIN_SCREEN_AUTHENTICATOR
                || gs == GameState.LOGGING_IN)
            {
                return true;
            }

            Widget pinPad = gameClient.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
            if (pinPad != null && !pinPad.isHidden())
            {
                return true;
            }

            Widget pinSettings = gameClient.getWidget(InterfaceID.BANKPIN_SETTINGS, 0);
            if (pinSettings != null && !pinSettings.isHidden())
            {
                return true;
            }
        }
        catch (Exception ex)
        {
            log.debug("Widget check error: {}", ex.getMessage());
        }
        return false;
    }

    // Returns a cached result, refreshing at most every SENSITIVE_RECHECK_MS
    private boolean fetchSensitiveFlag(long nowMs)
    {
        long prev = sensitiveCheckedAt.get();
        if (nowMs - prev >= SENSITIVE_RECHECK_MS)
        {
            if (sensitiveCheckedAt.compareAndSet(prev, nowMs))
            {
                boolean vis = querySensitiveWidgets();
                cachedSensitive.set(vis);
                return vis;
            }
        }
        return cachedSensitive.get();
    }

    // ------------------------------------------------------------------
    //  Blur / redaction
    // ------------------------------------------------------------------

    private BufferedImage redactImage(BufferedImage src)
    {
        int w = src.getWidth();
        int h = src.getHeight();

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(src, 0, 0, null);
        g2.dispose();

        // Three passes of box blur to thoroughly obscure
        for (int p = 0; p < 3; p++)
        {
            out = singlePassBoxBlur(out, REDACT_RADIUS);
        }

        // Dark overlay + warning text
        g2 = out.createGraphics();
        g2.setColor(new Color(0, 0, 0, 100));
        g2.fillRect(0, 0, w, h);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 24));
        FontMetrics fm = g2.getFontMetrics();
        String notice = "SENSITIVE CONTENT HIDDEN";
        int tx = (w - fm.stringWidth(notice)) / 2;
        int ty = h / 2;
        g2.drawString(notice, tx, ty);
        g2.dispose();

        return out;
    }

    // Horizontal then vertical averaging
    private BufferedImage singlePassBoxBlur(BufferedImage src, int rad)
    {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        src.getRGB(0, 0, w, h, px, 0, w);

        // Horizontal
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                int rAcc = 0, gAcc = 0, bAcc = 0, n = 0;
                for (int dx = -rad; dx <= rad; dx++)
                {
                    int sx = Math.max(0, Math.min(w - 1, col + dx));
                    int c = px[row * w + sx];
                    rAcc += (c >> 16) & 0xFF;
                    gAcc += (c >> 8) & 0xFF;
                    bAcc += c & 0xFF;
                    n++;
                }
                tmp[row * w + col] = (0xFF << 24)
                    | ((rAcc / n) << 16)
                    | ((gAcc / n) << 8)
                    | (bAcc / n);
            }
        }

        // Vertical
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                int rAcc = 0, gAcc = 0, bAcc = 0, n = 0;
                for (int dy = -rad; dy <= rad; dy++)
                {
                    int sy = Math.max(0, Math.min(h - 1, row + dy));
                    int c = tmp[sy * w + col];
                    rAcc += (c >> 16) & 0xFF;
                    gAcc += (c >> 8) & 0xFF;
                    bAcc += c & 0xFF;
                    n++;
                }
                px[row * w + col] = (0xFF << 24)
                    | ((rAcc / n) << 16)
                    | ((gAcc / n) << 8)
                    | (bAcc / n);
            }
        }

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, px, 0, w);
        return result;
    }

    // ------------------------------------------------------------------
    //  Recording lifecycle
    // ------------------------------------------------------------------

    public void startRecording()
    {
        if (recording.getAndSet(true))
        {
            return;
        }

        resetRing();
        previousFrameJpeg = null;
        noGpu = false;
        gpuMsgSent = false;

        activeFps = cfg.videoFps().getFps();
        activeJpegQuality = cfg.videoResolution().getJpegQuality();
        log.debug("Recording at {} fps, JPEG quality {}%", activeFps, (int) (activeJpegQuality * 100));

        gpuCapture = new AsyncFrameCapture(drawMgr, this, this::handleGpuMissing);
        gpuCapture.start();
    }

    public void stopRecording()
    {
        if (!recording.getAndSet(false))
        {
            return;
        }

        if (gpuCapture != null)
        {
            gpuCapture.stop();
            gpuCapture = null;
        }

        resetRing();
        previousFrameJpeg = null;
        activeFps = 0;
    }

    private void handleGpuMissing()
    {
        log.debug("GPU unavailable -- disabling video capture");
        if (gpuCapture != null)
        {
            gpuCapture.stop();
            gpuCapture = null;
        }
        recording.set(false);
        activeFps = 0;
        noGpu = true;
    }

    // Detects quality/mode changes and adjusts the live capture accordingly
    public void updateCaptureRateIfNeeded()
    {
        if (postEventActive.get())
        {
            return;
        }

        if (noGpu)
        {
            return;
        }

        float wantedQuality = cfg.videoResolution().getJpegQuality();
        if (wantedQuality != activeJpegQuality)
        {
            activeJpegQuality = wantedQuality;
        }

        int wantedFps = cfg.videoFps().getFps();
        if (wantedFps != activeFps)
        {
            log.debug("FPS change: {} -> {}", activeFps, wantedFps);
            resetRing();
            activeFps = wantedFps;

            if (gpuCapture == null)
            {
                gpuCapture = new AsyncFrameCapture(drawMgr, this, this::handleGpuMissing);
                gpuCapture.start();
            }
            recording.set(true);
        }
    }

    // ------------------------------------------------------------------
    //  Event capture API
    // ------------------------------------------------------------------

    public void captureEventVideo(VideoCallback cb)
    {
        captureEventVideo(cb, null, DEFAULT_POST_EVENT_MS);
    }

    public void captureEventVideo(VideoCallback cb, Runnable onEncodingStart)
    {
        captureEventVideo(cb, onEncodingStart, DEFAULT_POST_EVENT_MS);
    }

    public void captureEventVideo(VideoCallback cb, Runnable onEncodingStart, int postMs)
    {
        if (!recording.get())
        {
            // One-time warning when GPU plugin is not loaded
            if (noGpu && !gpuMsgSent)
            {
                gpuMsgSent = true;
                if (chatMgr != null)
                {
                    chatMgr.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("<col=ff9040>[Kill Clips] GPU plugin required for video. Using screenshot-only mode.</col>")
                        .build());
                }
            }
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(cb);
            return;
        }

        // If another capture is already in its post-event window, fall back to screenshot
        if (postEventActive.get())
        {
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(cb);
            return;
        }

        int totalMs = computeEffectiveDurationMs();
        int preMs = Math.max(1000, totalMs - postMs);

        postEventActive.set(true);

        long eventTime = System.currentTimeMillis();
        long windowStart = eventTime - preMs;
        long windowEnd = eventTime + postMs;

        timer.schedule(() ->
        {
            postEventActive.set(false);
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            assembleClip(cb, windowStart, windowEnd);
        }, postMs, TimeUnit.MILLISECONDS);
    }

    // Grabs a single frame for events that do not need video
    public void captureScreenshotOnly(VideoCallback cb)
    {
        boolean redact = querySensitiveWidgets();

        drawMgr.requestNextFrameListener(img ->
            encoderPool.submit(() ->
            {
                try
                {
                    BufferedImage shot = ImageUtil.bufferedImageFromImage(img);
                    if (redact)
                    {
                        shot = redactImage(shot);
                    }
                    String b64 = bufferedImageToPngBase64(shot);
                    cb.onComplete(b64, null);
                }
                catch (Exception ex)
                {
                    log.error("Screenshot capture failed", ex);
                    cb.onComplete(null, null);
                }
            })
        );
    }

    // ------------------------------------------------------------------
    //  Frame encoding pipeline
    // ------------------------------------------------------------------

    private void processRawFrame(ByteBuffer rgba, int w, int h, long ts, boolean redact)
    {
        try
        {
            if (rgba == null || w <= 0 || h <= 0)
            {
                return;
            }
            BufferedImage bimg = rgbaToBufferedImage(rgba, w, h);
            bimg = constrainResolution(bimg, w, h);
            byte[] jpeg = compressJpeg(bimg);

            // Byte-exact dedup: only reuse if the new JPEG encode is identical to the previous one.
            if (!redact && isDuplicateFrame(jpeg))
            {
                pushToRing(previousFrameJpeg, ts, false);
            }
            else
            {
                previousFrameJpeg = jpeg;
                pushToRing(jpeg, ts, redact);
            }
        }
        catch (IOException ex)
        {
            log.error("Frame processing failed at ts={}", ts, ex);
        }
    }

    private boolean isDuplicateFrame(byte[] jpeg)
    {
        byte[] prev = previousFrameJpeg;
        if (prev == null || jpeg == null)
        {
            return false;
        }
        if (Math.abs(jpeg.length - prev.length) > DEDUP_SIZE_TOLERANCE)
        {
            return false;
        }
        return java.util.Arrays.equals(jpeg, prev);
    }

    // Converts bottom-up RGBA pixels (OpenGL convention) into a top-down BufferedImage
    private BufferedImage rgbaToBufferedImage(ByteBuffer rgba, int w, int h)
    {
        BufferedImage bimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        rgba.rewind();
        int stride = w * 4;

        for (int destY = 0; destY < h; destY++)
        {
            int srcY = h - 1 - destY;
            int rowBase = srcY * stride;
            for (int x = 0; x < w; x++)
            {
                int off = rowBase + x * 4;
                int red = rgba.get(off) & 0xFF;
                int grn = rgba.get(off + 1) & 0xFF;
                int blu = rgba.get(off + 2) & 0xFF;
                bimg.setRGB(x, destY, (red << 16) | (grn << 8) | blu);
            }
        }
        return bimg;
    }

    // Downscales to the resolution cap based on quality preset
    private BufferedImage constrainResolution(Image src, int srcW, int srcH)
    {
        int capH = cfg.videoResolution().getMaxHeight();
        int capW = (int) (capH * 16.0 / 9.0);

        // Also enforce absolute maximum
        capW = Math.min(capW, ABS_CAP_W);
        capH = Math.min(capH, ABS_CAP_H);

        int dstW = srcW;
        int dstH = srcH;
        if (srcW > capW || srcH > capH)
        {
            double ratio = Math.min((double) capW / srcW, (double) capH / srcH);
            dstW = (int) (srcW * ratio);
            dstH = (int) (srcH * ratio);
        }
        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, dstW, dstH, null);
        g2.dispose();
        return out;
    }

    private byte[] compressJpeg(BufferedImage bimg) throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(30_000);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(activeJpegQuality);

        ImageOutputStream ios = ImageIO.createImageOutputStream(buf);
        writer.setOutput(ios);
        writer.write(null, new IIOImage(bimg, null, null), params);
        writer.dispose();
        ios.close();
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------
    //  Ring buffer operations
    // ------------------------------------------------------------------

    private void pushToRing(byte[] jpeg, long ts, boolean blur)
    {
        synchronized (ringLock)
        {
            int cap = computeEffectiveCapacity();
            int slot = ringHead.getAndIncrement() % RING_CAPACITY;
            ringJpeg[slot] = jpeg;
            ringTs[slot] = ts;
            ringBlurFlag[slot] = blur;

            int sz = ringSize.get();
            if (sz < cap)
            {
                ringSize.incrementAndGet();
            }
            else
            {
                // Evict the oldest entry beyond our effective window
                int oldSlot = (ringHead.get() - cap - 1 + RING_CAPACITY) % RING_CAPACITY;
                ringJpeg[oldSlot] = null;
            }
        }
    }

    private void resetRing()
    {
        synchronized (ringLock)
        {
            for (int i = 0; i < RING_CAPACITY; i++)
            {
                ringJpeg[i] = null;
                ringTs[i] = 0;
                ringBlurFlag[i] = false;
            }
            ringHead.set(0);
            ringSize.set(0);
        }
    }

    // ------------------------------------------------------------------
    //  Clip assembly
    // ------------------------------------------------------------------

    private void assembleClip(VideoCallback cb, long tStart, long tEnd)
    {
        boolean redact = querySensitiveWidgets();

        drawMgr.requestNextFrameListener(img ->
            encoderPool.submit(() ->
            {
                try
                {
                    BufferedImage shot = ImageUtil.bufferedImageFromImage(img);
                    if (redact)
                    {
                        shot = redactImage(shot);
                    }
                    String screenshotB64 = bufferedImageToPngBase64(shot);

                    FrameBatch batch = extractFrames(tStart, tEnd);
                    if (batch == null || batch.jpegList.isEmpty())
                    {
                        cb.onComplete(screenshotB64, null);
                        return;
                    }

                    serializerPool.submit(() ->
                    {
                        try
                        {
                            String videoB64 = framesToBase64Video(batch.jpegList);
                            cb.onComplete(screenshotB64, videoB64);
                        }
                        catch (Exception ex)
                        {
                            log.error("AVI serialization failed", ex);
                            cb.onComplete(screenshotB64, null);
                        }
                    });
                }
                catch (Exception ex)
                {
                    log.error("Clip assembly failed", ex);
                    cb.onComplete(null, null);
                }
            })
        );
    }

    // Snapshot + apply deferred blur for frames in the time window
    private FrameBatch extractFrames(long tStart, long tEnd)
    {
        List<PendingFrame> pending = new ArrayList<>();

        synchronized (ringLock)
        {
            int sz = ringSize.get();
            int head = ringHead.get() % RING_CAPACITY;
            for (int i = 0; i < sz; i++)
            {
                int slot = (head - sz + i + RING_CAPACITY) % RING_CAPACITY;
                long ts = ringTs[slot];
                byte[] data = ringJpeg[slot];
                boolean needsBlur = ringBlurFlag[slot];
                if (ts >= tStart && ts <= tEnd && data != null)
                {
                    pending.add(new PendingFrame(data, needsBlur));
                }
            }
        }

        List<byte[]> finalFrames = new ArrayList<>();
        for (PendingFrame pf : pending)
        {
            byte[] frameData = pf.jpeg;
            if (pf.blur)
            {
                try
                {
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frameData));
                    if (decoded != null)
                    {
                        BufferedImage blurred = redactImage(decoded);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(blurred, "jpg", bos);
                        frameData = bos.toByteArray();
                    }
                }
                catch (IOException ex)
                {
                    log.error("Deferred blur application failed", ex);
                }
            }
            finalFrames.add(frameData);
        }

        if (finalFrames.isEmpty())
        {
            return null;
        }
        FrameBatch batch = new FrameBatch();
        batch.jpegList = finalFrames;
        return batch;
    }

    private static class FrameBatch
    {
        List<byte[]> jpegList;
    }

    private static class PendingFrame
    {
        final byte[] jpeg;
        final boolean blur;

        PendingFrame(byte[] jpeg, boolean blur)
        {
            this.jpeg = jpeg;
            this.blur = blur;
        }
    }

    // ------------------------------------------------------------------
    //  MJPEG AVI builder
    // ------------------------------------------------------------------

    private String framesToBase64Video(List<byte[]> jpegs) throws IOException
    {
        byte[] avi = assembleMjpegAvi(jpegs);
        log.info("MJPEG AVI: {} frames -> {} bytes", jpegs.size(), avi.length);
        return Base64.getEncoder().encodeToString(avi);
    }

    private byte[] assembleMjpegAvi(List<byte[]> jpegs) throws IOException
    {
        if (jpegs.isEmpty())
        {
            return new byte[0];
        }

        int[] dim = parseJpegSize(jpegs.get(0));
        int imgW = dim[0];
        int imgH = dim[1];
        int fps = (activeFps > 0) ? activeFps : 30;
        int numFrames = jpegs.size();

        int biggestFrame = 0;
        for (byte[] j : jpegs)
        {
            biggestFrame = Math.max(biggestFrame, j.length);
        }

        // Build the movi chunk list, tracking offsets for the index
        ByteArrayOutputStream moviBody = new ByteArrayOutputStream();
        int[] offsets = new int[numFrames];
        int cursor = 4; // "movi" fourcc precedes these chunks

        for (int i = 0; i < numFrames; i++)
        {
            byte[] f = jpegs.get(i);
            offsets[i] = cursor;
            emitFourCC(moviBody, "00dc");
            emitLE32(moviBody, f.length);
            moviBody.write(f);
            if (f.length % 2 != 0)
            {
                moviBody.write(0);
            }
            cursor += 8 + f.length + (f.length % 2);
        }
        byte[] moviRaw = moviBody.toByteArray();

        // Size arithmetic for the RIFF structure
        int idx1Payload = 16 * numFrames;
        int riffPayload = 4           // "AVI "
            + 8 + 192                 // hdrl LIST
            + 8 + 4 + moviRaw.length  // movi LIST
            + 8 + idx1Payload;        // idx1

        ByteArrayOutputStream avi = new ByteArrayOutputStream();

        // RIFF wrapper
        emitFourCC(avi, "RIFF");
        emitLE32(avi, riffPayload);
        emitFourCC(avi, "AVI ");

        // hdrl LIST
        emitFourCC(avi, "LIST");
        emitLE32(avi, 192);
        emitFourCC(avi, "hdrl");

        // avih chunk (main AVI header, 56 bytes payload)
        emitFourCC(avi, "avih");
        emitLE32(avi, 56);
        emitLE32(avi, 1_000_000 / fps);    // microseconds per frame
        emitLE32(avi, biggestFrame * fps);  // max bytes/sec
        emitLE32(avi, 0);                   // padding granularity
        emitLE32(avi, 0x10);                // flags: AVIF_HASINDEX
        emitLE32(avi, numFrames);           // total frames
        emitLE32(avi, 0);                   // initial frames
        emitLE32(avi, 1);                   // stream count
        emitLE32(avi, biggestFrame);        // suggested buffer
        emitLE32(avi, imgW);
        emitLE32(avi, imgH);
        emitLE32(avi, 0);                   // reserved[4]
        emitLE32(avi, 0);
        emitLE32(avi, 0);
        emitLE32(avi, 0);

        // strl LIST
        emitFourCC(avi, "LIST");
        emitLE32(avi, 116);
        emitFourCC(avi, "strl");

        // strh (stream header, 56 bytes payload)
        emitFourCC(avi, "strh");
        emitLE32(avi, 56);
        emitFourCC(avi, "vids");
        emitFourCC(avi, "MJPG");
        emitLE32(avi, 0);               // flags
        emitLE16(avi, 0);               // priority
        emitLE16(avi, 0);               // language
        emitLE32(avi, 0);               // initial frames
        emitLE32(avi, 1);               // scale
        emitLE32(avi, fps);             // rate
        emitLE32(avi, 0);               // start
        emitLE32(avi, numFrames);       // length
        emitLE32(avi, biggestFrame);    // suggested buffer
        emitLE32(avi, 0xFFFFFFFF);      // quality (-1)
        emitLE32(avi, 0);               // sample size
        emitLE16(avi, 0);               // rcFrame left
        emitLE16(avi, 0);               // rcFrame top
        emitLE16(avi, imgW);            // rcFrame right
        emitLE16(avi, imgH);            // rcFrame bottom

        // strf (BITMAPINFOHEADER, 40 bytes payload)
        emitFourCC(avi, "strf");
        emitLE32(avi, 40);
        emitLE32(avi, 40);              // biSize
        emitLE32(avi, imgW);            // biWidth
        emitLE32(avi, imgH);            // biHeight
        emitLE16(avi, 1);               // biPlanes
        emitLE16(avi, 24);              // biBitCount
        emitFourCC(avi, "MJPG");        // biCompression
        emitLE32(avi, imgW * imgH * 3); // biSizeImage
        emitLE32(avi, 0);               // biXPelsPerMeter
        emitLE32(avi, 0);               // biYPelsPerMeter
        emitLE32(avi, 0);               // biClrUsed
        emitLE32(avi, 0);               // biClrImportant

        // movi LIST
        emitFourCC(avi, "LIST");
        emitLE32(avi, 4 + moviRaw.length);
        emitFourCC(avi, "movi");
        avi.write(moviRaw);

        // idx1 (legacy AVI index for seeking)
        emitFourCC(avi, "idx1");
        emitLE32(avi, idx1Payload);
        for (int i = 0; i < numFrames; i++)
        {
            emitFourCC(avi, "00dc");
            emitLE32(avi, 0x10);            // AVIIF_KEYFRAME
            emitLE32(avi, offsets[i]);
            emitLE32(avi, jpegs.get(i).length);
        }

        return avi.toByteArray();
    }

    // Scans JPEG SOF markers to find the image dimensions
    private static int[] parseJpegSize(byte[] jpeg)
    {
        for (int i = 0; i < jpeg.length - 9; i++)
        {
            if ((jpeg[i] & 0xFF) == 0xFF)
            {
                int mk = jpeg[i + 1] & 0xFF;
                if (mk == 0xC0 || mk == 0xC1 || mk == 0xC2)
                {
                    int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                    int w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                    if (w > 0 && h > 0)
                    {
                        return new int[]{w, h};
                    }
                }
            }
        }
        return new int[]{1280, 720};
    }

    // ------------------------------------------------------------------
    //  Binary helpers
    // ------------------------------------------------------------------

    private static void emitFourCC(ByteArrayOutputStream s, String tag)
    {
        byte[] b = tag.getBytes(StandardCharsets.US_ASCII);
        s.write(b, 0, 4);
    }

    private static void emitLE32(ByteArrayOutputStream s, int v)
    {
        s.write(v & 0xFF);
        s.write((v >>> 8) & 0xFF);
        s.write((v >>> 16) & 0xFF);
        s.write((v >>> 24) & 0xFF);
    }

    private static void emitLE16(ByteArrayOutputStream s, int v)
    {
        s.write(v & 0xFF);
        s.write((v >>> 8) & 0xFF);
    }

    // ------------------------------------------------------------------
    //  Image utilities
    // ------------------------------------------------------------------

    private String bufferedImageToPngBase64(BufferedImage bimg) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(bimg, "PNG", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    // ------------------------------------------------------------------
    //  Public callback interface
    // ------------------------------------------------------------------

    public interface VideoCallback
    {
        void onComplete(String screenshotBase64, String videoKey);
    }
}
