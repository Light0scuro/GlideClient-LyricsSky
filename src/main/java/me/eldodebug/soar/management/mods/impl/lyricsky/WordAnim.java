package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.util.List;

/**
 * Stato di animazione di una parola (parola per parola / riga intera). Senza dipendenze da Minecraft,
 * quindi testabile. Il risultato va letto dai campi pubblici dopo compute().
 */
public final class WordAnim {

	/** Quanto la parola e' "in evidenza" (0..1): sale quando viene cantata e poi torna a 0. */
	public float emph;

	/** Mescolanza tra colore "non ancora cantata" (0) e colore finale (1). */
	public float mix;

	/** Visibilita' (0..1): in modalita' parola per parola le parole non cantate sono nascoste. */
	public float vis;

	public static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	/** Durata "cantata" di una riga: fino alla successiva, al massimo 6 secondi. */
	public static long spanOf(List<LyricLine> ls, int i) {

		long start = ls.get(i).timeMs;
		long next = i + 1 < ls.size() ? ls.get(i + 1).timeMs : start + 4000L;
		return Math.max(300L, Math.min(next - start, 6000L));
	}

	/** Indice della parola in corso: -1 prima dell'inizio, numero di parole se la riga e' finita. */
	public static int currentWord(LyricLine l, double prog) {

		if (prog < 0) {
			return -1;
		}

		for (int k = 0; k < l.words.length; k++) {
			if (prog <= l.endFrac[k]) {
				return k;
			}
		}

		return l.words.length;
	}

	public void compute(LyricLine l, int k, double t, long start, double span, int curWord, boolean reveal) {

		float ws = k == 0 ? 0f : l.endFrac[k - 1], we = l.endFrac[k];
		double tWs = start + ws * span, tWe = start + we * span;
		float appear = clamp01((float) ((t - tWs) / 160.0));

		if (k < curWord) {
			emph = 1f - clamp01((float) ((t - tWe) / 220.0));
			mix = 1f;
			vis = reveal ? appear : 1f;
		} else if (k == curWord) {
			emph = clamp01((float) ((t - tWs) / 120.0));
			mix = emph;
			vis = reveal ? appear : 1f;
		} else {
			emph = 0f;
			mix = 0f;
			vis = reveal ? 0f : 1f;
		}
	}
}
