package me.eldodebug.soar.management.mods.impl;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.GL11;

import me.eldodebug.soar.Glide;
import me.eldodebug.soar.management.event.EventTarget;
import me.eldodebug.soar.management.event.impl.EventRender3D;
import me.eldodebug.soar.management.event.impl.EventTick;
import me.eldodebug.soar.management.language.TranslateText;
import me.eldodebug.soar.management.mods.Mod;
import me.eldodebug.soar.management.mods.ModCategory;
import me.eldodebug.soar.management.mods.impl.lyricsky.LyricLine;
import me.eldodebug.soar.management.mods.impl.lyricsky.LyricsProvider;
import me.eldodebug.soar.management.mods.impl.lyricsky.SpotifyBridge;
import me.eldodebug.soar.management.mods.settings.impl.BooleanSetting;
import me.eldodebug.soar.management.mods.settings.impl.ColorSetting;
import me.eldodebug.soar.management.mods.settings.impl.NumberSetting;
import me.eldodebug.soar.management.notification.NotificationType;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

/**
 * Mostra nel cielo le lyrics sincronizzate della canzone Spotify in ascolto.
 *
 * Ottimizzazioni: rete e lettura di Spotify girano su thread separati; il render thread legge solo
 * uno snapshot volatile, cerca la riga corrente con un indice che avanza (O(1) ammortizzato), cachea
 * la larghezza delle righe e non alloca oggetti per frame. Si disegnano solo le righe visibili.
 */
public class LyricsSkyMod extends Mod {

	private static final List<LyricLine> EMPTY = Collections.emptyList();

	private static final float LINE_H = 11f;
	private static final float WORLD_SCALE = 0.05f;
	private static final float MAX_TEXT_W = 240f;
	private static final double SCROLL_MS = 280.0;

	private NumberSetting distanceSetting = new NumberSetting(TranslateText.DISTANCE, this, 15, 5, 60, true);
	private NumberSetting heightSetting = new NumberSetting(TranslateText.HEIGHT, this, 35, 0, 80, true);
	private NumberSetting scaleSetting = new NumberSetting(TranslateText.SCALE, this, 1.0, 0.3, 3.0, false);
	private NumberSetting linesSetting = new NumberSetting(TranslateText.LINES_SHOWN, this, 5, 1, 9, true);
	private NumberSetting offsetSetting = new NumberSetting(TranslateText.TIMING_OFFSET, this, 0, -3000, 3000, true);
	private ColorSetting currentColorSetting = new ColorSetting(TranslateText.CURRENT_LINE_COLOR, this, new Color(255, 255, 255), false);
	private ColorSetting otherColorSetting = new ColorSetting(TranslateText.OTHER_LINES_COLOR, this, new Color(170, 170, 190), false);
	private BooleanSetting backgroundSetting = new BooleanSetting(TranslateText.BACKGROUND, this, true);
	private BooleanSetting shadowSetting = new BooleanSetting(TranslateText.SHADOW, this, true);
	private BooleanSetting followSetting = new BooleanSetting(TranslateText.FOLLOW_VIEW, this, true);

	private final SpotifyBridge bridge = new SpotifyBridge();
	private final AtomicInteger generation = new AtomicInteger();

	private volatile List<LyricLine> lyrics = EMPTY;
	private volatile boolean notFoundPending;

	private String currentKey = "";
	private int lineIndex;

	private float panelYaw, panelHalfW;
	private boolean panelInit;
	private long lastFrameNano;

	public LyricsSkyMod() {
		super(TranslateText.LYRICS_SKY, TranslateText.LYRICS_SKY_DESCRIPTION, ModCategory.RENDER, "spotify lyrics sky music");
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
	}

	@EventTarget
	public void onTick(EventTick event) {

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

	@EventTarget
	public void onRender3D(EventRender3D event) {

		SpotifyBridge.Snapshot s = bridge.get();
		List<LyricLine> ls = lyrics;
		Entity view = mc.getRenderViewEntity();

		if (s == null || ls.isEmpty() || mc.theWorld == null || view == null || mc.gameSettings.hideGUI) {
			return;
		}

		long now = System.nanoTime();

		// tempo di riproduzione: ultimo valore noto + tempo trascorso (se in play) + offset utente
		double t = s.positionMs + offsetSetting.getValue();

		if (s.playing) {
			t += (now - s.nanoStamp) / 1000000.0;
		}

		if (s.durationMs > 0 && t > s.durationMs) {
			t = s.durationMs;
		}

		// riga corrente: l'indice si muove solo di pochi passi per frame
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

		int cur = ls.get(0).timeMs > t ? -1 : lineIndex;

		// scorrimento morbido verso l'alto quando cambia riga
		float slide = 0f;

		if (cur >= 0) {

			double since = t - ls.get(cur).timeMs;

			if (since < SCROLL_MS) {
				float inv = 1f - (float) (Math.max(0.0, since) / SCROLL_MS);
				slide = inv * inv * inv;
			}
		}

		// direzione del pannello
		float pt = event.getPartialTicks();
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

		float dx = (float) (-Math.sin(yawRad) * cosEl * dist);
		float dy = (float) (Math.sin(elRad) * dist);
		float dz = (float) (Math.cos(yawRad) * cosEl * dist);

		// righe visibili
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
		float scale = WORLD_SCALE * scaleSetting.getValueFloat();
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
		GlStateManager.enableLighting();
		GlStateManager.disableBlend();
		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.popMatrix();
	}
}
