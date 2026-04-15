package com.killClips.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.killClips.KillClipsConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
@Singleton
public class StreamableUploader
{
    private static final String API_ENDPOINT = "https://api.streamable.com/upload";
    private static final MediaType BINARY = MediaType.parse("application/octet-stream");

    private final OkHttpClient http;
    private final KillClipsConfig cfg;
    private final Gson gson;

    // Listener called on successful upload: (description, url)
    private volatile BiConsumer<String, String> onClipUploaded;

    @Inject
    public StreamableUploader(OkHttpClient http, KillClipsConfig cfg, Gson gson)
    {
        // Override the shared RuneLite client's timeouts so uploads never hang forever.
        this.http = http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build();
        this.cfg = cfg;
        this.gson = gson;
    }

    public void setOnClipUploaded(BiConsumer<String, String> listener)
    {
        this.onClipUploaded = listener;
    }

    public void upload(Path clip, String description)
    {
        if (!cfg.streamableEnabled())
        {
            return;
        }

        String user = cfg.streamableEmail();
        String pass = cfg.streamablePassword();
        if (user == null || user.isEmpty() || pass == null || pass.isEmpty())
        {
            log.warn("Streamable credentials not configured -- skipping upload");
            return;
        }

        if (!Files.exists(clip))
        {
            log.warn("Video file missing, cannot upload: {}", clip);
            return;
        }

        try
        {
            String name = clip.getFileName().toString();
            // Stream the file from disk instead of loading it into memory
            RequestBody filePart = RequestBody.create(BINARY, clip.toFile());
            MultipartBody payload = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", name, filePart)
                .build();

            Request req = new Request.Builder()
                .url(API_ENDPOINT)
                .header("Authorization", Credentials.basic(user, pass))
                .post(payload)
                .build();

            log.info("Starting Streamable upload: {}", name);

            final String desc = (description != null) ? description : name;

            http.newCall(req).enqueue(new Callback()
            {
                @Override
                public void onFailure(Call call, IOException ex)
                {
                    log.error("Streamable upload error for {}", name, ex);
                }

                @Override
                public void onResponse(Call call, Response resp) throws IOException
                {
                    try (resp)
                    {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (!resp.isSuccessful())
                        {
                            log.error("Streamable returned HTTP {} -- {}", resp.code(), body);
                            return;
                        }

                        JsonObject obj = gson.fromJson(body, JsonObject.class);
                        if (obj != null && obj.has("shortcode"))
                        {
                            String code = obj.get("shortcode").getAsString();
                            String url = "https://streamable.com/" + code;
                            log.info("Streamable upload finished: {}", url);

                            BiConsumer<String, String> listener = onClipUploaded;
                            if (listener != null)
                            {
                                listener.accept(desc, url);
                            }
                        }
                        else
                        {
                            log.warn("Unexpected Streamable response: {}", body);
                        }
                    }
                }
            });
        }
        catch (Exception ex)
        {
            log.error("Could not prepare Streamable upload: {}", clip, ex);
        }
    }
}
