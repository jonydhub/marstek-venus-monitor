'use strict';
const NONCE = document.querySelector('meta[name="mvm-nonce"]').content;
const $ = id => document.getElementById(id);
let state = null;
let regions = [];

async function api(action, body) {
  const r = await fetch('/api/setup/' + action, {
    method: 'POST', cache: 'no-store',
    headers: {'Content-Type': 'application/json', 'X-MVM-Setup': NONCE},
    body: JSON.stringify(body || {}),
  });
  let data = {};
  try { data = await r.json(); } catch (e) { /* empty */ }
  if (!r.ok) throw new Error((data.errors || [data.error || ('HTTP ' + r.status)]).join('\n'));
  return data;
}

function setStatus(id, text, kind) {
  const el = $(id);
  el.textContent = text || '';
  el.className = 'status' + (kind ? ' ' + kind : '');
}

function fillQueues(selected) {
  const region = regions.find(r => r.id === $('region').value);
  const q = $('queue');
  q.replaceChildren(new Option('—', ''));
  (region ? region.queues : []).forEach(name => q.add(new Option(name, name, false, name === selected)));
  q.disabled = !region;
  $('region-note').textContent = region
    ? (region.sources.length ? 'Джерела: ' + region.sources.join(', ') + (region.operator ? ' · ' + region.operator : '') : 'Для цього регіону графік недоступний — стан світла визначатиметься лише за резервним виходом.')
    : 'Чергу можна дізнатися на сайті свого обленерго за адресою.';
}

async function loadRegions(selectedRegion, selectedQueue) {
  try {
    const r = await fetch('/api/regions', {cache: 'no-store'});
    regions = (await r.json()).regions || [];
  } catch (e) { regions = []; }
  const sel = $('region');
  sel.replaceChildren(new Option('— без графіка —', ''));
  regions.forEach(r => sel.add(new Option(r.name_ua, r.id, false, r.id === selectedRegion)));
  fillQueues(selectedQueue);
}

function renderAlerts() {
  const box = $('alerts');
  box.replaceChildren();
  state.alerts.forEach(a => {
    const label = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'checkbox'; input.checked = a.enabled; input.dataset.alert = a.id;
    label.append(input, ' ' + a.label);
    box.append(label);
  });
}

function renderPhone() {
  const p = state.phone;
  $('phone-enabled').checked = p.enabled;
  $('phone-details').hidden = !p.enabled;
  $('phone-urls').textContent = p.urls.length ? p.urls.join('  ') : 'немає адреси в домашній мережі';
  $('phone-token').textContent = p.token || '—';
  const qr = $('qr');
  qr.replaceChildren();
  if (p.qr_svg) {
    const img = new Image();
    img.alt = 'QR-код для телефона';
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(p.qr_svg);
    qr.append(img);
  }
}

function render() {
  const c = state.config;
  $('first-run').hidden = state.configured;
  $('title').textContent = state.configured ? 'Налаштування' : 'Перший запуск';
  $('host').value = c.battery.host;
  $('port').value = c.battery.port;
  $('backup_threshold_w').value = c.backup_threshold_w;
  $('expected_load_w').value = c.expected_load_w ?? '';
  $('poll_seconds').value = c.poll_seconds;
  $('autostart').checked = state.autostart.enabled;
  $('autostart').disabled = !state.autostart.available;
  $('autostart-note').textContent = state.autostart.available ? 'Програма стартує у треї після входу в Windows.' : 'Доступно у встановленій версії.';
  renderAlerts();
  renderPhone();
}

async function refresh() {
  const r = await fetch('/api/setup/state', {cache: 'no-store'});
  state = await r.json();
  render();
  await loadRegions(state.config.outage.region, state.config.outage.queue);
}

$('region').addEventListener('change', () => fillQueues(''));

$('discover').addEventListener('click', async () => {
  const btn = $('discover');
  btn.disabled = true;
  setStatus('discover-status', 'Шукаю в локальній мережі… (до 15 с)', 'busy');
  $('devices').replaceChildren();
  try {
    const res = await api('discover', {port: Number($('port').value) || 30000});
    if (!res.devices.length) {
      setStatus('discover-status', 'Не знайдено. Перевірте, що Open API увімкнено і ПК у тій самій мережі, або введіть IP вручну.', 'bad');
    } else {
      setStatus('discover-status', 'Знайдено: ' + res.devices.length + '. Виберіть батарею.', 'ok');
      res.devices.forEach(d => {
        const li = document.createElement('li');
        const b = document.createElement('button');
        b.type = 'button'; b.className = 'device';
        b.textContent = d.model + ' · ' + d.ip + ':' + d.port + (d.firmware ? ' · прошивка ' + d.firmware : '');
        b.addEventListener('click', () => { $('host').value = d.ip; $('port').value = d.port; setStatus('discover-status', 'Вибрано ' + d.ip + ' — натисніть «Зберегти».', 'ok'); });
        li.append(b); $('devices').append(li);
      });
      if (res.devices.length === 1 && !$('host').value) { $('host').value = res.devices[0].ip; $('port').value = res.devices[0].port; }
    }
  } catch (e) { setStatus('discover-status', e.message, 'bad'); }
  btn.disabled = false;
});

$('test').addEventListener('click', async () => {
  setStatus('test-status', 'Перевіряю…', 'busy');
  try {
    const r = await api('test', {host: $('host').value.trim(), port: Number($('port').value) || 30000});
    if (r.ok) setStatus('test-status', 'Є зв’язок: ' + (r.model || 'Marstek') + (r.soc != null ? ' · заряд ' + r.soc + '%' : ''), 'ok');
    else setStatus('test-status', r.error || 'Немає відповіді', 'bad');
  } catch (e) { setStatus('test-status', e.message, 'bad'); }
});

$('form').addEventListener('submit', async ev => {
  ev.preventDefault();
  const alerts = {};
  document.querySelectorAll('[data-alert]').forEach(i => { alerts[i.dataset.alert] = i.checked; });
  setStatus('save-status', 'Зберігаю…', 'busy');
  try {
    state = await api('save', {
      host: $('host').value.trim(), port: $('port').value.trim(), region: $('region').value, queue: $('queue').value,
      backup_threshold_w: $('backup_threshold_w').value.trim(), expected_load_w: $('expected_load_w').value.trim(),
      poll_seconds: $('poll_seconds').value.trim(), alerts,
    });
    render();
    setStatus('save-status', 'Збережено й застосовано.', 'ok');
  } catch (e) { setStatus('save-status', e.message, 'bad'); }
});

$('phone-enabled').addEventListener('change', async ev => {
  try { state = await api('phone', {enabled: ev.target.checked}); renderPhone(); }
  catch (e) { ev.target.checked = !ev.target.checked; alert(e.message); }
});
$('phone-regenerate').addEventListener('click', async () => {
  if (!confirm('Старий токен перестане працювати. Продовжити?')) return;
  state = await api('phone', {enabled: true, regenerate: true}); renderPhone();
});
$('autostart').addEventListener('change', async ev => {
  try { state = await api('autostart', {enabled: ev.target.checked}); }
  catch (e) { ev.target.checked = !ev.target.checked; alert(e.message); }
});

refresh().then(() => { if (location.hash) document.querySelector(location.hash)?.scrollIntoView(); });
