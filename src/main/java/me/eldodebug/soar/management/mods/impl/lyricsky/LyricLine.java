package me.eldodebug.soar.management.mods.impl.lyricsky;

/**
 * Una riga di testo sincronizzata. La larghezza viene calcolata una sola volta
 * (lazy) dal thread di rendering e poi riusata ad ogni frame.
 */
public final class LyricLine {

	public final long timeMs;
	public final String text;
	public int width = -1;

	public LyricLine(long timeMs, String text) {
		this.timeMs = timeMs;
		this.text = text;
	}
}
