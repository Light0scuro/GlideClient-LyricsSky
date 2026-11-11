package me.eldodebug.soar.management.mods.impl;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.GL11;

import me.eldodebug.soar.Glide;
import me.eldodebug.soar.injection.interfaces.IMixinRenderManager;
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
import me.eldodebug.soar.management.mods.impl.lyricsky.SpotifyBridge;
import me.eldodebug.soar.management.mods.settings.impl.BooleanSetting;
import me.eldodebug.soar.management.mods.settings.impl.ColorSetting;
import me.eldodebug.soar.management.mods.settings.impl.ComboSetting;
import me.eldodebug.soar.management.mods.settings.impl.NumberSetting;
import me.eldodebug.soar.management.mods.settings.impl.TextSetting;
import me.eldodebug.soar.management.mods.settings.impl.combo.Option;
import me.eldodebug.soar.management.notification.NotificationType;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

/**
 * Mostra le lyrics sincronizzate della canzone Spotify in ascolto.
 *
 * Stile "Fluttuante": ogni riga compare nel mondo davanti a te, ruotata a caso, con gradiente di colore
 * e la parola cantata che "salta fuori"; poi si allontana e sfuma. Stile "Pannello": blocco fisso nel cielo.
 * In piu' c'e' una barra karaoke in alto con la parola corrente evidenziata.
 *
 * Ottimizzazione: rete e lettura di Spotify girano su thread separati; le parole sono texture in cache
 * (colori e gradiente sono solo colori dei vertici); nessuna allocazione per frame; si disegnano solo
 * le righe nella finestra di tempo visibile e davanti alla camera.
 */
public class LyricsSkyMod extends Mod {

	private static final List<LyricLine> EMPTY = Collections.emptyList();

	// stile pannello (font vanilla)
	private static final float LINE_H = 11f;
	private static final float PANEL_SCALE = 0.05f;
	private static final float MAX_TEXT_W = 240f;
	private static final double SCROLL_MS = 280.0;

	// stile fluttuante (font a texture): unita' di mondo per pixel di raster
	private static final float WORLD_PER_PX = 0.0105f;
	private static final float MAX_LINE_PX = 900f;
	private static final float DRIFT_BLOCKS = 3f;

	private ComboSetting styleSetting = new ComboSetting(TranslateText.LYRICS_STYLE, this, TranslateText.LYRICS_STYLE_FLOATING, new ArrayList<Option>() {
		private static final long serialVersionUID = 1L;
		{
			add(new Option(TranslateText.LYRICS_STYLE_FLOATING));
			add(new Option(TranslateText.LYRICS_STYLE_PANEL));
		}
	});

	private NumberSetting distanceSetting = new NumberSetting(TranslateText.DISTANCE, this, 12, 5, 60, true);
	private NumberSetting heightSetting = new NumberSetting(TranslateText.HEIGHT, this, 25, 0, 80, true);
	private NumberSetting scaleSetting = new NumberSetting(TranslateText.SCALE, this, 1.0, 0.3, 3.0, false);
	private NumberSetting linesSetting = new NumberSetting(TranslateText.LINES_SHOWN, this, 4, 1, 9, true);
	private NumberSetting offsetSetting = new NumberSetting(TranslateText.TIMING_OFFSET, this, 0, -3000, 3000, true);

	private ComboSetting fontSetting = new ComboSetting(TranslateText.LYRICS_FONT, this, TranslateText.LYRICS_FONT_SEMIBOLD, new ArrayList<Option>() {
		private static final long serialVersionUID = 1L;
		{
			add(new Option(TranslateText.LYRICS_FONT_SEMIBOLD));
			add(new Option(TranslateText.LYRICS_FONT_MEDIUM));
			add(new Option(TranslateText.LYRICS_FONT_REGULAR));
			add(new Option(TranslateText.LYRICS_FONT_MOJANGLES));
			add(new Option(TranslateText.LYRICS_FONT_SANS));
			add(new Option(TranslateText.LYRICS_FONT_SERIF));
			add(new Option(TranslateText.LYRICS_FONT_MONO));
			add(new Option(TranslateText.LYRICS_FONT_CUSTOM));
		}
	});
	private TextSetting customFontSetting = new TextSetting(TranslateText.LYRICS_CUSTOM_FONT, this, "");

	private BooleanSetting gradientSetting = new BooleanSetting(TranslateText.GRADIENT, this, true);
	private ColorSetting gradientStartSetting = new ColorSetting(TranslateText.LYRICS_GRADIENT_START, this, new Color(167, 139, 250), false);
	private ColorSetting gradientEndSetting = new ColorSetting(TranslateText.LYRICS_GRADIENT_END, this, new Color(94, 234, 212), false);
	private ColorSetting currentColorSetting = new ColorSetting(TranslateText.CURRENT_LINE_COLOR, this, new Color(255, 255, 255), false);
	private ColorSetting otherColorSetting = new ColorSetting(TranslateText.OTHER_LINES_COLOR, this, new Color(190, 190, 205), false);
	private BooleanSetting wordHighlightSetting = new BooleanSetting(TranslateText.LYRICS_WORD_HIGHLIGHT, this, true);

	private NumberSetting spreadSetting = new NumberSetting(TranslateText.LYRICS_SPREAD, this, 40, 10, 90, true);
	private NumberSetting fadeSetting = new NumberSetting(TranslateText.LYRICS_FADE_TIME, this, 2.0, 0.5, 5.0, false);
	private NumberSetting tiltSetting = new NumberSetting(TranslateText.LYRICS_TILT, this, 10, 0, 30, true);

	private BooleanSetting backgroundSetting = new BooleanSetting(TranslateText.BACKGROUND, this, true);
	private BooleanSetting shadowSetting = new BooleanSetting(TranslateText.SHADOW, this, true);
	private BooleanSetting followSetting = new BooleanSetting(TranslateText.FOLLOW_VIEW, this, true);
	private BooleanSetting barSetting = new BooleanSetting(TranslateText.LYRICS_TOP_BAR, this, true);

	private final SpotifyBridge bridge = new SpotifyBridge();
	private final LyricFonts fonts = new LyricFonts();
	private final AtomicInteger generation = new AtomicInteger();

	private volatile List<LyricLine> lyrics = EMPTY;
	private volatile boolean notFoundPending;

	private String currentKey = "";
	private int lineIndex;

	// font attuale (per accorgersi dei cambi senza allocare)
	private TranslateText lastFontOpt;
	private String lastCustom = "";

	// stile pannello
	private float panelYaw, panelHalfW;
	private boolean panelInit;
	private long lastFrameNano;

	// stile fluttuante: posizioni delle righe, riusate (nessuna allocazione per frame)
	private static final class Placement {
		int idx = -1;
		double ax, ay, az;
		float dirX, dirZ, faceYaw, facePitch, tiltY, tiltX, roll, size;
	}

	private final Placement[] placements = new Placement[16];
	private long rng = 0x9E3779B97F4A7C15L;

	private final float[] gs = new float[3], ge = new float[3], cc = new float[3], un = new float[3];

	// barra in alto
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

		currentKey = "";
		panelInit = false;
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

		for (Placement p : placements) {
			p.idx = -1;
		}
	}

	private boolean isFloating() {
		return styleSetting.getOption().getTranslate() == TranslateText.LYRICS_STYLE_FLOATING;
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
		panelInit = false;

		for (Placement p : placements) {
			p.idx = -1;
		}

		final int gen = generation.incrementAndGet();

		LyricsProvider.fetch(s.artist, s.title, s.durationMs, new LyricsProvider.Callback() {
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

	private void updateFont() {

		TranslateText opt = fontSetting.getOption().getTranslate();
		String custom = customFontSetting.getText();

		if (fonts.isReady() && opt == lastFontOpt && custom.equals(lastCustom)) {
			return;
		}

		lastFontOpt = opt;
		lastCustom = custom;

		String id = opt == TranslateText.LYRICS_FONT_MEDIUM ? "medium"
				: opt == TranslateText.LYRICS_FONT_REGULAR ? "regular"
						: opt == TranslateText.LYRICS_FONT_MOJANGLES ? "mojangles"
								: opt == TranslateText.LYRICS_FONT_SANS ? "sans"
										: opt == TranslateText.LYRICS_FONT_SERIF ? "serif"
												: opt == TranslateText.LYRICS_FONT_MONO ? "mono"
														: opt == TranslateText.LYRICS_FONT_CUSTOM ? "custom" : "semibold";

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

	/** Durata "cantata" di una riga: fino alla successiva, al massimo 6 secondi. */
	private static long spanOf(List<LyricLine> ls, int i) {

		long start = ls.get(i).timeMs;
		long next = i + 1 < ls.size() ? ls.get(i + 1).timeMs : start + 4000L;
		return Math.max(300L, Math.min(next - start, 6000L));
	}

	/** Indice della parola in corso: -1 prima dell'inizio, numero di parole se la riga e' finita. */
	private static int currentWord(LyricLine l, double prog) {

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

	// ------------------------------------------------------------------ rendering 3D

	@EventTarget
	public void onRender3D(EventRender3D event) {

		SpotifyBridge.Snapshot s = bridge.get();
		List<LyricLine> ls = lyrics;
		Entity view = mc.getRenderViewEntity();

		if (s == null || ls.isEmpty() || mc.theWorld == null || view == null || mc.gameSettings.hideGUI) {
			return;
		}

		long now = System.nanoTime();
		double t = songTime(s, now);
		int cur = advanceIndex(ls, t);

		if (isFloating()) {

			if (fonts.isReady()) {
				drawFloating(ls, t, event.getPartialTicks(), view);
			}
		} else {
			drawPanel(ls, t, cur, event.getPartialTicks(), view, now);
		}
	}

	// ---- stile fluttuante

	private float rand() {
		rng ^= rng << 13;
		rng ^= rng >>> 7;
		rng ^= rng << 17;
		return (rng >>> 40) / 16777216f;
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static void load(float[] dst, Color c) {
		dst[0] = c.getRed() / 255f;
		dst[1] = c.getGreen() / 255f;
		dst[2] = c.getBlue() / 255f;
	}

	private void spawn(Placement p, int idx, Entity view, float pt, RenderManager rm, IMixinRenderManager im) {

		p.idx = idx;

		float sign = (idx & 1) == 0 ? 1f : -1f;
		float yawOff = sign * (0.3f + 0.7f * rand()) * spreadSetting.getValueFloat();
		float elev = heightSetting.getValueFloat() + (rand() - 0.5f) * 14f;
		float dist = distanceSetting.getValueFloat() * (0.8f + 0.45f * rand());

		float yaw = view.prevRotationYaw + MathHelper.wrapAngleTo180_float(view.rotationYaw - view.prevRotationYaw) * pt + yawOff;
		double yr = Math.toRadians(yaw), er = Math.toRadians(elev), ce = Math.cos(er);

		p.dirX = (float) -Math.sin(yr);
		p.dirZ = (float) Math.cos(yr);
		p.ax = im.getRenderPosX() + p.dirX * ce * dist;
		p.ay = im.getRenderPosY() + view.getEyeHeight() + Math.sin(er) * dist;
		p.az = im.getRenderPosZ() + p.dirZ * ce * dist;

		// la riga guarda verso dove stava la camera quando e' nata, con una inclinazione casuale
		p.faceYaw = rm.playerViewY;
		p.facePitch = rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1f : 1f);

		float tilt = tiltSetting.getValueFloat();
		p.roll = (rand() - 0.5f) * 2f * tilt;
		p.tiltY = (rand() - 0.5f) * 2f * tilt * 1.6f;
		p.tiltX = (rand() - 0.5f) * 2f * tilt * 0.6f;
		p.size = 0.85f + 0.5f * rand();
	}

	private void drawFloating(List<LyricLine> ls, double t, float pt, Entity view) {

		int n = ls.size();
		int lo = Math.max(0, lineIndex - linesSetting.getValueInt() + 1);
		int hi = Math.min(n - 1, lineIndex + 1);
		float fadeMs = fadeSetting.getValueFloat() * 1000f;

		RenderManager rm = mc.getRenderManager();
		IMixinRenderManager im = (IMixinRenderManager) rm;
		double rx = im.getRenderPosX(), ry = im.getRenderPosY(), rz = im.getRenderPosZ();
		float eye = view.getEyeHeight();

		// direzione dello sguardo, per non disegnare cio' che sta dietro
		float vYaw = view.prevRotationYaw + MathHelper.wrapAngleTo180_float(view.rotationYaw - view.prevRotationYaw) * pt;
		float vPitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * pt;
		double cp = Math.cos(Math.toRadians(vPitch));
		double vdx = -Math.sin(Math.toRadians(vYaw)) * cp;
		double vdy = -Math.sin(Math.toRadians(vPitch));
		double vdz = Math.cos(Math.toRadians(vYaw)) * cp;

		boolean hl = wordHighlightSetting.isToggled();
		boolean grad = gradientSetting.isToggled();
		boolean shadow = shadowSetting.isToggled();
		float userScale = scaleSetting.getValueFloat();

		load(gs, gradientStartSetting.getColor());
		load(ge, gradientEndSetting.getColor());
		load(cc, currentColorSetting.getColor());
		load(un, otherColorSetting.getColor());

		boolean stateSet = false;

		for (int i = lo; i <= hi; i++) {

			LyricLine l = ls.get(i);
			long start = l.timeMs;
			long span = spanOf(ls, i);
			double spanEnd = start + span;

			if (t < start - 250.0 || t > spanEnd + fadeMs) {
				continue;
			}

			Placement p = placements[i & 15];

			if (p.idx != i) {
				spawn(p, i, view, pt, rm, im);
			}

			double ax = p.ax - rx, ay = p.ay - ry, az = p.az - rz;

			if (ax * vdx + (ay - eye) * vdy + az * vdz < 0.5) {
				continue;
			}

			float fadeIn = clamp01((float) ((t - (start - 250.0)) / 350.0));
			float fo = t > spanEnd ? clamp01((float) ((t - spanEnd) / fadeMs)) : 0f;
			float alpha = (1f - (1f - fadeIn) * (1f - fadeIn)) * (1f - fo * fo);

			if (alpha < 0.01f) {
				continue;
			}

			// entrata con un piccolo rimbalzo, poi la riga rimpicciolisce e si allontana
			float c1 = 1.70158f, c3 = c1 + 1f, f1 = fadeIn - 1f;
			float life = (1f + c3 * f1 * f1 * f1 + c1 * f1 * f1) * (1f - 0.35f * fo);

			l.prepare(fonts);

			if (!stateSet) {
				stateSet = true;
				GlStateManager.enableTexture2D();
				GlStateManager.disableLighting();
				GlStateManager.disableDepth();
				GlStateManager.depthMask(false);
				GlStateManager.disableCull();
				GlStateManager.enableBlend();
				GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
			}

			float fit = l.bigTotal > MAX_LINE_PX ? MAX_LINE_PX / l.bigTotal : 1f;
			float s = WORLD_PER_PX * userScale * p.size * life * fit;

			GlStateManager.pushMatrix();
			GlStateManager.translate((float) ax + p.dirX * fo * DRIFT_BLOCKS, (float) ay + fo * 1.2f, (float) az + p.dirZ * fo * DRIFT_BLOCKS);
			GlStateManager.rotate(-(p.faceYaw + p.tiltY), 0f, 1f, 0f);
			GlStateManager.rotate(p.facePitch + p.tiltX, 1f, 0f, 0f);
			GlStateManager.rotate(p.roll, 0f, 0f, 1f);
			GlStateManager.scale(-s, -s, s);

			drawWords(l, t, start, span, alpha, hl, grad, shadow);

			GlStateManager.popMatrix();
		}

		if (stateSet) {
			GlStateManager.enableCull();
			GlStateManager.depthMask(true);
			GlStateManager.enableDepth();
			GlStateManager.disableBlend();
			GlStateManager.color(1f, 1f, 1f, 1f);
		}
	}

	private void drawWords(LyricLine l, double t, long start, double span, float alpha, boolean hl, boolean grad, boolean shadow) {

		int nW = l.words.length;
		float total = l.bigTotal;
		float half = total / 2f;
		int curWord = hl ? currentWord(l, (t - start) / span) : nW;

		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();

		for (int k = 0; k < nW; k++) {

			LyricFonts.Word w = l.bigWords[k];
			float ws = k == 0 ? 0f : l.endFrac[k - 1], we = l.endFrac[k];
			double tWs = start + ws * span, tWe = start + we * span;

			// emph: quanto la parola e' "in evidenza"; mix: da colore "non ancora cantata" a colore finale
			float emph, mix;

			if (!hl) {
				emph = 0f;
				mix = 1f;
			} else if (k < curWord) {
				emph = 1f - clamp01((float) ((t - tWe) / 220.0));
				mix = 1f;
			} else if (k == curWord) {
				emph = clamp01((float) ((t - tWs) / 120.0));
				mix = emph;
			} else {
				emph = 0f;
				mix = 0f;
			}

			float e = emph * emph * (3f - 2f * emph);
			float sc = 1f + 0.32f * e;
			float white = 0.28f * e;

			float fl = l.bigX[k] / total, fr = (l.bigX[k] + w.adv) / total;
			float a = alpha * (0.78f + 0.22f * mix);

			float rl, gl, bl, rr, gr, br;

			if (grad) {
				rl = lerp(gs[0], ge[0], fl); gl = lerp(gs[1], ge[1], fl); bl = lerp(gs[2], ge[2], fl);
				rr = lerp(gs[0], ge[0], fr); gr = lerp(gs[1], ge[1], fr); br = lerp(gs[2], ge[2], fr);
			} else {
				rl = rr = cc[0]; gl = gr = cc[1]; bl = br = cc[2];
			}

			rl = lerp(lerp(un[0], rl, mix), 1f, white); gl = lerp(lerp(un[1], gl, mix), 1f, white); bl = lerp(lerp(un[2], bl, mix), 1f, white);
			rr = lerp(lerp(un[0], rr, mix), 1f, white); gr = lerp(lerp(un[1], gr, mix), 1f, white); br = lerp(lerp(un[2], br, mix), 1f, white);

			float cx = -half + l.bigX[k] + w.adv / 2f;
			float hw = (w.adv / 2f + LyricFonts.PAD) * sc, hh = (w.h / 2f) * sc;

			GlStateManager.bindTexture(w.tex);
			wr.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);

			if (shadow) {
				quad(wr, cx - hw + 1.6f, -hh + 1.6f, cx + hw + 1.6f, hh + 1.6f, 0f, 0f, 0f, 0f, 0f, 0f, a * 0.5f);
			}

			quad(wr, cx - hw, -hh, cx + hw, hh, rl, gl, bl, rr, gr, br, a);
			tess.draw();
		}
	}

	private static void quad(WorldRenderer wr, float x0, float y0, float x1, float y1,
			float rl, float gl, float bl, float rr, float gr, float br, float a) {

		wr.pos(x0, y0, 0.0).tex(0.0, 0.0).color(rl, gl, bl, a).endVertex();
		wr.pos(x0, y1, 0.0).tex(0.0, 1.0).color(rl, gl, bl, a).endVertex();
		wr.pos(x1, y1, 0.0).tex(1.0, 1.0).color(rr, gr, br, a).endVertex();
		wr.pos(x1, y0, 0.0).tex(1.0, 0.0).color(rr, gr, br, a).endVertex();
	}

	// ---- stile pannello

	private void drawPanel(List<LyricLine> ls, double t, int cur, float pt, Entity view, long now) {

		int n = ls.size();

		// scorrimento morbido verso l'alto quando cambia riga
		float slide = 0f;

		if (cur >= 0) {

			double since = t - ls.get(cur).timeMs;

			if (since < SCROLL_MS) {
				float inv = 1f - (float) (Math.max(0.0, since) / SCROLL_MS);
				slide = inv * inv * inv;
			}
		}

		float yaw = view.prevRotationYaw + MathHelper.wrapAngleTo180_float(view.rotationYaw - view.prevRotationYaw) * pt;
		float dt = lastFrameNano == 0L ? 0f : Math.min(0.1f, (now - lastFrameNano) / 1.0E9f);
		lastFrameNano = now;

		if (!panelInit) {
			panelYaw = yaw;
			panelInit = true;
		} else if (followSetting.isToggled()) {
			panelYaw += MathHelper.wrapAngleTo180_float(yaw - panelYaw) * (1f - (float) Math.exp(-4f * dt));
		}

		// fuori dal campo visivo: non disegniamo nulla
		if (Math.abs(MathHelper.wrapAngleTo180_float(yaw - panelYaw)) > 100f) {
			return;
		}

		double yawRad = Math.toRadians(panelYaw);
		double elRad = Math.toRadians(heightSetting.getValue());
		double dist = distanceSetting.getValue();
		double cosEl = Math.cos(elRad);

		// l'origine del rendering e' ai piedi del giocatore: la camera sta all'altezza degli occhi
		float dx = (float) (-Math.sin(yawRad) * cosEl * dist);
		float dy = (float) (Math.sin(elRad) * dist) + view.getEyeHeight();
		float dz = (float) (Math.cos(yawRad) * cosEl * dist);

		int total = linesSetting.getValueInt();
		int before = (total - 1) / 2;
		int after = total - 1 - before;
		int first = Math.max(0, cur - before);
		int last = Math.min(n - 1, cur + after);

		FontRenderer font = mc.fontRendererObj;
		float maxW = 0f;

		for (int i = first; i <= last; i++) {

			LyricLine l = ls.get(i);

			if (l.width < 0) {
				l.width = font.getStringWidth(l.text);
			}

			maxW = Math.max(maxW, Math.min(l.width, MAX_TEXT_W));
		}

		float targetHalfW = maxW * 0.575f + 8f;
		panelHalfW = panelHalfW == 0f ? targetHalfW : panelHalfW + (targetHalfW - panelHalfW) * (1f - (float) Math.exp(-8f * dt));

		RenderManager rm = mc.getRenderManager();
		float scale = PANEL_SCALE * scaleSetting.getValueFloat();
		boolean shadow = shadowSetting.isToggled();
		int curRgb = currentColorSetting.getColor().getRGB() & 0xFFFFFF;
		int othRgb = otherColorSetting.getColor().getRGB() & 0xFFFFFF;

		GlStateManager.pushMatrix();
		GlStateManager.translate(dx, dy, dz);
		GL11.glNormal3f(0f, 1f, 0f);
		GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
		GlStateManager.rotate(rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1f : 1f), 1f, 0f, 0f);
		GlStateManager.scale(-scale, -scale, scale);
		GlStateManager.disableLighting();
		GlStateManager.disableDepth();
		GlStateManager.depthMask(false);
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

		if (backgroundSetting.isToggled()) {

			float y1 = -before * LINE_H - 4f;
			float y2 = after * LINE_H + 12f;

			GlStateManager.disableTexture2D();

			Tessellator tess = Tessellator.getInstance();
			WorldRenderer wr = tess.getWorldRenderer();
			wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
			wr.pos(-panelHalfW, y1, 0.0).color(0f, 0f, 0f, 0.35f).endVertex();
			wr.pos(-panelHalfW, y2, 0.0).color(0f, 0f, 0f, 0.35f).endVertex();
			wr.pos(panelHalfW, y2, 0.0).color(0f, 0f, 0f, 0.35f).endVertex();
			wr.pos(panelHalfW, y1, 0.0).color(0f, 0f, 0f, 0.35f).endVertex();
			tess.draw();

			GlStateManager.enableTexture2D();
		}

		for (int i = first; i <= last; i++) {

			LyricLine l = ls.get(i);
			int d = i - cur;
			boolean isCur = d == 0;

			int alpha = isCur ? 255 : (int) (255f * Math.max(0.2f, 1f - 0.3f * Math.abs(d)));
			int argb = (Math.max(alpha, 8) << 24) | (isCur ? curRgb : othRgb);
			float fit = l.width > MAX_TEXT_W ? MAX_TEXT_W / l.width : 1f;
			float s2 = (isCur ? 1.15f : 1f) * fit;

			GlStateManager.pushMatrix();
			GlStateManager.translate(0f, (d + slide) * LINE_H, 0f);
			GlStateManager.scale(s2, s2, 1f);
			font.drawString(l.text, -l.width / 2f, 0f, argb, shadow);
			GlStateManager.popMatrix();
		}

		GlStateManager.depthMask(true);
		GlStateManager.enableDepth();
		GlStateManager.disableBlend();
		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.popMatrix();
	}

	// ------------------------------------------------------------------ barra karaoke in alto

	@EventTarget
	public void onRender2D(EventRender2D event) {

		if (!barSetting.isToggled() || !fonts.isReady()) {
			return;
		}

		SpotifyBridge.Snapshot s = bridge.get();
		List<LyricLine> ls = lyrics;

		if (s == null || ls.isEmpty() || mc.gameSettings.hideGUI) {
			return;
		}

		long now = System.nanoTime();
		double t = songTime(s, now);
		int cur = advanceIndex(ls, t);
		int shown = Math.max(cur, 0);

		LyricLine l = ls.get(shown);
		l.ensureWords();
		LyricLine nx = shown + 1 < ls.size() ? ls.get(shown + 1) : null;

		if (nx != null) {
			nx.ensureWords();
		}

		if (scaled == null || lastDW != mc.displayWidth || lastDH != mc.displayHeight || lastGui != mc.gameSettings.guiScale) {
			scaled = new ScaledResolution(mc);
			lastDW = mc.displayWidth;
			lastDH = mc.displayHeight;
			lastGui = mc.gameSettings.guiScale;
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
		float pos = 0f;

		for (int k = 0; k < nc; k++) {
			LyricFonts.Word w = fonts.get(LyricFonts.SMALL, l.words[k]);
			barWords[k] = w;
			barX[k] = pos;
			pos += w.adv * ts + space;
		}

		pos += 12f;

		for (int k = 0; k < nn; k++) {
			LyricFonts.Word w = fonts.get(LyricFonts.SMALL, nx.words[k]);
			barWords[nc + k] = w;
			barX[nc + k] = pos;
			pos += w.adv * ts + space;
		}

		int curWord = cur < 0 ? -1 : currentWord(l, (t - l.timeMs) / (double) spanOf(ls, shown));
		float target = 0f;

		if (curWord >= 0 && curWord < nc) {
			float center = barX[curWord] + barWords[curWord].adv * ts / 2f;
			target = Math.max(0f, Math.min(center - stripW / 2f, pos - stripW));
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
