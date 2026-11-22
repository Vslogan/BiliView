package local.bilimp4.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/**
 * Persists media-probe results so a cache entry is only analyzed once. Entries are keyed
 * by the cache identity plus the track file sizes; a secondary MD5 index lets a moved or
 * re-imported copy of the same content reuse the stored result without re-parsing.
 */
final class ProbeStore {
    private final File file;
    private final JSONObject items;

    ProbeStore(File directory) {
        file = new File(directory, "cache-probe.json");
        items = load(file);
    }

    static String key(CacheEntry entry) {
        CacheEntry.Meta meta = entry.meta();
        return entry.key() + "|" + (meta == null ? 0 : meta.videoBytes()) + "|"
                + (meta == null ? 0 : meta.audioBytes());
    }

    synchronized MediaProbe.Info get(String key) {
        JSONObject item = items.optJSONObject(key);
        return item == null ? null : read(item);
    }

    /** The probe result of any stored entry whose video file has this MD5, or null. */
    synchronized MediaProbe.Info byMd5(String md5) {
        if (md5 == null) return null;
        Iterator<String> keys = items.keys();
        while (keys.hasNext()) {
            JSONObject item = items.optJSONObject(keys.next());
            if (item != null && md5.equals(item.optString("md5", null))) return read(item);
        }
        return null;
    }

    synchronized void put(String key, MediaProbe.Info info) {
        try {
            items.put(key, write(info));
        } catch (JSONException ignored) {
            return;
        }
        save();
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("items", items);
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(temp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(file)) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (JSONException | IOException ignored) {
            // A lost probe cache only means the next scan re-analyzes.
        }
    }

    private static JSONObject load(File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONObject items = root.optJSONObject("items");
            if (items != null) return items;
        } catch (IOException | JSONException ignored) {
            // Missing or corrupt caches start empty.
        }
        return new JSONObject();
    }

    private static JSONObject write(MediaProbe.Info info) throws JSONException {
        JSONObject item = new JSONObject();
        if (info.video() != null) {
            MediaProbe.Track video = info.video();
            JSONObject v = new JSONObject();
            v.put("codec", video.codec());
            v.put("width", video.width());
            v.put("height", video.height());
            v.put("fps", video.fps());
            v.put("bytes", video.bytes());
            v.put("samples", video.samples());
            v.put("durationMs", video.durationMs());
            item.put("video", v);
        }
        if (info.audio() != null) {
            MediaProbe.Track audio = info.audio();
            JSONObject a = new JSONObject();
            a.put("codec", audio.codec());
            a.put("sampleRate", audio.sampleRate());
            a.put("channels", audio.channels());
            a.put("bytes", audio.bytes());
            a.put("samples", audio.samples());
            a.put("durationMs", audio.durationMs());
            item.put("audio", a);
        }
        if (info.md5() != null) item.put("md5", info.md5());
        return item;
    }

    private static MediaProbe.Info read(JSONObject item) {
        MediaProbe.Track video = null;
        MediaProbe.Track audio = null;
        JSONObject v = item.optJSONObject("video");
        if (v != null) {
            video = new MediaProbe.Track(v.optString("codec"), v.optInt("width"), v.optInt("height"),
                    (float) v.optDouble("fps"), 0, 0, v.optLong("bytes"), v.optLong("samples"),
                    v.optLong("durationMs"));
        }
        JSONObject a = item.optJSONObject("audio");
        if (a != null) {
            audio = new MediaProbe.Track(a.optString("codec"), 0, 0, 0, a.optInt("sampleRate"),
                    a.optInt("channels"), a.optLong("bytes"), a.optLong("samples"),
                    a.optLong("durationMs"));
        }
        String md5 = item.optString("md5", "");
        return new MediaProbe.Info(video, audio, md5.isEmpty() ? null : md5);
    }
}
