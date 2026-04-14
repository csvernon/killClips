package com.killClips.video;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

// Double-buffered PBO readback that avoids stalling the render thread.
// On each rendered frame we issue an async glReadPixels into the "active" PBO
// while mapping the "previous" PBO whose DMA transfer has already completed.
@Slf4j
public class AsyncFrameCapture
{
    private final DrawManager drawMgr;
    private final VideoRecorder recorder;
    private final Runnable gpuFallback;

    // Two PBOs for ping-pong
    private final int[] bufferIds = new int[2];
    private int activeSlot = 0;

    // Viewport dimensions of the last initialised PBO pair
    private int knownW = 0;
    private int knownH = 0;

    private boolean buffersReady = false;
    private boolean initialFrame = true;
    private volatile boolean glMissing = false;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private Runnable frameHook;

    // Fixed-rate schedule tracking to avoid FPS drift
    private long nextAllowedNs = 0;

    public AsyncFrameCapture(DrawManager drawMgr, VideoRecorder recorder, Runnable gpuFallback)
    {
        this.drawMgr = drawMgr;
        this.recorder = recorder;
        this.gpuFallback = gpuFallback;
    }

    public void start()
    {
        if (frameHook != null)
        {
            return;
        }

        stopping.set(false);
        glMissing = false;
        initialFrame = true;
        activeSlot = 0;
        nextAllowedNs = 0;

        frameHook = this::tick;
        drawMgr.registerEveryFrameListener(frameHook);
        log.debug("PBO double-buffer capture started");
    }

    public void stop()
    {
        stopping.set(true);

        if (frameHook != null)
        {
            drawMgr.unregisterEveryFrameListener(frameHook);
            frameHook = null;
        }

        // Tear down GPU resources on the GL thread
        if (buffersReady)
        {
            drawMgr.requestNextFrameListener(img -> destroyBuffers());
        }

        log.debug("PBO capture halted");
    }

    public boolean isPboUnsupported()
    {
        return glMissing;
    }

    // Runs on the GL thread every frame
    private void tick()
    {
        if (stopping.get() || !recorder.isCurrentlyRecording() || glMissing)
        {
            return;
        }

        // Rate-limit to the configured capture FPS using fixed-rate advancement
        int targetFps = recorder.getCaptureFps();
        if (targetFps <= 0)
        {
            return;
        }

        long nowNs = System.nanoTime();
        long intervalNs = 1_000_000_000L / targetFps;
        if (nextAllowedNs == 0)
        {
            nextAllowedNs = nowNs;
        }
        if (nowNs < nextAllowedNs)
        {
            return;
        }
        nextAllowedNs += intervalNs;
        // Catch up if we fell behind by more than one interval
        if (nowNs - nextAllowedNs > intervalNs)
        {
            nextAllowedNs = nowNs + intervalNs;
        }

        if (!recorder.canAcceptFrame())
        {
            return;
        }

        try
        {
            // Verify GL availability
            GLCapabilities glCaps;
            try
            {
                glCaps = GL.getCapabilities();
            }
            catch (IllegalStateException ex)
            {
                glMissing = true;
                log.debug("GL context unavailable -- triggering fallback");
                gpuFallback.run();
                return;
            }
            if (glCaps == null || !glCaps.OpenGL21)
            {
                glMissing = true;
                log.debug("OpenGL 2.1 not present -- triggering fallback");
                gpuFallback.run();
                return;
            }

            // Read current viewport size
            int vpW, vpH;
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                IntBuffer vp = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
                vpW = vp.get(2);
                vpH = vp.get(3);
            }
            if (vpW <= 0 || vpH <= 0)
            {
                return;
            }

            // (Re-)allocate PBOs when viewport changes
            if (!buffersReady || vpW != knownW || vpH != knownH)
            {
                if (buffersReady)
                {
                    destroyBuffers();
                }
                allocateBuffers(vpW, vpH);
                initialFrame = true;
            }

            int prev = 1 - activeSlot;
            int cur = activeSlot;

            // Map the previous PBO (DMA already finished) and hand off pixels
            if (!initialFrame)
            {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, bufferIds[prev]);
                ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                if (mapped != null)
                {
                    byte[] copy = new byte[knownW * knownH * 4];
                    mapped.rewind();
                    mapped.get(copy);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    recorder.submitCapturedFrame(ByteBuffer.wrap(copy), knownW, knownH);
                }
                else
                {
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
            }

            // Kick off async readback into the current PBO
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, bufferIds[cur]);
            GL11.glReadBuffer(GL11.GL_FRONT);
            GL11.glReadPixels(0, 0, vpW, vpH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

            activeSlot = prev;
            initialFrame = false;
        }
        catch (Exception ex)
        {
            log.error("PBO capture tick failed", ex);
        }
    }

    private void allocateBuffers(int w, int h)
    {
        int bytes = w * h * 4;
        GL15.glGenBuffers(bufferIds);
        for (int i = 0; i < 2; i++)
        {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, bufferIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        knownW = w;
        knownH = h;
        buffersReady = true;
        log.debug("PBO pair allocated for {}x{}", w, h);
    }

    private void destroyBuffers()
    {
        if (!buffersReady)
        {
            return;
        }
        GL15.glDeleteBuffers(bufferIds);
        bufferIds[0] = 0;
        bufferIds[1] = 0;
        buffersReady = false;
        knownW = 0;
        knownH = 0;
    }
}
