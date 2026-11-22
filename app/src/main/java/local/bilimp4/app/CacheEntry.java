package local.bilimp4.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

record CacheEntry(String key, String title, String location, ExportTask.Source video, ExportTask.Source audio,
                  long aid, long cid, ExportTask.Source xml, ExportTask.Source protobuf, Meta meta) {
    static final String XML = "danmaku.xml";
    static final String PROTOBUF = "danmaku.pb";

    /** What the cache metadata (entry.json) and the folder scan know without opening the media. */
    record Meta(String quality, long durationMs, int width, int height, long videoBytes, long audioBytes) {
        static final Meta EMPTY = new Meta(null, 0, 0, 0, 0, 0);

        boolean empty() {
            return quality == null && durationMs == 0 && width == 0 && videoBytes == 0 && audioBytes == 0;
        }
    }

    boolean hasDanmakuSource() {
        return xml != null || protobuf != null || cid > 0;
    }

    static long cid(JSONObject metadata) {
        JSONObject page = metadata.optJSONObject("page_data");
        if (page == null) page = metadata.optJSONObject("ep");
        return page == null ? metadata.optLong("cid") : page.optLong("cid", metadata.optLong("cid"));
    }

    static long aid(JSONObject metadata) {
        JSONObject episode = metadata.optJSONObject("ep");
        long fallback = metadata.optLong("aid", metadata.optLong("avid"));
        return episode == null ? fallback : episode.optLong("aid", fallback);
    }

    /** The quick facts from entry.json; framerate and bitrate are only available from probing. */
    static Meta meta(JSONObject metadata, long videoBytes, long audioBytes) {
        if (metadata.length() == 0 && videoBytes == 0 && audioBytes == 0) return Meta.EMPTY;
        String quality = metadata.optString("quality_pithy_description", "").trim();
        long durationMs = metadata.optLong("total_time_milli", 0);
        JSONObject page = metadata.optJSONObject("ep");
        if (page == null) page = metadata.optJSONObject("page_data");
        int width = page == null ? 0 : page.optInt("width", 0);
        int height = page == null ? 0 : page.optInt("height", 0);
        return new Meta(quality.isEmpty() ? null : quality, durationMs, width, height,
                videoBytes, audioBytes);
    }

    static JSONObject readMetadata(InputStream input) {
        if (input == null) return new JSONObject();
        try (InputStream stream = input; ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (data.size() + count > 256 * 1024) return new JSONObject();
                data.write(buffer, 0, count);
            }
            return new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | JSONException ignored) {
            return new JSONObject();
        }
    }

    static String title(JSONObject metadata, String fallback) {
        String title = metadata.optString("title", "").trim();
        if (title.isEmpty()) return fallback;
        JSONObject page = metadata.optJSONObject("page_data");
        if (page != null && page.optInt("page") > 1) {
            title += " - P" + page.optInt("page") + " " + page.optString("part");
        }
        return title.trim();
    }
}
