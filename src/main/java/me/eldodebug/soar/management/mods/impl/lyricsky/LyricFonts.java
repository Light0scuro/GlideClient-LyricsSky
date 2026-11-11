package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.renderer.texture.TextureUtil;

/**
 * Trasforma le parole in texture usando un font vero (anche caricato dall'utente) e le tiene in cache.
 *
 * Ogni parola viene rasterizzata una sola volta in bianco: il colore (e il gradiente) si applica poi
 * con i colori dei vertici, quindi cambiare colori non costa nulla. Esistono due "facce": una grande
 * per il testo nel mondo e una piccola per la barra in alto, cosi' nessuna delle due viene rimpicciolita
 * troppo (che renderebbe il testo sgranato).
 *
 * Tutti i metodi vanno chiamati dal thread di rendering (usano OpenGL).
 */
public final class LyricFonts {

	public static final int BIG = 0, SMALL = 1;
	public static final int PAD = 3;

	private static final int[] RASTER = { 44, 18 };
	private static final int MAX_CACHED = 300;
	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

	public static final class Word {

		public final int tex;
		public final float adv, w, h;

		Word(int tex, float adv, float w, float h) {
			this.tex = tex;
			this.adv = adv;
			this.w = w;
			this.h = h;
		}
	}

	/** Aumenta quando le texture cambiano (font nuovo o parola rimossa dalla cache): le cache esterne si rifanno. */
	public int epoch;

	private String key = "";
	private final Face[] faces = { new Face(RASTER[BIG]), new Face(RASTER[SMALL]) };

	private final class Face {

		final int size;
		Font base, fallback;
		float ascent, descent, space;

		final Map<String, Word> cache = new LinkedHashMap<String, Word>(256, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Word> eldest) {

				if (size() > MAX_CACHED) {
					TextureUtil.deleteTexture(eldest.getValue().tex);
					epoch++;
					return true;
				}

				return false;
			}
		};

		Face(int size) {
			this.size = size;
		}

		void setup(Font f) {

			base = f.deriveFont(Font.PLAIN, (float) size);
			fallback = new Font(Font.DIALOG, Font.PLAIN, size);

			LineMetrics lm = base.getLineMetrics("Ag", FRC);
			ascent = lm.getAscent();
			descent = lm.getDescent();
			space = (float) base.getStringBounds(" ", FRC).getWidth();
		}

		Word render(String s) {

			Font f = base.canDisplayUpTo(s) == -1 ? base : fallback;
			float adv = (float) f.getStringBounds(s, FRC).getWidth();
			int iw = Math.max(1, (int) Math.ceil(adv) + 2 * PAD);
			int ih = Math.max(1, (int) Math.ceil(ascent + descent) + 2 * PAD);

			BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setFont(f);
			g.setColor(Color.WHITE);
			g.drawString(s, PAD, PAD + ascent);
			g.dispose();

			int id = TextureUtil.glGenTextures();
			TextureUtil.uploadTextureImageAllocate(id, img, true, true);

			return new Word(id, adv, iw, ih);
		}
	}

	public boolean isKey(String k) {
		return key.equals(k);
	}

	/** Cambia font: svuota la cache (cancellando le texture) e ricalcola le metriche. */
	public void configure(String k, Font font) {

		clear();
		key = k;

		for (Face f : faces) {
			f.setup(font);
		}
	}

	public Word get(int face, String s) {

		Face f = faces[face];
		Word w = f.cache.get(s);

		if (w == null) {
			w = f.render(s);
			f.cache.put(s, w);
		}

		return w;
	}

	public boolean isReady() {
		return faces[BIG].base != null;
	}

	public float space(int face) {
		return faces[face].space;
	}

	/** Altezza della riga (ascent + descent), senza padding. */
	public float height(int face) {
		return faces[face].ascent + faces[face].descent;
	}

	public void clear() {

		for (Face f : faces) {

			for (Word w : f.cache.values()) {
				TextureUtil.deleteTexture(w.tex);
			}

			f.cache.clear();
		}

		epoch++;
	}

	// ------------------------------------------------------------------ caricamento dei font

	private static final String RES = "/assets/minecraft/soar/fonts/";

	/**
	 * @param id     semibold, medium, regular, mojangles, sans, serif, mono oppure custom
	 * @param custom per "custom": nome di un file nella cartella dei font, un percorso, oppure il nome di un font installato
	 */
	public static Font load(String id, String custom, File fontsDir) {

		try {

			if (id.equals("sans")) {
				return new Font(Font.SANS_SERIF, Font.PLAIN, 1);
			}

			if (id.equals("serif")) {
				return new Font(Font.SERIF, Font.PLAIN, 1);
			}

			if (id.equals("mono")) {
				return new Font(Font.MONOSPACED, Font.PLAIN, 1);
			}

			if (id.equals("custom")) {

				Font f = loadCustom(custom == null ? "" : custom.trim(), fontsDir);

				if (f != null) {
					return f;
				}

				id = "semibold";
			}

			String path = id.equals("medium") ? "inter/Inter-Medium.ttf"
					: id.equals("regular") ? "inter/Inter-Regular.ttf"
							: id.equals("mojangles") ? "mojangles.ttf" : "inter/Inter-SemiBold.ttf";

			InputStream in = LyricFonts.class.getResourceAsStream(RES + path);

			try {
				return Font.createFont(Font.TRUETYPE_FONT, in);
			} finally {
				in.close();
			}
		} catch (Exception e) {
			return new Font(Font.SANS_SERIF, Font.PLAIN, 1);
		}
	}

	private static Font loadCustom(String name, File fontsDir) throws Exception {

		if (name.isEmpty()) {
			return null;
		}

		File file = new File(fontsDir, name);

		if (!file.isFile()) {
			file = new File(name);
		}

		if (file.isFile()) {
			return Font.createFont(Font.TRUETYPE_FONT, file);
		}

		Font installed = new Font(name, Font.PLAIN, 1);

		if (installed.getFamily().equalsIgnoreCase(name) || installed.getFontName().equalsIgnoreCase(name)) {
			return installed;
		}

		return null;
	}
}
