const $ = id => document.getElementById(id);
const TOKEN_KEY = 'marstek_api_token';
function readToken() { try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; } }
function saveToken(value) { try { value ? localStorage.setItem(TOKEN_KEY, value) : localStorage.removeItem(TOKEN_KEY); } catch (e) { /* private mode */ } }
(function bootstrapToken() {
  // One-time bootstrap: open /?token=XXXX (or #token=XXXX) once, the token moves to localStorage.
  const url = new URL(window.location.href);
  const fromHash = new URLSearchParams(url.hash.replace(/^#/, '')).get('token');
  const token = url.searchParams.get('token') || fromHash;
  if (!token) return;
  saveToken(token.trim());
  url.searchParams.delete('token'); url.hash = '';
  window.history.replaceState(null, '', url.pathname + url.search);
})();
async function apiFetch(path) {
  const token = readToken();
  const headers = token ? {Authorization: `Bearer ${token}`} : {};
  return fetch(path, {cache: 'no-store', headers});
}
function showTokenForm(failed) {
  const form = $('token-form'); if (!form) return;
  form.hidden = false;
  $('token-error').hidden = !failed;
  $('token-input').focus();
}
$('token-form')?.addEventListener('submit', event => {
  event.preventDefault();
  saveToken($('token-input').value.trim());
  $('token-form').hidden = true;
  refresh();
});
const history = [];
const MAX_POINTS = 90;
const labels = {
  'Wifi.GetStatus': 'Wi‑Fi', 'BLE.GetStatus': 'Bluetooth', 'Bat.GetStatus': 'Батарея',
  'ES.GetStatus': 'Енергосистема', 'ES.GetMode': 'Режим', 'EM.GetStatus': 'Лічильник EM'
};
const ukMode = value => ({Auto:'Авто',AI:'ШІ',Manual:'Ручний',Passive:'Пасивний',UPS:'UPS',Ups:'UPS'})[value] || value || '—';
const yesNo = value => value === true ? 'Так' : value === false ? 'Ні' : value ?? '—';
const num = (value, unit = '') => value === null || value === undefined || value === '' ? '—' : `${value}${unit ? ` ${unit}` : ''}`;
function formatKwh(value) {
  const number = Number(value);
  return value === null || value === undefined || value === '' || !Number.isFinite(number)
    ? '—'
    : `${(number / 1000).toLocaleString('uk-UA', {minimumFractionDigits:2, maximumFractionDigits:2})} kWh`;
}
function formatPowerKw(value) {
  const watts = Number(value);
  if (!Number.isFinite(watts)) return '—';
  const sign = watts > 0 ? '+' : watts < 0 ? '−' : '';
  return `≈ ${sign}${(Math.abs(watts) / 1000).toLocaleString('uk-UA', {minimumFractionDigits:2, maximumFractionDigits:2})} кВт`;
}
const safeText = value => value === null || value === undefined || value === '' ? '—' : String(value);
const good = method => method?.ok && method.data && typeof method.data === 'object' ? method.data : {};

function set(id, value) { const node = $(id); if (node) node.textContent = safeText(value); }
function renderMethodList(methods) {
  const root = $('method-list'); root.replaceChildren();
  for (const [name, label] of Object.entries(labels)) {
    const item = methods?.[name];
    const row = document.createElement('div'); row.className = 'method-row';
    const dot = document.createElement('i'); dot.className = `method-dot ${item?.ok ? 'ok' : 'bad'}`;
    const title = document.createElement('span'); title.textContent = label;
    const state = document.createElement('b'); state.textContent = item?.ok ? 'На зв’язку' : (item?.error === 'No response' ? 'Немає відповіді' : (item?.error || 'Очікування'));
    row.append(dot, title, state); root.append(row);
  }
}
function addPoint(es, bat) {
  history.push({t: Date.now(), backup:Number(es.offgrid_power), grid:Number(es.ongrid_power), soc:Number(bat.soc ?? es.bat_soc)});
  if (history.length > MAX_POINTS) history.shift();
  drawChart();
}
function drawChart() {
  const canvas = $('history-chart'); if (!canvas) return;
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.round(rect.width * ratio); canvas.height = Math.round(rect.height * ratio);
  const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
  const w=rect.width,h=rect.height,p={l:36,r:12,t:14,b:22},cw=w-p.l-p.r,ch=h-p.t-p.b;
  ctx.clearRect(0,0,w,h);
  ctx.strokeStyle='rgba(176,193,217,.10)'; ctx.lineWidth=1;
  for(let i=0;i<4;i++){const y=p.t+ch*i/3;ctx.beginPath();ctx.moveTo(p.l,y);ctx.lineTo(w-p.r,y);ctx.stroke();}
  if(history.length<2){$('chart-empty').classList.remove('hidden');return;}
  $('chart-empty').classList.add('hidden');
  const finite = key => history.map(x=>x[key]).filter(Number.isFinite);
  const powerValues=[...finite('backup'),...finite('grid')];
  let min=Math.min(0,...powerValues), max=Math.max(100,...powerValues); if(max-min<50) max=min+50;
  const x=i=>p.l+cw*i/(history.length-1), yp=v=>p.t+ch-(v-min)/(max-min)*ch, ys=v=>p.t+ch-v/100*ch;
  const line=(key,color,mapper)=>{ctx.beginPath();let begun=false;history.forEach((pt,i)=>{if(!Number.isFinite(pt[key]))return;const xx=x(i),yy=mapper(pt[key]);if(!begun){ctx.moveTo(xx,yy);begun=true;}else ctx.lineTo(xx,yy);});ctx.strokeStyle=color;ctx.lineWidth=2;ctx.lineJoin='round';ctx.lineCap='round';ctx.stroke();};
  line('backup','#55dfc2',yp); line('grid','#ffb467',yp); line('soc','#ac9aff',ys);
  ctx.fillStyle='rgba(180,194,214,.55)';ctx.font='10px system-ui';ctx.fillText(`${Math.round(max)} W`,2,p.t+5);ctx.fillText(`${Math.round(min)} W`,2,h-p.b);
}
function formatDuration(seconds) {
  if (seconds === 0) return 'Готово';
  if (seconds === null || seconds === undefined || !Number.isFinite(Number(seconds))) return '—';
  const total = Math.max(1, Math.ceil(Number(seconds) / 60));
  const days = Math.floor(total / 1440), hours = Math.floor((total % 1440) / 60), minutes = total % 60;
  if (days) return `${days} д ${hours} год`;
  if (hours) return minutes ? `${hours} год ${minutes} хв` : `${hours} год`;
  return `${minutes} хв`;
}
function renderBatteryFlow(flow = {}) {
  const windowSeconds = Number(flow.window_seconds) || 0;
  const windowMinutes = Math.round(windowSeconds / 60);
  const measured = flow.estimated === false;
  set('battery-flow-window', measured ? 'Вимір · мережевий / резервний вихід' : flow.available ? `Вікно ${windowMinutes} хв · ${flow.sample_count} вим.` : `Збір даних · ${windowMinutes} хв`);
  set('battery-flow-basis', measured ? 'Виміряно на мережевому / резервному виході' : 'Середня потужність за трендом ємності (оцінка)');
  if (!flow.available) {
    set('battery-flow-power', '—');
    set('battery-flow-state', 'Батарея ↔ Venus E · збираю виміри');
    set('battery-flow-arrow', '↔');
    set('battery-state', 'Очікую середній потік');
    set('charge-eta', '—'); set('charge-eta-note', 'Очікую стабільний потік');
    set('discharge-eta', '—'); set('discharge-eta-note', 'Очікую стабільний потік');
    return;
  }
  const power = Number(flow.power_w) || 0;
  if (flow.direction === 'charging') {
    set('battery-flow-power', formatPowerKw(power));
    set('battery-flow-state', 'Venus E → батарея · заряд');
    set('battery-flow-arrow', '→');
    set('battery-state', 'Заряджається');
    set('charge-eta', formatDuration(flow.charge_eta_seconds));
    set('charge-eta-note', 'За поточним середнім темпом');
    set('discharge-eta', 'Не розряджається');
    set('discharge-eta-note', 'Зараз є зарядний потік');
  } else if (flow.direction === 'discharging') {
    set('battery-flow-power', formatPowerKw(power));
    set('battery-flow-state', 'Батарея → Venus E · розряд');
    set('battery-flow-arrow', '→');
    set('battery-state', 'Розряджається');
    set('charge-eta', 'Не заряджається');
    set('charge-eta-note', 'Зараз є розрядний потік');
    set('discharge-eta', formatDuration(flow.discharge_eta_seconds));
    set('discharge-eta-note', 'До резерву розряду (оцінка)');
  } else {
    set('battery-flow-power', '≈ 0,00 кВт');
    set('battery-flow-state', 'Батарея ↔ Venus E · очікування');
    set('battery-flow-arrow', '↔');
    set('battery-state', 'Очікування');
    set('charge-eta', '—'); set('charge-eta-note', 'Потік заряджання не виявлено');
    set('discharge-eta', '—'); set('discharge-eta-note', 'Потік розряджання не виявлено');
  }
}
function formatClock(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const time = d.toLocaleTimeString('uk-UA', {hour:'2-digit', minute:'2-digit', timeZone:'Europe/Kyiv'});
  const today = new Date().toLocaleDateString('uk-UA', {timeZone:'Europe/Kyiv'});
  const day = d.toLocaleDateString('uk-UA', {timeZone:'Europe/Kyiv'});
  return day === today ? time : `${d.toLocaleDateString('uk-UA', {day:'2-digit', month:'2-digit', timeZone:'Europe/Kyiv'})} ${time}`;
}
const finiteOrNull = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Number(value);
const watts = value => { const n = finiteOrNull(value); return n === null ? '—' : `${Math.round(Math.abs(n))} W`; };
function outageSourceText(outage) {
  if (outage?.schedule_unavailable) return 'Графік недоступний — світло визначається за резервним виходом';
  if (!outage || outage.provider === 'off' || !outage.available) return 'Графік вимкнено';
  if (!outage.source_name) return 'Джерело недоступне';
  const fallback = outage.provider === 'auto' && Object.keys(outage.source_errors || {}).length;
  const name = outage.source === 'ua-outages' && outage.source_detail ? `${outage.source_detail} · ua-outages` : outage.source_name;
  return `${name}${fallback ? ' · резерв' : ''}${outage.source_stale ? ' · застаріле' : ''}`;
}
function autonomyBasisText(a) {
  if (a.load_basis === 'measured_backup_output') return `при поточному навантаженні ${Math.round(a.load_w)} Вт (вимір резервного виходу)`;
  if (a.load_basis === 'measured_average_24h') return `при середньому споживанні ${Math.round(a.load_w)} Вт (за 24 год)`;
  if (a.load_basis === 'configured_average') return `при заданому споживанні ${Math.round(a.load_w)} Вт`;
  if (a.load_basis === 'measured_discharge') return `при поточному розряді ≈ ${Math.round(a.load_w)} Вт (оцінка)`;
  return '';
}
function shortDuration(seconds) {
  const s = finiteOrNull(seconds);
  if (s === null) return '—';
  // «1 год», «2 год 5 хв», «1 хв», «0 хв» — full unit words, never «1г 0хв».
  const total = Math.round(s / 60), h = Math.floor(total / 60), m = total % 60;
  // No-break space keeps «11 год» / «35 хв» whole; a long value wraps between them instead of «11 год …».
  if (!h) return `${m}\u00A0хв`;
  return m ? `${h}\u00A0год ${m}\u00A0хв` : `${h}\u00A0год`;
}
const SVG_NS = 'http://www.w3.org/2000/svg';
function svgEl(name, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, name);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  if (text !== undefined) node.textContent = text;
  return node;
}
function floorPct(outage) { return finiteOrNull(outage?.autonomy?.floor_pct); }
/** Light state for the hero: ONLY from the backup output; a lost connection is never «світла нема». */
function lightView(data, fetchFailed) {
  const outage = data?.power_outage || {}, methods = data?.methods || {};
  const collected = data?.collected_at ? Date.parse(data.collected_at) : NaN;
  const interval = Number(data?.poll_interval_seconds) || 30;
  const stale = !Number.isFinite(collected) || Date.now() - collected > Math.max(180, interval * 6) * 1000;
  const noLink = fetchFailed || data?.connection?.lost || !methods['ES.GetStatus']?.ok || stale;
  const cur = outage.current_outage, now = Date.now();
  const scheduledNow = Boolean(cur && Date.parse(cur.start) <= now && Date.parse(cur.end) > now);
  if (noLink) return {state: 'nolink', title: 'Немає зв’язку з батареєю', hint: scheduledNow ? 'за графіком зараз відключення' : null};
  if (outage.grid_state === 'off') return {state: 'off', title: 'Світла нема', hint: null};
  if (outage.grid_state === 'on') return {state: 'on', title: 'Світло є', hint: null};
  return {state: 'unknown', title: 'Світло: —', hint: null};
}
function renderToday(today) {
  const tiles = $('today-tiles'), note = $('today-note');
  if (tiles) tiles.hidden = !today;
  if (note) note.hidden = Boolean(today);
  today = today || {};
  set('today-charge', shortDuration(today.charge_seconds));
  set('today-discharge', shortDuration(today.discharge_seconds));
  set('today-outage', shortDuration(today.outage_seconds));
}
function kyivMidnight(now = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {timeZone: 'Europe/Kyiv', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).formatToParts(now).map(p => [p.type, p.value]));
  const sinceMidnight = (Number(parts.hour) * 3600 + Number(parts.minute) * 60 + Number(parts.second)) * 1000;
  return now.getTime() - sinceMidnight - now.getMilliseconds();
}
function kyivHm(ms) { return new Date(ms).toLocaleTimeString('uk-UA', {hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Kyiv'}); }
/** Today / tomorrow (Kyiv) with windows clipped to each day; same rules as the Android ScheduleDays. */
function scheduleDays(outage = {}, nowMs = Date.now()) {
  const todayStart = kyivMidnight(new Date(nowMs));
  const tomorrowStart = kyivMidnight(new Date(todayStart + 26 * 3600 * 1000));
  const afterStart = kyivMidnight(new Date(tomorrowStart + 26 * 3600 * 1000));
  const dates = outage.schedule_dates || [];
  const isoDay = ms => new Date(ms).toLocaleDateString('en-CA', {timeZone: 'Europe/Kyiv'});
  return [[todayStart, tomorrowStart, true], [tomorrowStart, afterStart, false]].map(([start, end, isToday]) => {
    const clipped = (outage.windows || []).map(w => ({a: Math.max(Date.parse(w.start), start), b: Math.min(Date.parse(w.end), end), maybe: w.kind === 'maybe'}))
      .filter(w => Number.isFinite(w.a) && Number.isFinite(w.b) && w.b > w.a).sort((x, y) => x.a - y.a);
    const label = w => `${kyivHm(w.a)}–${w.b >= end ? '24:00' : kyivHm(w.b)}`;
    const published = Boolean(outage.available) && (dates.includes(isoDay(start)) || clipped.length > 0 || (!dates.length && outage.schedule_published && isToday));
    const definite = clipped.filter(w => !w.maybe).map(label), maybe = clipped.filter(w => w.maybe).map(label);
    const text = definite.length ? `Відключення ${definite.join(', ')}` : !published ? (isToday ? 'Графік не опубліковано' : 'Графік ще не опубліковано') : maybe.length ? 'Без точних відключень' : 'Без відключень';
    return {isToday, title: isToday ? 'Сьогодні' : 'Завтра', start, end, published, definite, maybe, text,
      segments: clipped.map(w => ({a: (w.a - start) / (end - start), b: (w.b - start) / (end - start), maybe: w.maybe,
        from: kyivHm(w.a), to: w.b >= end ? '24:00' : kyivHm(w.b)})),
      now: isToday ? Math.max(0, Math.min(1, (nowMs - start) / (end - start))) : null, nowText: isToday ? kyivHm(nowMs) : null};
  });
}
/**
 * Time labels above a 0–24 h schedule bar — same rules as the Android ScheduleTimeLabels (keep in sync):
 * start label left-aligned to the block's left edge, end label right-aligned to its right edge, now label centred
 * on the marker, all clamped to [0, width]. A window whose two labels would touch gets one «07:00–13:00» centred
 * over the block; if that still collides it gets none (the text line lists it). Definite windows before «можливе»;
 * the now label is hidden inside a labelled window or on any collision. segments: [{a, b, maybe, from, to}] (0..1).
 */
function scheduleTimeLabels(segments, now, nowText, width, measure, gap = 4) {
  const placed = [], shown = [];
  const x = f => Math.max(0, Math.min(1, f)) * width;
  const at = (text, kind, leftOf) => { const w = measure(text); return {text, kind, w, left: Math.max(0, Math.min(Math.max(0, width - w), leftOf(w)))}; };
  const touches = (p, q) => p.left < q.left + q.w + gap && q.left < p.left + p.w + gap;
  const free = l => placed.every(p => !touches(p, l));
  const ordered = (segments || []).filter(s => s.from && s.to && s.b > s.a).slice().sort((p, q) => (p.maybe - q.maybe) || (p.a - q.a));
  for (const s of ordered) {
    const kind = s.maybe ? 'maybe' : 'off';
    const from = at(s.from, kind, () => x(s.a)), to = at(s.to, kind, w => x(s.b) - w);
    if (!touches(from, to) && free(from) && free(to)) { placed.push(from, to); shown.push(s); continue; }
    const merged = at(`${s.from}–${s.to}`, kind, w => (x(s.a) + x(s.b)) / 2 - w / 2);
    if (free(merged)) { placed.push(merged); shown.push(s); }
  }
  if (now !== null && now !== undefined && nowText) {
    const inside = shown.some(s => now >= s.a && now <= s.b);
    const label = at(nowText, 'now', w => x(now) - w / 2);
    if (!inside && free(label)) placed.push(label);
  }
  return placed.sort((p, q) => p.left - q.left);
}
// end scheduleTimeLabels
/** Schedule sentence that never contradicts the measured light (backup output). */
function scheduleDetail(outage = {}, gridState = outage.grid_state) {
  if (outage.schedule_unavailable) return 'Графік недоступний — світло визначається за резервним виходом';
  if (!outage.available) return 'Графік вимкнено';
  if (outage.emergency) return 'Діють аварійні відключення — графік не застосовується';
  const cur = outage.current_outage, next = outage.next_outage, maybe = (outage.maybe_outages || [])[0];
  if (cur?.end) {
    if (gridState === 'off') return `Світло за графіком о ${formatClock(cur.end)}`;
    if (gridState === 'on') return `За графіком відключення до ${formatClock(cur.end)} · світло є`;
    return `За графіком відключення до ${formatClock(cur.end)}`;
  }
  if (next?.start) {
    const t = Date.parse(next.start), todayStart = kyivMidnight();
    const tomorrowStart = kyivMidnight(new Date(todayStart + 26 * 3600 * 1000)), afterStart = kyivMidnight(new Date(tomorrowStart + 26 * 3600 * 1000));
    if (t < tomorrowStart) return `Відключення о ${kyivHm(t)}`;
    if (t < afterStart) return `Відключення завтра о ${kyivHm(t)}`;
    return `Відключення ${formatClock(next.start).replace(' ', ' о ')}`;
  }
  if (maybe) return `Можливе відключення ${formatClock(maybe.start)}–${formatClock(maybe.end)}`;
  return outage.schedule_published ? 'За графіком без відключень' : 'Графік не опубліковано';
}
function el(tag, cls, text) { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; }
let lastScheduleOutage = null, labelCtx = null;
function labelWidth(text) {
  labelCtx = labelCtx || document.createElement('canvas').getContext('2d');
  labelCtx.font = `700 10px ${getComputedStyle(document.body).fontFamily || 'system-ui'}`;
  return Math.ceil(labelCtx.measureText(text).width);
}
function renderScheduleTimes(host, day, width) {
  host.replaceChildren();
  if (!width) return;
  for (const l of scheduleTimeLabels(day.segments, day.now, day.nowText, width, labelWidth)) {
    const span = el('span', l.kind, l.text); span.style.left = `${l.left.toFixed(1)}px`; host.append(span);
  }
}
window.addEventListener('resize', () => { if (lastScheduleOutage) renderScheduleDays(lastScheduleOutage); });
function renderScheduleDays(outage = {}) {
  const host = $('schedule-days'); if (!host) return;
  host.replaceChildren();
  const block = host.closest('.schedule-block');
  if (block) block.hidden = !outage.available || Boolean(outage.schedule_unavailable);
  for (const day of scheduleDays(outage)) {
    if (!day.isToday && !day.published) { const p = el('div', 'sched-day pending'); p.append(el('p', '', 'Завтра: графік ще не опубліковано')); host.append(p); continue; }
    const box = el('div', 'sched-day');
    box.append(el('h3', '', day.title));
    const track = el('div', `sched-track${day.published ? '' : ' unpublished'}`);
    track.setAttribute('role', 'img');
    track.setAttribute('aria-label', `${day.title}: ${day.text}${day.maybe.length ? `; можливе ${day.maybe.join(', ')}` : ''}`);
    for (const seg of day.segments) {
      const i = el('i', `seg ${seg.maybe ? 'maybe' : 'off'}`);
      i.style.left = `${(seg.a * 100).toFixed(2)}%`; i.style.width = `${((seg.b - seg.a) * 100).toFixed(2)}%`;
      track.append(i);
    }
    for (const h of [6, 12, 18]) { const i = el('i', 'six'); i.style.left = `${(h / 24 * 100).toFixed(3)}%`; track.append(i); }
    if (day.now !== null) { const i = el('i', 'now'); i.style.left = `${(day.now * 100).toFixed(2)}%`; track.append(i); }
    const ticks = el('div', 'sched-ticks');
    for (let h = 0; h <= 24; h++) { const i = el('i', h % 6 === 0 ? 'major' : ''); i.style.left = `calc(${(h / 24 * 100).toFixed(3)}% - ${h === 24 ? 1 : 0}px)`; ticks.append(i); }
    const axis = el('div', 'sched-axis');
    for (const h of [0, 6, 12, 18, 24]) { const sp = el('span', '', String(h)); sp.style.left = `${h / 24 * 100}%`; axis.append(sp); }
    const times = el('div', 'sched-times'); times.setAttribute('aria-hidden', 'true');
    box.append(times, track, ticks, axis, el('p', `sched-text${day.definite.length ? ' off' : ''}`, day.text));
    if (day.maybe.length) box.append(el('p', 'sched-text maybe', `Можливе ${day.maybe.join(', ')}`));
    host.append(box);
    if (day.published || day.nowText) renderScheduleTimes(times, day, track.getBoundingClientRect().width);
  }
  lastScheduleOutage = outage;
  set('schedule-caption', `Графік відключень${outage.group ? ` · черга ${outage.group}` : ''}`);
}
const EVENT_MARK = {light_lost: {label: '●', cls: 'ev-lost', title: 'Світло зникло'}, light_returned: {label: '○', cls: 'ev-back', title: 'Світло повернулось'},
  charging_started: {label: '⚡', cls: 'ev-charge', title: 'Почалось заряджання'}, charged_full: {label: '✓', cls: 'ev-full', title: 'Заряджено'}};
function renderSocChart(insights = {}, outage = {}) {
  const svg = $('soc-chart'); if (!svg) return;
  svg.replaceChildren();
  const W = 320, H = 150, L = 4, R = 286, T = 10, B = 132;
  const end = Date.now(), start = end - 24 * 3600 * 1000;
  const x = t => L + Math.max(0, Math.min(1, (t - start) / (end - start))) * (R - L);
  const y = soc => B - Math.max(0, Math.min(100, soc)) / 100 * (B - T);
  for (const p of insights.outage_periods || []) {
    const a = x(Date.parse(p.start)), b = x(Date.parse(p.end));
    if (b - a > 0.3) svg.append(svgEl('rect', {x: a.toFixed(1), y: T, width: (b - a).toFixed(1), height: B - T, class: 'chart-outage'}));
  }
  for (const level of [25, 50, 75, 100]) {
    svg.append(svgEl('line', {x1: L, x2: R, y1: y(level), y2: y(level), class: 'spark-grid'}));
    svg.append(svgEl('text', {x: R + 4, y: y(level) + 3.5, class: 'chart-label'}, `${level}%`));
  }
  const floor = floorPct(outage);
  if (floor !== null) {
    svg.append(svgEl('line', {x1: L, x2: R, y1: y(floor), y2: y(floor), class: 'chart-floor'}));
    svg.append(svgEl('text', {x: R + 4, y: y(floor) + 3.5, class: 'chart-label floor'}, `${Math.round(floor)}%`));
  }
  for (const [i, label] of ['−24 год', '−12 год', 'зараз'].entries()) {
    svg.append(svgEl('text', {x: L + i * (R - L) / 2, y: H - 3, class: 'chart-label', 'text-anchor': i === 0 ? 'start' : i === 2 ? 'end' : 'middle'}, label));
  }
  const clean = (insights.soc_history || []).map(p => ({t: Date.parse(p.t), soc: finiteOrNull(p.soc)})).filter(p => Number.isFinite(p.t) && p.soc !== null);
  if (clean.length < 2) {
    set('spark-range', clean.length ? `${Math.round(clean[0].soc)}% · історія збирається` : 'Історія збирається');
    return;
  }
  const coords = clean.map(p => `${x(p.t).toFixed(1)},${y(p.soc).toFixed(1)}`);
  svg.append(svgEl('path', {d: `M${x(clean[0].t).toFixed(1)},${B} L${coords.join(' L')} L${x(clean[clean.length - 1].t).toFixed(1)},${B} Z`, class: 'spark-area'}));
  svg.append(svgEl('polyline', {points: coords.join(' '), class: 'spark-line'}));
  const last = clean[clean.length - 1];
  svg.append(svgEl('circle', {cx: x(last.t).toFixed(1), cy: y(last.soc).toFixed(1), r: 3.5, class: 'chart-now'}));
  const socAt = t => { let best = clean[0]; for (const p of clean) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p; return best.soc; };
  for (const ev of insights.chart_events || []) {
    const mark = EVENT_MARK[ev.kind]; const t = Date.parse(ev.t);
    if (!mark || !Number.isFinite(t) || t < start) continue;
    const g = svgEl('g', {class: `chart-event ${mark.cls}`});
    g.append(svgEl('line', {x1: x(t), x2: x(t), y1: T, y2: B, class: 'chart-event-line'}));
    const cy = Math.max(T + 6, y(socAt(t)) - 9);
    g.append(svgEl('circle', {cx: x(t), cy, r: 6}));
    g.append(svgEl('text', {x: x(t), y: cy + 3, 'text-anchor': 'middle'}, mark.label));
    g.append(svgEl('title', {}, `${mark.title} · ${formatClock(ev.t)}`));
    svg.append(g);
  }
  const socs = clean.map(p => p.soc);
  set('spark-range', `${Math.round(Math.min(...socs))}–${Math.round(Math.max(...socs))}%`);
}
function renderHero(data, bat, socValue, fetchFailed = false) {
  const hero = $('hero'); if (!hero) return;
  const outage = data.power_outage || {}, insights = data.insights || {}, flow = data.battery_flow || {};
  const a = outage.autonomy || {};
  const soc = finiteOrNull(socValue);
  const light = lightView(data, fetchFailed);
  set('outage-group', outage.group);
  set('outage-source', outageSourceText(outage));
  set('backup-today', formatKwh(insights.backup_energy_today_wh));
  set('standby-w', finiteOrNull(insights.standby_w) !== null ? `≈ ${Math.round(insights.standby_w)} Вт` : '—');
  renderToday(insights.today || null);
  renderScheduleDays(outage);
  renderSocChart(insights, outage);
  const runtime = finiteOrNull(a.runtime_seconds);
  const floor = floorPct(outage);
  const floorText = floor !== null ? `понад резерв ${Math.round(floor)}%` : 'до 0%';
  const err = outage.error && outage.error !== 'not_fetched_yet' ? ` Графік не оновився (${outage.error}).` : '';
  set('hero-title', light.title);
  const forecast = $('hero-forecast');
  forecast.className = 'hero-forecast';
  if (light.state === 'nolink') {
    set('hero-forecast', 'Стан світла невідомий');
    set('hero-sub', light.hint ? `${light.hint} · ${scheduleDetail(outage, 'unknown')}` : scheduleDetail(outage, 'unknown'));
    set('hero-note', `Світло визначається лише за потужністю на резервному виході батареї. Без відповіді батареї стан невідомий — це не означає, що світла немає.${err}`);
  } else if (light.state === 'off') {
    const current = outage.current_outage;
    const covers = a.covers_next_outage;
    if (runtime === null) set('hero-forecast', 'Запас: збираю виміри навантаження…');
    else if (covers === false) { set('hero-forecast', `Вистачить лише на ${formatDuration(runtime)}`); forecast.classList.add('bad'); }
    else { set('hero-forecast', `Вистачить на ${formatDuration(runtime)}`); forecast.classList.add('good'); }
    // Only a positive backup-output reading is the house load (negative = sensor noise).
    const backupW = finiteOrNull(insights.backup_output_w);
    const parts = [backupW !== null && backupW > 0 ? `Споживання ${Math.round(backupW)} Вт` : null,
      current?.end ? (current.kind === 'schedule' ? `Світло за графіком о ${formatClock(current.end)}` : `Повернення о ${formatClock(current.end)}`) : 'час повернення невідомий'];
    set('hero-sub', parts.filter(Boolean).join(' · '));
    set('hero-note', `Час — енергія ${floorText} ${autonomyBasisText(a)}.${err}`);
  } else {
    if (flow.available && flow.direction === 'charging') set('hero-forecast', `Заряджається · до 100% ≈ ${formatDuration(flow.charge_eta_seconds)}`);
    else if (soc !== null && soc >= 99 && (!flow.available || flow.direction === 'idle')) set('hero-forecast', 'Заряджено');
    else if (runtime !== null) set('hero-forecast', `Запасу на ${formatDuration(runtime)}`);
    else set('hero-forecast', flow.direction === 'discharging' ? 'Розряджається' : 'Очікування');
    // «не опубліковано» / «без відключень» is already in the schedule rows below — not repeated in the hero.
    const detail = scheduleDetail(outage, light.state);
    const shownBelow = outage.available && !outage.schedule_unavailable && ['Графік не опубліковано', 'За графіком без відключень'].includes(detail);
    set('hero-sub', detail);
    const subNode = $('hero-sub'); if (subNode) subNode.dataset.redundant = shownBelow ? '1' : '';
    set('hero-note', runtime === null
      ? `Прогноз запасу з’явиться після першого відключення (навантаження міряється на резервному виході).${err}`
      : `Запас — енергія ${floorText} ${autonomyBasisText(a)}. Світло — за резервним виходом Venus.${err}`);
  }
  hero.dataset.state = light.state;
  const sub = $('hero-sub'); if (sub) { sub.hidden = light.state !== 'off' && light.state !== 'nolink' && sub.dataset.redundant === '1'; }
}
function render(data) {
  const m=data.methods||{}, wifi=good(m['Wifi.GetStatus']), ble=good(m['BLE.GetStatus']), bat=good(m['Bat.GetStatus']), es=good(m['ES.GetStatus']), mode=good(m['ES.GetMode']), em=good(m['EM.GetStatus']);
  const required = m['Bat.GetStatus']?.ok && m['ES.GetStatus']?.ok;
  $('conn-dot').className=`status-led ${required?'on':'off'}`;
  set('conn-label', required ? 'Пристрій відповідає' : 'Часткова телеметрія');
  const updated=data.collected_at ? new Date(data.collected_at) : null;
  set('last-updated', updated ? `Оновлено ${updated.toLocaleTimeString('uk-UA',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}` : 'Очікую на перший вимір');
  set('device-model', data.device?.model || 'Marstek'); set('device-ip',data.device?.ip); set('device-port',data.device?.udp_port);
  const soc=bat.soc ?? es.bat_soc;
  const socNumber=Number(soc);
  set('soc-value', Number.isFinite(socNumber) ? Math.round(socNumber) : '—');
  const socPct = Math.max(0, Math.min(100, socNumber || 0));
  $('soc-ring').style.setProperty('--soc', `${socPct}%`);
  // gradient spans the full track: the fill's colour at its tip matches the charge level (like the app)
  const fill = document.querySelector('#soc-ring .soc-fill'); if (fill) fill.style.backgroundSize = socPct > 0 ? `${(10000 / socPct).toFixed(1)}% 100%` : '100% 100%';
  $('soc-ring').setAttribute('aria-valuenow', Number.isFinite(socNumber) ? String(Math.round(socNumber)) : '0');
  const batteryWh = bat.bat_capacity ?? es.bat_cap;
  const ratedWh = bat.rated_capacity ?? es.bat_cap;
  set('capacity-kwh', formatKwh(batteryWh));
  set('capacity', batteryWh != null && ratedWh != null ? `${formatKwh(batteryWh)} / ${formatKwh(ratedWh)}` : formatKwh(batteryWh));
  set('temperature', num(bat.bat_temp,'°C'));
  set('mode',ukMode(mode.mode));
  set('charge-permission',`Заряджання: ${yesNo(bat.charg_flag)}`); set('discharge-permission',`Розряджання: ${yesNo(bat.dischrg_flag)}`);
  renderBatteryFlow(data.battery_flow || {});
  const usable = finiteOrNull(data.power_outage?.autonomy?.usable_wh), flowW = finiteOrNull(data.battery_flow?.power_w);
  if (data.battery_flow?.direction === 'discharging' && usable !== null && flowW) set('discharge-eta', formatDuration(usable / Math.abs(flowW) * 3600));
  const floor = floorPct(data.power_outage);
  set('discharge-eta-label', floor !== null ? `До резерву ${Math.round(floor)}%` : 'До 0%');
  lastData = data;
  renderHero(data, bat, soc);
  set('grid-sign-note', 'Знак +/− як у пристрої');
  set('grid-power',num(es.ongrid_power,'W')); set('offgrid-power',num(es.offgrid_power,'W'));
  set('ct-state',mode.ct_state === 0 ? 'Не підключено' : mode.ct_state === 1 ? 'Підключено' : '—');
  set('total-grid-in',formatKwh(es.total_grid_input_energy)); set('total-grid-out',formatKwh(es.total_grid_output_energy)); set('total-load',formatKwh(es.total_load_energy));
  set('em-a',num(em.a_power,'W'));set('em-b',num(em.b_power,'W'));set('em-c',num(em.c_power,'W'));set('em-total',num(em.total_power,'W'));
  set('em-energy',em.input_energy != null || em.output_energy != null ? `${formatKwh(em.input_energy)} / ${formatKwh(em.output_energy)}` : '—');

  set('wifi-rssi',num(wifi.rssi,'dBm'));set('wifi-ip',wifi.sta_ip);set('wifi-gateway',wifi.sta_gate);set('wifi-mask',wifi.sta_mask);set('wifi-dns',wifi.sta_dns);set('wifi-ssid',wifi.ssid);set('wifi-mac',wifi.wifi_mac);set('ble-state',ble.state);set('ble-mac',ble.ble_mac);
  const okCount=Object.values(m).filter(x=>x?.ok).length; set('api-summary',`${okCount}/${Object.keys(labels).length} методів відповіли`);
  renderMethodList(m);
  $('raw-data').textContent=JSON.stringify(m,null,2);
  if (m['ES.GetStatus']?.ok) addPoint(es,bat);
}
async function refresh() {
  try {
    const r = await apiFetch('/api/status');
    if (r.status === 401) { const had = Boolean(readToken()); saveToken(''); showTokenForm(had); throw new Error('Потрібен токен'); }
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    render(await r.json());
  }
  catch(e){
    $('conn-dot').className='status-led off';set('conn-label','Дашборд не отримує дані');set('last-updated',e.message);
    if (lastData) { const m = lastData.methods || {}; renderHero(lastData, good(m['Bat.GetStatus']), good(m['Bat.GetStatus']).soc, true); }
  }
}
let lastData = null;
let journalEvents = [];
let journalFilter = '';
const CATEGORY_LABEL = {light: 'Світло', battery: 'Батарея', connection: 'Зв’язок', anomaly: 'Аномалія', system: 'Система'};
let journalStartedAt = null;
function sinceText(ms) { const m = Math.max(1, Math.round(ms / 60000)), d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60); return d ? `${d} дн ${h} год` : h ? `${h} год ${m % 60} хв` : `${m} хв`; }
function journalTime(iso) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('uk-UA', {timeZone: 'Europe/Kyiv', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'});
}
function renderJournal() {
  const list = $('journal-list'); if (!list) return;
  list.replaceChildren();
  const rows = journalEvents.filter(e => !journalFilter || e.category === journalFilter);
  if (!rows.length) {
    const li = document.createElement('li'); li.className = 'journal-empty';
    const started = journalStartedAt ? Date.parse(journalStartedAt) : NaN;
    li.textContent = journalFilter ? 'У цій категорії подій ще немає.' : 'Подій ще немає. Вони зʼявляться, коли зміниться стан (світло, заряд, звʼязок).' +
      (Number.isFinite(started) ? ` Журнал сервера ведеться ${sinceText(Date.now() - started)}.` : '');
    list.append(li); return;
  }
  for (const e of rows) {
    const li = document.createElement('li'); li.className = `journal-row cat-${e.category}`;
    const time = document.createElement('time'); time.dateTime = e.ts; time.textContent = journalTime(e.ts);
    const cat = document.createElement('b'); cat.textContent = CATEGORY_LABEL[e.category] || e.category;
    const text = document.createElement('span'); text.textContent = e.text;
    li.append(time, cat, text); list.append(li);
  }
}
async function loadJournal() {
  try {
    const r = await apiFetch('/api/events?limit=200');
    if (r.status === 404) { journalEvents = []; const list = $('journal-list'); if (list) list.replaceChildren(el('li', 'journal-empty', 'Сервер старої версії — оновіть сервер, щоб бачити журнал.')); return; }
    if (!r.ok) return;
    const body = await r.json();
    journalEvents = body.events || [];
    journalStartedAt = body.journal_started_at || null;
    renderJournal();
  } catch (e) { /* keep the last list */ }
}
function csvCell(value) { const s = value === null || value === undefined ? '' : String(value); return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; }
function exportJournalCsv() {
  const rows = journalEvents.filter(e => !journalFilter || e.category === journalFilter);
  const lines = ['time;category;kind;value;text', ...rows.map(e => [e.ts, e.category, e.kind, e.value, e.text].map(csvCell).join(';'))];
  const blob = new Blob(['\ufeff' + lines.join('\n')], {type: 'text/csv;charset=utf-8'});
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'marstek-journal.csv';
  document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
document.querySelectorAll('.journal-filter button').forEach(button => button.addEventListener('click', () => {
  journalFilter = button.dataset.filter || '';
  document.querySelectorAll('.journal-filter button').forEach(b => b.classList.toggle('active', b === button));
  renderJournal();
}));
$('journal-csv')?.addEventListener('click', exportJournalCsv);
let deferredInstallPrompt = null;
const installButton = $('install-app');
window.addEventListener('beforeinstallprompt', event => {
  event.preventDefault();
  deferredInstallPrompt = event;
  if (installButton) installButton.hidden = false;
});
if (installButton) installButton.addEventListener('click', async () => {
  if (!deferredInstallPrompt) return;
  deferredInstallPrompt.prompt();
  await deferredInstallPrompt.userChoice;
  deferredInstallPrompt = null;
  installButton.hidden = true;
});
window.addEventListener('appinstalled', () => { if (installButton) installButton.hidden = true; });
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js', {scope: '/'}).catch(error => console.warn('PWA offline shell registration failed', error));
}
document.querySelector('.diagnostics')?.addEventListener('toggle', drawChart);
refresh(); loadJournal(); setInterval(refresh,20000); setInterval(loadJournal,60000); window.addEventListener('resize',drawChart);
