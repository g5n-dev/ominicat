'use strict';

// Browser-platform fakes drive the shipped page through its real DOM handlers.
// No browser, microphone, network, third-party DOM package or production test API.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {randomUUID} = require('node:crypto');

const web = path.join(__dirname, '../ominicat-server/src/main/resources/web');
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};
const settle = () => new Promise(resolve => setImmediate(resolve));

class Events {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, callback) {
    const list = this.listeners.get(type) || [];
    list.push(callback); this.listeners.set(type, list);
  }
  removeEventListener(type, callback) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter(item => item !== callback));
  }
  emit(type, properties = {}) {
    const event = {type, target: this, currentTarget: this, preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.propagationStopped = true; }, ...properties};
    const callbacks = [...(this.listeners.get(type) || [])];
    if (typeof this['on' + type] === 'function') callbacks.push(this['on' + type]);
    return Promise.all(callbacks.map(callback => callback.call(this, event)));
  }
  dispatchEvent(event) { return this.emit(event.type, event); }
}

class Element extends Events {
  constructor(tag, owner) {
    super(); this.tagName = tag.toUpperCase(); this.ownerDocument = owner;
    this.children = []; this.attributes = {}; this.dataset = {}; this.style = {setProperty(name, value) { this[name] = value; }};
    this.classes = new Set(); this.classList = {
      add: (...items) => items.forEach(item => this.classes.add(item)),
      remove: (...items) => items.forEach(item => this.classes.delete(item)),
      contains: item => this.classes.has(item),
      toggle: (item, force) => { const add = force === undefined ? !this.classes.has(item) : force; add ? this.classes.add(item) : this.classes.delete(item); return add; },
    };
    this.value = ''; this.files = []; this.disabled = false; this.hidden = false; this.checked = false;
    this.clientWidth = 1000; this.clientHeight = 480; this.scrollTop = 0;
  }
  get id() { return this.attributes.id || ''; }
  set id(value) { this.setAttribute('id', value); }
  get className() { return [...this.classes].join(' '); }
  set className(value) { this.classes = new Set(String(value).split(/\s+/).filter(Boolean)); }
  get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get hidden() { return this._hidden || false; }
  set hidden(value) { this._hidden = !!value; if (value) this.attributes.hidden = ''; else delete this.attributes.hidden; }
  get firstChild() { return this.children[0] || null; }
  get lastChild() { return this.children.at(-1) || null; }
  get childElementCount() { return this.children.length; }
  get scrollHeight() { return this.children.length * 24; }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'class') this.className = value;
    if (name === 'id') this.ownerDocument.ids.set(String(value), this);
    if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = String(value);
    if (name === 'disabled') this.disabled = true;
    if (name === 'hidden') this.hidden = true;
    if (name === 'checked') this.checked = true;
    if (name === 'value') this.value = String(value);
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  removeAttribute(name) { delete this.attributes[name]; if (name === 'disabled') this.disabled = false; if (name === 'hidden') this.hidden = false; }
  hasAttribute(name) { return this.getAttribute(name) !== null; }
  toggleAttribute(name, force) { const enabled = force === undefined ? !this.hasAttribute(name) : force; if (enabled) this.setAttribute(name, ''); else this.removeAttribute(name); return enabled; }
  append(...items) {
    for (let child of items) {
      if (!(child instanceof Element)) { const text = new Element('text', this.ownerDocument); text.text = String(child); child = text; }
      if (child.tagName === 'FRAGMENT') { this.append(...child.children.slice()); continue; }
      child.remove(); child.parentElement = this; this.children.push(child);
    }
  }
  appendChild(child) { this.append(child); return child; }
  prepend(...items) { const previous = this.children.slice(); this.replaceChildren(...items, ...previous); }
  replaceChildren(...items) { for (const child of this.children) child.parentElement = null; this.children = []; this.text = ''; this.append(...items); }
  remove() { if (this.parentElement) { this.parentElement.children = this.parentElement.children.filter(child => child !== this); this.parentElement = null; } }
  matches(selector) {
    selector = selector.trim();
    if (selector.startsWith('#')) return this.id === selector.slice(1);
    if (selector.startsWith('.')) return this.classes.has(selector.slice(1));
    const attribute = selector.match(/^\[([^=\]]+)(?:=["']?([^"'\]]+)["']?)?\]$/);
    if (attribute) return this.getAttribute(attribute[1]) !== null && (attribute[2] === undefined || this.getAttribute(attribute[1]) === attribute[2]);
    return this.tagName.toLowerCase() === selector.toLowerCase();
  }
  querySelectorAll(selector) {
    const result = []; const selectors = selector.split(',');
    const visit = node => { for (const child of node.children) { if (selectors.some(item => child.matches(item))) result.push(child); visit(child); } };
    visit(this); return result;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  closest(selector) { for (let node = this; node; node = node.parentElement) if (node.matches(selector)) return node; return null; }
  set innerHTML(markup) { this.replaceChildren(); parseMarkup(String(markup), this, this.ownerDocument); }
  get innerHTML() { return this.textContent; }
  getBoundingClientRect() {
    if (this.ownerDocument.measureElement) return this.ownerDocument.measureElement(this);
    return {left: 0, top: 0, x: 0, y: 0, width: this.clientWidth, height: this.clientHeight, right: this.clientWidth, bottom: this.clientHeight};
  }
  getBBox() { return this.getBoundingClientRect(); }
  getTotalLength() { return 1000; }
  getPointAtLength(length) { return {x: length, y: 100}; }
  getContext() { return new Proxy({}, {get: (_, key) => key === 'measureText' ? text => ({width: text.length * 8}) : () => {}}); }
  focus() { this.ownerDocument.activeElement = this; }
  setPointerCapture(id) { (this.pointerCaptures ||= new Set()).add(id); }
  hasPointerCapture(id) { return this.pointerCaptures?.has(id) || false; }
  releasePointerCapture(id) { this.pointerCaptures?.delete(id); }
  scrollIntoView() {}
  requestSubmit() { return this.emit('submit'); }
  showModal() { this.open = true; }
  close() { this.open = false; }
  click() { return this.disabled ? Promise.resolve() : this.emit('click'); }
}

function parseMarkup(html, root, document) {
  const stack = [root];
  const cleaned = html.replace(/<(style|script)\b[^>]*>[\s\S]*?<\/\1>/gi, '');
  for (const match of cleaned.matchAll(/<\/?([\w-]+)\b([^>]*)>|([^<]+)/g)) {
    if (!match[1]) { stack.at(-1).text = (stack.at(-1).text || '') + match[3]; continue; }
    const tag = match[1].toLowerCase();
    if (match[0].startsWith('</')) { if (stack.length > 1) stack.pop(); continue; }
    const node = new Element(tag, document);
    for (const attribute of match[2].matchAll(/([^\s=\/]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\s]+)))?/g))
      node.setAttribute(attribute[1], attribute[2] ?? attribute[3] ?? attribute[4] ?? '');
    stack.at(-1).append(node);
    if (!['input', 'meta', 'link', 'br', 'hr', 'img', 'path', 'circle', 'line'].includes(tag) && !match[0].endsWith('/>')) stack.push(node);
  }
}

function makeDocument(html) {
  const document = new Events(); document.ids = new Map();
  document.body = new Element('body', document); document.documentElement = new Element('html', document);
  document.hidden = false; document.visibilityState = 'visible'; document.readyState = 'complete';
  document.createElement = tag => new Element(tag, document);
  document.createElementNS = (_, tag) => new Element(tag, document);
  document.createDocumentFragment = () => new Element('fragment', document);
  document.createTextNode = text => { const node = new Element('text', document); node.text = text; return node; };
  document.getElementById = id => document.ids.get(id) || null;
  document.querySelectorAll = selector => document.body.querySelectorAll(selector);
  document.querySelector = selector => document.body.querySelector(selector);
  parseMarkup(html, document.body, document);
  const parsedRoot = document.body.querySelector('html');
  const parsedBody = document.body.querySelector('body');
  if (parsedRoot) document.documentElement = parsedRoot;
  if (parsedBody) document.body = parsedBody;
  return document;
}

function clock() {
  let next = 1; const timers = new Map(), frames = new Map(), intervals = new Set(), errors = [];
  const result = {now: 0, timers, frames, errors};
  result.setTimeout = (callback, delay = 0) => { const id = next++; timers.set(id, {callback, at: result.now + Number(delay)}); return id; };
  result.clearTimeout = id => { timers.delete(id); intervals.delete(id); };
  result.requestAnimationFrame = callback => { const id = next++; frames.set(id, callback); return id; };
  result.cancelAnimationFrame = id => frames.delete(id);
  result.setInterval = (callback, delay) => {
    const id = next++; intervals.add(id); const run = () => { callback(); if (intervals.has(id)) timers.set(id, {callback: run, at: result.now + delay}); };
    timers.set(id, {callback: run, at: result.now + delay}); return id;
  };
  result.tick = async milliseconds => {
    const end = result.now + milliseconds; let runs = 0;
    while (true) {
      const entry = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!entry) break;
      assert.ok(++runs < 1000, 'unbounded timer scheduling');
      const [id, timer] = entry; timers.delete(id); result.now = timer.at;
      Promise.resolve(timer.callback()).catch(error => errors.push(error)); await settle();
    }
    result.now = end; await settle();
  };
  return result;
}

const runtime = () => ({schemaVersion: 1, status: 'running', pipeline: {editable: false, nodeTracing: false,
  stages: [{id: 'stage-0', index: 0, type: 'echo'}]}, security: {bearerRequired: false},
  config: {queueCapacity: 256, maxSessions: 256, maxConnections: 512, maxFrameBytes: 1048576, idleTimeoutSeconds: 60, maxPendingOutputBytes: 4194304},
  runtime: {javaVersion: '17.0.21', workerBackend: 'platform', uptimeMillis: 1000, availableProcessors: 4},
  metrics: {connections: 1, activeSessions: 1, admittedSessions: 1, pipelineWorkers: 1, pendingOutputBytes: 0,
    sessionsTotal: 1, framesInTotal: 0, framesOutTotal: 0, rejectedTotal: 0, failuresTotal: 0, bytesOutTotal: 0},
  memory: {heapUsedBytes: 1048576, heapCommittedBytes: 16777216, heapMaxBytes: 268435456, nonHeapUsedBytes: 1048576,
    directUsedBytes: 0, directCapacityBytes: 0, directBufferCount: 0}, threads: {scope: 'platform', live: 8, daemon: 6, peak: 8}});

async function harness(options = {}) {
  const time = clock(), document = makeDocument(fs.readFileSync(path.join(web, 'index.html'), 'utf8'));
  const sockets = [], contexts = [], worklets = [], captures = [], modules = [], fetches = [];
  class Socket extends Events {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url, protocols) { super(); this.url = url; this.protocols = protocols; this.readyState = 0; this.sent = []; this.bufferedAmount = 0; sockets.push(this); }
    open() { this.readyState = 1; return this.emit('open'); }
    send(value) { assert.equal(this.readyState, 1); this.sent.push(JSON.parse(value)); }
    close(code = 1000) { if (this.readyState === 3) return; this.readyState = 3; return this.emit('close', {code, reason: ''}); }
    message(value) { return this.emit('message', {data: typeof value === 'string' ? value : JSON.stringify(value)}); }
  }
  class AudioContext extends Events {
    constructor() { super(); this.state = 'running'; this.sampleRate = 48000; this.destination = {}; this.nodes = []; contexts.push(this);
      this.audioWorklet = {addModule: () => { const operation = deferred(); modules.push(operation); if (!options.delayModule) operation.resolve(); return operation.promise; }}; }
    get currentTime() { return time.now / 1000; }
    resume() { return Promise.resolve(); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    node(extra = {}) { const node = {connect() {}, disconnect() { this.disconnected = true; }, ...extra}; this.nodes.push(node); return node; }
    createMediaStreamSource(stream) { return this.node({mediaStream: stream}); }
    createGain() { return this.node({gain: {value: 1}}); }
    createBuffer(channels, length, rate) { return {duration: length / rate, getChannelData: () => new Float32Array(length)}; }
    createBufferSource() { return this.node({start() { this.started = true; }, stop() { this.stopped = true; this.onended?.(); }}); }
  }
  class Worklet extends Events {
    constructor(context) { super(); this.context = context; this.port = new Events(); this.port.start = () => {}; this.port.postMessage = () => {}; this.port.close = () => { this.port.closed = true; }; worklets.push(this); }
    connect() {} disconnect() { this.disconnected = true; }
  }
  const media = {getUserMedia: () => { const operation = deferred(); captures.push(operation); return operation.promise; }};
  function stream() { const track = {kind: 'audio', readyState: 'live', stop() { this.readyState = 'ended'; this.stops = (this.stops || 0) + 1; }}; return {track, get active() { return track.readyState === 'live'; }, getTracks: () => [track], getAudioTracks: () => [track]}; }
  const fetch = (url, config = {}) => {
    const operation = deferred(); const item = {url, config, operation, active: true}; fetches.push(item);
    const aborted = () => { item.active = false; item.aborted = true; const error = new Error('aborted'); error.name = 'AbortError'; operation.reject(error); };
    config.signal?.addEventListener('abort', aborted, {once: true});
    if (config.signal?.aborted) aborted();
    if (!options.delayFetch) {
      const data = String(url).startsWith('/api/activity')
        ? options.activity?.(url, item)
        : (options.runtime ? options.runtime() : runtime());
      item.active = false; operation.resolve({ok: true, status: 200, json: async () => data});
    }
    return operation.promise;
  };
  const window = new Events(), motion = new Events(); motion.matches = false; motion.addListener = callback => motion.addEventListener('change', callback);
  const storage = () => { const values = new Map(); return {values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key)}; };
  class BrowserURL extends URL { static createObjectURL() { return 'blob:synthetic-capture'; } static revokeObjectURL() {} }
  let hash = '';
  const location = {protocol: 'http:', host: '127.0.0.1:8080', port: '8080', origin: 'http://127.0.0.1:8080',
    get href() { return this.origin + '/' + hash; }, get hash() { return hash; },
    set hash(value) { const next = String(value).startsWith('#') ? String(value) : '#' + value; if (next !== hash) { hash = next; Promise.resolve().then(() => window.emit('hashchange')); } }};
  const history = {replaceState(_, title, url) { if (url !== undefined) hash = new URL(url, location.href).hash; }};
  const sandbox = {document, navigator: {mediaDevices: media}, location, history,
    WebSocket: Socket, AudioContext, webkitAudioContext: AudioContext, AudioWorkletNode: Worklet, fetch,
    AbortController, DOMException, Blob, TextEncoder, TextDecoder, Uint8Array, Float32Array, ArrayBuffer, DataView,
    URL: BrowserURL, crypto: {randomUUID}, Event: class { constructor(type) { this.type = type; } preventDefault() {} },
    performance: {now: () => time.now}, matchMedia: () => motion, devicePixelRatio: 1, console,
    setTimeout: time.setTimeout, clearTimeout: time.clearTimeout, setInterval: time.setInterval, clearInterval: time.clearTimeout,
    requestAnimationFrame: time.requestAnimationFrame, cancelAnimationFrame: time.cancelAnimationFrame,
    atob: text => Buffer.from(text, 'base64').toString('binary'), btoa: text => Buffer.from(text, 'binary').toString('base64'),
    localStorage: storage(), sessionStorage: storage(), isSecureContext: true,
    ResizeObserver: class {observe() {} disconnect() {}}, getComputedStyle: () => ({getPropertyValue: () => '#ffffff'})};
  sandbox.window = sandbox; sandbox.self = sandbox;
  sandbox.addEventListener = window.addEventListener.bind(window); sandbox.removeEventListener = window.removeEventListener.bind(window);
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(web, 'console.js'), 'utf8'), context, {filename: 'console.js'});
  await document.emit('DOMContentLoaded'); await settle();
  return {document, time, sockets, contexts, worklets, captures, modules, fetches, stream, motion, window, sandbox,
    element(id) { const value = document.getElementById(id); assert.ok(value, 'missing public control #' + id); return value; },
    async hide() { document.hidden = true; document.visibilityState = 'hidden'; await document.emit('visibilitychange'); await settle(); },
    async dispose() { await window.emit('pagehide'); await settle(); for (const socket of sockets) await socket.close(); await settle(); assert.deepEqual(time.errors, []); }};
}

const payload = wire => wire.type === 'envelope' ? wire.frame : wire;
async function connect(h) {
  if (h.document.getElementById('debug-view')?.hidden) { await h.element('debug-tab').click(); await settle(); }
  const before = h.sockets.length;
  await h.element('connect').click(); await settle();
  assert.equal(h.sockets.length, before + 1, 'Connect must open one WebSocket');
  const socket = h.sockets.at(-1); await socket.open(); await settle();
  assert.equal(payload(socket.sent[0]).type, 'start');
  return socket;
}
async function mode(h, value) {
  const tab = h.document.querySelectorAll('[data-mode]').find(node => node.dataset.mode === value);
  assert.ok(tab, 'input tab ' + value); await tab.click(); await settle();
}
async function submit(h, text) { h.element('message').value = text; await h.element('compose').emit('submit'); await settle(); }

// Canonical ActivityJournal wire records. These are synthetic observations, not
// a second implementation of the page's projection or a simulated processor.
function activityEvent(seq, values = {}) {
  const sessionId = values.sessionId || 'ws-stream-a-1';
  const correlationId = values.correlationId ?? 'same-correlation';
  return {seq, timeNanos: String(1000000000 + seq * 1000000), sessionId,
    clientSessionId: 'browser-session', runId: correlationId ? sessionId + ':correlation:' + correlationId : '',
    correlationId, frameId: 'input-1', causationId: '', invocationId: '', stageId: 'transport', stageIndex: -1,
    stageType: 'transport', phase: 'ingress_accepted', frameType: 'text', preview: 'synthetic input', outcome: 'accepted',
    ...values};
}
function activityPage(streamId, events, values = {}) {
  const latest = events.at(-1)?.seq || 0;
  return {schemaVersion: 1, streamId, capacity: 1024, pageSize: 128, oldestSeq: latest ? 1 : 0,
    latestSeq: latest, nextCursor: latest, gap: false, lost: 0, reset: false, incomplete: false,
    events, ...values};
}
function observedRuntime() {
  const value = runtime(); value.activity = {supported: true, mode: 'connection-opt-in'};
  value.pipeline.stages.push({id: 'stage-1', index: 1, type: 'echo'}); return value;
}
async function paint(h, frames = 1) {
  for (let index = 0; index < frames; index++) {
    await h.time.tick(40);
    const callbacks = [...h.time.frames.values()]; h.time.frames.clear();
    for (const callback of callbacks) callback(h.time.now);
    await settle();
  }
}

test('late microphone permission cannot capture into a replacement session; disconnect stops playback', async t => {
  const h = await harness(); t.after(() => h.dispose());
  h.element('token').value = 'synthetic-token-✓';
  await connect(h); await mode(h, 'audio');
  const recording = h.element('record').click(); await settle();
  assert.equal(h.captures.length, 1);
  await h.element('connect').click(); await settle();
  const replacement = await connect(h);
  const media = h.stream(); h.captures[0].resolve(media); await recording; await settle();
  assert.equal(media.track.readyState, 'ended');
  assert.equal(h.worklets.length, 0, 'a stale permission result must never start a worklet');
  assert.equal(replacement.sent.filter(wire => payload(wire).type === 'audio').length, 0);
  assert.ok(!replacement.url.includes('synthetic-token'));
  assert.ok(replacement.protocols.some(item => item.startsWith('bearer.')));
  await replacement.message({type: 'audio', sampleRate: 16000, channels: 1, data: Buffer.alloc(320).toString('base64')});
  assert.ok(h.contexts.some(context => context.nodes.some(node => node.started)), 'normal received PCM must play');
  await h.element('connect').click(); await settle();
  assert.ok(h.contexts.every(context => context.state === 'closed'));
  assert.equal(h.sandbox.localStorage.values.size, 0);
  assert.equal(h.sandbox.sessionStorage.values.size, 0);
});

test('interrupt disposes a pending worklet and a subsequent recording still sends valid mono PCM', async t => {
  const h = await harness({delayModule: true}); t.after(() => h.dispose());
  const socket = await connect(h); await mode(h, 'audio');
  const first = h.element('record').click(); await settle();
  const old = h.stream(); h.captures[0].resolve(old); await settle();
  assert.equal(h.modules.length, 1);
  await h.element('interrupt').click(); await settle();
  assert.equal(old.track.readyState, 'ended', 'interrupt must stop tracks before module resolution');
  assert.equal(h.contexts[0].state, 'closed');
  h.modules[0].resolve(); await first; await settle();
  assert.equal(h.worklets.length, 0);
  const second = h.element('record').click(); await settle();
  const active = h.stream(); h.captures[1].resolve(active); await settle();
  h.modules[1].resolve(); await second; await settle();
  assert.equal(h.worklets.length, 1, 'fresh recording must succeed after cancellation');
  await h.worklets[0].port.emit('message', {data: new Float32Array(480).fill(0.25)});
  await h.element('record').click(); await settle();
  const frames = socket.sent.map(payload), audio = frames.find(frame => frame.type === 'audio');
  assert.ok(audio, 'normal capture must send a nonempty recording');
  assert.equal(audio.sampleRate, 16000); assert.equal(audio.channels, 1);
  assert.equal(Buffer.from(audio.data, 'base64').length, 320);
  assert.equal(frames.at(-1).type, 'turn_end');
  assert.equal(active.track.readyState, 'ended');
  assert.ok(h.worklets[0].disconnected);
  assert.ok(h.contexts.every(context => context.state === 'closed'));
});

test('a delayed image read cannot cross disconnect/reconnect or interruption', async t => {
  const h = await harness(); t.after(() => h.dispose());
  await connect(h); await mode(h, 'image');
  const image = deferred(); h.element('file').files = [{type: 'image/png', size: 4, arrayBuffer: () => image.promise}];
  const read = h.element('file').emit('change'); await settle();
  await h.element('connect').click(); await settle(); const next = await connect(h);
  image.resolve(new Uint8Array([1, 2, 3, 4]).buffer); await read; await settle();
  assert.equal(next.sent.filter(wire => payload(wire).type === 'image').length, 0);
  const second = deferred(); h.element('file').files = [{type: 'image/png', size: 4, arrayBuffer: () => second.promise}];
  const interrupted = h.element('file').emit('change'); await settle(); await h.element('interrupt').click();
  second.resolve(new Uint8Array([5, 6, 7, 8]).buffer); await interrupted; await settle();
  assert.equal(next.sent.filter(wire => payload(wire).type === 'image').length, 0);
  assert.equal(h.element('file').value, '');
});

test('RTT records only the first correlated reply; pending/log/particles stay bounded', async t => {
  const h = await harness(); t.after(() => h.dispose()); const socket = await connect(h);
  await mode(h, 'text'); await submit(h, 'synthetic request');
  const request = socket.sent.find(wire => payload(wire).type === 'text'); assert.equal(request.type, 'envelope');
  await h.time.tick(25);
  await socket.message({type: 'text', text: 'unrelated'});
  assert.ok(h.element('first-response').textContent.includes('—'));
  const reply = {...request, id: randomUUID(), causationId: request.id, frame: {type: 'text', text: 'first'}};
  await socket.message(reply);
  const first = h.element('first-response').textContent; assert.equal(parseFloat(first), 25);
  await h.time.tick(50); await socket.message({...reply, id: randomUUID(), frame: {type: 'text', text: 'second'}});
  assert.equal(h.element('first-response').textContent, first);
  for (let index = 0; index < 160; index++) await submit(h, 'synthetic-' + index);
  assert.equal(Number(h.element('pending-count').textContent), 64);
  assert.ok(h.element('feed').children.length <= 80);
  assert.ok(h.element('flow-particles').children.length <= 32);
  assert.ok(h.time.frames.size <= 1);
  h.motion.matches = true; await h.motion.emit('change');
  assert.equal(h.time.frames.size, 0); assert.equal(h.element('flow-particles').children.length, 0);
  await h.time.tick(61001); assert.equal(Number(h.element('pending-count').textContent), 0);
  const sent = socket.sent.length; socket.bufferedAmount = 2097151;
  await submit(h, 'must fit as a whole frame');
  assert.equal(socket.sent.length, sent, 'the next payload must fit within the outbound byte budget');
  socket.bufferedAmount = 0;
  await socket.message({type: 'text', text: 'x'.repeat(5000)});
  assert.ok(h.element('feed').lastChild.textContent.length < 1000, 'only a bounded payload preview belongs in DOM');
  await socket.message({type: 'text', text: '<img src=x onerror="unexpected()">'});
  assert.equal(h.element('feed').querySelectorAll('img').length, 0);
  await h.element('clear').click();
  assert.equal(h.element('feed').children.length, 0);
  assert.ok(!h.element('node-recent').textContent.includes('unexpected()'));
  assert.ok(!h.element('overview-recent').textContent.includes('unexpected()'));
});

test('metadata polling has one owned request and stops on hidden/pagehide', async t => {
  const h = await harness({delayFetch: true}); t.after(() => h.dispose());
  assert.equal(h.fetches.length, 1); assert.equal(h.fetches.filter(item => item.active).length, 1);
  await h.time.tick(1000); assert.equal(h.fetches.length, 1, 'do not overlap a slow request');
  await h.hide(); assert.ok(h.fetches[0].aborted); assert.equal(h.fetches.filter(item => item.active).length, 0);
  await h.time.tick(10000); assert.equal(h.fetches.length, 1);
  h.document.hidden = false; h.document.visibilityState = 'visible'; await h.document.emit('visibilitychange'); await settle();
  assert.equal(h.fetches.length, 2);
  await h.element('refresh-runtime').click(); await settle();
  await h.time.tick(0); // Let the aborted request's replacement enter its next task.
  assert.equal(h.fetches.filter(item => item.active).length, 1);
  const waiting = h.fetches.at(-1);
  await h.time.tick(5001); assert.ok(waiting.aborted, 'a slow metadata read needs a finite deadline');
  assert.ok(h.fetches.filter(item => item.active).length <= 1);
  await h.window.emit('pagehide'); await settle();
  const count = h.fetches.length; await h.time.tick(20000); assert.equal(h.fetches.length, count);
  assert.equal(h.fetches.filter(item => item.active).length, 0);
  await h.window.emit('pageshow', {persisted: true}); await settle(); await h.time.tick(0);
  assert.equal(h.fetches.length, count + 1, 'BFCache restoration must restart metadata polling');
  assert.equal(h.sockets.length, 0, 'restoration must not create a session or restart media');
});

test('overloaded END restores controls for an explicit drain retry', async t => {
  const h = await harness(); t.after(() => h.dispose()); const socket = await connect(h);
  await h.element('finish').click(); assert.equal(h.element('finish').disabled, true);
  await socket.message({type: 'error', code: 'overloaded', message: 'Session input queue is full or closed'});
  assert.equal(h.element('finish').disabled, true, 'a prior data rejection must not masquerade as END rejection');
  await socket.message({type: 'error', code: 'overloaded', message: 'Session input queue is full or closed; retry end after draining'});
  assert.equal(h.element('finish').disabled, false);
  assert.equal(h.element('interrupt').disabled, false);
  await h.element('finish').click();
  assert.equal(socket.sent.filter(wire => payload(wire).type === 'end').length, 2);
});

test('overview and debug are distinct views; observer data is real and ambient flight is bounded', async t => {
  const h = await harness(); t.after(() => h.dispose());
  assert.equal(h.element('overview-view').hidden, false);
  assert.equal(h.element('debug-view').hidden, true);
  assert.equal(h.element('debug-view').hasAttribute('hidden'), true);
  assert.equal(h.sockets.length, 0);
  const kernel = h.element('scene-nodes').querySelectorAll('.graph-node')
    .find(node => node.dataset.nodeId === 'kernel-runtime');
  assert.ok(kernel, 'real JVM observer card must be clickable'); await kernel.click();
  assert.equal(h.element('overview-details').hidden, false);
  assert.equal(h.document.activeElement, h.element('overview-close'));
  assert.ok(h.element('overview-fields').textContent.includes('17.0.21'));
  assert.ok(h.element('overview-fields').textContent.includes('全运行时'));
  assert.ok(h.element('overview-description').textContent.includes('不代表某个处理器'));
  await h.document.emit('keydown', {key: 'Escape'});
  assert.equal(h.element('overview-details').hidden, true);
  assert.equal(h.document.activeElement, kernel, 'Escape must restore focus to the selected observer');
  await kernel.click(); await h.element('overview-close').click();
  assert.equal(h.element('overview-details').hidden, true);
  assert.equal(h.document.activeElement, kernel, 'Close button must restore the same observer focus');
  await kernel.click();
  await h.element('enter-debug').click(); await settle();
  assert.equal(h.element('overview-view').hidden, true);
  assert.equal(h.element('debug-view').hidden, false);
  assert.equal(h.sandbox.location.hash, '#debug');
  assert.equal(h.document.activeElement, h.element('debug-tab'), 'routing must not focus a hidden scene node');
  assert.equal(h.sockets.length, 0, 'entering debug alone must not create a WebSocket');
  const socket = await connect(h); await mode(h, 'audio');
  const capture = h.element('record').click(); await settle();
  const controlCount = socket.sent.length;
  await h.element('workspace-tab').click(); await settle();
  assert.equal(h.element('overview-view').hidden, false);
  assert.equal(h.element('debug-view').hidden, true);
  assert.equal(h.sandbox.location.hash, '#overview');
  assert.equal(h.document.activeElement, h.element('workspace-tab'));
  const late = h.stream(); h.captures[0].resolve(late); await capture; await settle();
  assert.equal(late.track.readyState, 'ended');
  assert.equal(h.sockets.length, 1); assert.equal(socket.readyState, 1);
  assert.equal(socket.sent.length, controlCount, 'returning to overview must not send interrupt/END/probes');
  await socket.message({type: 'audio', sampleRate: 16000, channels: 1, data: Buffer.alloc(320).toString('base64')});
  assert.equal(h.contexts.length, 0, 'overview may observe PCM but must not restart playback');
  await socket.message({type: 'text', text: 'actual-spatial-event'});
  const output = h.element('scene-nodes').querySelectorAll('.graph-node')
    .find(node => node.dataset.nodeId === 'client-output');
  await output.click();
  assert.ok(h.element('overview-fields').textContent.includes('当前浏览器'));
  assert.ok(h.element('overview-recent').textContent.includes('actual-spatial-event'));
  const counts = [h.element('sent-total').textContent, h.element('received-total').textContent,
    h.element('pending-count').textContent, h.element('first-response').textContent];
  for (let frame = 0; frame < 180; frame++) {
    await h.time.tick(20);
    const callbacks = [...h.time.frames.values()]; h.time.frames.clear();
    for (const callback of callbacks) callback(h.time.now);
    await settle();
    assert.ok(h.element('scene-ambient').children.length <= 24);
    assert.ok(h.element('scene-particles').children.length <= 32);
    assert.ok(h.element('scene-tokens').children.length <= 8);
    assert.ok(h.time.frames.size <= 1);
  }
  assert.deepEqual([h.element('sent-total').textContent, h.element('received-total').textContent,
    h.element('pending-count').textContent, h.element('first-response').textContent], counts,
    'ambient animation must never manufacture frame or timing observations');
  assert.equal(h.element('overview-details').hidden, false, 'poll updates must preserve an open observer drawer');
  assert.ok(h.element('overview-fields').textContent.includes('当前浏览器'));
  await h.hide();
  assert.equal(h.time.frames.size, 0);
  for (const id of ['scene-ambient', 'scene-particles', 'scene-tokens']) assert.equal(h.element(id).children.length, 0);
});

test('opt-in activity joins real frame handoffs and qualified runs; replay never manufactures transport counts', async t => {
  // A fast recursive pipeline can finish before its ingress_accepted record.
  // The viewer must join identities rather than interpreting seq as causality.
  const stage0 = {stageId: 'stage-0', stageIndex: 0, stageType: 'echo', invocationId: 'invoke-0'};
  const stage1 = {stageId: 'stage-1', stageIndex: 1, stageType: 'echo', invocationId: 'invoke-1'};
  const events = [
    activityEvent(1, {...stage0, phase: 'enter', outcome: 'observed'}),
    activityEvent(2, {...stage0, phase: 'output', outcome: 'offered', frameId: 'middle-1', causationId: 'input-1', preview: 'synthetic middle'}),
    activityEvent(3, {...stage1, phase: 'enter', outcome: 'observed', frameId: 'middle-1', preview: 'synthetic middle'}),
    activityEvent(4, {...stage1, phase: 'output', outcome: 'offered', frameId: 'output-1', causationId: 'middle-1', preview: '<b>actual bounded result</b>'}),
    activityEvent(5, {phase: 'transport_output_queued', outcome: 'queued', frameId: 'output-1', preview: '<b>actual bounded result</b>'}),
    activityEvent(6, {...stage1, phase: 'sync_return', outcome: 'returned', frameId: 'middle-1'}),
    activityEvent(7, {...stage0, phase: 'sync_return', outcome: 'returned'}),
    activityEvent(8),
    activityEvent(9, {phase: 'transport_output_written', outcome: 'netty_write', frameId: 'output-1', preview: '<b>actual bounded result</b>'}),
    activityEvent(10, {correlationId: '', runId: '', frameId: '', preview: 'unassigned raw observation'}),
    activityEvent(11, {sessionId: 'ws-stream-a-2', frameId: 'input-other-session', preview: 'same correlation in another session'}),
    // Sending text also submits TURN_END under the same correlation. Control
    // output belongs in the journal without replacing a stage's content preview.
    activityEvent(12, {...stage0, invocationId: 'invoke-control-0', phase: 'enter', outcome: 'observed', frameId: 'control-input', frameType: 'turn_end', preview: ''}),
    activityEvent(13, {...stage0, invocationId: 'invoke-control-0', phase: 'output', outcome: 'offered', frameId: 'control-middle', causationId: 'control-input', frameType: 'turn_end', preview: ''}),
    activityEvent(14, {...stage1, invocationId: 'invoke-control-1', phase: 'enter', outcome: 'observed', frameId: 'control-middle', frameType: 'turn_end', preview: ''}),
    activityEvent(15, {...stage1, invocationId: 'invoke-control-1', phase: 'output', outcome: 'offered', frameId: 'control-output', causationId: 'control-middle', frameType: 'turn_end', preview: ''}),
    activityEvent(16, {...stage1, invocationId: 'invoke-control-1', phase: 'sync_return', outcome: 'returned', frameId: 'control-middle', frameType: 'turn_end', preview: ''}),
    activityEvent(17, {...stage0, invocationId: 'invoke-control-0', phase: 'sync_return', outcome: 'returned', frameId: 'control-input', frameType: 'turn_end', preview: ''}),
    activityEvent(18, {frameId: 'control-input', frameType: 'turn_end', preview: ''}),
    activityEvent(19, {phase: 'transport_output_written', outcome: 'netty_write', frameId: 'control-output', frameType: 'turn_end', preview: ''}),
  ];
  let first = true;
  const h = await harness({runtime: observedRuntime, activity: url => {
    if (first) { first = false; return activityPage('stream-a', events); }
    const after = Number(new URL(url, 'http://fixture').searchParams.get('after'));
    return activityPage('stream-a', [], {oldestSeq: 1, latestSeq: 19, nextCursor: after});
  }}); t.after(() => h.dispose()); await settle();
  assert.equal(h.element('task-total').textContent, '2', 'equal correlation ids in different server sessions must stay separate');
  assert.equal(h.element('task-produced').textContent, '1');
  assert.ok(h.element('activity-status').textContent.includes('1 条无关联活动未串成任务'));
  const taskKey = h.element('scene-nodes').querySelectorAll('.execution-card')
    .find(node => node.dataset.stageId === 'stage-1').dataset.taskKey;
  const stage = h.element('scene-nodes').querySelectorAll('.execution-card')
    .find(node => node.dataset.taskKey === taskKey && node.dataset.stageId === 'stage-1');
  assert.ok(stage.textContent.includes('synthetic middle')); assert.ok(stage.textContent.includes('<b>actual bounded result</b>'));
  assert.ok(!stage.textContent.includes('CONTROL'), 'later TURN_END output must not obscure the content preview');
  assert.ok(stage.textContent.includes('2 次调用 · 2 次输出观测'), 'control calls and outputs still contribute to observed counts');
  assert.equal(stage.querySelectorAll('b').length, 0, 'previews are text, never markup');
  const paths = h.element('task-paths').children.filter(node => node.dataset.taskKey === taskKey);
  assert.deepEqual(paths.map(node => node.dataset.eventSeq).sort(), ['1', '3', '9'], 'late ingress still permits frame-id handoffs');
  await stage.click();
  assert.equal(h.element('overview-details').hidden, false); assert.equal(h.element('task-detail').hidden, false);
  assert.equal(h.document.activeElement, h.element('overview-close'));
  assert.ok(h.element('task-detail').textContent.includes('输入 · synthetic middle'));
  assert.ok(h.element('task-detail').textContent.includes('输出 · <b>actual bounded result</b>'));
  assert.ok(h.element('task-detail').textContent.includes('middle-1'));
  assert.ok(h.element('task-detail').textContent.includes('CONTROL · turn_end'), 'the complete retained timeline must still include control events');
  assert.equal(h.element('task-detail').querySelector('.task-event-list').children.length, 17);
  assert.ok(h.element('task-detail').textContent.includes('记录顺序'));
  assert.match(h.element('overview-description').textContent, /不(?:是|代表)整项任务完成/);
  const counts = [h.element('sent-total').textContent, h.element('received-total').textContent,
    h.element('task-total').textContent, h.element('task-produced').textContent];
  await h.element('replay-latest').click(); await paint(h, 100);
  assert.deepEqual([h.element('sent-total').textContent, h.element('received-total').textContent,
    h.element('task-total').textContent, h.element('task-produced').textContent], counts);
  assert.ok(h.element('scene-particles').children.length <= 32); assert.ok(h.element('scene-tokens').children.length <= 8);
  assert.ok(h.element('task-paths').children.length <= 24); assert.ok(h.time.frames.size <= 1);
  await h.element('run-example').click(); await settle();
  assert.equal(h.sockets.length, 0, 'preparing an example must not start a session or submit data');
  assert.equal(h.element('debug-view').hidden, false);
  const socket = await connect(h); assert.ok(socket.url.endsWith('/ws?observe=1'));
  await h.element('connect').click(); await settle(); h.element('observe-activity').checked = false;
  const unobserved = await connect(h); assert.ok(unobserved.url.endsWith('/ws'), 'observation applies explicitly to the next connection');
});

test('activity gaps, bounded task eviction and stream resets remain visible without retaining old task details', async t => {
  const initial = activityPage('stream-a', [activityEvent(1, {preview: 'old-stream-sensitive-preview'})]);
  const pages = [initial]; let latest = 1, streamId = 'stream-a';
  const h = await harness({runtime: observedRuntime, activity: url => {
    if (pages.length) return pages.shift();
    const after = Number(new URL(url, 'http://fixture').searchParams.get('after'));
    return activityPage(streamId, [], {oldestSeq: latest ? 1 : 0, latestSeq: latest, nextCursor: after});
  }}); t.after(() => h.dispose()); await settle();
  let first = h.element('scene-nodes').querySelectorAll('.task-card')[0]; await first.click();
  assert.ok(h.element('task-detail').textContent.includes('old-stream-sensitive-preview'));
  const retained = Array.from({length: 64}, (_, index) => activityEvent(index + 8, {
    correlationId: 'retained-' + index, frameId: 'retained-frame-' + index, preview: 'retained preview ' + index}));
  latest = 71;
  pages.push(activityPage(streamId, retained, {oldestSeq: 8, latestSeq: latest, nextCursor: latest, gap: true, lost: 6, incomplete: true}));
  await h.element('activity-refresh').click(); await settle(); await h.time.tick(0);
  assert.equal(h.element('task-total').textContent, '48', 'retained task groups have a hard cap');
  assert.equal(h.element('activity-gap').hidden, false); assert.ok(h.element('activity-gap').textContent.includes('丢失 6 条'));
  assert.ok(h.element('activity-gap').textContent.includes('本地移出'));
  assert.equal(h.element('overview-details').hidden, true, 'evicting the selected task must retire its detail view');
  assert.ok(!h.element('task-detail').textContent.includes('old-stream-sensitive-preview'));
  first = h.element('scene-nodes').querySelectorAll('.task-card')[0]; await first.click();
  const oldPreview = h.element('task-detail').textContent;
  assert.ok(oldPreview.includes('retained preview'));
  streamId = 'stream-b'; latest = 71;
  pages.push(activityPage(streamId, [activityEvent(71, {sessionId: 'ws-stream-b-1', correlationId: 'new-run', preview: 'new-stream-content'})]));
  // Restart is detected by stream identity even when its seq is >= our cursor.
  pages.push(activityPage(streamId, [activityEvent(1, {sessionId: 'ws-stream-b-1', correlationId: 'new-run', preview: 'new-stream-content'})]));
  latest = 1;
  await h.element('activity-refresh').click(); await settle(); await h.time.tick(0);
  assert.ok(h.fetches.filter(item => String(item.url).startsWith('/api/activity')).at(-1).url.endsWith('after=0'));
  assert.equal(h.element('task-total').textContent, '1'); assert.equal(h.element('activity-gap').hidden, true);
  assert.equal(h.element('overview-details').hidden, true, 'a prior stream task must not remain selected after reset');
  assert.ok(!h.element('task-detail').textContent.includes('retained preview'));
  assert.ok(!h.element('scene-nodes').textContent.includes('old-stream-sensitive-preview'));
  assert.ok(h.element('scene-nodes').textContent.includes('new-stream-content'));
  const activeBeforeHide = h.fetches.length;
  await h.hide(); await h.time.tick(5000); assert.equal(h.fetches.length, activeBeforeHide, 'both observers stop polling while hidden');
  assert.equal(h.fetches.filter(item => item.active).length, 0);
});

test('dragging a scaled module preserves task identity and moves both structural and frame handoff links', async t => {
  const events = [activityEvent(1),
    activityEvent(2, {stageId: 'stage-0', stageIndex: 0, stageType: 'echo', invocationId: 'drag-invoke-0', phase: 'enter'}),
    activityEvent(3, {stageId: 'stage-0', stageIndex: 0, stageType: 'echo', invocationId: 'drag-invoke-0', phase: 'output', outcome: 'offered', frameId: 'drag-middle', preview: 'drag middle'}),
    activityEvent(4, {stageId: 'stage-1', stageIndex: 1, stageType: 'echo', invocationId: 'drag-invoke-1', phase: 'enter', frameId: 'drag-middle', preview: 'drag middle'}),
    activityEvent(5, {stageId: 'stage-1', stageIndex: 1, stageType: 'echo', invocationId: 'drag-invoke-1', phase: 'output', outcome: 'offered', frameId: 'drag-output', preview: 'drag result'}),
    activityEvent(6, {phase: 'transport_output_written', outcome: 'netty_write', frameId: 'drag-output', preview: 'drag result'}),
  ];
  let first = true;
  const h = await harness({runtime: observedRuntime, activity: url => {
    if (first) { first = false; return activityPage('drag-stream', events); }
    const after = Number(new URL(url, 'http://fixture').searchParams.get('after'));
    return activityPage('drag-stream', [], {oldestSeq: 1, latestSeq: 6, nextCursor: after});
  }}); t.after(() => h.dispose()); await settle();
  const world = h.element('scene-world'), viewport = h.element('scene-viewport');
  const transform = () => {
    const match = world.style.transform.match(/translate\(([-\d.]+)px,([-\d.]+)px\) scale\(([-\d.]+)\)/);
    assert.ok(match, 'world has a measurable browser transform'); return match.slice(1).map(Number);
  };
  const rect = (left, top, width, height) => ({left, top, x: left, y: top, width, height, right: left + width, bottom: top + height});
  // A deterministic browser-layout oracle: CSS positions and the actual world
  // transform produce measured screen rectangles. It does not calculate paths.
  h.document.measureElement = node => {
    const [panX, panY, scale] = transform();
    if (node === world) return rect(11 + panX, 19 + panY, parseFloat(world.style.width) * scale, parseFloat(world.style.height) * scale);
    const lane = node.closest('.task-lane');
    if (lane) {
      const isHeader = node.classList.contains('lane-heading');
      const card = node.classList.contains('task-card') || node.classList.contains('execution-card');
      const cardContainer = node.classList.contains('lane-cards');
      const inset = card ? 12 : 0, offset = card ? 80 + node.parentElement.children.indexOf(node) * 100 - node.parentElement.scrollTop : cardContainer ? 64 : 0;
      return rect(11 + panX + (parseFloat(lane.style.left) + inset) * scale,
        19 + panY + (parseFloat(lane.style.top) + offset) * scale,
        (parseFloat(lane.style.width) - 2 * inset) * scale, (isHeader ? 64 : cardContainer ? 420 : 90) * scale);
    }
    return rect(0, 0, node.clientWidth, node.clientHeight);
  };
  await h.element('overview-zoom-in').click(); await h.element('refresh-runtime').click(); await settle();
  const header = h.element('scene-nodes').querySelectorAll('.lane-heading').find(node => node.dataset.nodeId === 'stage-0');
  assert.ok(header); const lane = header.closest('.task-lane');
  const stageCard = h.element('scene-nodes').querySelectorAll('.execution-card').find(node => node.dataset.stageId === 'stage-0');
  stageCard.focus();
  const focusedKey = stageCard.dataset.focusKey;
  await h.element('refresh-runtime').click(); await settle();
  assert.equal(h.document.activeElement.dataset.focusKey, focusedKey, 'poll rendering preserves the focused task card');
  const structural = () => h.element('scene-paths').children.map(node => node.getAttribute('d'));
  const handoffs = () => new Map(h.element('task-paths').children.map(node => [node.dataset.eventSeq,
    {d: node.getAttribute('d'), taskKey: node.dataset.taskKey}]));
  const before = {x: parseFloat(lane.style.left), y: parseFloat(lane.style.top), transform: world.style.transform,
    structural: structural(), handoffs: handoffs()};
  const zoom = transform()[2]; assert.notEqual(zoom, 1);
  header.focus();
  await viewport.emit('pointerdown', {target: header.querySelector('.node-label'), button: 0, pointerId: 17, clientX: 300, clientY: 150});
  await viewport.emit('pointermove', {pointerId: 17, buttons: 1, clientX: 342, clientY: 171});
  await h.element('refresh-runtime').click(); await settle();
  assert.equal(h.element('scene-nodes').querySelectorAll('.lane-heading').find(node => node.dataset.nodeId === 'stage-0'), header,
    'an unchanged blueprint must not replace the captured drag handle');
  await viewport.emit('pointermove', {pointerId: 17, buttons: 1, clientX: 384, clientY: 192});
  await viewport.emit('pointerup', {pointerId: 17, clientX: 384, clientY: 192});
  await header.emit('click', {detail: 1, clientX: 384, clientY: 192}); // Compatibility click after pointer release.
  assert.equal(h.element('overview-details').hidden, true, 'a completed drag must not also open a detail panel');
  assert.ok(Math.abs(parseFloat(lane.style.left) - before.x - 84 / zoom) < 0.00001);
  assert.ok(Math.abs(parseFloat(lane.style.top) - before.y - 42 / zoom) < 0.00001);
  assert.equal(world.style.transform, before.transform, 'dragging a module must not pan or rescale the world');
  assert.equal(h.document.activeElement, header, 'polling during a drag must not steal handle focus');
  const after = structural(); assert.notEqual(after[0], before.structural[0]); assert.notEqual(after[1], before.structural[1]);
  assert.equal(after[2], before.structural[2], 'unrelated configuration links keep their endpoints');
  const movedHandoffs = handoffs();
  assert.deepEqual([...movedHandoffs.keys()], [...before.handoffs.keys()]);
  for (const seq of ['2', '4']) assert.notEqual(movedHandoffs.get(seq).d, before.handoffs.get(seq).d);
  assert.equal(movedHandoffs.get('6').d, before.handoffs.get('6').d);
  for (const [seq, value] of movedHandoffs) assert.equal(value.taskKey, before.handoffs.get(seq).taskKey,
    'visual movement must never reassign a frame handoff to another task');
  const settled = [lane.style.left, lane.style.top];
  await viewport.emit('pointerdown', {target: header, button: 0, pointerId: 18, clientX: 400, clientY: 220});
  await viewport.emit('pointercancel', {pointerId: 18});
  await viewport.emit('pointermove', {pointerId: 18, buttons: 1, clientX: 700, clientY: 440});
  assert.deepEqual([lane.style.left, lane.style.top], settled, 'canceled pointer ownership must stop moving the module');
  await h.element('refresh-runtime').click(); await settle();
  assert.deepEqual([lane.style.left, lane.style.top], settled, 'ordinary metadata refresh preserves the local layout');
  const cards = lane.querySelector('.lane-cards'); let prevented = false;
  await viewport.emit('wheel', {target: cards, deltaX: 0, deltaY: 40, preventDefault() { prevented = true; }});
  assert.equal(prevented, false, 'scrolling task contents must remain native rather than pan the board');
  assert.equal(world.style.transform, before.transform);
  await viewport.emit('pointerdown', {target: cards, button: 0, pointerId: 19, clientX: 300, clientY: 300});
  await viewport.emit('pointermove', {pointerId: 19, buttons: 1, clientX: 360, clientY: 340});
  await viewport.emit('pointerup', {pointerId: 19});
  assert.equal(world.style.transform, before.transform, 'interacting with task contents must not begin background panning');
  cards.scrollTop = 200; await viewport.emit('scroll', {target: cards}); await settle();
  assert.deepEqual([...handoffs().keys()], ['6'], 'an offscreen task card must not leave a link attached to an invisible item');
  cards.scrollTop = 0; await viewport.emit('scroll', {target: cards}); await settle();
  assert.deepEqual([...handoffs().keys()], ['2', '4', '6'], 'scrolling the real cards back restores their original frame handoffs');
});
