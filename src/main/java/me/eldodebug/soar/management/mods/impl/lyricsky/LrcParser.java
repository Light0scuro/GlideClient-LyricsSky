package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Parser minimale del formato LRC ("[mm:ss.xx] testo", anche con piu' timestamp per riga). */
public final class LrcParser {

	private LrcParser() {
	}

	public static List<LyricLine> parse(String lrc) {

		List<LyricLine> out = new ArrayList<LyricLine>();

		if (lrc == null || lrc.isEmpty()) {
			return out;
		}

		for (String raw : lrc.split("\\r?\\n")) {

			List<Long> times = new ArrayList<Long>(2);
			int i = 0;

			while (i < raw.length() && raw.charAt(i) == '[') {

				int end = raw.indexOf(']', i);

				if (end < 0) {
					break;
				}

				long t = parseTimestamp(raw.substring(i + 1, end));

				if (t < 0) {
					break;
				}

				times.add(t);
				i = end + 1;
			}

			if (times.isEmpty()) {
				continue;
			}

			String text = raw.substring(i).trim();

			if (text.isEmpty()) {
				text = "\u266A";
			}

			for (long t : times) {
				out.add(new LyricLine(t, text));
			}
		}

		Collections.sort(out, new Comparator<LyricLine>() {
			@Override
			public int compare(LyricLine a, LyricLine b) {
				return a.timeMs < b.timeMs ? -1 : (a.timeMs == b.timeMs ? 0 : 1);
			}
		});

		return out;
	}

	private static long parseTimestamp(String s) {

		int colon = s.indexOf(':');

		if (colon <= 0) {
			return -1;
		}

		try {
			long minutes = Long.parseLong(s.substring(0, colon));
			double seconds = Double.parseDouble(s.substring(colon + 1));
			return (long) (minutes * 60000L + seconds * 1000.0);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
