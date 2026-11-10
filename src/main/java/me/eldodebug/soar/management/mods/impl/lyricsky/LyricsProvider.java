package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.eldodebug.soar.utils.Multithreading;

/**
 * Scarica le lyrics sincronizzate da LRCLIB (gratuito, nessuna chiave).
 * Tutto avviene fuori dal thread di rendering; i risultati vanno in una cache LRU in memoria.
 */
public final class LyricsProvider {

	public interface Callback {
		void onResult(List<LyricLine> lines);
	}

	private static final String API = "https://lrclib.net/api";
	private static final List<LyricLine> NONE = Collections.emptyList();

	private static final Map<String, List<LyricLine>> CACHE = Collections.synchronizedMap(
			new LinkedHashMap<String, List<LyricLine>>(32, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<LyricLine>> eldest) {
					return size() > 40;
				}
			});

	private LyricsProvider() {
	}

	public static void fetch(final String artist, final String title, final long durationMs, final Callback callback) {

		final String key = (artist + '\u0000' + title).toLowerCase(Locale.ROOT);
		List<LyricLine> hit = CACHE.get(key);

		if (hit != null) {
			callback.onResult(hit);
			return;
		}

		Multithreading.POOL.execute(new Runnable() {
			@Override
			public void run() {

				List<LyricLine> result;

				try {
					result = query(artist, title, durationMs);
					CACHE.put(key, result);
				} catch (Exception e) {
					// errore di rete: niente cache, cosi' riprova al prossimo cambio canzone
					result = NONE;
				}

				callback.onResult(result);
			}
		});
	}

	private static List<LyricLine> query(String artist, String title, long durationMs) throws IOException {

		if (durationMs > 0) {

			List<LyricLine> exact = fromObject(get(API + "/get?artist_name=" + enc(artist) + "&track_name=" + enc(title)
					+ "&duration=" + Math.round(durationMs / 1000.0)));

			if (exact != null) {
				return exact;
			}
		}

		String mainArtist = artist.split("[,;&]")[0].trim();
		List<LyricLine> found = fromSearch(get(API + "/search?track_name=" + enc(title) + "&artist_name=" + enc(mainArtist)), durationMs);

		if (found != null) {
			return found;
		}

		String cleaned = title.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "").replaceAll("\\s+-\\s+.*$", "").trim();

		if (!cleaned.isEmpty() && !cleaned.equals(title)) {

			found = fromSearch(get(API + "/search?track_name=" + enc(cleaned) + "&artist_name=" + enc(mainArtist)), durationMs);

			if (found != null) {
				return found;
			}
		}

		return NONE;
	}

	private static List<LyricLine> fromObject(String body) {

		if (body == null) {
			return null;
		}

		JsonElement root = new JsonParser().parse(body);
		return root.isJsonObject() ? synced(root.getAsJsonObject()) : null;
	}

	private static List<LyricLine> fromSearch(String body, long durationMs) {

		if (body == null) {
			return null;
		}

		JsonElement root = new JsonParser().parse(body);

		if (!root.isJsonArray()) {
			return null;
		}

		JsonArray array = root.getAsJsonArray();
		JsonObject best = null;
		double bestDiff = Double.MAX_VALUE;

		for (JsonElement e : array) {

			if (!e.isJsonObject() || synced(e.getAsJsonObject()) == null) {
				continue;
			}

			JsonObject o = e.getAsJsonObject();
			double diff = 0;

			if (durationMs > 0 && o.has("duration") && !o.get("duration").isJsonNull()) {
				diff = Math.abs(o.get("duration").getAsDouble() * 1000.0 - durationMs);
			}

			if (diff < bestDiff) {
				bestDiff = diff;
				best = o;
			}
		}

		return best == null ? null : synced(best);
	}

	private static List<LyricLine> synced(JsonObject o) {

		if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) {
			return null;
		}

		List<LyricLine> lines = LrcParser.parse(o.get("syncedLyrics").getAsString());
		return lines.isEmpty() ? null : lines;
	}

	private static String get(String url) throws IOException {

		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(5000);
		c.setReadTimeout(6000);
		c.setRequestProperty("User-Agent", "GlideClient LyricsSky (https://github.com/GlideClient/client)");

		try {

			if (c.getResponseCode() != 200) {
				return null;
			}

			InputStream in = c.getInputStream();
			ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
			byte[] buf = new byte[8192];
			int r;

			while ((r = in.read(buf)) != -1) {
				out.write(buf, 0, r);
			}

			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} finally {
			c.disconnect();
		}
	}

	private static String enc(String s) throws IOException {
		return URLEncoder.encode(s, "UTF-8");
	}
}
