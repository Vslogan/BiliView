package local.bilimp4.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts Bilibili danmaku comments into an ASS (SubStation Alpha) subtitle script.
 * The same layout feeds both the standalone .ass export and the subtitle track embedded
 * in MKV files, so both look like the burned-in rendering: scrolling comments move right
 * to left, top/bottom comments stay fixed, and dense comments avoid each other by lane.
 */
final class DanmakuAss {
    private static final long SCROLL_BASE_MS = 8500;
    private static final long FIXED_MS = 3800;

    /** One laid-out subtitle line: time range, ASS style name and the override tags + text. */
    record Event(long startMs, long endMs, String style, String text) {}

    /** The ASS header (everything down to the Events format line) plus the laid-out events. */
    record Script(String header, List<Event> events) {}

    private record Lane(long timeMs, float width, boolean reverse) {}

    static Script create(List<Danmaku.Comment> comments, DanmakuStyle style, int width, int height) {
        if (width <= 0 || height <= 0) {
            width = 1280;
            height = 720;
        }
        float shrink = Math.min(1f, 720f / Math.min(width, height));
        int playX = Math.max(1, Math.round(width * shrink));
        int playY = Math.max(1, Math.round(height * shrink));
        float base = Math.max(10f, Math.min(playY * 0.043f, playX * 0.066f) * style.scale());
        float row = base * 1.58f;
        float start = playY * 0.026f;
        int available = (int) ((playY * style.area() - start) / row);
        int fixedRows = available >= 6 ? 2 : 1;
        int laneCount = Math.min(9, Math.max(1, available - fixedRows));
        long scrollMs = Math.max(2000, Math.round(SCROLL_BASE_MS / style.speed()));
        int alpha = 255 - Math.round(255f * Math.min(1f, Math.max(0f, style.opacity())));
        String alphaTag = alpha <= 0 ? ""
                : String.format(Locale.ROOT, "\\1a&H%02X&\\3a&H%02X&", alpha, alpha);

        Lane[] lanes = new Lane[laneCount];
        long[] top = new long[fixedRows];
        long[] bottom = new long[fixedRows];
        List<Event> events = new ArrayList<>();
        for (Danmaku.Comment comment : comments) {
            float font = base * comment.fontSize() / 25f;
            String text = clip(comment.text(), font, playX * 0.9f);
            if (text.isEmpty()) continue;
            float textWidth = measure(text, font);
            StringBuilder tags = new StringBuilder("{");
            String styleName;
            long endMs;
            int mode = comment.mode();
            if (mode == 4 || mode == 5) {
                long[] rows = mode == 4 ? bottom : top;
                int assigned = -1;
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i] <= comment.timeMs()) {
                        rows[i] = comment.timeMs() + FIXED_MS;
                        assigned = i;
                        break;
                    }
                }
                if (assigned < 0) continue;
                styleName = mode == 4 ? "Bottom" : "Top";
                if (mode == 5) {
                    tags.append("\\an8\\pos(").append(trim(playX / 2f)).append(',')
                            .append(trim(start + assigned * row + base * 0.4f)).append(')');
                } else {
                    tags.append("\\an2\\pos(").append(trim(playX / 2f)).append(',')
                            .append(trim(playY * 0.9f - assigned * row)).append(')');
                }
                endMs = comment.timeMs() + FIXED_MS;
            } else {
                boolean reverse = mode == 6;
                int assigned = -1;
                for (int i = 0; i < lanes.length; i++) {
                    if (fits(lanes[i], comment.timeMs(), textWidth, reverse, scrollMs, playX, row * 0.32f)) {
                        assigned = i;
                        break;
                    }
                }
                if (assigned < 0) continue;
                lanes[assigned] = new Lane(comment.timeMs(), textWidth, reverse);
                styleName = "Scroll";
                float y = start + (assigned + fixedRows) * row + base * 0.4f;
                float fromX = reverse ? -textWidth / 2 : playX + textWidth / 2;
                float toX = reverse ? playX + textWidth / 2 : -textWidth / 2;
                tags.append("\\an8\\move(").append(trim(fromX)).append(',').append(trim(y)).append(',')
                        .append(trim(toX)).append(',').append(trim(y)).append(')');
                endMs = comment.timeMs() + scrollMs;
            }
            if (style.originalColors() && comment.color() != 0xffffff) {
                tags.append(String.format(Locale.ROOT, "\\c&H%02X%02X%02X&",
                        comment.color() & 0xff, (comment.color() >> 8) & 0xff, (comment.color() >> 16) & 0xff));
            }
            tags.append(alphaTag);
            if (comment.fontSize() != 25) tags.append(String.format(Locale.ROOT, "\\fs%s", trim(font)));
            tags.append('}').append(escape(text));
            events.add(new Event(comment.timeMs(), endMs, styleName, tags.toString()));
        }
        return new Script(header(playX, playY, base), events);
    }

    /** The full standalone .ass document. */
    static String serialize(Script script) {
        StringBuilder out = new StringBuilder(script.header());
        for (Event event : script.events()) {
            out.append("Dialogue: 0,").append(time(event.startMs())).append(',').append(time(event.endMs()))
                    .append(',').append(event.style()).append(",,0,0,0,,").append(event.text()).append('\n');
        }
        return out.toString();
    }

    /** One Matroska subtitle block: the Dialogue line without its time fields. */
    static byte[] blockPayload(Event event, int readOrder) {
        return (readOrder + ",0," + event.style() + ",,0,0,0,," + event.text())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String header(int playX, int playY, float base) {
        String font = trim(base);
        String outline = trim(Math.max(1.5f, base * 0.13f));
        return "[Script Info]\n"
                + "Title: BiliView 弹幕字幕\n"
                + "ScriptType: v4.00+\n"
                + "PlayResX: " + playX + "\n"
                + "PlayResY: " + playY + "\n"
                + "WrapStyle: 0\n"
                + "ScaledBorderAndShadow: yes\n"
                + "\n[V4+ Styles]\n"
                + "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour,"
                + " BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle,"
                + " BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
                + style("Scroll", font, outline, 8)
                + style("Top", font, outline, 8)
                + style("Bottom", font, outline, 2)
                + "\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
    }

    private static String style(String name, String font, String outline, int alignment) {
        return "Style: " + name + ",Sans-serif," + font
                + ",&H00FFFFFF,&H00FFFFFF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,"
                + outline + ",0," + alignment + ",10,10,10,1\n";
    }

    private static boolean fits(Lane previous, long timeMs, float width, boolean reverse,
                                long scrollMs, int playX, float gap) {
        if (previous == null) return true;
        long elapsed = timeMs - previous.timeMs();
        if (elapsed >= scrollMs) return true;
        if (previous.reverse() != reverse) return false;
        float oldGap = (playX + previous.width()) * elapsed / scrollMs - previous.width();
        float newGap = playX - (playX + width) * (scrollMs - elapsed) / (float) scrollMs;
        return oldGap >= gap && newGap >= gap;
    }

    private static String clip(String text, float font, float maxWidth) {
        if (measure(text, font) <= maxWidth) return text;
        StringBuilder clipped = new StringBuilder(text);
        while (clipped.length() > 1 && measure(clipped, font) > maxWidth) {
            int end = clipped.length() - 1;
            if (Character.isLowSurrogate(clipped.charAt(end)) && end > 0) end--;
            clipped.delete(end, clipped.length());
        }
        return clipped.append('…').toString();
    }

    private static float measure(CharSequence text, float font) {
        float width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += font * (text.charAt(i) < 0x80 ? 0.55f : 1f);
        }
        return width;
    }

    private static String escape(String text) {
        return text.replace('{', '｛').replace('}', '｝');
    }

    private static String time(long ms) {
        long cs = ms / 10;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d",
                cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100);
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
