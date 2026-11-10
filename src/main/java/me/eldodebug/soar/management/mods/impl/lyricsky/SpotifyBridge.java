package me.eldodebug.soar.management.mods.impl.lyricsky;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

import me.eldodebug.soar.logger.GlideLogger;

/**
 * Legge cosa sta suonando Spotify desktop su Windows, senza API ne' account Premium.
 *
 * Un solo processo PowerShell resta aperto e stampa una riga al secondo
 * (stato, posizione, durata, artista, titolo). Prima prova le Windows Media Controls (SMTC),
 * che danno anche la posizione esatta; se non disponibili ripiega sul titolo della finestra
 * di Spotify e stima la posizione con un orologio locale.
 */
public final class SpotifyBridge {

	public static final class Snapshot {

		public final String artist, title;
		public final boolean playing;
		public final long positionMs, durationMs, nanoStamp;

		Snapshot(String artist, String title, boolean playing, long positionMs, long durationMs, long nanoStamp) {
			this.artist = artist;
			this.title = title;
			this.playing = playing;
			this.positionMs = positionMs;
			this.durationMs = durationMs;
			this.nanoStamp = nanoStamp;
		}
	}

	private static final String[] SCRIPT = {
		"param([int]$ParentPid = 0)",
		"$ErrorActionPreference = 'SilentlyContinue'",
		"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
		"$TAB = [char]9",
		"$smtc = $true",
		"try {",
		"  Add-Type -AssemblyName System.Runtime.WindowsRuntime",
		"  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]",
		"  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]",
		"  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
		"  function Await($op, $type) { $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }",
		"  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
		"} catch { $smtc = $false }",
		"while ($true) {",
		"  if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid)) { exit }",
		"  $out = ('None', 0, 0, '', '') -join $TAB",
		"  try {",
		"    $done = $false",
		"    if ($smtc -and $mgr) {",
		"      $s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1",
		"      if ($s) {",
		"        $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
		"        $tl = $s.GetTimelineProperties()",
		"        $status = $s.GetPlaybackInfo().PlaybackStatus.ToString()",
		"        $pos = $tl.Position.TotalMilliseconds",
		"        $dur = ($tl.EndTime - $tl.StartTime).TotalMilliseconds",
		"        if ($tl.LastUpdatedTime.Year -lt 2000 -or $dur -le 0) { $pos = -1 }",
		"        elseif ($status -eq 'Playing') { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }",
		"        if ($p.Title) { $out = ($status, [int64]$pos, [int64]$dur, $p.Artist, $p.Title) -join $TAB; $done = $true }",
		"      }",
		"    }",
		"    if (-not $done) {",
		"      $w = Get-Process -Name Spotify | Where-Object { $_.MainWindowTitle } | Select-Object -First 1",
		"      if ($w) {",
		"        $wt = $w.MainWindowTitle",
		"        $i = $wt.IndexOf(' - ')",
		"        if ($i -gt 0) { $out = ('Playing', -1, 0, $wt.Substring(0, $i), $wt.Substring($i + 3)) -join $TAB }",
		"        else { $out = ('Paused', -1, 0, '', '') -join $TAB }",
		"      }",
		"    }",
		"  } catch { }",
		"  [Console]::Out.WriteLine($out)",
		"  [Console]::Out.Flush()",
		"  Start-Sleep -Milliseconds 1000",
		"}"
	};

	private volatile Snapshot snapshot;
	private volatile boolean running;
	private Process process;

	// stato usato solo dal thread lettore, per stimare la posizione quando SMTC non la fornisce
	private String lastKey, lastArtist, lastTitle;
	private boolean lastPlaying;
	private long lastNano;
	private double estPos;

	public static boolean isSupported() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/** Ultima lettura, oppure null se Spotify non e' in esecuzione. */
	public Snapshot get() {
		return snapshot;
	}

	public synchronized void start() {

		if (running) {
			return;
		}

		running = true;

		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				loop();
			}
		}, "Glide-LyricsSky-Spotify");

		t.setDaemon(true);
		t.start();
	}

	public synchronized void stop() {

		running = false;
		snapshot = null;
		lastKey = null;

		Process p = process;

		if (p != null) {
			p.destroy();
		}
	}

	private void loop() {

		while (running) {

			try {
				runOnce();
			} catch (Exception e) {
				GlideLogger.info("[LyricsSky] Spotify bridge: " + e);
			}

			if (running) {
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ignored) {
					return;
				}
			}
		}
	}

	private void runOnce() throws Exception {

		File script = File.createTempFile("glide-spotify-", ".ps1");
		script.deleteOnExit();

		try (Writer w = new OutputStreamWriter(new FileOutputStream(script), StandardCharsets.UTF_8)) {
			for (String line : SCRIPT) {
				w.write(line);
				w.write("\r\n");
			}
		}

		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath(),
				"-ParentPid", pid());
		pb.redirectErrorStream(true);

		Process p = pb.start();
		process = p;

		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

			String line;

			while (running && (line = r.readLine()) != null) {
				handle(line);
			}
		} finally {
			p.destroy();
			script.delete();
		}
	}

	private void handle(String line) {

		String[] f = line.split("\t", -1);

		if (f.length < 5) {
			return;
		}

		if (f[0].equals("None")) {
			snapshot = null;
			lastKey = null;
			return;
		}

		boolean playing = f[0].equals("Playing");
		long pos = parseLong(f[1], -1), dur = parseLong(f[2], 0);
		String artist = f[3].trim(), title = f[4].trim();

		if (title.isEmpty()) {
			// in pausa col solo titolo finestra: teniamo l'ultima canzone nota
			if (lastTitle == null) {
				return;
			}
			artist = lastArtist;
			title = lastTitle;
			playing = false;
		}

		long now = System.nanoTime();
		String key = artist + '\u0000' + title;

		if (pos < 0) {

			if (!key.equals(lastKey)) {
				estPos = 0;
			} else if (lastPlaying) {
				estPos += (now - lastNano) / 1000000.0;
			}

			pos = (long) estPos;
		}

		lastKey = key;
		lastArtist = artist;
		lastTitle = title;
		lastNano = now;
		lastPlaying = playing;

		if (dur > 0 && pos > dur) {
			pos = dur;
		}

		snapshot = new Snapshot(artist, title, playing, Math.max(pos, 0), dur, now);
	}

	private static long parseLong(String s, long fallback) {
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String pid() {

		String name = ManagementFactory.getRuntimeMXBean().getName();
		int at = name.indexOf('@');
		return at > 0 ? name.substring(0, at) : "0";
	}
}
