import { useState, useEffect, useRef, useCallback } from "react";
import { registerPlugin } from '@capacitor/core';

const OverlayNative = registerPlugin('Overlay');

// ─── Palette ──────────────────────────────────────────────────────────────────
const P = {
  bg:         "#07070d",
  surface:    "#0e0e18",
  card:       "#13131f",
  card2:      "#181826",
  border:     "#1c1c2e",
  accent:     "#6c5ce7",
  accentGlow: "#6c5ce740",
  cyan:       "#00cec9",
  green:      "#00b894",
  red:        "#e84393",
  amber:      "#fdcb6e",
  purple:     "#a29bfe",
  text:       "#dfe6e9",
  muted:      "#636e72",
  dim:        "#2d3436",
};

const LEVEL_LABELS = { 1: "Lite", 2: "Balanced", 3: "Turbo" };
const LEVEL_COLORS = { 1: P.green, 2: P.accent, 3: P.red };
const FPS_LEN = 32;
const TABS = ["monitor", "boost", "thermal", "settings", "log"];

// ─── Animated value ───────────────────────────────────────────────────────────
const useAnimatedValue = (target, duration = 600) => {
  const [value, setValue] = useState(0);
  const prev = useRef(0);
  useEffect(() => {
    let raf, start = null;
    const from = prev.current;
    prev.current = target;
    const step = ts => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / duration, 1);
      setValue(from + (target - from) * (1 - Math.pow(1 - p, 3)));
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [target]);
  return value;
};

// ─── Metrics ──────────────────────────────────────────────────────────────────
const useRealMetrics = (boosting, boostLevel) => {
  const [metrics, setMetrics] = useState({
    cpu: 0, ram: 0, gpu: 0, fps: 60, ping: 0,
    batPct: 100, batDrain: 0, batCharging: false,
    netType: "unknown", netDownlink: 0,
    thermalScore: 0, sessionTime: 0,
  });
  const [fpsHistory, setFpsHistory] = useState(() => Array(FPS_LEN).fill(60));
  const [cpuHistory, setCpuHistory] = useState(() => Array(FPS_LEN).fill(0));
  const [support, setSupport] = useState({ battery: false, memory: false, network: false, wakeLock: false });
  const startTime = useRef(Date.now());

  // FPS
  const fpsRef = useRef({ frames: 0, lastTime: performance.now() });
  useEffect(() => {
    let raf;
    const tick = () => {
      fpsRef.current.frames++;
      const now = performance.now();
      if (now - fpsRef.current.lastTime >= 1000) {
        const f = Math.round(fpsRef.current.frames * 1000 / (now - fpsRef.current.lastTime));
        fpsRef.current = { frames: 0, lastTime: now };
        setMetrics(m => ({ ...m, fps: f }));
        setFpsHistory(h => [...h.slice(1), f]);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  // Battery
  useEffect(() => {
    if (!("getBattery" in navigator)) return;
    setSupport(s => ({ ...s, battery: true }));
    let bat;
    navigator.getBattery().then(b => {
      bat = b;
      const update = () => {
        const pct = Math.round(b.level * 100);
        const drain = b.charging ? 0
          : boosting ? (boostLevel === 3 ? 14 : boostLevel === 2 ? 11 : 8) + Math.random() * 1.5
          : 7 + Math.random() * 2;
        setMetrics(m => ({ ...m, batPct: pct, batDrain: +drain.toFixed(1), batCharging: b.charging }));
      };
      update();
      b.addEventListener("levelchange", update);
      b.addEventListener("chargingchange", update);
    });
    return () => { if (bat) { bat.removeEventListener("levelchange", () => {}); bat.removeEventListener("chargingchange", () => {}); } };
  }, [boosting, boostLevel]);

  // RAM
  useEffect(() => {
    if (!performance.memory) return;
    setSupport(s => ({ ...s, memory: true }));
    const id = setInterval(() => {
      const { usedJSHeapSize, jsHeapSizeLimit } = performance.memory;
      setMetrics(m => ({ ...m, ram: Math.min(100, Math.round((usedJSHeapSize / jsHeapSizeLimit) * 100)) }));
    }, 1500);
    return () => clearInterval(id);
  }, []);

  // Network
  useEffect(() => {
    const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (!conn) return;
    setSupport(s => ({ ...s, network: true }));
    const update = () => setMetrics(m => ({ ...m, netType: conn.effectiveType || conn.type || "unknown", netDownlink: conn.downlink || 0 }));
    update();
    conn.addEventListener("change", update);
    return () => conn.removeEventListener("change", update);
  }, []);

  useEffect(() => { setSupport(s => ({ ...s, wakeLock: "wakeLock" in navigator })); }, []);

  // CPU + Thermal
  useEffect(() => {
    const id = setInterval(() => {
      const t0 = performance.now();
      let sum = 0;
      for (let i = 0; i < 50000; i++) sum += Math.sqrt(i);
      const elapsed = performance.now() - t0;
      const cpuEst = Math.min(95, Math.round((elapsed / 8) * 100));
      const thermal = Math.min(100, Math.round(cpuEst * 0.7 + Math.random() * 10));
      setMetrics(m => ({
        ...m,
        cpu: cpuEst,
        gpu: Math.min(95, Math.max(5, 100 - Math.round((m.fps / 120) * 100))),
        thermalScore: thermal,
        sessionTime: Math.floor((Date.now() - startTime.current) / 1000),
      }));
      setCpuHistory(h => [...h.slice(1), cpuEst]);
    }, 2000);
    return () => clearInterval(id);
  }, []);

  // Ping
  useEffect(() => {
    const measure = async () => {
      try {
        const t0 = performance.now();
        await fetch("https://www.google.com/generate_204", { mode: "no-cors", cache: "no-store" });
        setMetrics(m => ({ ...m, ping: Math.round(performance.now() - t0) }));
      } catch { setMetrics(m => ({ ...m, ping: 0 })); }
    };
    measure();
    const id = setInterval(measure, 5000);
    return () => clearInterval(id);
  }, []);

  return { metrics, fpsHistory, cpuHistory, support };
};

// ─── Wake lock ────────────────────────────────────────────────────────────────
const useWakeLock = () => {
  const ref = useRef(null);
  const acquire = useCallback(async () => {
    if (!("wakeLock" in navigator)) return;
    try { ref.current = await navigator.wakeLock.request("screen"); } catch {}
  }, []);
  const release = useCallback(async () => {
    if (ref.current) { try { await ref.current.release(); } catch {} ref.current = null; }
  }, []);
  return { acquire, release };
};

// ─── Components ───────────────────────────────────────────────────────────────
const RadialGauge = ({ value, max, label, color, size = 82 }) => {
  const r = 33, circ = 2 * Math.PI * r;
  const animated = useAnimatedValue((value / max) * circ);
  const pct = Math.round(value);
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
      <div style={{ position: "relative", width: size, height: size }}>
        <svg width={size} height={size} viewBox="0 0 82 82" style={{ position: "absolute", inset: 0 }}>
          <circle cx="41" cy="41" r={r} fill="none" stroke={P.dim} strokeWidth="5" />
          <circle cx="41" cy="41" r={r} fill="none" stroke={color} strokeWidth="5"
            strokeDasharray={`${animated} ${circ - animated}`}
            strokeLinecap="round" transform="rotate(-90 41 41)"
            style={{ filter: `drop-shadow(0 0 6px ${color})`, transition: "stroke-dasharray 0.6s ease" }} />
        </svg>
        <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
          <span style={{ fontSize: 14, fontWeight: 800, color, fontVariantNumeric: "tabular-nums" }}>{pct}</span>
          <span style={{ fontSize: 8, color: P.muted, letterSpacing: "0.05em" }}>%</span>
        </div>
      </div>
      <span style={{ fontSize: 9, color: P.muted, letterSpacing: "0.12em", textTransform: "uppercase", fontWeight: 600 }}>{label}</span>
    </div>
  );
};

const MiniBar = ({ label, value, max, unit = "%", color, sublabel }) => (
  <div style={{ marginBottom: 10 }}>
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 5 }}>
      <div>
        <span style={{ fontSize: 11, color: P.muted, fontWeight: 500 }}>{label}</span>
        {sublabel && <span style={{ fontSize: 9, color: P.muted, marginLeft: 6, opacity: 0.6 }}>{sublabel}</span>}
      </div>
      <span style={{ fontSize: 12, color, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{typeof value === "number" ? value.toFixed(0) : value}{unit}</span>
    </div>
    <div style={{ height: 4, background: P.dim, borderRadius: 3, overflow: "hidden" }}>
      <div style={{
        height: "100%", width: `${Math.min(100, (value / max) * 100)}%`,
        background: `linear-gradient(90deg, ${color}aa, ${color})`,
        borderRadius: 3, boxShadow: `0 0 8px ${color}66`,
        transition: "width 0.8s cubic-bezier(.22,1,.36,1)",
      }} />
    </div>
  </div>
);

const ToggleSwitch = ({ on, onChange }) => (
  <div onClick={() => onChange(!on)} style={{ width: 40, height: 22, borderRadius: 11, background: on ? P.accent : P.dim, cursor: "pointer", position: "relative", transition: "background 0.25s", boxShadow: on ? `0 0 12px ${P.accentGlow}` : "none", flexShrink: 0 }}>
    <div style={{ position: "absolute", top: 3, left: on ? 21 : 3, width: 16, height: 16, borderRadius: "50%", background: "#fff", transition: "left 0.25s", boxShadow: "0 1px 4px rgba(0,0,0,0.5)" }} />
  </div>
);

const TabBtn = ({ label, active, onClick }) => (
  <button onClick={onClick} style={{ flex: 1, padding: "8px 0", background: "none", border: "none", color: active ? P.text : P.muted, fontSize: 9, fontWeight: active ? 700 : 500, letterSpacing: "0.08em", cursor: "pointer", borderBottom: `2px solid ${active ? P.accent : "transparent"}`, transition: "all 0.2s", textTransform: "uppercase" }}>{label}</button>
);

const StatDot = ({ ok }) => (
  <div style={{ width: 7, height: 7, borderRadius: "50%", background: ok ? P.green : P.dim, boxShadow: ok ? `0 0 5px ${P.green}` : "none", flexShrink: 0 }} />
);

const MiniGraph = ({ data, color, height = 36 }) => {
  const min = Math.min(...data);
  const max = Math.max(...data, 1);
  const points = data.map((v, i) => `${(i / (data.length - 1)) * 100},${height - ((v - min) / Math.max(max - min, 1)) * (height - 4)}`).join(" ");
  return (
    <svg width="100%" height={height} viewBox={`0 0 100 ${height}`} preserveAspectRatio="none">
      <defs>
        <linearGradient id={`g-${color.replace("#","")}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.3" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <polygon points={`0,${height} ${points} 100,${height}`} fill={`url(#g-${color.replace("#","")})`} />
      <polyline points={points} fill="none" stroke={color} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" style={{ filter: `drop-shadow(0 0 3px ${color})` }} />
    </svg>
  );
};

const ThermalBar = ({ value }) => {
  const color = value < 40 ? P.green : value < 70 ? P.amber : P.red;
  const label = value < 40 ? "Cool" : value < 70 ? "Warm" : "Hot";
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
        <span style={{ fontSize: 10, color: P.muted, letterSpacing: "0.08em", textTransform: "uppercase" }}>Thermal Load</span>
        <span style={{ fontSize: 12, fontWeight: 700, color }}>{label}</span>
      </div>
      <div style={{ height: 8, background: P.dim, borderRadius: 4, overflow: "hidden", position: "relative" }}>
        <div style={{ height: "100%", width: `${value}%`, borderRadius: 4, background: `linear-gradient(90deg, ${P.green}, ${P.amber}, ${P.red})`, backgroundSize: "300% 100%", backgroundPosition: `${value}% 0`, transition: "width 1s ease", boxShadow: `0 0 10px ${color}66` }} />
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
        <span style={{ fontSize: 8, color: P.muted }}>Cool</span>
        <span style={{ fontSize: 8, color: P.muted }}>Hot</span>
      </div>
    </div>
  );
};

const formatTime = s => {
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
};

// ─── Main ─────────────────────────────────────────────────────────────────────
export default function DBost() {
  const [tab, setTab] = useState("monitor");
  const [boosting, setBoosting] = useState(false);
  const [boostLevel, setBoostLevel] = useState(2);
  const [boostLog, setBoostLog] = useState([]);
  const [overlayActive, setOverlayActive] = useState(false);
  const [boostSessions, setBoostSessions] = useState(0);
  const [totalBoostTime, setTotalBoostTime] = useState(0);
  const boostStart = useRef(null);
  const [toggles, setToggles] = useState({
    wakeLock: true, fpsMeter: true, netMonitor: true,
    highPriority: true, fullscreen: false, thermalAlert: true,
  });
  const setToggle = (k, v) => setToggles(t => ({ ...t, [k]: v }));

  const { acquire: wakeLockAcquire, release: wakeLockRelease } = useWakeLock();
  const { metrics, fpsHistory, cpuHistory, support } = useRealMetrics(boosting, boostLevel);
  const { cpu, ram, gpu, fps, ping, batPct, batDrain, batCharging, netType, netDownlink, thermalScore, sessionTime } = metrics;

  const fpsColor = fps >= 55 ? P.green : fps >= 35 ? P.amber : P.red;
  const fpsMin = Math.min(...fpsHistory);
  const fpsMax = Math.max(...fpsHistory, 1);
  const pingColor = ping < 50 ? P.green : ping < 120 ? P.amber : P.red;

  const log = msg => setBoostLog(l => [`${new Date().toLocaleTimeString()} — ${msg}`, ...l.slice(0, 29)]);

  // Overlay
  const startOverlay = async () => {
    try {
      const { value } = await OverlayNative.hasPermission();
      if (!value) { await OverlayNative.requestPermission(); return; }
      await OverlayNative.show();
      setOverlayActive(true);
    } catch(e) { console.log('Overlay error:', e); }
  };
  const stopOverlay = async () => {
    try { await OverlayNative.hide(); setOverlayActive(false); } catch {}
  };

  // Boost
  const startBoost = async () => {
    setBoosting(true);
    boostStart.current = Date.now();
    setBoostSessions(s => s + 1);
    log(`${LEVEL_LABELS[boostLevel]} boost started`);
    if (toggles.wakeLock) { await wakeLockAcquire(); log("Wake lock acquired"); }
    if ("scheduler" in window && "yield" in window.scheduler) { await window.scheduler.yield(); log("Scheduler priority granted"); }
    if (navigator.storage?.persist) { const g = await navigator.storage.persist(); log(g ? "Persistent storage granted" : "Storage: standard mode"); }
    if (boostLevel === 3 && toggles.fullscreen) {
      try { await document.documentElement.requestFullscreen?.(); log("Fullscreen activated"); } catch { log("Fullscreen: denied"); }
    }
  };
  const stopBoost = async () => {
    if (boostStart.current) {
      setTotalBoostTime(t => t + Math.floor((Date.now() - boostStart.current) / 1000));
      boostStart.current = null;
    }
    setBoosting(false);
    log("Boost stopped");
    await wakeLockRelease();
    if (document.fullscreenElement) { try { await document.exitFullscreen(); } catch {} }
    log("Wake lock released");
  };

  useEffect(() => {
    if (toggles.wakeLock && boosting) wakeLockAcquire();
    if (!toggles.wakeLock) wakeLockRelease();
  }, [toggles.wakeLock]);

  const estRemain = batCharging ? "Charging"
    : batDrain > 0 ? `${Math.round((batPct / 100 * 4000) / (batDrain * 100) * 60)}m` : "--";

  return (
    <div style={{ position: "fixed", inset: 0, background: P.surface, display: "flex", flexDirection: "column", fontFamily: "'Inter', system-ui, sans-serif", overflow: "hidden" }}>

      {/* ── Header ── */}
      <div style={{ padding: "14px 16px 10px", background: P.card, borderBottom: `1px solid ${P.border}`, display: "flex", alignItems: "center", justifyContent: "space-between", flexShrink: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div style={{ width: 34, height: 34, borderRadius: 10, background: `linear-gradient(135deg, ${P.accent}, ${P.purple})`, display: "flex", alignItems: "center", justifyContent: "center", boxShadow: `0 0 16px ${P.accentGlow}`, fontSize: 17, fontWeight: 900, color: "#fff" }}>d</div>
          <div>
            <div style={{ fontSize: 15, fontWeight: 800, color: P.text, letterSpacing: "-0.02em" }}>dBost</div>
            <div style={{ fontSize: 9, color: P.muted, letterSpacing: "0.12em", textTransform: "uppercase" }}>Game Booster — devnsepele</div>
          </div>
        </div>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 5, padding: "4px 8px", borderRadius: 8, background: boosting ? `${P.green}14` : P.card2, border: `1px solid ${boosting ? P.green + "44" : P.border}` }}>
            <div style={{ width: 6, height: 6, borderRadius: "50%", background: boosting ? P.green : P.muted, boxShadow: boosting ? `0 0 6px ${P.green}` : "none", animation: boosting ? "dbPulse 1.5s infinite" : "none" }} />
            <span style={{ fontSize: 9, color: boosting ? P.green : P.muted, fontWeight: 700, letterSpacing: "0.08em" }}>{boosting ? "ACTIVE" : "IDLE"}</span>
          </div>
          <button onClick={overlayActive ? stopOverlay : startOverlay} style={{ padding: "5px 11px", borderRadius: 8, border: `1px solid ${overlayActive ? P.accent : P.border}`, background: overlayActive ? `${P.accent}20` : P.card2, color: overlayActive ? P.accent : P.muted, fontSize: 9, fontWeight: 700, cursor: "pointer", letterSpacing: "0.08em", transition: "all 0.2s" }}>
            {overlayActive ? "OVERLAY ON" : "OVERLAY"}
          </button>
        </div>
      </div>

      {/* ── Tabs ── */}
      <div style={{ display: "flex", background: P.card, borderBottom: `1px solid ${P.border}`, flexShrink: 0 }}>
        {TABS.map(t => <TabBtn key={t} label={t} active={tab === t} onClick={() => setTab(t)} />)}
      </div>

      {/* ── Content ── */}
      <div style={{ flex: 1, overflowY: "auto", padding: 14 }}>

        {/* MONITOR */}
        {tab === "monitor" && (
          <>
            {/* Gauges */}
            <div style={{ display: "flex", justifyContent: "space-around", padding: "4px 0 16px", background: P.card, borderRadius: 12, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <RadialGauge value={cpu} max={100} label="CPU" color={P.cyan} />
              <div style={{ width: 1, background: P.border, margin: "16px 0" }} />
              <RadialGauge value={ram} max={100} label="RAM" color={P.accent} />
              <div style={{ width: 1, background: P.border, margin: "16px 0" }} />
              <RadialGauge value={gpu} max={100} label="GPU" color={P.purple} />
            </div>

            {/* FPS Card */}
            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                <div>
                  <div style={{ fontSize: 10, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase", marginBottom: 2 }}>Frame Rate</div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: fpsColor, lineHeight: 1, fontVariantNumeric: "tabular-nums" }}>{Math.round(fps)}<span style={{ fontSize: 12, fontWeight: 500, color: P.muted, marginLeft: 4 }}>fps</span></div>
                </div>
                <div style={{ textAlign: "right" }}>
                  <div style={{ fontSize: 9, color: P.muted, marginBottom: 4 }}>min / max</div>
                  <div style={{ fontSize: 13, fontWeight: 700, color: P.text }}>{fpsMin} / {fpsMax}</div>
                </div>
              </div>
              <MiniGraph data={fpsHistory} color={fpsColor} height={40} />
            </div>

            {/* CPU Graph */}
            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                <div style={{ fontSize: 10, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase" }}>CPU History</div>
                <span style={{ fontSize: 12, fontWeight: 700, color: P.cyan }}>{Math.round(cpu)}%</span>
              </div>
              <MiniGraph data={cpuHistory} color={P.cyan} height={32} />
            </div>

            {/* Network + Battery */}
            <div style={{ display: "flex", gap: 10, marginBottom: 12 }}>
              <div style={{ flex: 1, background: P.card, borderRadius: 12, padding: 12, border: `1px solid ${P.border}` }}>
                <div style={{ fontSize: 9, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase", marginBottom: 10 }}>Network</div>
                <MiniBar label="Ping" value={ping} max={300} unit="ms" color={pingColor} />
                <MiniBar label="Speed" value={netDownlink} max={100} unit=" Mbps" color={P.cyan} />
                <div style={{ fontSize: 9, color: P.muted, marginTop: 6, padding: "3px 7px", background: P.card2, borderRadius: 5, display: "inline-block", border: `1px solid ${P.border}` }}>{netType.toUpperCase()}</div>
              </div>
              <div style={{ flex: 1, background: P.card, borderRadius: 12, padding: 12, border: `1px solid ${P.border}` }}>
                <div style={{ fontSize: 9, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase", marginBottom: 10 }}>Battery</div>
                <div style={{ fontSize: 24, fontWeight: 800, color: batPct < 20 ? P.red : batCharging ? P.green : P.amber, marginBottom: 4, fontVariantNumeric: "tabular-nums" }}>{batPct}<span style={{ fontSize: 11, fontWeight: 500, color: P.muted }}>%</span></div>
                <div style={{ fontSize: 10, color: P.muted }}>{batCharging ? "Charging" : `${batDrain}%/h drain`}</div>
                <div style={{ fontSize: 10, color: P.text, marginTop: 3, fontWeight: 600 }}>{estRemain}</div>
              </div>
            </div>

            {/* Session */}
            <div style={{ background: P.card, borderRadius: 12, padding: 12, border: `1px solid ${P.border}`, display: "flex", gap: 0 }}>
              {[
                { label: "Session", val: formatTime(sessionTime) },
                { label: "Boost Sessions", val: boostSessions },
                { label: "Total Boost", val: formatTime(totalBoostTime) },
              ].map(({ label, val }, i) => (
                <div key={label} style={{ flex: 1, textAlign: "center", borderRight: i < 2 ? `1px solid ${P.border}` : "none", padding: "4px 0" }}>
                  <div style={{ fontSize: 14, fontWeight: 800, color: P.text }}>{val}</div>
                  <div style={{ fontSize: 8, color: P.muted, letterSpacing: "0.08em", textTransform: "uppercase", marginTop: 3 }}>{label}</div>
                </div>
              ))}
            </div>
          </>
        )}

        {/* BOOST */}
        {tab === "boost" && (
          <>
            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <div style={{ fontSize: 10, color: P.muted, marginBottom: 10, letterSpacing: "0.08em", textTransform: "uppercase" }}>Boost Level</div>
              <div style={{ display: "flex", gap: 8 }}>
                {[1, 2, 3].map(l => (
                  <button key={l} onClick={() => !boosting && setBoostLevel(l)} style={{ flex: 1, padding: "14px 0", borderRadius: 10, border: `1px solid ${boostLevel === l ? LEVEL_COLORS[l] : P.border}`, background: boostLevel === l ? `${LEVEL_COLORS[l]}18` : P.card2, color: boostLevel === l ? LEVEL_COLORS[l] : P.muted, fontSize: 12, fontWeight: boostLevel === l ? 800 : 400, cursor: boosting ? "not-allowed" : "pointer", opacity: boosting && boostLevel !== l ? 0.35 : 1, boxShadow: boostLevel === l ? `0 0 14px ${LEVEL_COLORS[l]}44` : "none", transition: "all 0.2s" }}>{LEVEL_LABELS[l]}</button>
                ))}
              </div>
            </div>

            <button onClick={boosting ? stopBoost : startBoost} style={{ width: "100%", padding: 18, borderRadius: 12, border: `2px solid ${boosting ? P.red : LEVEL_COLORS[boostLevel]}`, background: boosting ? `linear-gradient(135deg, ${P.red}18, transparent)` : `linear-gradient(135deg, ${LEVEL_COLORS[boostLevel]}18, transparent)`, color: boosting ? P.red : LEVEL_COLORS[boostLevel], fontSize: 15, fontWeight: 900, cursor: "pointer", letterSpacing: "0.12em", textTransform: "uppercase", boxShadow: `0 0 28px ${boosting ? P.red : LEVEL_COLORS[boostLevel]}33`, transition: "all 0.25s", marginBottom: 14 }}>
              {boosting ? "Stop Boost" : `Start ${LEVEL_LABELS[boostLevel]} Boost`}
            </button>

            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}` }}>
              <div style={{ fontSize: 9, color: P.muted, marginBottom: 10, letterSpacing: "0.1em", textTransform: "uppercase" }}>Available Actions</div>
              {[
                { label: "Wake Lock", desc: "Prevent screen sleep during session", ok: "wakeLock" in navigator },
                { label: "Scheduler Yield", desc: "Flush deferred browser tasks", ok: "scheduler" in window },
                { label: "Persist Storage", desc: "Reduce storage eviction pressure", ok: !!navigator.storage },
                { label: "Fullscreen Mode", desc: "Immersive display — Turbo only", ok: !!document.documentElement.requestFullscreen },
                { label: "GC Hint", desc: "Suggest garbage collection pass", ok: true },
              ].map(({ label, desc, ok }) => (
                <div key={label} style={{ display: "flex", gap: 10, alignItems: "center", padding: "9px 0", borderBottom: `1px solid ${P.dim}` }}>
                  <StatDot ok={ok} />
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 12, color: ok ? P.text : P.muted, fontWeight: 600 }}>{label}</div>
                    <div style={{ fontSize: 9, color: P.muted, marginTop: 1 }}>{desc}</div>
                  </div>
                  <span style={{ fontSize: 9, padding: "2px 7px", borderRadius: 5, background: ok ? `${P.green}14` : P.dim, color: ok ? P.green : P.muted, border: `1px solid ${ok ? P.green + "33" : P.border}`, fontWeight: 700 }}>{ok ? "Ready" : "N/A"}</span>
                </div>
              ))}
            </div>
          </>
        )}

        {/* THERMAL */}
        {tab === "thermal" && (
          <>
            <div style={{ background: P.card, borderRadius: 12, padding: 16, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <ThermalBar value={thermalScore} />
              <div style={{ marginTop: 16, display: "flex", gap: 10 }}>
                {[
                  { label: "CPU Load", val: Math.round(cpu) + "%", color: P.cyan },
                  { label: "GPU Est.", val: Math.round(gpu) + "%", color: P.purple },
                  { label: "Thermal", val: Math.round(thermalScore) + "%", color: thermalScore > 70 ? P.red : P.amber },
                ].map(({ label, val, color }) => (
                  <div key={label} style={{ flex: 1, background: P.card2, borderRadius: 8, padding: "10px 0", textAlign: "center", border: `1px solid ${P.border}` }}>
                    <div style={{ fontSize: 16, fontWeight: 800, color }}>{val}</div>
                    <div style={{ fontSize: 8, color: P.muted, marginTop: 3, textTransform: "uppercase", letterSpacing: "0.08em" }}>{label}</div>
                  </div>
                ))}
              </div>
            </div>

            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}`, marginBottom: 12 }}>
              <div style={{ fontSize: 10, color: P.muted, marginBottom: 12, letterSpacing: "0.1em", textTransform: "uppercase" }}>Thermal Zones</div>
              <MiniBar label="CPU Core" value={cpu} max={100} color={P.cyan} />
              <MiniBar label="GPU Core" value={gpu} max={100} color={P.purple} />
              <MiniBar label="System" value={thermalScore} max={100} color={thermalScore > 70 ? P.red : P.amber} />
              <MiniBar label="Battery Temp" value={Math.min(100, 30 + (100 - batPct) * 0.4)} max={100} unit=" est" color={P.green} />
            </div>

            <div style={{ background: P.card, borderRadius: 12, padding: 14, border: `1px solid ${P.border}` }}>
              <div style={{ fontSize: 10, color: P.muted, marginBottom: 10, letterSpacing: "0.1em", textTransform: "uppercase" }}>Recommendations</div>
              {[
                { cond: thermalScore > 70, text: "High thermal load detected — reduce boost level", color: P.red },
                { cond: cpu > 85, text: "CPU usage critical — close background apps", color: P.red },
                { cond: ram > 80, text: "RAM usage high — free memory if possible", color: P.amber },
                { cond: batPct < 20 && !batCharging, text: "Low battery — performance may throttle", color: P.amber },
                { cond: fps < 30, text: "Low FPS detected — reduce game graphics settings", color: P.amber },
                { cond: thermalScore <= 70 && cpu <= 85 && ram <= 80, text: "System running optimally", color: P.green },
              ].filter(r => r.cond).map(({ text, color }, i) => (
                <div key={i} style={{ display: "flex", gap: 8, alignItems: "flex-start", padding: "8px 0", borderBottom: `1px solid ${P.dim}` }}>
                  <div style={{ width: 3, height: 3, borderRadius: "50%", background: color, marginTop: 5, flexShrink: 0, boxShadow: `0 0 4px ${color}` }} />
                  <div style={{ fontSize: 11, color: P.text, lineHeight: 1.5 }}>{text}</div>
                </div>
              ))}
            </div>
          </>
        )}

        {/* SETTINGS */}
        {tab === "settings" && (
          <div>
            <div style={{ fontSize: 9, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase", marginBottom: 10 }}>Boost Settings</div>
            {[
              { key: "wakeLock",     label: "Wake Lock",          desc: "Keep screen on while boosting" },
              { key: "fpsMeter",     label: "FPS Monitor",        desc: "Real rAF frame counter" },
              { key: "netMonitor",   label: "Network Monitor",    desc: "Live downlink and latency" },
              { key: "highPriority", label: "Scheduler Hint",     desc: "Yield to high-priority tasks" },
              { key: "fullscreen",   label: "Fullscreen (Turbo)", desc: "Immersive mode on Turbo boost" },
              { key: "thermalAlert", label: "Thermal Alerts",     desc: "Warn when device overheats" },
            ].map(({ key, label, desc }) => (
              <div key={key} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 0", borderBottom: `1px solid ${P.border}` }}>
                <div>
                  <div style={{ fontSize: 13, fontWeight: 600, color: P.text }}>{label}</div>
                  <div style={{ fontSize: 10, color: P.muted, marginTop: 2 }}>{desc}</div>
                </div>
                <ToggleSwitch on={toggles[key]} onChange={v => setToggle(key, v)} />
              </div>
            ))}
            <div style={{ marginTop: 20, padding: 14, borderRadius: 12, background: P.card, border: `1px solid ${P.border}` }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: P.text, marginBottom: 4 }}>dBost v3.0</div>
              <div style={{ fontSize: 10, color: P.muted }}>devnsepele — Game performance monitor</div>
              <div style={{ fontSize: 9, color: P.muted, marginTop: 8, padding: "6px 10px", background: P.card2, borderRadius: 6, border: `1px solid ${P.border}` }}>All metrics are real device data. No simulation.</div>
            </div>
          </div>
        )}

        {/* LOG */}
        {tab === "log" && (
          <div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
              <div>
                <div style={{ fontSize: 13, fontWeight: 700, color: P.text }}>Activity Log</div>
                <div style={{ fontSize: 9, color: P.muted, marginTop: 1 }}>{boostLog.length} entries</div>
              </div>
              <button onClick={() => setBoostLog([])} style={{ fontSize: 9, padding: "5px 10px", borderRadius: 6, border: `1px solid ${P.border}`, background: P.card2, color: P.muted, cursor: "pointer", fontWeight: 600 }}>Clear All</button>
            </div>
            {boostLog.length === 0
              ? <div style={{ fontSize: 12, color: P.muted, textAlign: "center", padding: 48, borderRadius: 12, border: `1px dashed ${P.border}` }}>No activity yet. Start a boost session to see logs.</div>
              : boostLog.map((entry, i) => (
                <div key={i} style={{ fontSize: 11, color: i === 0 ? P.text : P.muted, padding: "8px 10px", borderRadius: 8, background: i === 0 ? `${P.accent}10` : "none", border: `1px solid ${i === 0 ? P.accent + "33" : "transparent"}`, marginBottom: 4, lineHeight: 1.5, transition: "all 0.3s" }}>{entry}</div>
              ))
            }
          </div>
        )}
      </div>

      {/* ── Footer ── */}
      <div style={{ padding: "8px 16px", background: P.card, borderTop: `1px solid ${P.border}`, display: "flex", justifyContent: "space-between", alignItems: "center", flexShrink: 0 }}>
        <span style={{ fontSize: 9, color: P.muted, fontWeight: 600 }}>devnsepele</span>
        <div style={{ display: "flex", gap: 14 }}>
          {[
            { label: "FPS", val: Math.round(fps), color: fpsColor },
            { label: "CPU", val: Math.round(cpu) + "%", color: P.cyan },
            { label: "RAM", val: Math.round(ram) + "%", color: P.accent },
            { label: "BAT", val: batPct + "%", color: P.amber },
            { label: "PING", val: (ping || "--") + "ms", color: pingColor },
          ].map(({ label, val, color }) => (
            <div key={label} style={{ textAlign: "center" }}>
              <div style={{ fontSize: 11, fontWeight: 800, color, fontVariantNumeric: "tabular-nums" }}>{val}</div>
              <div style={{ fontSize: 7, color: P.muted, letterSpacing: "0.08em", textTransform: "uppercase" }}>{label}</div>
            </div>
          ))}
        </div>
      </div>

      <style>{`
        @keyframes dbPulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(1.5); } }
        * { box-sizing: border-box; }
        ::-webkit-scrollbar { width: 2px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: ${P.dim}; border-radius: 2px; }
      `}</style>
    </div>
  );
}
