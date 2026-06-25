import { useState, useEffect, useRef, useCallback } from "react";
sed -n '880,892p' src/App.jsximport { useState, useEffect, useRef, useCallback } from "react";

const PALETTE = {
  bg: "#0a0a0f",
  surface: "#111118",
  card: "#16161f",
  border: "#1e1e2e",
  accent: "#5b4aff",
  accentGlow: "#5b4aff44",
  accentSoft: "#5b4aff22",
  cyan: "#00d4ff",
  green: "#00ff88",
  red: "#ff4466",
  amber: "#ffaa00",
  text: "#e8e8f0",
  muted: "#5a5a7a",
  dim: "#2a2a3e",
};

const useAnimatedValue = (target, duration = 800) => {
  const [value, setValue] = useState(0);
  useEffect(() => {
    let start = null;
    const from = value;
    const step = (ts) => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / duration, 1);
      const ease = 1 - Math.pow(1 - p, 3);
      setValue(from + (target - from) * ease);
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }, [target]);
  return value;
};

const RadialGauge = ({ value, max, label, color, size = 80 }) => {
  const pct = value / max;
  const r = 32;
  const circ = 2 * Math.PI * r;
  const dash = circ * pct;
  const gap = circ - dash;
  const animated = useAnimatedValue(dash);

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
      <svg width={size} height={size} viewBox="0 0 80 80">
        <circle cx="40" cy="40" r={r} fill="none" stroke={PALETTE.dim} strokeWidth="6" />
        <circle
          cx="40" cy="40" r={r} fill="none"
          stroke={color} strokeWidth="6"
          strokeDasharray={`${animated} ${circ - animated}`}
          strokeLinecap="round"
          transform="rotate(-90 40 40)"
          style={{ filter: `drop-shadow(0 0 6px ${color})`, transition: "none" }}
        />
        <text x="40" y="44" textAnchor="middle" fill={PALETTE.text}
          fontSize="14" fontWeight="700" fontFamily="'SF Pro Display', system-ui, sans-serif">
          {Math.round(value)}%
        </text>
      </svg>
      <span style={{ fontSize: 10, color: PALETTE.muted, letterSpacing: "0.08em", textTransform: "uppercase" }}>{label}</span>
    </div>
  );
};

const MiniBar = ({ label, value, max, unit = "%", color }) => {
  const pct = (value / max) * 100;
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
        <span style={{ fontSize: 11, color: PALETTE.muted, letterSpacing: "0.06em" }}>{label}</span>
        <span style={{ fontSize: 11, color: color, fontWeight: 600 }}>{value}{unit}</span>
      </div>
      <div style={{ height: 3, background: PALETTE.dim, borderRadius: 2, overflow: "hidden" }}>
        <div style={{
          height: "100%", width: `${pct}%`, background: color,
          borderRadius: 2, boxShadow: `0 0 6px ${color}`,
          transition: "width 0.8s cubic-bezier(.22,1,.36,1)"
        }} />
      </div>
    </div>
  );
};

const ToggleSwitch = ({ on, onChange }) => (
  <div onClick={() => onChange(!on)} style={{
    width: 36, height: 20, borderRadius: 10,
    background: on ? PALETTE.accent : PALETTE.dim,
    cursor: "pointer", position: "relative",
    transition: "background 0.2s",
    boxShadow: on ? `0 0 10px ${PALETTE.accentGlow}` : "none",
    flexShrink: 0,
  }}>
    <div style={{
      position: "absolute", top: 3, left: on ? 19 : 3,
      width: 14, height: 14, borderRadius: "50%",
      background: "#fff", transition: "left 0.2s",
      boxShadow: "0 1px 4px rgba(0,0,0,0.4)"
    }} />
  </div>
);

const Tab = ({ label, active, onClick }) => (
  <button onClick={onClick} style={{
    flex: 1, padding: "7px 0", background: "none", border: "none",
    color: active ? PALETTE.text : PALETTE.muted,
    fontSize: 11, fontWeight: active ? 700 : 400,
    letterSpacing: "0.06em", cursor: "pointer",
    borderBottom: `2px solid ${active ? PALETTE.accent : "transparent"}`,
    transition: "all 0.2s",
    textTransform: "uppercase",
  }}>{label}</button>
);

const FPS_HISTORY_LEN = 24;

export default function DBost() {
  // Draggable state
  const [pos, setPos] = useState({ x: 40, y: 60 });
  const [dragging, setDragging] = useState(false);
  const dragOffset = useRef({ x: 0, y: 0 });

  // App state
  const [tab, setTab] = useState("status");
  const [minimized, setMinimized] = useState(false);
  const [boosting, setBoosting] = useState(false);
  const [boostLevel, setBoostLevel] = useState(2); // 1=lite 2=balanced 3=turbo

  // Simulated metrics
  const [cpu, setCpu] = useState(38);
  const [ram, setRam] = useState(61);
  const [gpu, setGpu] = useState(44);
  const [temp, setTemp] = useState(42);
  const [fps, setFps] = useState(58);
  const [fpsHistory, setFpsHistory] = useState(() => Array(FPS_HISTORY_LEN).fill(58));
  const [ping, setPing] = useState(24);
  const [batPct, setBatPct] = useState(74);
  const [batDrain, setBatDrain] = useState(8.4);

  // Toggles
  const [toggles, setToggles] = useState({
    fpsUnlock: true,
    networkPriority: true,
    ramClean: false,
    thermalCap: false,
    noNotif: true,
    touchBoost: false,
  });

  const setToggle = (k, v) => setToggles(t => ({ ...t, [k]: v }));

  // Simulate live data
  useEffect(() => {
    const interval = setInterval(() => {
      const jitter = (base, range, boosted, boostRange) => {
        const b = boosting ? boosted : base;
        const r = boosting ? boostRange : range;
        return Math.max(0, Math.min(100, b + (Math.random() - 0.5) * r));
      };
      const newCpu = jitter(38, 12, 28, 8);
      const newRam = jitter(61, 4, 52, 4);
      const newGpu = jitter(44, 16, 34, 10);
      const newFps = Math.max(20, Math.min(120, (boosting ? 88 : 58) + (Math.random() - 0.4) * 20));
      const newTemp = Math.max(30, Math.min(90, (boosting ? 48 : 42) + (Math.random() - 0.5) * 4));
      const newPing = Math.max(8, (boosting ? 18 : 24) + (Math.random() - 0.5) * 12);

      setCpu(newCpu);
      setRam(newRam);
      setGpu(newGpu);
      setFps(newFps);
      setTemp(newTemp);
      setPing(Math.round(newPing));
      setBatDrain(boosting ? 12.1 + Math.random() * 2 : 8.4 + Math.random() * 1.5);
      setFpsHistory(h => [...h.slice(1), Math.round(newFps)]);
    }, 1200);
    return () => clearInterval(interval);
  }, [boosting]);

  // Drag handlers
  const onMouseDown = useCallback((e) => {
    setDragging(true);
    dragOffset.current = { x: e.clientX - pos.x, y: e.clientY - pos.y };
  }, [pos]);

  const onTouchStart = useCallback((e) => {
    const t = e.touches[0];
    setDragging(true);
    dragOffset.current = { x: t.clientX - pos.x, y: t.clientY - pos.y };
  }, [pos]);

  useEffect(() => {
    const move = (cx, cy) => {
      if (!dragging) return;
      const nx = cx - dragOffset.current.x;
      const ny = cy - dragOffset.current.y;
      setPos({ x: Math.max(0, nx), y: Math.max(0, ny) });
    };
    const mm = (e) => move(e.clientX, e.clientY);
    const tm = (e) => { const t = e.touches[0]; move(t.clientX, t.clientY); };
    const up = () => setDragging(false);
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
  }, [dragging]);

  // FPS sparkline
  const fpsMin = Math.min(...fpsHistory);
  const fpsMax = Math.max(...fpsHistory, 1);
  const sparkPath = fpsHistory.map((v, i) => {
    const x = (i / (FPS_HISTORY_LEN - 1)) * 200;
    const y = 30 - ((v - fpsMin) / (fpsMax - fpsMin + 1)) * 28;
    return `${i === 0 ? "M" : "L"}${x},${y}`;
  }).join(" ");

  const LEVEL_LABELS = { 1: "Lite", 2: "Balanced", 3: "Turbo" };
  const LEVEL_COLORS = { 1: PALETTE.green, 2: PALETTE.accent, 3: PALETTE.red };

  const tempColor = temp > 70 ? PALETTE.red : temp > 55 ? PALETTE.amber : PALETTE.green;
  const fpsColor = fps >= 60 ? PALETTE.green : fps >= 40 ? PALETTE.amber : PALETTE.red;

  return (
    <div style={{
      width: "100vw", height: "100vh", background: PALETTE.bg,
      fontFamily: "'SF Pro Display', 'Inter', system-ui, sans-serif",
      position: "relative", overflow: "hidden",
      backgroundImage: `radial-gradient(ellipse at 20% 20%, ${PALETTE.accentSoft} 0%, transparent 60%)`
    }}>
      {/* Background game mockup */}
      <div style={{
        position: "absolute", inset: 0,
        background: "linear-gradient(135deg, #0d0d1a 0%, #0a0a12 50%, #0f0f1a 100%)",
        display: "flex", alignItems: "center", justifyContent: "center",
      }}>
        <div style={{ textAlign: "center", opacity: 0.06 }}>
          <div style={{ fontSize: 80, fontWeight: 900, color: "#fff", letterSpacing: "-4px" }}>GAME</div>
          <div style={{ fontSize: 14, color: "#fff", letterSpacing: "6px" }}>RUNNING IN BACKGROUND</div>
        </div>
      </div>

      {/* Floating Panel */}
      <div style={{
        position: "absolute",
        left: pos.x, top: pos.y,
        width: 280,
        background: PALETTE.surface,
        border: `1px solid ${PALETTE.border}`,
        borderRadius: 16,
        boxShadow: `0 24px 60px rgba(0,0,0,0.7), 0 0 0 1px ${PALETTE.border}, inset 0 1px 0 rgba(255,255,255,0.04)`,
        overflow: "hidden",
        userSelect: "none",
        zIndex: 100,
        transition: dragging ? "none" : "box-shadow 0.2s",
      }}>

        {/* Title Bar */}
        <div
          onMouseDown={onMouseDown}
          onTouchStart={onTouchStart}
          style={{
            padding: "12px 14px",
            background: PALETTE.card,
            borderBottom: `1px solid ${PALETTE.border}`,
            cursor: dragging ? "grabbing" : "grab",
            display: "flex", alignItems: "center", justifyContent: "space-between",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={{
              width: 28, height: 28, borderRadius: 8,
              background: `linear-gradient(135deg, ${PALETTE.accent}, #8b5cf6)`,
              display: "flex", alignItems: "center", justifyContent: "center",
              boxShadow: `0 0 12px ${PALETTE.accentGlow}`,
              fontSize: 14, fontWeight: 900, color: "#fff",
            }}>d</div>
            <div>
              <div style={{ fontSize: 13, fontWeight: 700, color: PALETTE.text, lineHeight: 1 }}>dBost</div>
              <div style={{ fontSize: 9, color: PALETTE.muted, letterSpacing: "0.1em", textTransform: "uppercase" }}>Game Booster</div>
            </div>
          </div>
          <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
            {/* Live indicator */}
            <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
              <div style={{
                width: 6, height: 6, borderRadius: "50%",
                background: boosting ? PALETTE.green : PALETTE.muted,
                boxShadow: boosting ? `0 0 6px ${PALETTE.green}` : "none",
                animation: boosting ? "pulse 1.5s infinite" : "none",
              }} />
              <span style={{ fontSize: 9, color: boosting ? PALETTE.green : PALETTE.muted, letterSpacing: "0.08em" }}>
                {boosting ? "ACTIVE" : "IDLE"}
              </span>
            </div>
            <button onClick={() => setMinimized(m => !m)} style={{
              width: 22, height: 22, borderRadius: 6, border: "none",
              background: PALETTE.dim, color: PALETTE.muted, cursor: "pointer",
              fontSize: 12, display: "flex", alignItems: "center", justifyContent: "center",
            }}>{minimized ? "+" : "-"}</button>
          </div>
        </div>

        {!minimized && (
          <>
            {/* FPS + Ping quick bar */}
            <div style={{
              padding: "8px 14px",
              background: PALETTE.bg,
              display: "flex", gap: 6,
              borderBottom: `1px solid ${PALETTE.border}`,
            }}>
              <div style={{
                flex: 1, padding: "6px 8px", borderRadius: 8,
                background: PALETTE.card, border: `1px solid ${PALETTE.border}`,
                display: "flex", alignItems: "center", gap: 6,
              }}>
                <span style={{ fontSize: 9, color: PALETTE.muted, textTransform: "uppercase", letterSpacing: "0.08em" }}>FPS</span>
                <span style={{ fontSize: 18, fontWeight: 800, color: fpsColor, lineHeight: 1 }}>{Math.round(fps)}</span>
                <svg width="50" height="20" style={{ marginLeft: "auto", overflow: "visible" }}>
                  <path d={fpsHistory.map((v, i) => {
                    const x = (i / (FPS_HISTORY_LEN - 1)) * 50;
                    const y = 18 - ((v - fpsMin) / (fpsMax - fpsMin + 1)) * 16;
                    return `${i === 0 ? "M" : "L"}${x},${y}`;
                  }).join(" ")}
                    fill="none" stroke={fpsColor} strokeWidth="1.5"
                    strokeLinecap="round" strokeLinejoin="round"
                    style={{ filter: `drop-shadow(0 0 3px ${fpsColor})` }}
                  />
                </svg>
              </div>
              <div style={{
                width: 60, padding: "6px 8px", borderRadius: 8,
                background: PALETTE.card, border: `1px solid ${PALETTE.border}`,
                display: "flex", flexDirection: "column", alignItems: "center",
              }}>
                <span style={{ fontSize: 9, color: PALETTE.muted, textTransform: "uppercase", letterSpacing: "0.08em" }}>PING</span>
                <span style={{ fontSize: 16, fontWeight: 800, color: ping < 30 ? PALETTE.green : ping < 60 ? PALETTE.amber : PALETTE.red, lineHeight: 1.2 }}>{ping}</span>
                <span style={{ fontSize: 8, color: PALETTE.muted }}>ms</span>
              </div>
            </div>

            {/* Tabs */}
            <div style={{
              display: "flex", borderBottom: `1px solid ${PALETTE.border}`,
              background: PALETTE.card, padding: "0 14px"
            }}>
              {["status", "boost", "settings"].map(t => (
                <Tab key={t} label={t} active={tab === t} onClick={() => setTab(t)} />
              ))}
            </div>

            {/* Content */}
            <div style={{ padding: 14, maxHeight: 320, overflowY: "auto" }}>

              {/* STATUS TAB */}
              {tab === "status" && (
                <>
                  <div style={{ display: "flex", justifyContent: "space-around", marginBottom: 14 }}>
                    <RadialGauge value={cpu} max={100} label="CPU" color={PALETTE.cyan} />
                    <RadialGauge value={ram} max={100} label="RAM" color={PALETTE.accent} />
                    <RadialGauge value={gpu} max={100} label="GPU" color={PALETTE.green} />
                  </div>
                  <MiniBar label="Temperature" value={temp} max={100} unit="°C" color={tempColor} />
                  <MiniBar label="Battery" value={batPct} max={100} unit="%" color={PALETTE.amber} />
                  <div style={{
                    padding: "8px 10px", borderRadius: 8,
                    background: PALETTE.card, border: `1px solid ${PALETTE.border}`,
                    display: "flex", justifyContent: "space-between", alignItems: "center",
                    marginTop: 8,
                  }}>
                    <div>
                      <div style={{ fontSize: 10, color: PALETTE.muted, letterSpacing: "0.06em" }}>POWER DRAW</div>
                      <div style={{ fontSize: 16, fontWeight: 700, color: PALETTE.text }}>{batDrain.toFixed(1)} W</div>
                    </div>
                    <div style={{ textAlign: "right" }}>
                      <div style={{ fontSize: 10, color: PALETTE.muted, letterSpacing: "0.06em" }}>EST. REMAIN</div>
                      <div style={{ fontSize: 16, fontWeight: 700, color: PALETTE.text }}>
                        {Math.round((batPct / 100 * 4000) / (batDrain * 100) * 60)}m
                      </div>
                    </div>
                  </div>
                </>
              )}

              {/* BOOST TAB */}
              {tab === "boost" && (
                <>
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ fontSize: 10, color: PALETTE.muted, marginBottom: 8, letterSpacing: "0.08em", textTransform: "uppercase" }}>Boost Level</div>
                    <div style={{ display: "flex", gap: 6 }}>
                      {[1, 2, 3].map(l => (
                        <button key={l} onClick={() => setBoostLevel(l)} style={{
                          flex: 1, padding: "10px 0", borderRadius: 8,
                          border: `1px solid ${boostLevel === l ? LEVEL_COLORS[l] : PALETTE.border}`,
                          background: boostLevel === l ? `${LEVEL_COLORS[l]}18` : PALETTE.card,
                          color: boostLevel === l ? LEVEL_COLORS[l] : PALETTE.muted,
                          fontSize: 11, fontWeight: boostLevel === l ? 700 : 400,
                          cursor: "pointer",
                          boxShadow: boostLevel === l ? `0 0 10px ${LEVEL_COLORS[l]}44` : "none",
                          transition: "all 0.2s",
                          letterSpacing: "0.04em",
                        }}>{LEVEL_LABELS[l]}</button>
                      ))}
                    </div>
                  </div>

                  <button
                    onClick={() => setBoosting(b => !b)}
                    style={{
                      width: "100%", padding: "13px", borderRadius: 10,
                      border: `1px solid ${boosting ? PALETTE.red : LEVEL_COLORS[boostLevel]}`,
                      background: boosting
                        ? `linear-gradient(135deg, ${PALETTE.red}22, transparent)`
                        : `linear-gradient(135deg, ${LEVEL_COLORS[boostLevel]}22, transparent)`,
                      color: boosting ? PALETTE.red : LEVEL_COLORS[boostLevel],
                      fontSize: 13, fontWeight: 800, cursor: "pointer",
                      letterSpacing: "0.1em", textTransform: "uppercase",
                      boxShadow: `0 0 20px ${boosting ? PALETTE.red : LEVEL_COLORS[boostLevel]}44`,
                      transition: "all 0.2s",
                    }}
                  >
                    {boosting ? "Stop Boost" : `Start ${LEVEL_LABELS[boostLevel]} Boost`}
                  </button>

                  <div style={{ marginTop: 12 }}>
                    {[
                      { label: "FPS Gain", before: "58", after: "88+", unit: "" },
                      { label: "RAM Freed", before: "", after: "~420", unit: "MB" },
                      { label: "CPU Load", before: "38%", after: "28%", unit: "" },
                    ].map(({ label, before, after, unit }) => (
                      <div key={label} style={{
                        display: "flex", justifyContent: "space-between",
                        padding: "6px 0", borderBottom: `1px solid ${PALETTE.border}`,
                        alignItems: "center",
                      }}>
                        <span style={{ fontSize: 11, color: PALETTE.muted }}>{label}</span>
                        <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
                          {before && <span style={{ fontSize: 11, color: PALETTE.dim, textDecoration: "line-through" }}>{before}</span>}
                          <span style={{ fontSize: 12, fontWeight: 700, color: LEVEL_COLORS[boostLevel] }}>{after}{unit}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </>
              )}

              {/* SETTINGS TAB */}
              {tab === "settings" && (
                <div>
                  {[
                    { key: "fpsUnlock", label: "FPS Unlock", desc: "Remove frame rate cap" },
                    { key: "networkPriority", label: "Network Priority", desc: "Prioritize game traffic" },
                    { key: "ramClean", label: "Auto RAM Clean", desc: "Clear background apps" },
                    { key: "thermalCap", label: "Thermal Limit", desc: "Cap at 65°C to protect device" },
                    { key: "noNotif", label: "Block Notifications", desc: "Mute during sessions" },
                    { key: "touchBoost", label: "Touch Sampling Boost", desc: "High-rate touch input" },
                  ].map(({ key, label, desc }) => (
                    <div key={key} style={{
                      display: "flex", justifyContent: "space-between",
                      alignItems: "center", padding: "9px 0",
                      borderBottom: `1px solid ${PALETTE.border}`,
                    }}>
                      <div>
                        <div style={{ fontSize: 12, fontWeight: 600, color: PALETTE.text }}>{label}</div>
                        <div style={{ fontSize: 10, color: PALETTE.muted, marginTop: 1 }}>{desc}</div>
                      </div>
                      <ToggleSwitch on={toggles[key]} onChange={(v) => setToggle(key, v)} />
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Footer */}
            <div style={{
              padding: "8px 14px",
              background: PALETTE.card,
              borderTop: `1px solid ${PALETTE.border}`,
              display: "flex", justifyContent: "space-between", alignItems: "center",
            }}>
              <span style={{ fontSize: 9, color: PALETTE.muted, letterSpacing: "0.1em" }}>dBost v1.0.0</span>
              <div style={{ display: "flex", gap: 8 }}>
                {[
                  { label: "CPU", val: Math.round(cpu), color: PALETTE.cyan },
                  { label: "RAM", val: Math.round(ram), color: PALETTE.accent },
                  { label: "TEMP", val: Math.round(temp) + "°", color: tempColor },
                ].map(({ label, val, color }) => (
                  <div key={label} style={{ textAlign: "center" }}>
                    <div style={{ fontSize: 10, fontWeight: 700, color }}>{val}</div>
                    <div style={{ fontSize: 8, color: PALETTE.muted }}>{label}</div>
                  </div>
                ))}
              </div>
            </div>
          </>
        )}

        {/* Minimized state */}
        {minimized && (
          <div style={{
            padding: "8px 14px",
            display: "flex", gap: 12, alignItems: "center",
          }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: fpsColor }}>{Math.round(fps)} FPS</div>
            <div style={{ fontSize: 12, fontWeight: 700, color: tempColor }}>{Math.round(temp)}°C</div>
            <div style={{ fontSize: 12, fontWeight: 700, color: ping < 30 ? PALETTE.green : PALETTE.amber }}>{ping}ms</div>
          </div>
        )}
      </div>

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.5; transform: scale(1.3); }
        }
        * { box-sizing: border-box; }
        ::-webkit-scrollbar { width: 4px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: ${PALETTE.dim}; border-radius: 2px; }
      `}</style>
    </div>
  );
}
