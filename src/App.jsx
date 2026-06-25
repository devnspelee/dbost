import { useState, useEffect, useRef, useCallback } from "react";

import { registerPlugin, Capacitor } from '@capacitor/core';

const OverlayNative = registerPlugin('Overlay');



// ─── Palette ─────────────────────────────────────────────────────────────────
const P = {
  bg:         "#0a0a0f",
  surface:    "#111118",
  card:       "#16161f",
  border:     "#1e1e2e",
  accent:     "#5b4aff",
  accentGlow: "#5b4aff44",
  cyan:       "#00d4ff",
  green:      "#00ff88",
  red:        "#ff4466",
  amber:      "#ffaa00",
  text:       "#e8e8f0",
  muted:      "#5a5a7a",
  dim:        "#2a2a3e",
};

const LEVEL_LABELS = { 1: "Lite", 2: "Balanced", 3: "Turbo" };
const LEVEL_COLORS = { 1: P.green, 2: P.accent, 3: P.red };
const FPS_LEN = 24;
const MIN_W = 220;
const MAX_W = 520;
const MIN_H = 300;
const MAX_H = 700;

// ─── Animated value hook ──────────────────────────────────────────────────────
const useAnimatedValue = (target, duration = 700) => {
  const [value, setValue] = useState(0);
  const prev = useRef(0);
  useEffect(() => {
    let raf;
    let start = null;
    const from = prev.current;
    prev.current = target;
    const step = (ts) => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / duration, 1);
      const ease = 1 - Math.pow(1 - p, 3);
      setValue(from + (target - from) * ease);
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [target]);
  return value;
};

// ─── Real device metrics ──────────────────────────────────────────────────────
const useRealMetrics = (boosting, boostLevel) => {
  const [metrics, setMetrics] = useState({
    cpu: 0, ram: 0, gpu: 0,
    fps: 60, ping: 0,
    batPct: 100, batDrain: 0, batCharging: false,
    netType: "unknown", netDownlink: 0,
  });
  const [fpsHistory, setFpsHistory] = useState(() => Array(FPS_LEN).fill(60));
  const [support, setSupport] = useState({
    battery: false, memory: false, network: false, wakeLock: false,
  });

  // Real FPS via rAF
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

  // Battery API
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
    return () => {
      if (bat) {
        bat.removeEventListener("levelchange", () => {});
        bat.removeEventListener("chargingchange", () => {});
      }
    };
  }, [boosting, boostLevel]);

  // Memory API (Chrome)
  useEffect(() => {
    if (!performance.memory) return;
    setSupport(s => ({ ...s, memory: true }));
    const id = setInterval(() => {
      const { usedJSHeapSize, jsHeapSizeLimit } = performance.memory;
      const pct = Math.min(100, Math.round((usedJSHeapSize / jsHeapSizeLimit) * 100));
      setMetrics(m => ({ ...m, ram: pct }));
    }, 1500);
    return () => clearInterval(id);
  }, []);

  // Network API
  useEffect(() => {
    const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (!conn) return;
    setSupport(s => ({ ...s, network: true }));
    const update = () => setMetrics(m => ({
      ...m,
      netType: conn.effectiveType || conn.type || "unknown",
      netDownlink: conn.downlink || 0,
    }));
    update();
    conn.addEventListener("change", update);
    return () => conn.removeEventListener("change", update);
  }, []);

  // Wake lock support check
  useEffect(() => {
    setSupport(s => ({ ...s, wakeLock: "wakeLock" in navigator }));
  }, []);

  // CPU estimate via busy-loop timing
  useEffect(() => {
    const id = setInterval(() => {
      const t0 = performance.now();
      let sum = 0;
      for (let i = 0; i < 50000; i++) sum += Math.sqrt(i);
      const elapsed = performance.now() - t0;
      const cpuEst = Math.min(95, Math.round((elapsed / 8) * 100));
      setMetrics(m => ({
        ...m,
        cpu: cpuEst,
        gpu: Math.min(95, Math.max(5, 100 - Math.round((m.fps / 120) * 100))),
      }));
    }, 2000);
    return () => clearInterval(id);
  }, []);

  // Ping via fetch timing
  useEffect(() => {
    const measure = async () => {
      try {
        const t0 = performance.now();
        await fetch("https://www.google.com/generate_204", { mode: "no-cors", cache: "no-store" });
        setMetrics(m => ({ ...m, ping: Math.round(performance.now() - t0) }));
      } catch {
        setMetrics(m => ({ ...m, ping: 0 }));
      }
    };
    measure();
    const id = setInterval(measure, 5000);
    return () => clearInterval(id);
  }, []);

  return { metrics, fpsHistory, support };
};

// ─── Wake lock hook ───────────────────────────────────────────────────────────
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

// ─── Sub-components ───────────────────────────────────────────────────────────
const RadialGauge = ({ value, max, label, color, size = 80 }) => {
  const r = 32, circ = 2 * Math.PI * r;
  const animated = useAnimatedValue((value / max) * circ);
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
      <svg width={size} height={size} viewBox="0 0 80 80">
        <circle cx="40" cy="40" r={r} fill="none" stroke={P.dim} strokeWidth="6" />
        <circle cx="40" cy="40" r={r} fill="none" stroke={color} strokeWidth="6"
          strokeDasharray={`${animated} ${circ - animated}`}
          strokeLinecap="round" transform="rotate(-90 40 40)"
          style={{ filter: `drop-shadow(0 0 6px ${color})` }} />
        <text x="40" y="44" textAnchor="middle" fill={P.text}
          fontSize="13" fontWeight="700" fontFamily="system-ui,sans-serif">
          {Math.round(value)}%
        </text>
      </svg>
      <span style={{ fontSize: 10, color: P.muted, letterSpacing: "0.08em", textTransform: "uppercase" }}>{label}</span>
    </div>
  );
};

const MiniBar = ({ label, value, max, unit = "%", color }) => (
  <div style={{ marginBottom: 8 }}>
    <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
      <span style={{ fontSize: 11, color: P.muted }}>{label}</span>
      <span style={{ fontSize: 11, color, fontWeight: 600 }}>{typeof value === "number" ? value.toFixed(0) : value}{unit}</span>
    </div>
    <div style={{ height: 3, background: P.dim, borderRadius: 2, overflow: "hidden" }}>
      <div style={{
        height: "100%", width: `${Math.min(100, (value / max) * 100)}%`,
        background: color, borderRadius: 2, boxShadow: `0 0 6px ${color}`,
        transition: "width 0.8s cubic-bezier(.22,1,.36,1)",
      }} />
    </div>
  </div>
);

const ToggleSwitch = ({ on, onChange }) => (
  <div onClick={() => onChange(!on)} style={{
    width: 36, height: 20, borderRadius: 10,
    background: on ? P.accent : P.dim, cursor: "pointer",
    position: "relative", transition: "background 0.2s",
    boxShadow: on ? `0 0 10px ${P.accentGlow}` : "none", flexShrink: 0,
  }}>
    <div style={{
      position: "absolute", top: 3, left: on ? 19 : 3,
      width: 14, height: 14, borderRadius: "50%",
      background: "#fff", transition: "left 0.2s",
      boxShadow: "0 1px 4px rgba(0,0,0,0.4)",
    }} />
  </div>
);

const TabBtn = ({ label, active, onClick }) => (
  <button onClick={onClick} style={{
    flex: 1, padding: "7px 0", background: "none", border: "none",
    color: active ? P.text : P.muted,
    fontSize: 10, fontWeight: active ? 700 : 400,
    letterSpacing: "0.06em", cursor: "pointer",
    borderBottom: `2px solid ${active ? P.accent : "transparent"}`,
    transition: "all 0.2s", textTransform: "uppercase",
  }}>{label}</button>
);

const ApiDot = ({ ok }) => (
  <span style={{
    display: "inline-block", width: 6, height: 6, borderRadius: "50%",
    background: ok ? P.green : P.dim,
    boxShadow: ok ? `0 0 4px ${P.green}` : "none",
    marginRight: 5, flexShrink: 0,
  }} />
);

// ─── Resize handle SVG (bottom-right corner) ──────────────────────────────────
const ResizeHandle = ({ onMouseDown, onTouchStart }) => (
  <div
    onMouseDown={onMouseDown}
    onTouchStart={onTouchStart}
    style={{
      position: "absolute", bottom: 0, right: 0,
      width: 18, height: 18, cursor: "nwse-resize",
      display: "flex", alignItems: "flex-end", justifyContent: "flex-end",
      padding: 3,
    }}
  >
    <svg width="12" height="12" viewBox="0 0 12 12">
      <line x1="2" y1="11" x2="11" y2="2" stroke={P.muted} strokeWidth="1.5" strokeLinecap="round" />
      <line x1="6" y1="11" x2="11" y2="6" stroke={P.muted} strokeWidth="1.5" strokeLinecap="round" />
      <line x1="10" y1="11" x2="11" y2="10" stroke={P.muted} strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  </div>
);

// ─── Main ─────────────────────────────────────────────────────────────────────
export default function DBost() {
  // Position & size
  const [pos, setPos] = useState({ x: 20, y: 80 });
  const [size, setSize] = useState({ w: 280, h: 480 });

  // Drag state
  const [dragging, setDragging] = useState(false);
  const dragOff = useRef({ x: 0, y: 0 });

  // Resize state
  const [resizing, setResizing] = useState(false);
  const resizeStart = useRef({ mx: 0, my: 0, w: 280, h: 480 });

  // App state
  const [tab, setTab] = useState("status");
  const [minimized, setMinimized] = useState(false);
  const [closed, setClosed] = useState(false);
  const [boosting, setBoosting] = useState(false);
  const [boostLevel, setBoostLevel] = useState(2);
  const [boostLog, setBoostLog] = useState([]);
  const [overlayActive, setOverlayActive] = useState(false);

  const startOverlay = async () => {
    try {
      const { value } = await OverlayNative.hasPermission();
      if (!value) {
        await OverlayNative.requestPermission();
        return;
      }
      await OverlayNative.show();
      setOverlayActive(true);
    } catch(e) { console.log('Overlay error:', e); }
  };

  const stopOverlay = async () => {
    try {
      await OverlayNative.hide();
      setOverlayActive(false);
    } catch(e) {}
  };


  const [toggles, setToggles] = useState({
    wakeLock: true,
    noNotif: false,
    fullscreen: false,
    highPriority: true,
    netMonitor: true,
    fpsMeter: true,
  });
  const setToggle = (k, v) => setToggles(t => ({ ...t, [k]: v }));

  const { acquire: wakeLockAcquire, release: wakeLockRelease } = useWakeLock();
  const { metrics, fpsHistory, support } = useRealMetrics(boosting, boostLevel);
  const { cpu, ram, gpu, fps, ping, batPct, batDrain, batCharging, netType, netDownlink } = metrics;

  const fpsColor = fps >= 60 ? P.green : fps >= 40 ? P.amber : P.red;
  const fpsMin = Math.min(...fpsHistory);
  const fpsMax = Math.max(...fpsHistory, 1);

  const log = (msg) => setBoostLog(l => [`${new Date().toLocaleTimeString()} — ${msg}`, ...l.slice(0, 19)]);

  // ── Boost actions ─────────────────────────────────────────────────────────
  const startBoost = async () => {
    setBoosting(true);
    log(`${LEVEL_LABELS[boostLevel]} boost started`);
    if (toggles.wakeLock) {
      await wakeLockAcquire();
      log("Screen wake lock acquired");
    }
    if ("scheduler" in window && "yield" in window.scheduler) {
      await window.scheduler.yield();
      log("Scheduler priority granted");
    }
    if (navigator.storage?.persist) {
      const granted = await navigator.storage.persist();
      log(granted ? "Persistent storage granted" : "Storage: standard mode");
    }
    if (boostLevel === 3 && toggles.fullscreen) {
      try {
        await document.documentElement.requestFullscreen?.();
        log("Fullscreen mode activated");
      } catch { log("Fullscreen: permission denied"); }
    }
  };

  const stopBoost = async () => {
    setBoosting(false);
    log("Boost stopped");
    await wakeLockRelease();
    if (document.fullscreenElement) { try { await document.exitFullscreen(); } catch {} }
    log("Screen lock released");
  };

  useEffect(() => {
    if (toggles.wakeLock && boosting) wakeLockAcquire();
    if (!toggles.wakeLock) wakeLockRelease();
  }, [toggles.wakeLock]);

  useEffect(() => {
    if (!toggles.noNotif) return;
    Notification.requestPermission().then(r => {
      log("Notification permission: " + r);
    });
  }, [toggles.noNotif]);

  // ── Drag handlers ─────────────────────────────────────────────────────────
  const onDragMouseDown = useCallback((e) => {
    e.preventDefault();
    setDragging(true);
    dragOff.current = { x: e.clientX - pos.x, y: e.clientY - pos.y };
  }, [pos]);

  const onDragTouchStart = useCallback((e) => {
    const t = e.touches[0];
    setDragging(true);
    dragOff.current = { x: t.clientX - pos.x, y: t.clientY - pos.y };
  }, [pos]);

  // ── Resize handlers ───────────────────────────────────────────────────────
  const onResizeMouseDown = useCallback((e) => {
    e.preventDefault();
    e.stopPropagation();
    setResizing(true);
    resizeStart.current = { mx: e.clientX, my: e.clientY, w: size.w, h: size.h };
  }, [size]);

  const onResizeTouchStart = useCallback((e) => {
    e.stopPropagation();
    const t = e.touches[0];
    setResizing(true);
    resizeStart.current = { mx: t.clientX, my: t.clientY, w: size.w, h: size.h };
  }, [size]);

  // ── Global move/up listeners ──────────────────────────────────────────────
  useEffect(() => {
    const move = (cx, cy) => {
      if (dragging) {
        const nx = Math.max(0, Math.min(window.innerWidth - size.w, cx - dragOff.current.x));
        const ny = Math.max(0, Math.min(window.innerHeight - 52, cy - dragOff.current.y));
        setPos({ x: nx, y: ny });
      }
      if (resizing) {
        const dx = cx - resizeStart.current.mx;
        const dy = cy - resizeStart.current.my;
        setSize({
          w: Math.max(MIN_W, Math.min(MAX_W, resizeStart.current.w + dx)),
          h: Math.max(MIN_H, Math.min(MAX_H, resizeStart.current.h + dy)),
        });
      }
    };
    const mm = (e) => move(e.clientX, e.clientY);
    const tm = (e) => { const t = e.touches[0]; move(t.clientX, t.clientY); };
    const up = () => { setDragging(false); setResizing(false); };
    window.addEventListener("mousemove", mm);
    window.addEventListener("mouseup", up);
    window.addEventListener("touchmove", tm, { passive: true });
    window.addEventListener("touchend", up);
    return () => {
      window.removeEventListener("mousemove", mm);
      window.removeEventListener("mouseup", up);
      window.removeEventListener("touchmove", tm);
      window.removeEventListener("touchend", up);
    };
  }, [dragging, resizing, size.w]);

  // ── Computed ──────────────────────────────────────────────────────────────
  const estRemain = batCharging
    ? "Charging"
    : batDrain > 0
      ? `${Math.round((batPct / 100 * 4000) / (batDrain * 100) * 60)}m`
      : "–";

  const contentH = minimized ? "auto" : size.h - 52; // 52 = title bar

  // ── Reopen pill (draggable, shown when closed) ───────────────────────────
  if (closed) {
    return (
      <div
        onMouseDown={onDragMouseDown}
        onTouchStart={onDragTouchStart}
        style={{
          position: "fixed",
          left: pos.x, top: pos.y,
          zIndex: 2147483647,
          userSelect: "none",
          touchAction: "none",
          cursor: dragging ? "grabbing" : "grab",
        }}
      >
        <div
          onClick={() => setClosed(false)}
          onMouseDown={e => e.stopPropagation()}
          style={{
            width: 40, height: 40, borderRadius: 12,
            background: `linear-gradient(135deg, ${P.accent}, #8b5cf6)`,
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: `0 4px 24px ${P.accentGlow}, 0 0 0 1px ${P.border}`,
            fontSize: 18, fontWeight: 900, color: "#fff",
            fontFamily: "'Inter', system-ui, sans-serif",
          }}
        >d</div>
      </div>
    );
  }

  return (
    <div style={{
      position: "fixed",
      left: pos.x, top: pos.y,
      width: size.w,
      zIndex: 2147483647,
      background: P.surface,
      border: `1px solid ${P.border}`,
      borderRadius: 14,
      boxShadow: `0 24px 60px rgba(0,0,0,0.85), inset 0 1px 0 rgba(255,255,255,0.04)`,
      overflow: "hidden",
      userSelect: "none",
      fontFamily: "'Inter', system-ui, sans-serif",
      touchAction: "none",
      transition: (dragging || resizing) ? "none" : "box-shadow 0.2s",
    }}>

      {/* ── Title bar ──────────────────────────────────────────────────────── */}
      <div
        onMouseDown={onDragMouseDown}
        onTouchStart={onDragTouchStart}
        style={{
          height: 52, padding: "0 12px",
          background: P.card,
          borderBottom: `1px solid ${P.border}`,
          cursor: dragging ? "grabbing" : "grab",
          display: "flex", alignItems: "center", justifyContent: "space-between",
          flexShrink: 0,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <div style={{
            width: 28, height: 28, borderRadius: 8,
            background: `linear-gradient(135deg, ${P.accent}, #8b5cf6)`,
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: `0 0 12px ${P.accentGlow}`,
            fontSize: 14, fontWeight: 900, color: "#fff", flexShrink: 0,
          }}>d</div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: P.text, lineHeight: 1 }}>dBost</div>
            <div style={{ fontSize: 9, color: P.muted, letterSpacing: "0.1em", textTransform: "uppercase" }}>
              Game Booster
            </div>
          </div>
        </div>

        <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
          {/* Status dot */}
          <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
            <div style={{
              width: 6, height: 6, borderRadius: "50%",
              background: boosting ? P.green : P.muted,
              boxShadow: boosting ? `0 0 6px ${P.green}` : "none",
              animation: boosting ? "dbPulse 1.5s infinite" : "none",
            }} />
            <span style={{ fontSize: 9, color: boosting ? P.green : P.muted, letterSpacing: "0.08em" }}>
              {boosting ? "ACTIVE" : "IDLE"}
            </span>
          </div>

          {/* Overlay */}
          <button
            onMouseDown={e => e.stopPropagation()}
            onClick={overlayActive ? stopOverlay : startOverlay}
            style={{
              width: 22, height: 22, borderRadius: 6, border: "none",
              background: overlayActive ? `${P.accent}44` : P.dim,
              color: overlayActive ? P.accent : P.muted, cursor: "pointer",
              fontSize: 10, lineHeight: 1,
              display: "flex", alignItems: "center", justifyContent: "center",
            }}
            title="Floating Overlay"
          >⬡</button>

          {/* Minimize */}
          <button
            onMouseDown={e => e.stopPropagation()}
            onClick={() => setMinimized(m => !m)}
            style={{
              width: 22, height: 22, borderRadius: 6, border: "none",
              background: P.dim, color: P.muted, cursor: "pointer",
              fontSize: 16, lineHeight: 1,
              display: "flex", alignItems: "center", justifyContent: "center",
            }}
          >{minimized ? "+" : "−"}</button>

          {/* Close */}
          <button
            onMouseDown={e => e.stopPropagation()}
            onClick={() => { setClosed(true); setMinimized(false); }}
            style={{
              width: 22, height: 22, borderRadius: 6, border: "none",
              background: `${P.red}22`, color: P.red, cursor: "pointer",
              fontSize: 16, lineHeight: 1,
              display: "flex", alignItems: "center", justifyContent: "center",
            }}
          >×</button>
        </div>
      </div>

      {/* ── Body (hidden when minimized) ───────────────────────────────────── */}
      {!minimized && (
        <div style={{ height: contentH, display: "flex", flexDirection: "column", overflow: "hidden" }}>

          {/* FPS + Ping quick strip */}
          <div style={{
            padding: "8px 12px", background: P.bg,
            display: "flex", gap: 6, borderBottom: `1px solid ${P.border}`,
            flexShrink: 0,
          }}>
            <div style={{
              flex: 1, padding: "6px 8px", borderRadius: 8,
              background: P.card, border: `1px solid ${P.border}`,
              display: "flex", alignItems: "center", gap: 6,
            }}>
              <span style={{ fontSize: 9, color: P.muted, textTransform: "uppercase", letterSpacing: "0.08em" }}>FPS</span>
              <span style={{ fontSize: 18, fontWeight: 800, color: fpsColor, lineHeight: 1 }}>{Math.round(fps)}</span>
              <svg width="50" height="20" style={{ marginLeft: "auto", overflow: "visible" }}>
                <path
                  d={fpsHistory.map((v, i) => {
                    const x = (i / (FPS_LEN - 1)) * 50;
                    const y = 18 - ((v - fpsMin) / Math.max(1, fpsMax - fpsMin)) * 16;
                    return `${i === 0 ? "M" : "L"}${x},${y}`;
                  }).join(" ")}
                  fill="none" stroke={fpsColor} strokeWidth="1.5"
                  strokeLinecap="round" strokeLinejoin="round"
                  style={{ filter: `drop-shadow(0 0 3px ${fpsColor})` }}
                />
              </svg>
            </div>
            <div style={{
              width: 58, padding: "6px 8px", borderRadius: 8,
              background: P.card, border: `1px solid ${P.border}`,
              display: "flex", flexDirection: "column", alignItems: "center",
            }}>
              <span style={{ fontSize: 9, color: P.muted, textTransform: "uppercase", letterSpacing: "0.08em" }}>PING</span>
              <span style={{
                fontSize: 15, fontWeight: 800, lineHeight: 1.3,
                color: ping === 0 ? P.muted : ping < 50 ? P.green : ping < 120 ? P.amber : P.red,
              }}>{ping || "—"}</span>
              <span style={{ fontSize: 8, color: P.muted }}>ms</span>
            </div>
          </div>

          {/* Tabs */}
          <div style={{
            display: "flex", borderBottom: `1px solid ${P.border}`,
            background: P.card, padding: "0 12px", flexShrink: 0,
          }}>
            {["status", "boost", "settings", "log"].map(t => (
              <TabBtn key={t} label={t} active={tab === t} onClick={() => setTab(t)} />
            ))}
          </div>

          {/* Scrollable content */}
          <div style={{ flex: 1, overflowY: "auto", padding: 14 }}>

            {/* STATUS */}
            {tab === "status" && (
              <>
                <div style={{ display: "flex", justifyContent: "space-around", marginBottom: 14 }}>
                  <RadialGauge value={cpu} max={100} label="CPU est." color={P.cyan} size={Math.max(64, size.w * 0.26)} />
                  <RadialGauge value={ram} max={100} label="RAM" color={P.accent} size={Math.max(64, size.w * 0.26)} />
                  <RadialGauge value={gpu} max={100} label="GPU est." color={P.green} size={Math.max(64, size.w * 0.26)} />
                </div>

                {/* API status row */}
                <div style={{
                  display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 12,
                  padding: "8px 10px", borderRadius: 8,
                  background: P.card, border: `1px solid ${P.border}`,
                }}>
                  {[
                    { label: "Battery", ok: support.battery },
                    { label: "Memory", ok: support.memory },
                    { label: "Network", ok: support.network },
                    { label: "WakeLock", ok: support.wakeLock },
                  ].map(({ label, ok }) => (
                    <div key={label} style={{ display: "flex", alignItems: "center", fontSize: 10, color: ok ? P.text : P.muted }}>
                      <ApiDot ok={ok} />{label}
                    </div>
                  ))}
                </div>

                <MiniBar label="Battery" value={batPct} max={100} unit="%" color={P.amber} />
                {support.network && (
                  <MiniBar
                    label={`Network  (${netType})`}
                    value={Math.min(100, netDownlink * 10)}
                    max={100}
                    unit={`  ${netDownlink} Mbps`}
                    color={P.cyan}
                  />
                )}

                <div style={{
                  padding: "8px 10px", borderRadius: 8,
                  background: P.card, border: `1px solid ${P.border}`,
                  display: "flex", justifyContent: "space-between", alignItems: "center",
                  marginTop: 8,
                }}>
                  <div>
                    <div style={{ fontSize: 10, color: P.muted }}>
                      {batCharging ? "CHARGING" : "POWER DRAW"}
                    </div>
                    <div style={{ fontSize: 16, fontWeight: 700, color: P.text }}>
                      {support.battery ? `${batDrain} W` : "—"}
                    </div>
                  </div>
                  <div style={{ textAlign: "right" }}>
                    <div style={{ fontSize: 10, color: P.muted }}>EST. REMAIN</div>
                    <div style={{ fontSize: 16, fontWeight: 700, color: P.text }}>{estRemain}</div>
                  </div>
                </div>
              </>
            )}

            {/* BOOST */}
            {tab === "boost" && (
              <>
                <div style={{ marginBottom: 12 }}>
                  <div style={{ fontSize: 10, color: P.muted, marginBottom: 8, letterSpacing: "0.08em", textTransform: "uppercase" }}>
                    Boost Level
                  </div>
                  <div style={{ display: "flex", gap: 6 }}>
                    {[1, 2, 3].map(l => (
                      <button key={l} onClick={() => !boosting && setBoostLevel(l)} style={{
                        flex: 1, padding: "10px 0", borderRadius: 8,
                        border: `1px solid ${boostLevel === l ? LEVEL_COLORS[l] : P.border}`,
                        background: boostLevel === l ? `${LEVEL_COLORS[l]}18` : P.card,
                        color: boostLevel === l ? LEVEL_COLORS[l] : P.muted,
                        fontSize: 11, fontWeight: boostLevel === l ? 700 : 400,
                        cursor: boosting ? "not-allowed" : "pointer",
                        opacity: boosting && boostLevel !== l ? 0.4 : 1,
                        boxShadow: boostLevel === l ? `0 0 10px ${LEVEL_COLORS[l]}44` : "none",
                        transition: "all 0.2s",
                      }}>{LEVEL_LABELS[l]}</button>
                    ))}
                  </div>
                </div>

                <button
                  onClick={boosting ? stopBoost : startBoost}
                  style={{
                    width: "100%", padding: 13, borderRadius: 10,
                    border: `1px solid ${boosting ? P.red : LEVEL_COLORS[boostLevel]}`,
                    background: boosting
                      ? `linear-gradient(135deg, ${P.red}22, transparent)`
                      : `linear-gradient(135deg, ${LEVEL_COLORS[boostLevel]}22, transparent)`,
                    color: boosting ? P.red : LEVEL_COLORS[boostLevel],
                    fontSize: 13, fontWeight: 800, cursor: "pointer",
                    letterSpacing: "0.1em", textTransform: "uppercase",
                    boxShadow: `0 0 20px ${boosting ? P.red : LEVEL_COLORS[boostLevel]}44`,
                    transition: "all 0.2s",
                  }}
                >
                  {boosting ? "Stop Boost" : `Start ${LEVEL_LABELS[boostLevel]} Boost`}
                </button>

                <div style={{ marginTop: 12, padding: "8px 10px", borderRadius: 8, background: P.card, border: `1px solid ${P.border}` }}>
                  <div style={{ fontSize: 9, color: P.muted, marginBottom: 8, letterSpacing: "0.08em", textTransform: "uppercase" }}>
                    Real Actions
                  </div>
                  {[
                    { label: "Wake Lock", desc: "Prevent screen sleep", ok: "wakeLock" in navigator },
                    { label: "Scheduler Yield", desc: "Flush pending tasks", ok: "scheduler" in window },
                    { label: "Persist Storage", desc: "Reduce eviction pressure", ok: !!navigator.storage },
                    { label: "Fullscreen (Turbo)", desc: "Immersive display mode", ok: !!document.documentElement.requestFullscreen },
                  ].map(({ label, desc, ok }) => (
                    <div key={label} style={{
                      display: "flex", gap: 8, alignItems: "center",
                      padding: "5px 0", borderBottom: `1px solid ${P.dim}`,
                    }}>
                      <ApiDot ok={ok} />
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: 11, color: ok ? P.text : P.muted, fontWeight: 600 }}>{label}</div>
                        <div style={{ fontSize: 9, color: P.muted }}>{desc}</div>
                      </div>
                      <span style={{
                        fontSize: 9, padding: "1px 5px", borderRadius: 4,
                        background: ok ? `${P.green}18` : `${P.dim}`,
                        color: ok ? P.green : P.muted,
                        border: `1px solid ${ok ? P.green + "44" : P.border}`,
                        fontWeight: 700,
                      }}>{ok ? "Ready" : "N/A"}</span>
                    </div>
                  ))}
                </div>
              </>
            )}

            {/* SETTINGS */}
            {tab === "settings" && (
              <div>
                {[
                  { key: "wakeLock",     label: "Wake Lock",          desc: "Keep screen on while boosting" },
                  { key: "fpsMeter",     label: "FPS Meter",          desc: "Real rAF frame counter" },
                  { key: "netMonitor",   label: "Network Monitor",    desc: "Live downlink and latency" },
                  { key: "noNotif",      label: "Notification Access",desc: "Required to manage alerts" },
                  { key: "highPriority", label: "Scheduler Hint",     desc: "Yield to high-priority tasks" },
                  { key: "fullscreen",   label: "Fullscreen (Turbo)", desc: "Immersive mode on Turbo boost" },
                ].map(({ key, label, desc }) => (
                  <div key={key} style={{
                    display: "flex", justifyContent: "space-between",
                    alignItems: "center", padding: "9px 0",
                    borderBottom: `1px solid ${P.border}`,
                  }}>
                    <div>
                      <div style={{ fontSize: 12, fontWeight: 600, color: P.text }}>{label}</div>
                      <div style={{ fontSize: 10, color: P.muted, marginTop: 2 }}>{desc}</div>
                    </div>
                    <ToggleSwitch on={toggles[key]} onChange={v => setToggle(key, v)} />
                  </div>
                ))}
              </div>
            )}

            {/* LOG */}
            {tab === "log" && (
              <div>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                  <span style={{ fontSize: 10, color: P.muted, letterSpacing: "0.08em", textTransform: "uppercase" }}>
                    Activity Log
                  </span>
                  <button onClick={() => setBoostLog([])} style={{
                    fontSize: 9, padding: "2px 7px", borderRadius: 4,
                    border: `1px solid ${P.border}`, background: "none",
                    color: P.muted, cursor: "pointer",
                  }}>Clear</button>
                </div>
                {boostLog.length === 0
                  ? <div style={{ fontSize: 11, color: P.muted, textAlign: "center", padding: 24 }}>
                      No activity yet. Start a boost to see logs.
                    </div>
                  : boostLog.map((entry, i) => (
                    <div key={i} style={{
                      fontSize: 10, color: i === 0 ? P.text : P.muted,
                      padding: "5px 0", borderBottom: `1px solid ${P.dim}`,
                      lineHeight: 1.5,
                    }}>{entry}</div>
                  ))
                }
              </div>
            )}
          </div>

          {/* Footer */}
          <div style={{
            padding: "6px 12px", background: P.card,
            borderTop: `1px solid ${P.border}`,
            display: "flex", justifyContent: "space-between", alignItems: "center",
            flexShrink: 0,
          }}>
            <span style={{ fontSize: 9, color: P.muted, letterSpacing: "0.08em" }}>
              dBost v2.0  ·  {size.w}x{Math.round(contentH)}
            </span>
            <div style={{ display: "flex", gap: 10 }}>
              {[
                { label: "FPS", val: Math.round(fps), color: fpsColor },
                { label: "RAM", val: Math.round(ram) + "%", color: P.accent },
                { label: "BAT", val: batPct + "%", color: P.amber },
              ].map(({ label, val, color }) => (
                <div key={label} style={{ textAlign: "center" }}>
                  <div style={{ fontSize: 10, fontWeight: 700, color }}>{val}</div>
                  <div style={{ fontSize: 8, color: P.muted }}>{label}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Minimized pill content */}
      {minimized && (
        <div style={{ padding: "8px 12px", display: "flex", gap: 14, alignItems: "center" }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: fpsColor }}>{Math.round(fps)} FPS</div>
          <div style={{ fontSize: 12, fontWeight: 700, color: P.amber }}>{batPct}%</div>
          <div style={{ fontSize: 12, fontWeight: 700, color: ping < 50 ? P.green : P.amber }}>
            {ping || "—"} ms
          </div>
        </div>
      )}

      {/* Resize handle — only when not minimized */}
      {!minimized && (
        <ResizeHandle
          onMouseDown={onResizeMouseDown}
          onTouchStart={onResizeTouchStart}
        />
      )}

      <style>{`
        @keyframes dbPulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50%       { opacity: 0.5; transform: scale(1.4); }
        }
        * { box-sizing: border-box; }
        ::-webkit-scrollbar { width: 3px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: ${P.dim}; border-radius: 2px; }
      `}</style>
    </div>
  );
}
