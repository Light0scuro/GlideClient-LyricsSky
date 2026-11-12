package me.eldodebug.soar.management.mods.impl.lyricsky;

/**
 * Geometria del testo "scritto nel cielo": le parole stanno su una cupola attorno al giocatore.
 * Coordinate come nel rendering di Minecraft (origine ai piedi, la camera e' all'altezza degli occhi);
 * yaw 0 guarda verso +Z e ruotare a destra fa AUMENTARE lo yaw.
 */
public final class SkyMath {

	private SkyMath() {
	}

	/**
	 * Posizione di una parola sulla cupola. cx/cy sono in pixel di testo (cy positivo = verso il basso),
	 * s e' la dimensione in unita' di mondo per pixel. Scrive in out: x, y, z, yaw della parola.
	 */
	public static void curved(float[] out, float yawC, float elevDeg, float dist, float cx, float cy, float s, float tanRoll, float eye) {

		float yawK = yawC + (float) Math.toDegrees((cx * s) / dist);
		double yr = Math.toRadians(yawK), er = Math.toRadians(elevDeg), ce = Math.cos(er);

		out[0] = (float) (-Math.sin(yr) * ce * dist);
		out[1] = eye + (float) (Math.sin(er) * dist) - cy * s - cx * s * tanRoll;
		out[2] = (float) (Math.cos(yr) * ce * dist);
		out[3] = yawK;
	}

	/** Come curved(), per un'intera riga piatta (tutte le parole sullo stesso piano). */
	public static void flat(float[] out, float yawC, float elevDeg, float dist, float eye) {
		curved(out, yawC, elevDeg, dist, 0f, 0f, 0f, 0f, eye);
	}
}
