package me.eldodebug.soar.management.mods.impl.lyricsky;

/**
 * Una riga di testo sincronizzata. Tutto cio' che e' derivato (parole, tempi stimati delle parole,
 * layout con il font corrente) viene calcolato una sola volta e riusato ad ogni frame.
 */
public final class LyricLine {

	public final long timeMs;
	public final String text;

	/** Larghezza con il font vanilla (stile "Pannello"), calcolata lazy. */
	public int width = -1;

	/** Parole della riga. */
	public String[] words;

	/**
	 * Frazione (0..1) della durata cantata a cui finisce ogni parola. LRCLIB da' solo i tempi delle righe,
	 * quindi i tempi delle parole sono stimati in proporzione alla lunghezza di ciascuna.
	 */
	public float[] endFrac;

	// layout con il font grande, a capo quando supera il limite di larghezza; valido finche' l'epoca dei font
	// e il limite non cambiano
	public LyricFonts.Word[] bigWords;
	public float[] bigX;
	public int[] bigRow;
	public float[] rowW;
	public int rows;
	public float maxRowW;
	private int bigEpoch = -1;
	private float bigLimit = -1f;

	public LyricLine(long timeMs, String text) {
		this.timeMs = timeMs;
		this.text = text;
	}

	public void ensureWords() {

		if (words != null) {
			return;
		}

		String[] w = text.trim().split("\\s+");
		float[] weight = new float[w.length];
		float total = 0f;

		for (int i = 0; i < w.length; i++) {

			char last = w[i].charAt(w[i].length() - 1);
			weight[i] = w[i].length() + 1f + (",.;:!?".indexOf(last) >= 0 ? 2f : 0f);
			total += weight[i];
		}

		float[] end = new float[w.length];
		float acc = 0f;

		for (int i = 0; i < w.length; i++) {
			acc += weight[i];
			end[i] = acc / total;
		}

		end[w.length - 1] = 1f;
		words = w;
		endFrac = end;
	}

	/** Prepara parole e posizioni per il font grande, andando a capo oltre rowLimit pixel. Solo dal thread di rendering. */
	public void prepare(LyricFonts fonts, float rowLimit) {

		ensureWords();

		if (bigEpoch == fonts.epoch && bigLimit == rowLimit) {
			return;
		}

		int n = words.length;

		if (bigWords == null || bigWords.length != n) {
			bigWords = new LyricFonts.Word[n];
			bigX = new float[n];
			bigRow = new int[n];
			rowW = new float[n];
		}

		float space = fonts.space(LyricFonts.BIG);
		float x = 0f;
		int row = 0;
		maxRowW = 1f;

		for (int k = 0; k < n; k++) {

			LyricFonts.Word w = fonts.get(LyricFonts.BIG, words[k]);

			if (x > 0f && x + w.adv > rowLimit) {
				rowW[row] = x - space;
				maxRowW = Math.max(maxRowW, rowW[row]);
				row++;
				x = 0f;
			}

			bigWords[k] = w;
			bigRow[k] = row;
			bigX[k] = x;
			x += w.adv + space;
		}

		rowW[row] = Math.max(1f, x - space);
		maxRowW = Math.max(maxRowW, rowW[row]);
		rows = row + 1;
		bigEpoch = fonts.epoch;
		bigLimit = rowLimit;
	}
}
