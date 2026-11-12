package me.eldodebug.soar.management.mods.impl;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.GL11;

import me.eldodebug.soar.Glide;
import me.eldodebug.soar.management.event.EventTarget;
import me.eldodebug.soar.management.event.impl.EventRender2D;
import me.eldodebug.soar.management.event.impl.EventRender3D;
import me.eldodebug.soar.management.event.impl.EventTick;
import me.eldodebug.soar.management.language.TranslateText;
import me.eldodebug.soar.management.mods.Mod;
import me.eldodebug.soar.management.mods.ModCategory;
import me.eldodebug.soar.management.mods.impl.lyricsky.LyricFonts;
import me.eldodebug.soar.management.mods.impl.lyricsky.LyricLine;
import me.eldodebug.soar.management.mods.impl.lyricsky.LyricsProvider;
import me.eldodebug.soar.management.mods.impl.lyricsky.SkyMath;
import me.eldodebug.soar.management.mods.impl.lyricsky.SpotifyBridge;
import me.eldodebug.soar.management.mods.impl.lyricsky.WordAnim;
import me.eldodebug.soar.management.mods.settings.impl.BooleanSetting;
import me.eldodebug.soar.management.mods.settings.impl.ColorSetting;
import me.eldodebug.soar.management.mods.settings.impl.ComboSetting;
import me.eldodebug.soar.management.mods.settings.impl.NumberSetting;
import me.eldodebug.soar.management.mods.settings.impl.TextSetting;
import me.eldodebug.soar.management.mods.settings.impl.combo.Option;
import me.eldodebug.soar.management.notification.NotificationType;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Lyrics sincronizzate della canzone Spotify in ascolto, in tre modalita':
 * Sky (scritte in grande nel cielo, sulla cupola attorno a te), Floating (davanti a te, sempre
 * dove guardi) e Screen (sottotitoli sullo schermo). Parola per parola o a riga intera, con preset
 * di stile, gradiente o colore che segue il cielo, scheda "now playing" e barra karaoke in alto.
 *
 * Ottimizzazione: rete e lettura di Spotify girano su thread separati; le parole sono texture in cache
 * (colori e gradiente sono solo colori dei vertici); nessuna allocazione per frame; si disegnano solo
 * le righe nella finestra di tempo visibile e davanti alla camera.
 */
public class LyricsSkyMod extends Mod {

	private static final List<LyricLine> EMPTY = Collections.emptyList();

	// unita' di mondo per pixel di raster del font grande
	private static final float WORLD_PER_PX = 0.0105f;
	private static final float ROW_LIMIT_SKY = 520f;
	private static final float ROW_LIMIT_FLOAT = 460f;
	private static final double CARD_SECS = 5.0;

	/** Un preset di stile: font e due colori del gradiente. */
	private static final class Look {

		final TranslateText key;
		final String font;
		final float[] a = new float[3], b = new float[3];

		Look(TranslateText key, String font, int ar, int ag, int ab, int br, int bg, int bb) {
			this.key = key;
			this.font = font;
			a[0] = ar / 255f; a[1] = ag / 255f; a[2] = ab / 255f;
			b[0] = br / 255f; b[1] = bg / 255f; b[2] = bb / 255f;
		}
	}

	private static final Look[] LOOKS = {
		new Look(TranslateText.LYRICS_LOOK_LAVENDER, "poppins", 255, 255, 255, 196, 181, 253),
		new Look(TranslateText.LYRICS_LOOK_DREAMY, "caveat", 255, 255, 255, 251, 182, 206),
		new Look(TranslateText.LYRICS_LOOK_FIGHT, "kanit", 94, 234, 212, 203, 213, 225),
		new Look(TranslateText.LYRICS_LOOK_COMIC, "fredoka", 253, 224, 71, 244, 114, 182),
		new Look(TranslateText.LYRICS_LOOK_SCRIPT, "pacifico", 244, 114, 182, 167, 139, 250),
		new Look(TranslateText.LYRICS_LOOK_FIRE, "bebas", 253, 224, 71, 239, 68, 68),
		new Look(TranslateText.LYRICS_LOOK_ICE, "montserrat", 224, 242, 254, 125, 211, 252),
		new Look(TranslateText.LYRICS_LOOK_ROYAL, "unbounded", 167, 139, 250, 250, 204, 21),
		new Look(TranslateText.LYRICS_LOOK_CLEAN, "medium", 255, 255, 255, 203, 213, 225)
	};

	private static ArrayList<Option> opts(TranslateText... keys) {

		ArrayList<Option> list = new ArrayList<Option>();

		for (TranslateText k : keys) {
			list.add(new Option(k));
		}

		return list;
	}

	private ComboSetting whereSetting = new ComboSetting(TranslateText.LYRICS_WHERE, this, TranslateText.LYRICS_WHERE_SKY,
			opts(TranslateText.LYRICS_WHERE_SKY, TranslateText.LYRICS_WHERE_FLOATING, TranslateText.LYRICS_WHERE_SCREEN));
	private ComboSetting showSetting = new ComboSetting(TranslateText.LYRICS_SHOW, this, TranslateText.LYRICS_SHOW_WORD,
			opts(TranslateText.LYRICS_SHOW_WORD, TranslateText.LYRICS_SHOW_LINE));
	private BooleanSetting followSetting = new BooleanSetting(TranslateText.FOLLOW_VIEW, this, true);

	private ComboSetting lookSetting = new ComboSetting(TranslateText.LYRICS_LOOK, this, TranslateText.LYRICS_LOOK_LAVENDER,
			opts(TranslateText.LYRICS_LOOK_LAVENDER, TranslateText.LYRICS_LOOK_DREAMY, TranslateText.LYRICS_LOOK_FIGHT,
					TranslateText.LYRICS_LOOK_COMIC, TranslateText.LYRICS_LOOK_SCRIPT, TranslateText.LYRICS_LOOK_FIRE,
					TranslateText.LYRICS_LOOK_ICE, TranslateText.LYRICS_LOOK_ROYAL, TranslateText.LYRICS_LOOK_CLEAN,
					TranslateText.LYRICS_LOOK_CUSTOM));
	private ComboSetting fontSetting = new ComboSetting(TranslateText.LYRICS_FONT, this, TranslateText.LYRICS_FONT_POPPINS,
			opts(TranslateText.LYRICS_FONT_POPPINS, TranslateText.LYRICS_FONT_MONTSERRAT, TranslateText.LYRICS_FONT_KANIT,
					TranslateText.LYRICS_FONT_FREDOKA, TranslateText.LYRICS_FONT_UNBOUNDED, TranslateText.LYRICS_FONT_BEBAS,
					TranslateText.LYRICS_FONT_PACIFICO, TranslateText.LYRICS_FONT_CAVEAT, TranslateText.LYRICS_FONT_SEMIBOLD,
					TranslateText.LYRICS_FONT_MOJANGLES, TranslateText.LYRICS_FONT_SANS, TranslateText.LYRICS_FONT_SERIF,
					TranslateText.LYRICS_FONT_MONO, TranslateText.LYRICS_FONT_CUSTOM));
	private TextSetting customFontSetting = new TextSetting(TranslateText.LYRICS_CUSTOM_FONT, this, "");

	private ComboSetting colorModeSetting = new ComboSetting(TranslateText.LYRICS_COLOR_MODE, this, TranslateText.LYRICS_COLOR_GRADIENT,
			opts(TranslateText.LYRICS_COLOR_GRADIENT, TranslateText.LYRICS_COLOR_ONE, TranslateText.LYRICS_COLOR_SKY));
	private ColorSetting gradientStartSetting = new ColorSetting(TranslateText.LYRICS_GRADIENT_START, this, new Color(167, 139, 250), false);
	private ColorSetting gradientEndSetting = new ColorSetting(TranslateText.LYRICS_GRADIENT_END, this, new Color(94, 234, 212), false);
	private ColorSetting otherColorSetting = new ColorSetting(TranslateText.OTHER_LINES_COLOR, this, new Color(190, 190, 205), false);

	private NumberSetting scaleSetting = new NumberSetting(TranslateText.SCALE, this, 1.4, 0.3, 4.0, false);
	private NumberSetting distanceSetting = new NumberSetting(TranslateText.DISTANCE, this, 18, 5, 60, true);
	private NumberSetting heightSetting = new NumberSetting(TranslateText.HEIGHT, this, 28, 0, 80, true);
	private NumberSetting spreadSetting = new NumberSetting(TranslateText.LYRICS_SPREAD, this, 35, 10, 90, true);
	private NumberSetting tiltSetting = new NumberSetting(TranslateText.LYRICS_TILT, this, 8, 0, 30, true);
	private NumberSetting fadeSetting = new NumberSetting(TranslateText.LYRICS_FADE_TIME, this, 1.8, 0.5, 5.0, false);
	private NumberSetting linesSetting = new NumberSetting(TranslateText.LINES_SHOWN, this, 3, 1, 6, true);
	private NumberSetting offsetSetting = new NumberSetting(TranslateText.TIMING_OFFSET, this, 0, -3000, 3000, true);

	private BooleanSetting curveSetting = new BooleanSetting(TranslateText.LYRICS_CURVE, this, true);
	private BooleanSetting shadowSetting = new BooleanSetting(TranslateText.SHADOW, this, true);
	private BooleanSetting titleCardSetting = new BooleanSetting(TranslateText.LYRICS_TITLE_CARD, this, true);
	private BooleanSetting extraSourceSetting = new BooleanSetting(TranslateText.LYRICS_EXTRA_SOURCE, this, true);
	private BooleanSetting barSetting = new BooleanSetting(TranslateText.LYRICS_TOP_BAR, this, true);

	private final SpotifyBridge bridge = new SpotifyBridge();
	private final LyricFonts fonts = new LyricFonts();
	private final WordAnim anim = new WordAnim();
	private final AtomicInteger generation = new AtomicInteger();

	private volatile List<LyricLine> lyrics = EMPTY;
	private volatile boolean notFoundPending;

	private String currentKey = "";
	private int lineIndex;
	private long cardStartNano;

	private String lastFontId = "", lastCustom = "";

	// posizioni delle righe nel cielo, riusate (nessuna allocazione per frame)
	private static final class Placement {
		int idx = -1;
		float yawRel, yawAbs, elev, dist, roll, size;
	}

	private final Placement[] placements = new Placement[16];
	private long rng = 0x9E3779B97F4A7C15L;

	private float skyYaw;
	private boolean skyInit;
	private long lastFrameNano;

	private final float[] gs = new float[3], ge = new float[3], un = new float[3], cl = new float[3], cr = new float[3];
	private final float[] pos = new float[4];

	// barra in alto e sottotitoli
	private ScaledResolution scaled;
	private int lastDW, lastDH, lastGui;
	private String timeStr = "0:00";
	private int timeSec = -1;
	private float barScroll;
	private long lastBarNano;
	private final LyricFonts.Word[] barWords = new LyricFonts.Word[64];
	private final float[] barX = new float[64];

	public LyricsSkyMod() {

		super(TranslateText.LYRICS_SKY, TranslateText.LYRICS_SKY_DESCRIPTION, ModCategory.RENDER, "spotify lyrics sky music karaoke");

		for (int i = 0; i < placements.length; i++) {
			placements[i] = new Placement();
		}
	}

	@Override
	public void onEnable() {

		super.onEnable();

		if (!isToggled()) {
			return;
		}

		if (!SpotifyBridge.isSupported()) {
			Glide.getInstance().getNotificationManager().post(getName(), "Windows only", NotificationType.WARNING);
			setToggled(false);
			return;
		}

		// cartella dove l'utente puo' mettere i propri font (.ttf / .otf)
		new File(Glide.getInstance().getFileManager().getGlideDir(), "fonts").mkdirs();

		// cartella per i file .lrc personali ("Artista - Titolo.lrc"): hanno la precedenza sulle fonti online
		new File(Glide.getInstance().getFileManager().getGlideDir(), "lyrics").mkdirs();

		currentKey = "";
		skyInit = false;
		bridge.start();
	}

	@Override
	public void onDisable() {

		super.onDisable();

		bridge.stop();
		generation.incrementAndGet();
		lyrics = EMPTY;
		currentKey = "";
		fonts.clear();
		resetPlacements();
	}

	private void resetPlacements() {

		for (Placement p : placements) {
			p.idx = -1;
		}
	}

	private TranslateText where() {
		return whereSetting.getOption().getTranslate();
	}

	private Look currentLook() {

		TranslateText k = lookSetting.getOption().getTranslate();

		for (Look l : LOOKS) {
			if (l.key == k) {
				return l;
			}
		}

		return null;
	}

	// ------------------------------------------------------------------ logica (tick)

	@EventTarget
	public void onTick(EventTick event) {

		updateFont();

		if (notFoundPending) {
			notFoundPending = false;
			Glide.getInstance().getNotificationManager().post(getName(), "No synced lyrics found", NotificationType.INFO);
		}

		SpotifyBridge.Snapshot s = bridge.get();

		if (s == null) {
			return;
		}

		String key = s.artist + '\u0000' + s.title;

		if (key.equals(currentKey)) {
			return;
		}

		currentKey = key;
		lineIndex = 0;
		lyrics = EMPTY;
		cardStartNano = System.nanoTime();
		resetPlacements();

		final int gen = generation.incrementAndGet();

		File lyricsDir = new File(Glide.getInstance().getFileManager().getGlideDir(), "lyrics");

		LyricsProvider.fetch(s.artist, s.title, s.durationMs, lyricsDir, extraSourceSetting.isToggled(), new LyricsProvider.Callback() {
			@Override
			public void onResult(List<LyricLine> lines) {

				if (generation.get() != gen) {
					return;
				}

				lyrics = lines;

				if (lines.isEmpty()) {
					notFoundPending = true;
				}
			}
		});
	}

	private static String fontIdFor(TranslateText t) {

		return t == TranslateText.LYRICS_FONT_MONTSERRAT ? "montserrat"
				: t == TranslateText.LYRICS_FONT_KANIT ? "kanit"
						: t == TranslateText.LYRICS_FONT_FREDOKA ? "fredoka"
								: t == TranslateText.LYRICS_FONT_UNBOUNDED ? "unbounded"
										: t == TranslateText.LYRICS_FONT_BEBAS ? "bebas"
												: t == TranslateText.LYRICS_FONT_PACIFICO ? "pacifico"
														: t == TranslateText.LYRICS_FONT_CAVEAT ? "caveat"
																: t == TranslateText.LYRICS_FONT_SEMIBOLD ? "semibold"
																		: t == TranslateText.LYRICS_FONT_MOJANGLES ? "mojangles"
																				: t == TranslateText.LYRICS_FONT_SANS ? "sans"
																						: t == TranslateText.LYRICS_FONT_SERIF ? "serif"
																								: t == TranslateText.LYRICS_FONT_MONO ? "mono"
																										: t == TranslateText.LYRICS_FONT_CUSTOM ? "custom" : "poppins";
	}

	private void updateFont() {

		Look look = currentLook();
		String id = look != null ? look.font : fontIdFor(fontSetting.getOption().getTranslate());
		String custom = customFontSetting.getText();

		if (fonts.isReady() && id.equals(lastFontId) && (!id.equals("custom") || custom.equals(lastCustom))) {
			return;
		}

		lastFontId = id;
		lastCustom = custom;

		File dir = new File(Glide.getInstance().getFileManager().getGlideDir(), "fonts");
		fonts.configure(id + '|' + (id.equals("custom") ? custom : ""), LyricFonts.load(id, custom, dir));
	}

	private double songTime(SpotifyBridge.Snapshot s, long now) {

		double t = s.positionMs + offsetSetting.getValue();

		if (s.playing) {
			t += (now - s.nanoStamp) / 1000000.0;
		}

		if (s.durationMs > 0 && t > s.durationMs) {
			t = s.durationMs;
		}

		return t;
	}

	/** Sposta l'indice della riga corrente (pochi passi per frame). Ritorna -1 prima della prima riga. */
	private int advanceIndex(List<LyricLine> ls, double t) {

		int n = ls.size();

		if (lineIndex >= n) {
			lineIndex = n - 1;
		}

		while (lineIndex + 1 < n && ls.get(lineIndex + 1).timeMs <= t) {
			lineIndex++;
		}

		while (lineIndex > 0 && ls.get(lineIndex).timeMs > t) {
			lineIndex--;
		}

		return ls.get(0).timeMs > t ? -1 : lineIndex;
	}

	private float rand() {
		rng ^= rng << 13;
		rng ^= rng >>> 7;
		rng ^= rng << 17;
		return (rng >>> 40) / 16777216f;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static void load(float[] dst, Color c) {
		dst[0] = c.getRed() / 255f;
		dst[1] = c.getGreen() / 255f;
		dst[2] = c.getBlue() / 255f;
	}

	/** Colori della frame: gradiente (da preset o da impostazioni), colore unico o colore del cielo. */
	private void prepareColors(float pt, Entity view) {

		Look look = currentLook();

		if (look != null) {
			System.arraycopy(look.a, 0, gs, 0, 3);
			System.arraycopy(look.b, 0, ge, 0, 3);
		} else {
			load(gs, gradientStartSetting.getColor());
			load(ge, gradientEndSetting.getColor());
		}

		TranslateText mode = colorModeSetting.getOption().getTranslate();

		if (mode == TranslateText.LYRICS_COLOR_ONE) {
			System.arraycopy(gs, 0, ge, 0, 3);
		} else if (mode == TranslateText.LYRICS_COLOR_SKY && mc.theWorld != null) {

			// tinta chiara del colore del cielo, cosi' il testo "si intona" con l'ora e il bioma
			Vec3 sky = mc.theWorld.getSkyColor(view, pt);
			gs[0] = lerp((float) sky.xCoord, 1f, 0.72f);
			gs[1] = lerp((float) sky.yCoord, 1f, 0.72f);
			gs[2] = lerp((float) sky.zCoord, 1f, 0.72f);

			for (int i = 0; i < 3; i++) {
				ge[i] = lerp(gs[i], 1f, 0.3f);
			}
		}

		load(un, otherColorSetting.getColor());
	}

	/** Colore di una parola in un punto del gradiente (f = 0..1 lungo la riga). */
	private void gradColor(float[] dst, float f, float mix, float white) {

		for (int i = 0; i < 3; i++) {
			float c = lerp(gs[i], ge[i], f);
			c = lerp(un[i], c, mix);
			dst[i] = lerp(c, 1f, white);
		}
	}

	// ------------------------------------------------------------------ disegno di una parola

	private static void quad(WorldRenderer wr, float x0, float y0, float x1, float y1,
			float rl, float gl, float bl, float rr, float gr, float br, float a) {

		wr.pos(x0, y0, 0.0).tex(0.0, 0.0).color(rl, gl, bl, a).endVertex();
		wr.pos(x0, y1, 0.0).tex(0.0, 1.0).color(rl, gl, bl, a).endVertex();
		wr.pos(x1, y1, 0.0).tex(1.0, 1.0).color(rr, gr, br, a).endVertex();
		wr.pos(x1, y0, 0.0).tex(1.0, 0.0).color(rr, gr, br, a).endVertex();
	}

	/** Disegna una parola centrata in (cx, cy); sc = fattore di scala totale (testo -> unita' correnti). */
	private void drawWord(Tessellator tess, WorldRenderer wr, LyricFonts.Word w, float cx, float cy, float sc,
			float[] left, float[] right, float a, boolean shadow) {

		float hw = (w.adv / 2f + LyricFonts.PAD) * sc, hh = w.h / 2f * sc;

		GlStateManager.bindTexture(w.tex);
		wr.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);

		if (shadow) {
			float off = w.h * 0.045f * sc;
			quad(wr, cx - hw + off, cy - hh + off * 1.3f, cx + hw + off, cy + hh + off * 1.3f, 0f, 0f, 0f, 0f, 0f, 0f, a * 0.5f);
		}

		quad(wr, cx - hw, cy - hh, cx + hw, cy + hh, left[0], left[1], left[2], right[0], right[1], right[2], a);
		tess.draw();
	}

	// ------------------------------------------------------------------ rendering 3D (Sky e Floating)

	private void worldState() {

		GlStateManager.enableTexture2D();
		GlStateManager.disableLighting();
		GlStateManager.disableDepth();
		GlStateManager.depthMask(false);
		GlStateManager.disableCull();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
	}

	private void worldStateEnd() {

		GlStateManager.enableCull();
		GlStateManager.depthMask(true);
		GlStateManager.enableDepth();
		GlStateManager.disableBlend();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	@EventTarget
	public void onRender3D(EventRender3D event) {

		SpotifyBridge.Snapshot s = bridge.get();
		List<LyricLine> ls = lyrics;
		Entity view = mc.getRenderViewEntity();
		TranslateText where = where();

		if (s == null || mc.theWorld == null || view == null || mc.gameSettings.hideGUI || !fonts.isReady()
				|| where == TranslateText.LYRICS_WHERE_SCREEN) {
			return;
		}

		long now = System.nanoTime();
		float pt = event.getPartialTicks();

		float vYaw = view.prevRotationYaw + MathHelper.wrapAngleTo180_float(view.rotationYaw - view.prevRotationYaw) * pt;
		float vPitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * pt;
		float dt = lastFrameNano == 0L ? 0f : Math.min(0.1f, (now - lastFrameNano) / 1.0E9f);
		lastFrameNano = now;

		// l'angolo "morbido" con cui le scritte seguono lo sguardo
		if (!skyInit) {
			skyYaw = vYaw;
			skyInit = true;
		} else {
			skyYaw += MathHelper.wrapAngleTo180_float(vYaw - skyYaw) * (1f - (float) Math.exp(-4f * dt));
		}

		prepareColors(pt, view);

		boolean stateSet = false;
		float eye = view.getEyeHeight();

		if (!ls.isEmpty()) {

			double t = songTime(s, now);
			advanceIndex(ls, t);
			stateSet = drawLines(ls, t, vYaw, vPitch, eye, where == TranslateText.LYRICS_WHERE_SKY);
		}

		// scheda "now playing"
		double since = (now - cardStartNano) / 1.0E9;

		if (titleCardSetting.isToggled() && cardStartNano != 0L && since < CARD_SECS) {

			if (!stateSet) {
				worldState();
				stateSet = true;
			}

			drawCard(s, since, vPitch, eye);
		}

		if (stateSet) {
			worldStateEnd();
		}
	}

	private void spawn(Placement p, int idx) {

		p.idx = idx;

		float sign = (idx & 1) == 0 ? 1f : -1f;
		p.yawRel = sign * (0.25f + 0.75f * rand()) * spreadSetting.getValueFloat();
		p.yawAbs = skyYaw + p.yawRel;
		p.elev = heightSetting.getValueFloat() + ((idx & 1) == 0 ? 3f : -4f) + (rand() - 0.5f) * 8f;
		p.dist = distanceSetting.getValueFloat() * (0.85f + 0.3f * rand());
		p.roll = (rand() - 0.5f) * 2f * tiltSetting.getValueFloat();
		p.size = 0.9f + 0.35f * rand();
	}

	/** Disegna le righe visibili. Ritorna true se ha attivato lo stato GL (da ripristinare dal chiamante). */
	private boolean drawLines(List<LyricLine> ls, double t, float vYaw, float vPitch, float eye, boolean sky) {

		int n = ls.size();
		int lo = Math.max(0, lineIndex - (sky ? linesSetting.getValueInt() : 2) + 1);
		int hi = Math.min(n - 1, lineIndex + 1);
		float fadeMs = fadeSetting.getValueFloat() * 1000f;

		boolean reveal = showSetting.getOption().getTranslate() == TranslateText.LYRICS_SHOW_WORD;
		boolean curved = sky && curveSetting.isToggled();
		boolean follow = followSetting.isToggled();
		boolean shadow = shadowSetting.isToggled();
		float userScale = scaleSetting.getValueFloat();
		float rowLimit = sky ? ROW_LIMIT_SKY : ROW_LIMIT_FLOAT;
		float rowH = fonts.height(LyricFonts.BIG) * 1.12f;

		boolean stateSet = false;

		for (int i = lo; i <= hi; i++) {

			LyricLine l = ls.get(i);
			long start = l.timeMs;
			long span = WordAnim.spanOf(ls, i);
			double spanEnd = start + span;

			if (t < start - 250.0 || t > spanEnd + fadeMs) {
				continue;
			}

			float yawC, elev, dist, roll, size;

			if (sky) {

				Placement p = placements[i & 15];

				if (p.idx != i) {
					spawn(p, i);
				}

				yawC = follow ? skyYaw + p.yawRel : p.yawAbs;
				elev = p.elev;
				dist = p.dist;
				roll = p.roll;
				size = p.size;

				// fuori dal campo visivo: non disegniamo nulla
				if (Math.abs(MathHelper.wrapAngleTo180_float(yawC - vYaw)) > 110f) {
					continue;
				}
			} else {
				// Floating: attaccato allo sguardo, poco sopra il mirino
				yawC = vYaw;
				elev = -vPitch + 6f;
				dist = 7f;
				roll = 0f;
				size = 1f;
			}

			float fadeIn = WordAnim.clamp01((float) ((t - (start - 250.0)) / 350.0));
			float fo = t > spanEnd ? WordAnim.clamp01((float) ((t - spanEnd) / fadeMs)) : 0f;
			float alpha = (1f - (1f - fadeIn) * (1f - fadeIn)) * (1f - fo * fo);

			if (alpha < 0.01f) {
				continue;
			}

			// entrata con un piccolo rimbalzo, poi la riga rimpicciolisce
			float c1 = 1.70158f, c3 = c1 + 1f, f1 = fadeIn - 1f;
			float life = (1f + c3 * f1 * f1 * f1 + c1 * f1 * f1) * (1f - 0.3f * fo);

			l.prepare(fonts, rowLimit);

			float fit = l.maxRowW > rowLimit * 1.35f ? rowLimit * 1.35f / l.maxRowW : 1f;
			float s = WORLD_PER_PX * userScale * (sky ? 1f : 0.45f) * size * life * fit;
			float stackY = sky ? 0f : (i - lineIndex) * (l.rows + 0.35f) * rowH;

			if (!stateSet) {
				worldState();
				stateSet = true;
			}

			drawLine3D(l, t, start, span, alpha, s, yawC, elev, dist, eye, roll, stackY, rowH, curved, reveal, shadow);
		}

		return stateSet;
	}

	private void drawLine3D(LyricLine l, double t, long start, double span, float alpha, float s, float yawC, float elev,
			float dist, float eye, float roll, float stackY, float rowH, boolean curved, boolean reveal, boolean shadow) {

		int nW = l.words.length;
		int curWord = WordAnim.currentWord(l, (t - start) / span);
		float tanRoll = (float) Math.tan(Math.toRadians(roll));
		float half = l.maxRowW / 2f;

		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();

		if (!curved) {
			SkyMath.flat(pos, yawC, elev, dist, eye);
			GlStateManager.pushMatrix();
			GlStateManager.translate(pos[0], pos[1], pos[2]);
			GlStateManager.rotate(-yawC, 0f, 1f, 0f);
			GlStateManager.rotate(-elev, 1f, 0f, 0f);
			GlStateManager.rotate(roll, 0f, 0f, 1f);
			GlStateManager.scale(-s, -s, s);
		}

		for (int k = 0; k < nW; k++) {

			anim.compute(l, k, t, start, span, curWord, reveal);

			if (anim.vis <= 0.01f) {
				continue;
			}

			LyricFonts.Word w = l.bigWords[k];
			int row = l.bigRow[k];
			float cx = -l.rowW[row] / 2f + l.bigX[k] + w.adv / 2f;
			float cy = (row - (l.rows - 1) / 2f) * rowH + stackY;

			float e = anim.emph * anim.emph * (3f - 2f * anim.emph);
			float sc = (0.7f + 0.3f * anim.vis) * (1f + 0.32f * e);
			float fl = WordAnim.clamp01((cx - w.adv / 2f + half) / l.maxRowW);
			float fr = WordAnim.clamp01((cx + w.adv / 2f + half) / l.maxRowW);

			gradColor(cl, fl, anim.mix, 0.28f * e);
			gradColor(cr, fr, anim.mix, 0.28f * e);

			float a = alpha * anim.vis * (0.78f + 0.22f * anim.mix);
			float qx = cx, qy = cy;

			if (curved) {
				// ogni parola sta sulla cupola, di fronte al centro: la riga appare curva
				SkyMath.curved(pos, yawC, elev, dist, cx, cy, s, tanRoll, eye);
				GlStateManager.pushMatrix();
				GlStateManager.translate(pos[0], pos[1], pos[2]);
				GlStateManager.rotate(-pos[3], 0f, 1f, 0f);
				GlStateManager.rotate(-elev, 1f, 0f, 0f);
				GlStateManager.rotate(roll, 0f, 0f, 1f);
				GlStateManager.scale(-s, -s, s);
				qx = 0f;
				qy = 0f;
			}

			drawWord(tess, wr, w, qx, qy, sc, cl, cr, a, shadow);

			if (curved) {
				GlStateManager.popMatrix();
			}
		}

		if (!curved) {
			GlStateManager.popMatrix();
		}
	}

	/** Scheda "NOW PLAYING / titolo / artista" con due filetti, attaccata allo sguardo. */
	private void drawCard(SpotifyBridge.Snapshot s, double since, float vPitch, float eye) {

		float a = (float) Math.min(1.0, since / 0.5) * (float) Math.min(1.0, (CARD_SECS - since) / 1.0);

		if (a <= 0.01f) {
			return;
		}

		LyricFonts.Word wn = fonts.get(LyricFonts.BIG, "NOW PLAYING");
		LyricFonts.Word wt = fonts.get(LyricFonts.BIG, s.title);
		LyricFonts.Word wa = fonts.get(LyricFonts.BIG, s.artist);

		float sc = WORLD_PER_PX * scaleSetting.getValueFloat() * 0.5f;
		float elev = -vPitch + 8f;
		float fitT = Math.min(1f, 760f / wt.adv);
		float fitA = Math.min(1f, 520f / wa.adv);
		float rule = Math.max(wt.adv * fitT / 2f + 24f, 140f);

		SkyMath.flat(pos, skyYaw, elev, 7f, eye);
		GlStateManager.pushMatrix();
		GlStateManager.translate(pos[0], pos[1], pos[2]);
		GlStateManager.rotate(-skyYaw, 0f, 1f, 0f);
		GlStateManager.rotate(-elev, 1f, 0f, 0f);
		GlStateManager.scale(-sc, -sc, sc);

		// filetti sopra e sotto
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		GlStateManager.disableTexture2D();
		wr.begin(7, DefaultVertexFormats.POSITION_COLOR);

		for (int i = 0; i < 2; i++) {
			float y0 = i == 0 ? -66f : 70f;
			wr.pos(-rule, y0, 0.0).color(gs[0], gs[1], gs[2], a * 0.55f).endVertex();
			wr.pos(-rule, y0 + 1.6f, 0.0).color(gs[0], gs[1], gs[2], a * 0.55f).endVertex();
			wr.pos(rule, y0 + 1.6f, 0.0).color(ge[0], ge[1], ge[2], a * 0.55f).endVertex();
			wr.pos(rule, y0, 0.0).color(ge[0], ge[1], ge[2], a * 0.55f).endVertex();
		}

		tess.draw();
		GlStateManager.enableTexture2D();

		boolean shadow = shadowSetting.isToggled();

		drawWord(tess, wr, wn, 0f, -42f, 0.34f, un, un, a * 0.75f, shadow);

		cl[0] = gs[0]; cl[1] = gs[1]; cl[2] = gs[2];
		cr[0] = ge[0]; cr[1] = ge[1]; cr[2] = ge[2];
		drawWord(tess, wr, wt, 0f, 2f, fitT, cl, cr, a, shadow);

		cl[0] = cr[0] = 1f; cl[1] = cr[1] = 1f; cl[2] = cr[2] = 1f;
		drawWord(tess, wr, wa, 0f, 46f, 0.55f * fitA, cl, cr, a * 0.85f, shadow);

		GlStateManager.popMatrix();
	}

	// ------------------------------------------------------------------ rendering 2D (barra e sottotitoli)

	@EventTarget
	public void onRender2D(EventRender2D event) {

		if (!fonts.isReady() || mc.gameSettings.hideGUI) {
			return;
		}

		boolean screen = where() == TranslateText.LYRICS_WHERE_SCREEN;

		if (!barSetting.isToggled() && !screen) {
			return;
		}

		SpotifyBridge.Snapshot s = bridge.get();
		List<LyricLine> ls = lyrics;

		if (s == null || ls.isEmpty()) {
			return;
		}

		long now = System.nanoTime();
		double t = songTime(s, now);
		int cur = advanceIndex(ls, t);

		if (scaled == null || lastDW != mc.displayWidth || lastDH != mc.displayHeight || lastGui != mc.gameSettings.guiScale) {
			scaled = new ScaledResolution(mc);
			lastDW = mc.displayWidth;
			lastDH = mc.displayHeight;
			lastGui = mc.gameSettings.guiScale;
		}

		if (screen && mc.theWorld != null) {
			prepareColors(event.getPartialTicks(), mc.getRenderViewEntity() != null ? mc.getRenderViewEntity() : mc.thePlayer);
			drawScreen(ls, t, cur);
		}

		if (barSetting.isToggled()) {
			drawBar(s, ls, t, cur, now);
		}
	}

	/** Sottotitoli: la riga corrente, centrata in basso. */
	private void drawScreen(List<LyricLine> ls, double t, int cur) {

		if (cur < 0) {
			return;
		}

		LyricLine l = ls.get(cur);
		long start = l.timeMs;
		long span = WordAnim.spanOf(ls, cur);
		double spanEnd = start + span;

		if (t > spanEnd + 700.0) {
			return;
		}

		float ts = 10f * scaleSetting.getValueFloat() / fonts.height(LyricFonts.BIG);
		float sw = scaled.getScaledWidth(), sh = scaled.getScaledHeight();
		float rowH = fonts.height(LyricFonts.BIG) * 1.12f;

		l.prepare(fonts, sw * 0.7f / ts);

		float alpha = WordAnim.clamp01((float) ((spanEnd + 700.0 - t) / 300.0));
		boolean reveal = showSetting.getOption().getTranslate() == TranslateText.LYRICS_SHOW_WORD;
		boolean shadow = shadowSetting.isToggled();
		int curWord = WordAnim.currentWord(l, (t - start) / (double) span);
		float half = l.maxRowW / 2f;
		float centerY = sh - 86f;

		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.enableTexture2D();

		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();

		for (int k = 0; k < l.words.length; k++) {

			anim.compute(l, k, t, start, span, curWord, reveal);

			if (anim.vis <= 0.01f) {
				continue;
			}

			LyricFonts.Word w = l.bigWords[k];
			int row = l.bigRow[k];
			float cx = -l.rowW[row] / 2f + l.bigX[k] + w.adv / 2f;
			float cy = (row - (l.rows - 1) / 2f) * rowH;

			float e = anim.emph * anim.emph * (3f - 2f * anim.emph);
			float sc = (0.7f + 0.3f * anim.vis) * (1f + 0.25f * e) * ts;

			gradColor(cl, WordAnim.clamp01((cx - w.adv / 2f + half) / l.maxRowW), anim.mix, 0.28f * e);
			gradColor(cr, WordAnim.clamp01((cx + w.adv / 2f + half) / l.maxRowW), anim.mix, 0.28f * e);

			drawWord(tess, wr, w, sw / 2f + cx * ts, centerY + cy * ts, sc, cl, cr, alpha * anim.vis * (0.78f + 0.22f * anim.mix), shadow);
		}

		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	private void drawBar(SpotifyBridge.Snapshot s, List<LyricLine> ls, double t, int cur, long now) {

		int shown = Math.max(cur, 0);
		LyricLine l = ls.get(shown);
		l.ensureWords();
		LyricLine nx = shown + 1 < ls.size() ? ls.get(shown + 1) : null;

		if (nx != null) {
			nx.ensureWords();
		}

		final float W = 270f, H = 17f, Y = 5f;
		float x = (scaled.getScaledWidth() - W) / 2f;
		int sf = scaled.getScaleFactor();
		float dt = lastBarNano == 0L ? 0f : Math.min(0.1f, (now - lastBarNano) / 1.0E9f);
		lastBarNano = now;

		// sfondo a pillola e puntino
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.disableTexture2D();
		fillRound(x, Y, W, H, H / 2f, 0.075f, 0.065f, 0.12f, 0.85f);
		fillRound(x + 4f, Y + H / 2f - 3.5f, 7f, 7f, 3.5f, 0.65f, 0.55f, 0.98f, 1f);
		GlStateManager.enableTexture2D();

		// tempo trascorso, a sinistra della pillola
		int sec = (int) (t / 1000.0);

		if (sec != timeSec) {
			timeSec = sec;
			timeStr = (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
		}

		mc.fontRendererObj.drawString(timeStr, x - 4f - mc.fontRendererObj.getStringWidth(timeStr), Y + 5f, 0xFFFFFFFF, true);

		// riga corrente + successiva in una striscia che scorre
		float sx = x + 16f, ex = x + W - 26f, stripW = ex - sx;
		float ts = 8.5f / fonts.height(LyricFonts.SMALL);
		float space = fonts.space(LyricFonts.SMALL) * ts;

		int nc = Math.min(l.words.length, 40);
		int nn = nx == null ? 0 : Math.min(nx.words.length, 24);
		float p = 0f;

		for (int k = 0; k < nc; k++) {
			LyricFonts.Word w = fonts.get(LyricFonts.SMALL, l.words[k]);
			barWords[k] = w;
			barX[k] = p;
			p += w.adv * ts + space;
		}

		p += 12f;

		for (int k = 0; k < nn; k++) {
			LyricFonts.Word w = fonts.get(LyricFonts.SMALL, nx.words[k]);
			barWords[nc + k] = w;
			barX[nc + k] = p;
			p += w.adv * ts + space;
		}

		int curWord = cur < 0 ? -1 : WordAnim.currentWord(l, (t - l.timeMs) / (double) WordAnim.spanOf(ls, shown));
		float target = 0f;

		if (curWord >= 0 && curWord < nc) {
			float center = barX[curWord] + barWords[curWord].adv * ts / 2f;
			target = Math.max(0f, Math.min(center - stripW / 2f, p - stripW));
		}

		barScroll += (target - barScroll) * (1f - (float) Math.exp(-10f * dt));

		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor((int) (sx * sf), mc.displayHeight - (int) ((Y + H) * sf), (int) (stripW * sf), (int) (H * sf));

		// pillola viola dietro la parola in corso
		if (curWord >= 0 && curWord < nc) {
			GlStateManager.disableTexture2D();
			fillRound(sx + barX[curWord] - barScroll - 3f, Y + 2.5f, barWords[curWord].adv * ts + 6f, H - 5f, (H - 5f) / 2f, 0.65f, 0.55f, 0.98f, 0.95f);
			GlStateManager.enableTexture2D();
		}

		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();

		for (int k = 0; k < nc + nn; k++) {

			LyricFonts.Word w = barWords[k];
			float wx = sx + barX[k] - barScroll;

			if (wx > ex + 4f || wx + w.adv * ts < sx - 4f) {
				continue;
			}

			float c, a;

			if (k >= nc) {
				c = 0.55f;
				a = 0.8f;
			} else if (k <= curWord) {
				c = 1f;
				a = 1f;
			} else {
				c = 0.62f;
				a = 0.9f;
			}

			float y0 = Y + H / 2f - w.h * ts / 2f;

			GlStateManager.bindTexture(w.tex);
			wr.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
			quad(wr, wx - LyricFonts.PAD * ts, y0, wx + (w.adv + LyricFonts.PAD) * ts, y0 + w.h * ts, c, c, c, c, c, c, a);
			tess.draw();
		}

		GL11.glDisable(GL11.GL_SCISSOR_TEST);

		// barrette "equalizzatore" a destra
		int bx = (int) (x + W - 20f);
		int mid = (int) (Y + H / 2f);
		double ms = now / 1000000.0;

		for (int i = 0; i < 4; i++) {

			float h = s.playing ? 3f + 6f * (float) Math.abs(Math.sin(ms / 180.0 + i * 1.7)) : 3f;
			Gui.drawRect(bx + i * 4, mid - (int) (h / 2f), bx + i * 4 + 2, mid + (int) (h / 2f) + 1, 0xFFA78BFA);
		}

		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/** Rettangolo con angoli arrotondati (richiede texture disattivate). */
	private static void fillRound(float x, float y, float w, float h, float r, float cr, float cg, float cb, float ca) {

		GlStateManager.color(cr, cg, cb, ca);
		GL11.glBegin(GL11.GL_TRIANGLE_FAN);
		GL11.glVertex2f(x + w / 2f, y + h / 2f);

		float[] cx = { x + r, x + w - r, x + w - r, x + r };
		float[] cy = { y + r, y + r, y + h - r, y + h - r };
		int[] startDeg = { 180, 270, 0, 90 };

		for (int c = 0; c < 4; c++) {

			for (int j = 0; j <= 8; j++) {
				double a = Math.toRadians(startDeg[c] + 90.0 * j / 8.0);
				GL11.glVertex2f(cx[c] + (float) Math.cos(a) * r, cy[c] + (float) Math.sin(a) * r);
			}
		}

		double a0 = Math.toRadians(180.0);
		GL11.glVertex2f(cx[0] + (float) Math.cos(a0) * r, cy[0] + (float) Math.sin(a0) * r);
		GL11.glEnd();
	}
}
