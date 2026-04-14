package com.killClips.video;

import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Singleton
public class H264Encoder
{
    @Inject
    public H264Encoder()
    {
    }

    /**
     * Encodes JPEG frames to an H.264 MP4 using jcodec (pure Java, no native code).
     *
     * @param jpegs ordered list of JPEG-encoded frame bytes
     * @param fps   target frame rate
     * @return raw MP4 bytes, or null if encoding failed
     */
    public byte[] encode(List<byte[]> jpegs, int fps)
    {
        if (jpegs == null || jpegs.isEmpty())
        {
            return null;
        }

        Path tempMp4 = null;
        try
        {
            tempMp4 = Files.createTempFile("killclips_", ".mp4");

            log.info("jcodec encoding: {} frames @ {} fps", jpegs.size(), fps);
            long startMs = System.currentTimeMillis();

            try (SeekableByteChannel channel = NIOUtils.writableChannel(tempMp4.toFile()))
            {
                AWTSequenceEncoder encoder = new AWTSequenceEncoder(channel, Rational.R(fps, 1));

                for (int i = 0; i < jpegs.size(); i++)
                {
                    BufferedImage frame = ImageIO.read(new ByteArrayInputStream(jpegs.get(i)));
                    if (frame == null)
                    {
                        log.warn("Skipping unreadable frame {}", i);
                        continue;
                    }

                    // jcodec requires even dimensions — crop 1 pixel if odd
                    if (frame.getWidth() % 2 != 0 || frame.getHeight() % 2 != 0)
                    {
                        int w = frame.getWidth() & ~1;
                        int h = frame.getHeight() & ~1;
                        frame = frame.getSubimage(0, 0, w, h);
                    }

                    encoder.encodeImage(frame);
                }

                encoder.finish();
            }

            byte[] mp4Bytes = Files.readAllBytes(tempMp4);
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (mp4Bytes.length == 0)
            {
                log.error("jcodec produced empty output");
                return null;
            }

            log.info("jcodec complete: {} frames -> {} bytes in {}ms", jpegs.size(), mp4Bytes.length, elapsedMs);
            return mp4Bytes;
        }
        catch (Exception e)
        {
            log.error("jcodec encoding failed", e);
            return null;
        }
        finally
        {
            if (tempMp4 != null)
            {
                try { Files.deleteIfExists(tempMp4); }
                catch (IOException ignored) {}
            }
        }
    }
}
