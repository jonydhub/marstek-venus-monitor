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
  if (hours) return `${hours} год ${minutes} хв`;
  return `${minutes} хв`;
}
function renderBatteryFlow(flow = {}) {
  const windowSeconds = Number(flow.window_seconds) || 0;
  const windowMinutes = Math.round(windowSeconds / 60);
  set('battery-flow-window', flow.available ? `Вікно ${windowMinutes} хв · ${flow.sample_count} вим.` : `Збір даних · ${windowMinutes} хв`);
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
    set('discharge-eta-note', 'Теоретично до 0%, без резерву');
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
function outageWindowText(outage) {
  const next = outage?.next_outage;
  if (outage?.emergency && !next) return 'Діють аварійні відключення — графік не застосовується';
  const maybe = (outage?.maybe_outages || [])[0];
  const maybeText = maybe ? ` · можливе ${formatClock(maybe.start)}–${formatClock(maybe.end)}` : '';
  if (next) return `Наступне відключення: ${formatClock(next.start)}–${formatClock(next.end)} · ${formatDuration(next.duration_seconds)}${maybeText}`;
  if (maybe) return `Можливе відключення: ${formatClock(maybe.start)}–${formatClock(maybe.end)}`;
  if (!outage?.available) return 'Наступне відключення: —';
  return outage.schedule_published ? 'Відключень для черги не заплановано' : 'Графік не опубліковано';
}
function autonomyBasisText(a) {
  if (a.load_basis === 'measured_average_24h') return `при середньому споживанні ${Math.round(a.load_w)} W (за 24 год)`;
  if (a.load_basis === 'configured_average') return `при заданому споживанні ${Math.round(a.load_w)} W`;
  if (a.load_basis === 'measured_discharge') return `при поточному розряді ≈ ${Math.round(a.load_w)} W`;
  return '';
}
function renderSparkline(points = []) {
  const svg = $('soc-sparkline'); if (!svg) return;
  const ns = 'http://www.w3.org/2000/svg';
  svg.replaceChildren();
  for (const level of [25, 50, 75]) {
    const grid = document.createElementNS(ns, 'line');
    const y = 62 - level / 100 * 60;
    grid.setAttribute('x1', '0'); grid.setAttribute('x2', '300'); grid.setAttribute('y1', String(y)); grid.setAttribute('y2', String(y));
    grid.setAttribute('class', 'spark-grid'); svg.append(grid);
  }
  const clean = points.map(p => ({t: Date.parse(p.t), soc: finiteOrNull(p.soc)})).filter(p => Number.isFinite(p.t) && p.soc !== null);
  if (clean.length < 2) {
    set('spark-range', clean.length ? `${Math.round(clean[0].soc)}% · історія збирається` : 'Історія збирається');
    return;
  }
  const end = Date.now(), start = end - 24 * 3600 * 1000;
  const x = t => Math.max(0, Math.min(300, (t - start) / (end - start) * 300));
  const y = soc => 62 - Math.max(0, Math.min(100, soc)) / 100 * 60;
  const coords = clean.map(p => `${x(p.t).toFixed(1)},${y(p.soc).toFixed(1)}`);
  const area = document.createElementNS(ns, 'path');
  area.setAttribute('d', `M${x(clean[0].t).toFixed(1)},62 L${coords.join(' L')} L${x(clean[clean.length - 1].t).toFixed(1)},62 Z`);
  area.setAttribute('class', 'spark-area');
  const line = document.createElementNS(ns, 'polyline');
  line.setAttribute('points', coords.join(' ')); line.setAttribute('class', 'spark-line');
  line.setAttribute('vector-effect', 'non-scaling-stroke');
  svg.append(area, line);
  const socs = clean.map(p => p.soc);
  set('spark-range', `${Math.round(Math.min(...socs))}–${Math.round(Math.max(...socs))}%`);
}
function renderHero(data, bat, socValue) {
  const hero = $('hero'); if (!hero) return;
  const outage = data.power_outage || {}, insights = data.insights || {}, flow = data.battery_flow || {};
  const a = outage.autonomy || {};
  const soc = finiteOrNull(socValue);
  set('outage-group', outage.group);
  set('outage-source', outageSourceText(outage));
  set('hero-soc', soc === null ? '—' : `${Math.round(soc)}%`);
  const temp = finiteOrNull(insights.temperature_c);
  set('hero-temp', temp === null ? '—' : `${temp.toLocaleString('uk-UA', {maximumFractionDigits:1})} °C`);
  set('backup-today', formatKwh(insights.backup_energy_today_wh));
  renderSparkline(insights.soc_history || []);
  const runtime = finiteOrNull(a.runtime_seconds);
  const err = outage.error && outage.error !== 'not_fetched_yet' ? ` Графік не оновився (${outage.error}).` : '';
  let state = 'unknown';
  if (outage.grid_state === 'off') {
    state = 'off';
    set('hero-title', `Світла нема · від батареї ${watts(insights.backup_output_w)}`);
    const current = outage.current_outage;
    set('hero-sub', current?.end ? `Світло повернуть о ${formatClock(current.end)}${current.kind === 'schedule' ? ' (за графіком)' : ''}` : 'Світло повернуть: невідомо');
    const verdict = a.covers_next_outage === true ? 'Запасу вистачить' : a.covers_next_outage === false ? 'Запасу не вистачить' : 'Запас';
    set('hero-forecast', runtime === null ? 'Прогноз запасу: збираю виміри розряду…' : `${verdict} · ≈ ${formatDuration(runtime)} до 0% (оцінка)`);
    set('hero-extra-label', 'До 0% (оцінка)'); set('hero-extra', runtime === null ? '—' : formatDuration(runtime));
    set('hero-note', `Потужність — вимір резервного виходу; час — оцінка${autonomyBasisText(a) ? ` ${autonomyBasisText(a)}` : ''}, без резерву/DOD.${err}`);
  } else if (flow.available && flow.direction === 'charging') {
    state = 'charging';
    set('hero-title', `Заряджається · +${watts(flow.power_w)} (оцінка)`);
    set('hero-sub', `До 100% · ${formatDuration(flow.charge_eta_seconds)}`);
    set('hero-forecast', outageWindowText(outage));
    set('hero-extra-label', 'Запас'); set('hero-extra', formatKwh(bat.bat_capacity));
    set('hero-note', `Потужність і час — оцінка за трендом ємності (до 5 хв).${err}`);
  } else if (outage.grid_state === 'on') {
    state = 'on';
    set('hero-title', outage.current_outage ? 'Світло є (за графіком мало б не бути)' : 'Світло є');
    set('hero-sub', outageWindowText(outage));
    set('hero-forecast', runtime === null
      ? 'Запас: прогноз з’явиться після першого відключення (споживання міряється на резервному виході)'
      : `Запасу на ≈ ${formatDuration(runtime)} ${autonomyBasisText(a)}`);
    if (flow.available && flow.direction === 'discharging') {
      set('hero-extra-label', 'До 0% (оцінка)'); set('hero-extra', formatDuration(flow.discharge_eta_seconds));
    } else {
      set('hero-extra-label', 'Запас'); set('hero-extra', formatKwh(bat.bat_capacity));
    }
    set('hero-note', `Час — оцінка до 0%, без резерву/DOD. Стан мережі — за резервним виходом Venus.${err}`);
  } else {
    set('hero-title', 'Стан мережі невідомий');
    set('hero-sub', outageWindowText(outage));
    set('hero-forecast', '—');
    set('hero-extra-label', 'Запас'); set('hero-extra', formatKwh(bat.bat_capacity));
    set('hero-note', `Немає свіжих даних від Venus.${err}`);
  }
  hero.dataset.state = state;
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
  $('soc-ring').style.setProperty('--soc', `${Math.max(0,Math.min(100,socNumber||0))}%`);
  $('soc-ring').setAttribute('aria-valuenow', Number.isFinite(socNumber) ? String(Math.round(socNumber)) : '0');
  const batteryWh = bat.bat_capacity ?? es.bat_cap;
  const ratedWh = bat.rated_capacity ?? es.bat_cap;
  set('capacity-kwh', formatKwh(batteryWh));
  set('capacity', batteryWh != null && ratedWh != null ? `${formatKwh(batteryWh)} / ${formatKwh(ratedWh)}` : formatKwh(batteryWh));
  set('temperature', num(bat.bat_temp,'°C'));
  set('mode',ukMode(mode.mode));
  set('charge-permission',`Заряджання: ${yesNo(bat.charg_flag)}`); set('discharge-permission',`Розряджання: ${yesNo(bat.dischrg_flag)}`);
  renderBatteryFlow(data.battery_flow || {});
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
  catch(e){$('conn-dot').className='status-led off';set('conn-label','Дашборд не отримує дані');set('last-updated',e.message);}
}
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
refresh(); setInterval(refresh,20000); window.addEventListener('resize',drawChart);
