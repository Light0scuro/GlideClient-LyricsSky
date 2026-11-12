package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

	/**
	 * Cerca le lyrics, in quest'ordine: file .lrc locali, cache, LRCLIB e (se attivo) NetEase.
	 * Il callback arriva da un thread di lavoro.
	 */
	public static void fetch(final String artist, final String title, final long durationMs, final File localDir,
			final boolean useNetease, final Callback callback) {

		final String key = (artist + '\u0000' + title).toLowerCase(Locale.ROOT);

		Multithreading.POOL.execute(new Runnable() {
			@Override
			public void run() {

				List<LyricLine> result = NONE;

				try {

					List<LyricLine> local = localLyrics(localDir, artist, title);

					if (local != null) {
						callback.onResult(local);
						return;
					}

					List<LyricLine> hit = CACHE.get(key);

					if (hit != null) {
						callback.onResult(hit);
						return;
					}

					result = query(artist, title, durationMs, useNetease);

					if (!result.isEmpty()) {
						CACHE.put(key, result);
					}
				} catch (Exception e) {
					// errore di rete: niente cache, cosi' riprova al prossimo cambio canzone
					result = NONE;
				}

				callback.onResult(result);
			}
		});
	}

	// ------------------------------------------------------------------ file locali

	/** Cerca "Artista - Titolo.lrc" o "Titolo.lrc" nella cartella indicata. Ritorna null se non c'e'. */
	public static List<LyricLine> localLyrics(File dir, String artist, String title) {

		if (dir == null || !dir.isDirectory()) {
			return null;
		}

		String[] names = { safeName(artist + " - " + title), safeName(title) };

		for (String n : names) {

			if (n.isEmpty()) {
				continue;
			}

			File f = new File(dir, n + ".lrc");

			if (f.isFile()) {

				try {
					List<LyricLine> lines = LrcParser.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));

					if (!lines.isEmpty()) {
						return lines;
					}
				} catch (IOException ignored) {
				}
			}
		}

		return null;
	}

	static String safeName(String s) {
		return s.replaceAll("[\\\\/:*?\"<>|]", "").trim();
	}

	/** Prova le fonti online in ordine; un errore o una risposta strana di una fonte non blocca le altre. */
	private static List<LyricLine> query(String artist, String title, long durationMs, boolean useNetease) {

		try {

			List<LyricLine> r = queryLrclib(artist, title, durationMs);

			if (r != null && !r.isEmpty()) {
				return r;
			}
		} catch (IOException | RuntimeException e) {
			// passa alla fonte successiva
		}

		if (useNetease) {

			try {

				List<LyricLine> n = netease(artist, title, durationMs);

				if (n != null && !n.isEmpty()) {
					return n;
				}
			} catch (IOException | RuntimeException e) {
				// nessuna fonte ha risposto
			}
		}

		return NONE;
	}

	private static List<LyricLine> queryLrclib(String artist, String title, long durationMs) throws IOException {

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

	// ------------------------------------------------------------------ NetEase (fonte di riserva)

	private static final String NETEASE = "https://music.163.com";

	/** Endpoint pubblici e non documentati di NetEase Cloud Music: senza chiave, ma possono cambiare. */
	private static List<LyricLine> netease(String artist, String title, long durationMs) throws IOException {

		String mainArtist = artist.split("[,;&]")[0].trim();
		String search = get(NETEASE + "/api/search/get?s=" + enc(title + " " + mainArtist) + "&type=1&limit=8&offset=0", NETEASE + "/");
		long id = pickNeteaseId(search, title, artist, durationMs);

		if (id < 0) {
			return null;
		}

		return neteaseLyrics(get(NETEASE + "/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1", NETEASE + "/"));
	}

	/** Sceglie il risultato giusto: stessa durata (se nota) e titolo/artista simili. Ritorna -1 se nessuno va bene. */
	static long pickNeteaseId(String body, String title, String artist, long durationMs) {

		if (body == null) {
			return -1;
		}

		JsonElement root = new JsonParser().parse(body);

		if (!root.isJsonObject() || !root.getAsJsonObject().has("result") || !root.getAsJsonObject().get("result").isJsonObject()) {
			return -1;
		}

		JsonObject result = root.getAsJsonObject().getAsJsonObject("result");

		if (!result.has("songs") || !result.get("songs").isJsonArray()) {
			return -1;
		}

		long bestId = -1;
		double bestDiff = Double.MAX_VALUE;

		for (JsonElement e : result.getAsJsonArray("songs")) {

			if (!e.isJsonObject()) {
				continue;
			}

			JsonObject o = e.getAsJsonObject();

			if (!o.has("id") || !o.has("name")) {
				continue;
			}

			boolean titleOk = similar(o.get("name").getAsString(), title);
			boolean artistOk = false;

			if (o.has("artists") && o.get("artists").isJsonArray()) {

				for (JsonElement a : o.getAsJsonArray("artists")) {

					if (a.isJsonObject() && a.getAsJsonObject().has("name") && similar(a.getAsJsonObject().get("name").getAsString(), artist.split("[,;&]")[0])) {
						artistOk = true;
					}
				}
			}

			double dur = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble()
					: (o.has("dt") && !o.get("dt").isJsonNull() ? o.get("dt").getAsDouble() : 0);
			double diff = 0;

			if (durationMs > 0 && dur > 0) {

				diff = Math.abs(dur - durationMs);

				if (diff > 4500 || !(titleOk || artistOk)) {
					continue;
				}
			} else if (!(titleOk && artistOk)) {
				continue;
			}

			if (diff < bestDiff) {
				bestDiff = diff;
				bestId = o.get("id").getAsLong();
			}
		}

		return bestId;
	}

	static List<LyricLine> neteaseLyrics(String body) {

		if (body == null) {
			return null;
		}

		JsonElement root = new JsonParser().parse(body);

		if (!root.isJsonObject() || !root.getAsJsonObject().has("lrc") || !root.getAsJsonObject().get("lrc").isJsonObject()) {
			return null;
		}

		JsonObject lrc = root.getAsJsonObject().getAsJsonObject("lrc");

		if (!lrc.has("lyric") || lrc.get("lyric").isJsonNull()) {
			return null;
		}

		List<LyricLine> parsed = LrcParser.parse(lrc.get("lyric").getAsString());
		List<LyricLine> out = new ArrayList<LyricLine>(parsed.size());

		// le prime righe sono spesso i crediti (autore, compositore...): non sono testo cantato
		for (LyricLine l : parsed) {

			if (!l.text.matches("(?i)^(\\u4F5C\\u8BCD|\\u4F5C\\u66F2|\\u7F16\\u66F2|\\u5236\\u4F5C\\u4EBA|\\u4F5C\\u8A5E|\\u7DE8\\u66F2|\\u8BCD|\\u66F2|lyrics|composer|composed|written|arranged|producer)\\s*(by)?\\s*[:\\uFF1A].*")) {
				out.add(l);
			}
		}

		return out.isEmpty() ? null : out;
	}

	private static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	private static boolean similar(String a, String b) {

		String na = norm(a), nb = norm(b);
		return !na.isEmpty() && !nb.isEmpty() && (na.contains(nb) || nb.contains(na));
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
		return get(url, null);
	}

	private static String get(String url, String referer) throws IOException {

		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(5000);
		c.setReadTimeout(6000);
		c.setRequestProperty("User-Agent", referer == null ? "GlideClient LyricsSky (https://github.com/GlideClient/client)" : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");

		if (referer != null) {
			c.setRequestProperty("Referer", referer);
		}

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
