'use strict';

const $ = (id) => document.getElementById(id);

let key = null;
const fromQr = /^#k=([A-Za-z0-9_-]+)$/.exec(location.hash);
if (fromQr) {
  key = fromQr[1];
  try { sessionStorage.setItem('nxk', key); } catch (e) {}
  history.replaceState(null, '', '/');
} else {
  try { key = sessionStorage.getItem('nxk'); } catch (e) {}
}

let me = null;
let timer = null;

function show(section) {
  for (const s of ['join', 'app', 'gone']) $(s).hidden = s !== section;
}

function size(n) {
  if (n < 1024) return n + ' B';
  const units = ['KB', 'MB', 'GB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < units.length - 1);
  return n.toFixed(n < 10 ? 1 : 0) + ' ' + units[i];
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function gone(text) {
  stopPolling();
  $('gone-text').textContent = text;
  show('gone');
}

function askJoin(message) {
  stopPolling();
  me = null;
  $('me').textContent = '';
  $('pin-row').hidden = !!key;
  $('pin').required = !key;
  const err = $('join-error');
  err.hidden = !message;
  err.textContent = message || '';
  show('join');
  $('name').focus();
}

async function api(path, options) {
  const opts = Object.assign({ credentials: 'same-origin' }, options || {});
  opts.headers = Object.assign({ 'X-NX': '1' }, opts.headers || {});
  let r;
  try {
    r = await fetch(path, opts);
  } catch (e) {
    gone("Can't reach the phone. Sharing may have been shut down, or this device left the network.");
    return undefined;
  }
  if (r.status === 401) {
    askJoin(me ? 'The phone removed this device, or sharing was started again. Join again to go on.' : '');
    return undefined;
  }
  return r;
}

async function start() {
  const r = await api('/api/me');
  if (!r) return;
  entered((await r.json()).name);
}

function entered(name) {
  me = name;
  $('me').textContent = 'You: ' + name;
  show('app');
  startPolling();
}

$('join-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const form = new URLSearchParams({ name: $('name').value });
  if (key) form.set('key', key); else form.set('pin', $('pin').value);
  const r = await api('/api/join', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form.toString(),
  });
  if (!r) return;
  const data = await r.json();
  if (r.ok) return entered(data.name);
  if (key && r.status === 403) {
    key = null;
    try { sessionStorage.removeItem('nxk'); } catch (e) {}
  }
  askJoin(data.error || 'Couldn’t join');
});

function startPolling() {
  stopPolling();
  refresh();
  timer = setInterval(refresh, 4000);
}

function stopPolling() {
  if (timer) clearInterval(timer);
  timer = null;
}

const closed = new Set();

async function refresh() {
  if (document.hidden) return;
  const r = await api('/api/list');
  if (!r || !r.ok) return;
  const data = await r.json();
  const box = $('folders');
  box.replaceChildren();
  for (const f of data.folders) {
    if (!f.files.length) continue;
    const d = el('details');
    d.open = !closed.has(f.name);
    d.addEventListener('toggle', () => { if (d.open) closed.delete(f.name); else closed.add(f.name); });
    const label = f.name + (f.phone ? ' (the phone)' : f.mine ? ' (you)' : '');
    const s = el('summary');
    const title = el('span', 'title', label + ' ');
    title.append(el('span', 'size', '· ' + f.files.length));
    const all = el('a', 'all', 'Download all');
    all.href = '/api/zip?owner=' + encodeURIComponent(f.name);
    all.setAttribute('download', '');
    all.addEventListener('click', (e) => e.stopPropagation());
    s.append(title, all);
    d.append(s);
    const ul = el('ul', 'list');
    for (const file of f.files) {
      const li = el('li');
      li.append(el('span', 'name', file.name), el('span', 'size', size(file.size)));
      const a = el('a', null, 'Download');
      a.href = '/api/file/' + encodeURIComponent(file.id);
      a.setAttribute('download', '');
      li.append(a);
      ul.append(li);
    }
    d.append(ul);
    box.append(d);
  }
  $('empty').hidden = box.childElementCount > 0;
}

document.addEventListener('visibilitychange', () => { if (!document.hidden && me) refresh(); });

$('choose').addEventListener('click', () => $('files').click());

$('files').addEventListener('change', async () => {
  const files = Array.from($('files').files);
  $('files').value = '';
  if (!files.length) return;
  const rows = files.map((f) => {
    const li = el('li');
    const bar = el('progress');
    bar.max = 1;
    bar.value = 0;
    const state = el('span', 'size', 'Waiting for the phone to approve');
    li.append(el('span', 'name', f.name), bar, state);
    $('uploads').prepend(li);
    return { f, bar, state };
  });
  const fail = (text) => rows.forEach((r) => { r.state.textContent = text; r.state.classList.add('error'); });

  const r = await api('/api/offer', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: files.map((f) => f.size + ':' + encodeURIComponent(f.name)).join('\n'),
  });
  if (!r) return fail('Not sent');
  const offer = await r.json();
  if (!r.ok) return fail(offer.error || 'Not sent');
  let answer = offer.state;
  while (answer === 'waiting') {
    await new Promise((ok) => setTimeout(ok, 1000));
    const s = await api('/api/offer?id=' + encodeURIComponent(offer.id));
    if (!s) return fail('Not sent');
    if (!s.ok) return fail('Not sent');
    answer = (await s.json()).state;
  }
  if (answer !== 'approved') return fail('The phone declined');
  rows.forEach((row) => { row.state.textContent = 'Approved'; });
  for (let i = 0; i < rows.length; i++) await send(rows[i], offer.id, i);
});

function send({ f, bar, state }, offer, index) {
  return new Promise((done) => {
    const x = new XMLHttpRequest();
    x.open('POST', '/api/upload?offer=' + encodeURIComponent(offer) + '&file=' + index);
    x.setRequestHeader('X-NX', '1');
    x.upload.onprogress = (e) => {
      if (e.lengthComputable) bar.value = e.loaded / e.total;
      state.textContent = size(e.loaded) + ' of ' + size(f.size);
    };
    x.onload = () => {
      if (x.status === 200) {
        bar.value = 1;
        state.textContent = 'Sent';
      } else if (x.status === 401) {
        state.textContent = 'Not sent';
        askJoin('The phone removed this device, or sharing was started again. Join again to go on.');
      } else {
        let msg = 'Not sent';
        try { msg = JSON.parse(x.responseText).error || msg; } catch (e) {}
        state.textContent = msg;
        state.classList.add('error');
      }
      done();
    };
    x.onerror = () => {
      state.textContent = 'Not sent: the connection broke';
      state.classList.add('error');
      done();
    };
    state.textContent = 'Sending';
    x.send(f);
  });
}

$('retry').addEventListener('click', start);

start();
