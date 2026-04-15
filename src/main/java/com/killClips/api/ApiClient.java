package com.killClips.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Singleton
public class ApiClient
{
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d");
    private static final Path CLIPS_JSON = RuneLite.RUNELITE_DIR.toPath().resolve("videos").resolve("clips.json");

    private final Gson gson;
    private final StreamableUploader streamable;

    @Inject
    public ApiClient(Gson gson, StreamableUploader streamable)
    {
        this.gson = gson;
        this.streamable = streamable;
    }

    public void sendEventToApi(String endpoint, String jsonBody, String label, byte[] videoBytes)
    {
        try
        {
            String kind = extractEventKind(endpoint);
            String ts = LocalDateTime.now().format(TS_FMT);
            String stem = kind + "_" + ts;

            JsonObject meta = toJsonObject(jsonBody);
            meta.addProperty("event_type", kind);
            meta.addProperty("saved_at", LocalDateTime.now().toString());

            String rawPlayer = meta.has("playername") ? meta.get("playername").getAsString() : "unknown";
            String player = sanitizePathSegment(rawPlayer);
            Path folder = RuneLite.RUNELITE_DIR.toPath().resolve("videos").resolve(player).resolve(kind);
            Files.createDirectories(folder);

            if (videoBytes != null && videoBytes.length > 0)
            {
                String ext = (videoBytes.length >= 8 && "ftyp".equals(new String(videoBytes, 4, 4, StandardCharsets.US_ASCII))) ? ".mp4" : ".avi";
                Path vidPath = folder.resolve(stem + ext);
                Files.write(vidPath, videoBytes);
                meta.addProperty("video_file", vidPath.toString());

                String clipDesc = buildClipDescription(meta);
                streamable.upload(vidPath, clipDesc);
            }

            appendToClipsJson(meta);
            log.debug("Persisted {} to clips.json", label);
        }
        catch (Exception ex)
        {
            log.error("Failed to persist {}", label, ex);
        }
    }

    // Keep only letters, digits, underscore, dash, and space — prevents any path-traversal
    // (.. or / or \) if a malformed player name ever reaches this code path.
    private static String sanitizePathSegment(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return "unknown";
        }
        String cleaned = raw.replaceAll("[^a-zA-Z0-9 _-]", "_").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned))
        {
            return "unknown";
        }
        if (cleaned.length() > 32)
        {
            cleaned = cleaned.substring(0, 32);
        }
        return cleaned;
    }

    private synchronized void appendToClipsJson(JsonObject entry)
    {
        try
        {
            Files.createDirectories(CLIPS_JSON.getParent());

            JsonArray clips;
            if (Files.exists(CLIPS_JSON))
            {
                String existing = Files.readString(CLIPS_JSON, StandardCharsets.UTF_8);
                JsonElement parsed = gson.fromJson(existing, JsonElement.class);
                clips = (parsed != null && parsed.isJsonArray()) ? parsed.getAsJsonArray() : new JsonArray();
            }
            else
            {
                clips = new JsonArray();
            }

            clips.add(entry);
            Files.writeString(CLIPS_JSON, gson.toJson(clips), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            log.error("Failed to update clips.json", ex);
        }
    }

    private JsonObject toJsonObject(String raw)
    {
        try
        {
            JsonElement el = gson.fromJson(raw, JsonElement.class);
            if (el != null && el.isJsonObject())
            {
                return el.getAsJsonObject();
            }
        }
        catch (Exception ignored) {}

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("raw_payload", raw != null ? raw : "");
        return wrapper;
    }

    private String buildClipDescription(JsonObject meta)
    {
        String kind = meta.has("event_type") ? meta.get("event_type").getAsString() : "clip";
        String player = meta.has("playername") ? meta.get("playername").getAsString() : "unknown";
        String date = LocalDateTime.now().format(DISPLAY_DATE);

        switch (kind)
        {
            case "death":
                return player + " - death - " + date;
            case "kills":
                String victim = meta.has("killed_player") ? meta.get("killed_player").getAsString() : "someone";
                return victim + " - kill - " + date;
            default:
                return kind + " - " + date;
        }
    }

    private String extractEventKind(String ep)
    {
        if (ep == null || ep.isEmpty())
        {
            return "event";
        }
        int slash = ep.lastIndexOf('/');
        String segment = (slash >= 0) ? ep.substring(slash + 1) : ep;
        segment = segment.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        return segment.isEmpty() ? "event" : segment;
    }
}
