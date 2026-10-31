/* OminiCat Studio: native browser UI; configured topology is not a stage trace. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const SVG = 'http://www.w3.org/2000/svg';
  const LIMIT = { logs: 80, preview: 800, pending: 64, pendingMs: 60000, samples: 60,
    particles: 32, frameBytes: 1048576, outgoingBytes: 2097152, imageBytes: 600000,
    playbackSeconds: 15, playbackSources: 32, captureSeconds: 15 };
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const state = { socket: null, sessionEpoch: 0, mediaEpoch: 0, captureEpoch: 0,
    sessionId: '', connectedAt: 0, finishing: false, runtime: null, runtimeState: 'loading',
    sent: 0, received: 0, bytes: 0, lastRtt: null, pending: new Map(), samples: [],
    selected: 'client-input', nodes: [], edges: [], topologyKey: '', graphWidth: 800,
    graphHeight: 250, zoom: 1, panX: 0, panY: 0, particles: [], motion: !reducedMotion.matches,
    recording: null, pendingCapture: null, recordPending: false, imageOperation: null,
    playback: null, playhead: 0, playbackSources: new Set(), sound: true, sequence: 0, disposed: false,
    view: null, recent: [] };
  const scene = { nodes: [], edges: [], ambient: [], particles: [], tokens: [], selected: 'current-pipeline',
    zoom: 1, panX: 0, panY: 0, width: 1280, height: 650, raf: 0, renderedAt: 0 };
  const activity = { streamId: null, cursor: 0, latest: 0, tasks: new Map(), origins: new Map(),
    events: [], selectedTask: null, focusTargets: new Map(), returnFocus: null, incomplete: false,
    lost: 0, unlinked: 0, evicted: 0, controller: null, timer: 0, timeout: 0, epoch: 0,
    supported: true, status: '等待任务活动', replay: [], replaySkipped: 0, replayFocus: null, replayAt: 0,
    observeSocket: false, taskEdges: new Map() };
  const TASK_LIMIT = { tasks: 48, events: 512, perTask: 64, stages: 64, invocations: 128, origins: 512, replay: 48 };
  const CONTROL_TYPES = new Set(['start','end','turn_end','interrupt','speech_started','speech_stopped']);
  let pollTimer = 0, pollController = null, pollTimeout = 0, pollEpoch = 0;
  let animationId = 0, toastTimer = 0, lastActivity = 0;
  const now = () => performance.now();
  const short = (value, max = LIMIT.preview) => String(value ?? '').slice(0, max);
  const known = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
  const number = value => known(value) ? value.toLocaleString('en-US') : '—';
  const bytes = value => !known(value) ? '—' : value >= 1048576 ? (value / 1048576).toFixed(1) + ' MiB' : value >= 1024 ? (value / 1024).toFixed(1) + ' KiB' : value + ' B';
  const time = () => new Date().toLocaleTimeString('zh-CN', { hour12: false });
  const unique = () => typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : 'studio-' + Date.now().toString(36) + '-' + (++state.sequence);
  function text(id, value) { $(id).textContent = String(value); }
  function element(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = String(content);
    return node;
  }
  function toast(message, error = false) {
    clearTimeout(toastTimer); text('toast', short(message, 180));
    $('toast').classList.toggle('error', error); $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3600);
    text('statusbar-message', short(message, 180));
  }
  function log(direction, type, body, kind = '') {
    $('empty')?.remove();
    const row = element('div', 'event ' + kind);
    row.append(element('time', '', time()), element('span', 'direction', direction),
      element('span', 'event-type', short(type, 48)), element('span', 'event-body', short(body)));
    $('feed').append(row);
    while ($('feed').children.length > LIMIT.logs) $('feed').firstChild.remove();
    $('feed').scrollTop = $('feed').scrollHeight;
    text('count', $('feed').children.length);
    state.recent.push({ direction, type: short(type, 48), body: short(body, 180), at: time() });
    if (state.recent.length > 12) state.recent.shift();
    renderRecent();
  }
  function metric(id, value, unit) {
    const node = $(id); node.replaceChildren(document.createTextNode(String(value) + ' '), element('small', '', unit));
  }
  function isOpen() { return state.socket?.readyState === WebSocket.OPEN && !state.finishing; }
  function controls() {
    const open = isOpen();
    $('send').disabled = !open;
    $('record').disabled = !open || state.recordPending;
    $('image').disabled = !open || !!state.imageOperation;
    $('interrupt').disabled = !open;
    $('finish').disabled = !open;
    $('use-envelope').disabled = !!state.recording;
    $('observe-activity').disabled = !!state.socket;
    const connecting = state.socket?.readyState === WebSocket.CONNECTING;
    $('connect').disabled = false;
    $('connect').replaceChildren(element('span', 'button-dot'),
      element('span', '', state.socket ? (connecting ? '取消连接' : '断开连接') : '连接运行时'),
      element('span', 'button-arrow', state.socket ? '×' : '↗'));
    $('dot').classList.toggle('live', open);
    $('timeline-dot').classList.toggle('live', open);
    text('session-badge', state.finishing ? '排空中' : open ? '已连接' : connecting ? '连接中' : '未连接');
  }
  function clientMetrics() {
    metric('sent-total', number(state.sent), 'frames');
    metric('received-total', number(state.received), 'frames');
    metric('first-response', state.lastRtt === null ? '—' : state.lastRtt.toFixed(1), 'ms');
    const formatted = bytes(state.bytes).split(' ');
    metric('sent-bytes', formatted[0], formatted[1] || 'B');
    text('pending-count', state.pending.size);
    text('session-duration', state.connectedAt && state.socket?.readyState === WebSocket.OPEN
      ? Math.floor((now() - state.connectedAt) / 1000) + ' s' : '—');
    for (const node of state.nodes) {
      if (node.id === 'client-input') node.count.textContent = state.sent + ' 已发送';
      if (node.id === 'client-output') node.count.textContent = state.received + ' 已接收';
    }
    renderInspector();
    sceneMetrics();
  }
  function expirePending() {
    const cutoff = now() - LIMIT.pendingMs;
    for (const [id, item] of state.pending) if (item.started < cutoff) state.pending.delete(id);
  }
  function requestContext() {
    return { correlationId: unique(), turnId: unique(), envelope: $('use-envelope').checked };
  }
  function base64(data) {
    let string = '';
    for (let index = 0; index < data.length; index += 8192) string += String.fromCharCode(...data.subarray(index, index + 8192));
    return btoa(string);
  }
  function preview(frame) {
    if (frame.type === 'audio') return 'PCM16 · ' + frame.sampleRate + ' Hz · ' + frame.channels + ' ch';
    if (frame.type === 'image') return short(frame.mimeType, 60) + ' · base64 payload';
    if (typeof frame.text === 'string') return short(frame.text);
    if (typeof frame.message === 'string') return short(frame.message);
    return short(JSON.stringify(frame.data ?? frame));
  }
  function send(frame, context = requestContext(), trackResponse = false) {
    if (!isOpen()) { toast('请先连接运行时', true); return false; }
    const started = now();
    const nanos = Math.min(Number.MAX_SAFE_INTEGER, Math.floor(started * 1000000)); // Carried only; no cross-clock subtraction.
    const wire = context.envelope ? { type: 'envelope', id: unique(), sessionId: state.sessionId,
      turnId: context.turnId, correlationId: context.correlationId, causationId: '',
      originTimeNanos: nanos, ingestTimeNanos: nanos, emitTimeNanos: nanos, frame } : frame;
    const json = JSON.stringify(wire), size = new TextEncoder().encode(json).byteLength;
    const serverLimit = state.runtime?.config?.maxFrameBytes;
    if (size > Math.min(LIMIT.frameBytes, known(serverLimit) ? serverLimit : LIMIT.frameBytes)) {
      toast('该帧超过服务端或本页的发送大小限制', true); return false;
    }
    if (state.socket.bufferedAmount + size > LIMIT.outgoingBytes) {
      toast('客户端发送缓冲已满，请等待后再发', true); return false;
    }
    try { state.socket.send(json); } catch (_) { toast('发送失败，请检查连接状态', true); return false; }
    if (trackResponse) {
      state.lastRtt = null;
      if (context.envelope) {
        expirePending();
        if (state.pending.size >= LIMIT.pending) state.pending.delete(state.pending.keys().next().value);
        state.pending.set(context.correlationId, { started, sessionEpoch: state.sessionEpoch });
      }
    }
    state.sent++; state.bytes += size;
    log('TX ↑', frame.type, preview(frame));
    pulse('sent', frame.type); clientMetrics(); return true;
  }
  function stopPlayback() {
    for (const source of state.playbackSources) {
      try { source.stop(); source.disconnect(); } catch (_) { /* already ended */ }
    }
    state.playbackSources.clear(); state.playhead = 0;
    const context = state.playback; state.playback = null;
    if (context) Promise.resolve(context.close()).catch(() => {});
  }
  function audioContext() {
    const Audio = window.AudioContext || window.webkitAudioContext;
    if (!Audio) throw new Error('此浏览器不支持 Web Audio');
    if (!state.playback || state.playback.state === 'closed') state.playback = new Audio();
    if (state.playback.state === 'suspended') Promise.resolve(state.playback.resume()).catch(() => {});
    return state.playback;
  }
  function playAudio(frame) {
    if (!state.sound || document.hidden || state.view !== 'debug') return;
    if (typeof frame.data !== 'string' || frame.data.length > 1500000
      || !Number.isInteger(frame.channels) || frame.channels < 1 || frame.channels > 32
      || !Number.isInteger(frame.sampleRate) || frame.sampleRate < 1 || frame.sampleRate > 384000) throw new Error('收到不支持的 PCM 格式');
    const decoded = atob(frame.data);
    if (!decoded.length || decoded.length % (frame.channels * 2)) throw new Error('PCM 采样边界无效');
    const samples = decoded.length / 2 / frame.channels, duration = samples / frame.sampleRate;
    const context = audioContext();
    if (Math.max(context.currentTime, state.playhead) - context.currentTime + duration > LIMIT.playbackSeconds
      || state.playbackSources.size >= LIMIT.playbackSources) {
      stopPlayback(); throw new Error('播放队列超过 15 秒或 32 段限制，已停止本地播放');
    }
    const data = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index++) data[index] = decoded.charCodeAt(index);
    const view = new DataView(data.buffer), buffer = context.createBuffer(frame.channels, samples, frame.sampleRate);
    for (let channel = 0; channel < frame.channels; channel++) {
      const output = buffer.getChannelData(channel);
      for (let index = 0; index < samples; index++) output[index] = view.getInt16((index * frame.channels + channel) * 2, true) / 32768;
    }
    const source = context.createBufferSource(); source.buffer = buffer; source.connect(context.destination);
    source.onended = () => { state.playbackSources.delete(source); try { source.disconnect(); } catch (_) {} };
    state.playbackSources.add(source);
    state.playhead = Math.max(context.currentTime, state.playhead); source.start(state.playhead); state.playhead += duration;
  }
  function operation() { return { socket: state.socket, sessionEpoch: state.sessionEpoch, mediaEpoch: state.mediaEpoch }; }
  function current(owner) {
    return owner.socket === state.socket && owner.sessionEpoch === state.sessionEpoch
      && owner.mediaEpoch === state.mediaEpoch && isOpen() && !document.hidden && state.view === 'debug';
  }
  function disposeCapture(capture) {
    if (!capture || capture.disposed) return;
    capture.disposed = true;
    clearTimeout(capture.timer);
    if (capture.moduleUrl) { URL.revokeObjectURL(capture.moduleUrl); capture.moduleUrl = null; }
    if (capture.worklet) capture.worklet.port.onmessage = null;
    for (const node of [capture.source, capture.worklet, capture.mute]) { try { node?.disconnect(); } catch (_) {} }
    capture.stream?.getTracks().forEach(track => track.stop());
    if (capture.context) Promise.resolve(capture.context.close()).catch(() => {});
  }
  function stopRecording(transmit) {
    const capture = state.recording; state.recording = null;
    state.captureEpoch++;
    const pending = state.pendingCapture; state.pendingCapture = null; disposeCapture(pending);
    state.recordPending = false;
    $('record').classList.remove('recording'); text('record', '开始录音');
    text('capture-time', '00:00'); $('capture-level').style.width = '0%';
    if (!capture) { controls(); return; }
    disposeCapture(capture);
    if (transmit && current(capture.owner)) {
      const input = new Float32Array(capture.length);
      let offset = 0; for (const chunk of capture.chunks) { input.set(chunk, offset); offset += chunk.length; }
      const count = Math.floor(input.length * 16000 / capture.rate), data = new Uint8Array(count * 2), view = new DataView(data.buffer);
      for (let index = 0; index < count; index++) {
        const from = Math.floor(index * capture.rate / 16000);
        const to = Math.min(input.length, Math.max(from + 1, Math.floor((index + 1) * capture.rate / 16000)));
        let value = 0; for (let cursor = from; cursor < to; cursor++) value += input[cursor];
        value = Math.max(-1, Math.min(1, value / Math.max(1, to - from)));
        view.setInt16(index * 2, Math.round(value * (value < 0 ? 32768 : 32767)), true);
      }
      if (data.length) {
        const context = requestContext();
        if (send({ type: 'audio', data: base64(data), sampleRate: 16000, channels: 1, streamId: 'microphone', startSample: -1 }, context, true))
          send({ type: 'turn_end' }, context);
      } else toast('未采集到音频，请重试', true);
    }
    capture.chunks.length = 0; controls();
  }
  function cancelMedia() {
    state.mediaEpoch++; stopRecording(false); state.imageOperation = null; stopPlayback(); controls();
  }
  async function startRecording() {
    if (state.recording) { stopRecording(true); return; }
    if (!isOpen() || state.recordPending || state.view !== 'debug') return;
    if (!navigator.mediaDevices?.getUserMedia) { toast('录音需要 localhost / HTTPS 与麦克风权限', true); return; }
    const owner = operation(), epoch = ++state.captureEpoch;
    state.recordPending = true; controls(); text('record', '等待麦克风权限…');
    let capture = null;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } });
      capture = { owner, stream, chunks: [], length: 0, context: null };
      if (!current(owner) || epoch !== state.captureEpoch) { disposeCapture(capture); return; }
      const Audio = window.AudioContext || window.webkitAudioContext;
      if (!Audio) throw new Error('浏览器不支持 Web Audio');
      const context = new Audio(); capture.context = context; capture.rate = context.sampleRate;
      state.pendingCapture = capture;
      if (!context.audioWorklet || typeof AudioWorkletNode !== 'function') throw new Error('浏览器不支持 AudioWorklet');
      const module = URL.createObjectURL(new Blob(["class OminiCapture extends AudioWorkletProcessor{process(inputs){const data=inputs[0]&&inputs[0][0];if(data)this.port.postMessage(data.slice());return true;}}registerProcessor('ominicat-capture',OminiCapture);"], { type: 'text/javascript' }));
      capture.moduleUrl = module;
      try { await context.audioWorklet.addModule(module); } finally {
        if (capture.moduleUrl) { URL.revokeObjectURL(capture.moduleUrl); capture.moduleUrl = null; }
      }
      if (!current(owner) || epoch !== state.captureEpoch) { disposeCapture(capture); return; }
      capture.source = context.createMediaStreamSource(stream);
      capture.worklet = new AudioWorkletNode(context, 'ominicat-capture');
      capture.mute = context.createGain(); capture.mute.gain.value = 0;
      capture.worklet.port.onmessage = event => {
        if (state.recording !== capture || !current(owner)) return;
        const data = event.data;
        if (!(data instanceof Float32Array) || data.length > 4096) { stopRecording(false); toast('录音块格式或长度无效', true); return; }
        const remaining = Math.floor(capture.rate * LIMIT.captureSeconds) - capture.length;
        const chunk = data.slice(0, Math.max(0, remaining));
        if (chunk.length) {
          capture.chunks.push(chunk); capture.length += chunk.length;
          let energy = 0; for (const value of chunk) energy += value * value;
          $('capture-level').style.width = Math.min(100, Math.sqrt(energy / chunk.length) * 400) + '%';
          text('capture-time', '00:' + String(Math.floor(capture.length / capture.rate)).padStart(2, '0'));
        }
        if (capture.length >= capture.rate * LIMIT.captureSeconds) stopRecording(true);
      };
      capture.source.connect(capture.worklet); capture.worklet.connect(capture.mute); capture.mute.connect(context.destination);
      if (context.state === 'suspended') await context.resume();
      if (!current(owner) || epoch !== state.captureEpoch) { disposeCapture(capture); return; }
      state.pendingCapture = null; state.recording = capture;
      capture.timer = setTimeout(() => { if (state.recording === capture) stopRecording(true); }, LIMIT.captureSeconds * 1000);
      text('record', '停止录音并发送 ■'); $('record').classList.add('recording');
      log('LOCAL', 'microphone', '正在采集单声道音频，停止后转换为 16 kHz PCM16。', 'system');
    } catch (_) {
      disposeCapture(capture);
      if (current(owner) && epoch === state.captureEpoch) { toast('无法录音，请检查权限与浏览器支持', true); text('record', '开始录音'); }
    } finally {
      if (epoch === state.captureEpoch) { state.recordPending = false; if (state.pendingCapture === capture) state.pendingCapture = null; controls(); }
    }
  }
  function disconnect(reason = '会话已断开') {
    const socket = state.socket;
    state.socket = null; state.sessionEpoch++; state.finishing = false; state.connectedAt = 0;
    state.pending.clear(); cancelMedia(); controls(); text('status', reason);
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'Client closed');
  }
  function connect() {
    if (state.socket) { disconnect(); log('LOCAL', 'disconnect', '已主动关闭当前会话并释放本地音频。', 'system'); return; }
    state.sessionEpoch++; state.sessionId = unique(); state.finishing = false;
    state.sent = 0; state.received = 0; state.bytes = 0; state.lastRtt = null; state.pending.clear();
    text('session-id', state.sessionId.slice(0, 8)); $('session-id').title = state.sessionId;
    const protocols = ['ominicat'];
    if ($('token').value) protocols.push('bearer.' + base64(new TextEncoder().encode($('token').value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''));
    let socket;
    activity.observeSocket = $('observe-activity').checked;
    try { socket = new WebSocket((location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host + '/ws' + (activity.observeSocket ? '?observe=1' : ''), protocols); }
    catch (_) { toast('无法创建 WebSocket 连接，请检查当前地址', true); return; }
    state.socket = socket; controls(); clientMetrics(); text('status', '正在建立同源连接…');
    socket.onopen = () => {
      if (state.socket !== socket) { socket.close(); return; }
      state.connectedAt = now(); text('status', '已连接 · /ws'); controls();
      log('LOCAL', 'connected', '会话已建立。配置处理器与节点示意见中央画布。', 'system');
      send({ type: 'start' }); refreshSoon(); refreshActivity();
    };
    socket.onclose = event => {
      if (state.socket !== socket) return;
      disconnect('连接关闭 · ' + event.code); clientMetrics();
      log('LOCAL', 'closed', 'WebSocket ' + event.code + (event.reason ? ' · ' + short(event.reason, 140) : ''), 'system');
    };
    socket.onerror = () => { if (state.socket === socket) { toast('连接失败，请检查令牌与运行时', true); log('LOCAL', 'connection', 'WebSocket 连接失败；不会显示令牌。', 'err'); } };
    socket.onmessage = event => {
      if (state.socket !== socket) return;
      try {
        if (typeof event.data !== 'string' || event.data.length > LIMIT.frameBytes * 2) throw new Error('返回帧超出本页大小限制');
        const wire = JSON.parse(event.data), frame = wire.type === 'envelope' ? wire.frame : wire;
        if (!frame || typeof frame.type !== 'string') throw new Error('返回帧缺少类型');
        state.received++; text('last-frame', short(frame.type, 25));
        if (state.finishing && frame.type === 'error' && frame.code === 'overloaded'
            && typeof frame.message === 'string' && frame.message.includes('retry end after draining')) {
          state.finishing = false; controls(); text('status', 'END 未被接收 · 可等待后重试');
          toast('入口拥塞，END 未被接收；可等待排空后重试或中断', true);
        }
        expirePending();
        if (wire.type === 'envelope' && typeof wire.correlationId === 'string') {
          const item = state.pending.get(wire.correlationId);
          if (item && item.sessionEpoch === state.sessionEpoch) { state.lastRtt = now() - item.started; state.pending.delete(wire.correlationId); }
        }
        log('RX ↓', frame.type, preview(frame), frame.type === 'error' || frame.type === 'failure' ? 'err' : 'out');
        if (frame.type === 'audio') playAudio(frame);
        pulse('received', frame.type); clientMetrics();
      } catch (error) { log('LOCAL', 'client', short(error.message, 180), 'err'); }
    };
  }

  function svg(tag, attributes) {
    const node = document.createElementNS(SVG, tag);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
    return node;
  }
  function topology(stages) {
    const narrow = window.innerWidth <= 1020;
    const key = JSON.stringify(stages.map(stage => [stage.id, stage.type])) + (narrow ? ':narrow' : ':wide');
    if (key === state.topologyKey && state.nodes.length) return;
    state.topologyKey = key;
    clearParticles(); $('graph-nodes').replaceChildren(); $('edge-paths').replaceChildren();
    const nodes = [{ id: 'client-input', label: '客户端输入', type: 'INPUT SOURCE', glyph: 'IN', role: 'client' },
      { id: 'transport-ingress', label: '会话入口', type: 'WEBSOCKET / QUEUE', glyph: 'WS', role: 'transport' },
      ...stages.map((stage, index) => ({ id: stage.id, label: stage.type, type: 'CONFIGURED PROCESSOR', glyph: String(index + 1).padStart(2, '0'), role: 'processor', index })),
      { id: 'client-output', label: '输出到客户端', type: 'OUTPUT OBSERVATION', glyph: 'OUT', role: 'client' }];
    const singleRow = !narrow && nodes.length <= 6;
    const columns = singleRow ? nodes.length : narrow ? 2 : 4;
    const rows = Math.ceil(nodes.length / columns);
    const top = singleRow ? 32 : 48;
    state.graphWidth = 32 + (columns - 1) * 184 + 160;
    state.graphHeight = top + (rows - 1) * 130 + 98 + 24;
    $('graph-viewport').style.minHeight = narrow
      ? Math.min(760, Math.ceil(state.graphHeight * Math.min(1, (($('graph-viewport').clientWidth || 400) - 24) / state.graphWidth) + 64)) + 'px' : '';
    $('graph-world').style.width = state.graphWidth + 'px'; $('graph-world').style.height = state.graphHeight + 'px';
    $('graph-edges').setAttribute('width', state.graphWidth); $('graph-edges').setAttribute('height', state.graphHeight);
    state.nodes = nodes.map((node, index) => {
      const row = Math.floor(index / columns), column = row % 2 ? columns - 1 - index % columns : index % columns;
      node.x = 16 + column * 184; node.y = top + row * 130;
      const button = element('button', 'graph-node' + (node.role !== 'processor' ? ' endpoint-node' : ''));
      button.type = 'button'; button.style.left = node.x + 'px'; button.style.top = node.y + 'px';
      button.setAttribute('aria-label', '查看节点 ' + node.label);
      const title = element('div', 'node-titlebar'), titles = element('div');
      titles.append(element('span', 'node-label', node.label), element('span', 'node-type', node.type));
      title.append(element('span', 'node-glyph', node.glyph), titles);
      const bottom = element('div', 'node-bottom'), status = element('span'), dot = element('i');
      status.append(dot, document.createTextNode(node.role === 'processor' ? '已配置' : '观测边界'));
      node.count = element('span', 'node-count', node.role === 'processor' ? '计数 —' : node.role === 'transport' ? '有界入口' : '0 frames');
      bottom.append(status, node.count); button.append(title, bottom);
      if (index > 0) button.append(element('i', 'node-port in'));
      if (index < nodes.length - 1) button.append(element('i', 'node-port out'));
      button.onclick = () => { state.selected = node.id; renderInspector(); };
      button.onfocus = () => {
        state.selected = node.id; renderInspector();
        const viewport = $('graph-viewport');
        const left = node.x * state.zoom + state.panX, top = node.y * state.zoom + state.panY;
        if (left < 0 || left + 160 * state.zoom > viewport.clientWidth || top < 0 || top + 98 * state.zoom > viewport.clientHeight) {
          state.panX = viewport.clientWidth / 2 - (node.x + 80) * state.zoom;
          state.panY = viewport.clientHeight / 2 - (node.y + 49) * state.zoom;
          graphTransform();
        }
      };
      $('graph-nodes').append(button); node.element = button; return node;
    });
    state.edges = [];
    for (let index = 0; index < state.nodes.length - 1; index++) {
      const from = state.nodes[index], to = state.nodes[index + 1], vertical = from.y !== to.y;
      const p0 = vertical ? [from.x + 80, from.y + 98] : [from.x + (to.x > from.x ? 160 : 0), from.y + 49];
      const p3 = vertical ? [to.x + 80, to.y] : [to.x + (to.x > from.x ? 0 : 160), to.y + 49];
      const p1 = vertical ? [p0[0], p0[1] + 30] : [p0[0] + (p3[0] > p0[0] ? 28 : -28), p0[1]];
      const p2 = vertical ? [p3[0], p3[1] - 30] : [p3[0] - (p3[0] > p0[0] ? 28 : -28), p3[1]];
      $('edge-paths').append(svg('path', { class: 'graph-edge', d: 'M' + p0.join(',') + ' C' + p1.join(',') + ' ' + p2.join(',') + ' ' + p3.join(',') }));
      state.edges.push({ p0, p1, p2, p3 });
    }
    if (!state.nodes.some(node => node.id === state.selected)) state.selected = stages[0]?.id || 'client-input';
    fitGraph(); clientMetrics();
  }
  function renderInspector() {
    const node = state.nodes.find(item => item.id === state.selected);
    if (!node) return;
    for (const item of state.nodes) item.element.classList.toggle('selected', item === node);
    text('node-title', node.label); text('node-id', node.id); text('selected-node-mark', node.glyph);
    text('node-role', node.role === 'processor' ? 'SPI 节点' : node.role === 'transport' ? '传输入口' : '客户端');
    let pairs, description;
    if (node.role === 'processor') {
      pairs = [['配置类型', node.label], ['配置顺序', '#' + String(node.index + 1).padStart(2, '0')], ['逐节点计数', '未提供'], ['逐节点耗时', '未提供']];
      description = '这里只显示服务端配置蓝图。收发脉冲是客户端流转示意，不证明该节点已执行，也不推断节点健康或耗时。';
    } else if (node.role === 'transport') {
      pairs = [['协议', 'WebSocket / JSON'], ['入口容量', number(state.runtime?.config?.queueCapacity)], ['会话队列深度', '未提供'], ['观测范围', '配置 / 传输边界']];
      description = '入口容量来自服务端配置。API 未提供本会话队列深度，不把全运行时指标分摊到这个节点。';
    } else {
      pairs = [['位置', node.id === 'client-input' ? '本页发送边界' : '本页接收边界'], ['实际帧数', node.id === 'client-input' ? number(state.sent) : number(state.received)], ['观测方式', 'WebSocket 事件'], ['单帧预览上限', LIMIT.preview + ' 字符']];
      description = '计数仅属于当前浏览器会话，包含控制帧。首响应仅通过匹配的 correlationId 计算，不与服务端时钟相减。';
    }
    $('node-details').replaceChildren();
    for (const [name, value] of pairs) { const row = element('div'); row.append(element('dt', '', name), element('dd', '', value)); $('node-details').append(row); }
    text('node-description', description);
  }
  function graphTransform() {
    $('graph-world').style.transform = 'translate(' + state.panX + 'px,' + state.panY + 'px) scale(' + state.zoom + ')';
    text('zoom-level', Math.round(state.zoom * 100) + '%');
  }
  function fitGraph() {
    const viewport = $('graph-viewport');
    const width = viewport.clientWidth || 800, height = viewport.clientHeight || 340;
    state.zoom = Math.max(.04, Math.min(1.1, (width - 32) / state.graphWidth, (height - 64) / state.graphHeight));
    state.panX = (width - state.graphWidth * state.zoom) / 2;
    state.panY = (height - state.graphHeight * state.zoom) / 2 + 12; graphTransform();
  }
  function zoomBy(factor) {
    const old = state.zoom, next = Math.max(.04, Math.min(2.4, old * factor)), viewport = $('graph-viewport');
    state.panX = viewport.clientWidth / 2 - (viewport.clientWidth / 2 - state.panX) * next / old;
    state.panY = viewport.clientHeight / 2 - (viewport.clientHeight / 2 - state.panY) * next / old;
    state.zoom = next; graphTransform();
  }
  function clearParticles() {
    cancelAnimationFrame(animationId); animationId = 0; state.particles.length = 0;
    $('flow-particles').replaceChildren();
    for (const node of state.nodes) node.element?.classList.remove('observed');
  }
  function pulse(direction, type = 'frame') {
    if (state.view === 'overview') { scenePulse(direction, type); return; }
    if (!state.motion || reducedMotion.matches || document.hidden) return;
    lastActivity = now();
    const endpoint = state.nodes.find(node => node.id === (direction === 'sent' ? 'client-input' : 'client-output'));
    endpoint?.element.classList.add('observed');
    const edges = direction === 'sent' ? state.edges.slice(0, -1) : state.edges.slice(-1);
    for (let index = 0; index < edges.length; index++) {
      if (state.particles.length >= LIMIT.particles) state.particles.shift().node.remove();
      const node = svg('circle', { r: 2.8, class: 'flow-particle' }); $('flow-particles').append(node);
      state.particles.push({ node, edge: edges[index], started: now() + index * 45 });
    }
    if (!animationId && state.particles.length) animationId = requestAnimationFrame(animate);
  }
  function animate(timestamp) {
    animationId = 0;
    if (!state.motion || reducedMotion.matches || document.hidden) { clearParticles(); return; }
    state.particles = state.particles.filter(particle => {
      const progress = (timestamp - particle.started) / 750;
      if (progress >= 1) { particle.node.remove(); return false; }
      const t = Math.max(0, progress), u = 1 - t, { p0, p1, p2, p3 } = particle.edge;
      particle.node.setAttribute('cx', u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]);
      particle.node.setAttribute('cy', u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]);
      particle.node.setAttribute('opacity', progress < 0 ? 0 : 1);
      return true;
    });
    if (!state.particles.length || timestamp - lastActivity > 900)
      for (const node of state.nodes) node.element.classList.remove('observed');
    if (state.particles.length) animationId = requestAnimationFrame(animate);
  }

  function renderRuntime(runtime) {
    const config = runtime?.config, memory = runtime?.memory, metrics = runtime?.metrics;
    text('runtime-version', runtime ? 'JAVA ' + runtime.runtime.javaVersion + ' / ' + runtime.runtime.workerBackend.toUpperCase() : '运行时 —');
    text('heap-value', bytes(memory?.heapUsedBytes) + ' / ' + bytes(memory?.heapMaxBytes));
    $('heap-bar').style.width = known(memory?.heapUsedBytes) && memory.heapMaxBytes > 0 ? Math.min(100, memory.heapUsedBytes / memory.heapMaxBytes * 100) + '%' : '0%';
    text('runtime-sessions', number(metrics?.activeSessions) + ' / ' + number(config?.maxSessions));
    text('runtime-workers', number(metrics?.pipelineWorkers)); text('runtime-threads', number(runtime?.threads?.live));
    text('runtime-direct', bytes(memory?.directUsedBytes)); text('runtime-pending', bytes(metrics?.pendingOutputBytes));
    const stages = runtime?.pipeline?.stages || [];
    topology(stages);
    $('blueprint-empty').hidden = !!runtime;
    text('pipeline-name', runtime ? stages.length + ' processors' : '等待蓝图');
    text('pipeline-stages', runtime ? stages.length + ' 个配置处理器 · 当前部署只读' : '尚未读取服务端配置');
    if (known(memory?.heapUsedBytes)) {
      state.samples.push({ heap: memory.heapUsedBytes, at: Date.now() });
      if (state.samples.length > LIMIT.samples) state.samples.shift();
    }
    drawChart(); clientMetrics();
    buildScene();
    if(runtime?.activity?.supported && activity.supported && !activity.controller && !activity.timer)pollActivity();
  }
  function drawChart() {
    text('sample-count', state.samples.length + ' snapshots');
    if (!state.samples.length) { $('heap-chart-path').setAttribute('d', ''); return; }
    const maximum = Math.max(1, ...state.samples.map(sample => sample.heap));
    const minimum = Math.min(...state.samples.map(sample => sample.heap));
    const span = Math.max(maximum - minimum, maximum * .08, 1);
    const path = state.samples.map((sample, index) => (index ? 'L' : 'M') + (index / Math.max(1, state.samples.length - 1) * 600).toFixed(1) + ',' + (95 - (sample.heap - minimum) / span * 80).toFixed(1)).join(' ');
    $('heap-chart-path').setAttribute('d', path);
    text('chart-scope', (state.runtime ? '真实快照 · ' : '历史快照 / 当前不可用 · ') + bytes(minimum) + ' — ' + bytes(maximum) + '，纵轴按采样范围缩放');
  }
  function stopPoll() {
    pollEpoch++; clearTimeout(pollTimer); clearTimeout(pollTimeout); pollTimer = 0; pollTimeout = 0;
    if (pollController) pollController.abort();
  }
  async function pollRuntime() {
    if (document.hidden || state.disposed || pollController) return;
    const epoch = pollEpoch, controller = new AbortController(); pollController = controller;
    pollTimeout = setTimeout(() => controller.abort(), 5000);
    try {
      const headers = $('token').value ? { Authorization: 'Bearer ' + $('token').value } : {};
      const response = await fetch('/api/runtime', { headers, signal: controller.signal, cache: 'no-store', credentials: 'same-origin' });
      if (epoch !== pollEpoch || document.hidden) return;
      if (!response.ok) {
        state.runtime = null; state.runtimeState = response.status === 401 ? 'auth' : 'offline';
        text('runtime-status', response.status === 401 ? '需要访问令牌' : '状态接口不可用 · ' + response.status);
        $('runtime-dot').className = 'error'; renderRuntime(null); return;
      }
      const data = await response.json();
      if (epoch !== pollEpoch || document.hidden) return;
      if (data.schemaVersion !== 1 || !Array.isArray(data.pipeline?.stages) || data.pipeline.stages.length > 64
          || typeof data.runtime?.javaVersion !== 'string' || typeof data.runtime?.workerBackend !== 'string') throw new Error('unsupported runtime response');
      state.runtime = data; state.runtimeState = 'online';
      text('runtime-status', '运行时在线'); $('runtime-dot').className = 'live';
      text('snapshot-time', 'SNAPSHOT ' + time()); renderRuntime(data);
      expirePending(); clientMetrics();
    } catch (_) {
      if (epoch === pollEpoch && !document.hidden) {
        state.runtime = null; state.runtimeState = 'offline';
        text('runtime-status', '运行时离线 / 读取失败'); $('runtime-dot').className = 'error'; renderRuntime(null);
      }
    } finally {
      if (pollController === controller) {
        clearTimeout(pollTimeout); pollTimeout = 0; pollController = null;
        if (!document.hidden && !state.disposed) pollTimer = setTimeout(pollRuntime, epoch === pollEpoch ? 1000 : 0);
      }
    }
  }
  function refreshSoon() { stopPoll(); if (!document.hidden && !state.disposed) pollRuntime(); }
  function selectMode(mode) {
    if (mode !== 'audio' && (state.recording || state.recordPending)) stopRecording(false);
    $('mode').value = mode;
    for (const tab of document.querySelectorAll('[data-mode]')) { const selected = tab.dataset.mode === mode; tab.classList.toggle('active', selected); tab.setAttribute('aria-selected', selected); }
    $('text-input').hidden = mode === 'audio' || mode === 'image';
    $('transcript-fields').hidden = mode !== 'transcript'; $('audio-input').hidden = mode !== 'audio'; $('image-input').hidden = mode !== 'image';
    text('message-format', mode === 'transcript' ? 'Final transcript' : 'Text frame');
  }
  function draft() {
    const runtime = state.runtime;
    if (!runtime) return '# 尚未读取运行时；此草稿不会应用到服务端。\n# 请读取蓝图后重新生成，或手动填写完整配置。\n';
    const config = runtime.config, lines = [
      '# OminiCat Studio 本地草稿；下载不等于应用。',
      '# API 不公开 stage options。请补齐必填项并重启服务端。',
      'host: "127.0.0.1"', 'port: ' + (location.port || (location.protocol === 'https:' ? 443 : 80))
    ];
    if (runtime.security?.bearerRequired) {
      lines.push('# 必须替换为原部署的鉴权环境变量名；此占位保持 fail-closed。', 'tokenEnv: REPLACE_WITH_EXISTING_TOKEN_ENV');
    }
    for (const name of ['queueCapacity', 'maxSessions', 'maxConnections', 'maxFrameBytes', 'idleTimeoutSeconds']) {
      if (known(config[name])) lines.push(name + ': ' + config[name]);
    }
    lines.push('stages:');
    for (const stage of runtime.pipeline.stages) lines.push('  - type: ' + JSON.stringify(stage.type), '    options: {} # 按处理器要求补齐，未从服务端复制');
    return lines.join('\n') + '\n';
  }
  function renderRecent() {
    for (const id of ['node-recent', 'overview-recent']) {
      $(id).replaceChildren();
      if (!state.recent.length) { $(id).append(element('p', '', '尚无客户端事件')); continue; }
      for (const item of state.recent.slice(-5).reverse()) {
        const row = element('div', 'recent-event');
        row.append(element('small', 'mono', item.at + ' · ' + item.direction + ' · ' + item.type), element('p', '', item.body));
        $(id).append(row);
      }
    }
  }
  function phaseLabel(phase) {
    return ({ingress_accepted:'入口已收下',ingress_rejected:'入口拒绝',enter:'进入调用',output:'观测到输出',
      output_rejected:'输出被拒绝',sync_return:'同步调用返回',async_return:'异步注册返回',
      async_complete:'异步操作 complete',async_fail:'请求 fail',invocation_error:'调用异常',
      downstream_error:'下游异常',cancel:'调用取消',transport_output_queued:'排入传输',
      transport_output_written:'Netty 写出',transport_output_failed:'传输失败',interrupt:'会话中断',
      session_settled:'会话收尾'})[phase] || phase;
  }
  function taskPhase(task) {
    if(task.error)return '已观测异常'+(task.produced?' / 有产出':'');
    if(task.failureRequested)return '已记录 fail 请求';
    if(task.produced)return '已观测写出';
    if(task.outputCount)return '已观测阶段输出';
    return phaseLabel(task.lastPhase);
  }
  function activityPreview(event) {
    return event.preview || (CONTROL_TYPES.has(event.frameType)?'CONTROL · '+event.frameType:'（空 '+event.frameType+'）');
  }
  function forgetSelectedTask(message) {
    if(!activity.selectedTask)return;
    const wasOpen=!$('overview-details').hidden;
    activity.selectedTask=null;activity.returnFocus=null;scene.returnFocusId=null;
    $('task-detail').replaceChildren();$('task-detail').hidden=true;$('overview-fields').replaceChildren();
    text('overview-title',message);text('overview-description','该任务已不在当前本地活动窗口。');
    $('overview-details').hidden=true;document.body.classList.remove('inspector-open');
    if(wasOpen&&state.view==='overview')$('scene-viewport').focus({preventScroll:true});
  }
  function resetActivity(message) {
    forgetSelectedTask(message);stopScene();
    activity.tasks.clear();activity.origins.clear();activity.events.length=0;activity.selectedTask=null;
    activity.replay.length=0;activity.replayFocus=null;activity.incomplete=false;activity.lost=0;activity.unlinked=0;
    activity.evicted=0;activity.replaySkipped=0;activity.returnFocus=null;activity.taskEdges.clear();
    $('task-paths').replaceChildren();$('task-ports').replaceChildren();
    activity.focusTargets.clear();activity.status=message;activity.cursor=0;activity.latest=0;
  }
  function normalizeActivity(data) {
    if(data?.schemaVersion!==1 || typeof data.streamId!=='string' || data.streamId.length>128
      || !Array.isArray(data.events)||data.events.length>128)throw new Error('unsupported activity schema');
    for(const key of ['oldestSeq','latestSeq','nextCursor','lost']){
      if(!Number.isSafeInteger(data[key])||data[key]<0)throw new Error('invalid activity cursor');
    }
    let previous=-1;
    const events=data.events.map(raw=>{
      if(!Number.isSafeInteger(raw.seq)||raw.seq<1||raw.seq<=previous||raw.seq>data.latestSeq)
        throw new Error('invalid activity sequence');
      previous=raw.seq;
      const event={seq:raw.seq};
      for(const key of ['sessionId','clientSessionId','runId','correlationId','frameId','causationId','invocationId','stageId','stageType','phase','frameType','outcome']){
        if(typeof raw[key]!=='string'||raw[key].length>1024)throw new Error('invalid activity identity');
        event[key]=raw[key];
      }
      if(typeof raw.timeNanos!=='string'||!/^-[0-9]{1,19}$|^[0-9]{1,19}$/.test(raw.timeNanos)
        ||!Number.isInteger(raw.stageIndex)||raw.stageIndex < -1||raw.stageIndex>63
        ||typeof raw.preview!=='string'||raw.preview.length>240)throw new Error('invalid activity description');
      event.timeNanos=raw.timeNanos;event.stageIndex=raw.stageIndex;event.preview=raw.preview;
      return event;
    });
    if(events.length&&data.nextCursor<events[events.length-1].seq)throw new Error('activity cursor precedes events');
    return {...data,events};
  }
  function originKey(taskKey,frameId){return JSON.stringify([taskKey,frameId]);}
  function rememberOrigin(taskKey,frameId,stageId){
    if(!frameId)return;
    const key=originKey(taskKey,frameId),old=activity.origins.get(key);
    activity.origins.set(key,old&&old!==stageId?'ambiguous':stageId);
    if(activity.origins.size>TASK_LIMIT.origins)activity.origins.delete(activity.origins.keys().next().value);
  }
  function ingestActivity(data) {
    const replayCandidates=[];
    if(data.gap||data.incomplete){activity.incomplete=true;activity.lost+=data.lost;for(const task of activity.tasks.values())task.incomplete=true;}
    for(const event of data.events){
      if(event.seq<=activity.cursor)continue;
      activity.events.push(event);if(activity.events.length>TASK_LIMIT.events)activity.events.shift();
      if(!event.runId){
        if(event.phase==='session_settled'){
          for(const task of activity.tasks.values())if(task.sessionId===event.sessionId)task.sessionOutcome=event.outcome;
        }else activity.unlinked++;
        continue;
      }
      const key=JSON.stringify([data.streamId,event.sessionId,event.runId]);
      let task=activity.tasks.get(key);
      if(!task&&CONTROL_TYPES.has(event.frameType))continue;
      if(!task){
        if(activity.tasks.size>=TASK_LIMIT.tasks){
          const oldest=activity.tasks.keys().next().value;activity.tasks.delete(oldest);activity.evicted++;
          if(activity.selectedTask===oldest)forgetSelectedTask('任务已移出本地窗口');
        }
        task={key,streamId:data.streamId,sessionId:event.sessionId,runId:event.runId,correlationId:event.correlationId,
          events:[],stages:new Map(),lastPhase:event.phase,lastSeq:event.seq,firstTime:event.timeNanos,
          input:null,output:null,produced:false,outputCount:0,error:false,incomplete:activity.incomplete,sessionOutcome:''};
        activity.tasks.set(key,task);
      }
      task.events.push(event);if(task.events.length>TASK_LIMIT.perTask){task.events.shift();task.incomplete=true;}
      task.lastSeq=event.seq;task.lastPhase=event.phase;
      if(event.phase==='ingress_accepted'&&!CONTROL_TYPES.has(event.frameType)){task.input=event;rememberOrigin(key,event.frameId,'client-input');}
      if(event.stageIndex>=0){
        let stage=task.stages.get(event.stageId);
        if(!stage&&task.stages.size<TASK_LIMIT.stages){
          stage={id:event.stageId,index:event.stageIndex,type:event.stageType,input:null,output:null,outputs:0,invocations:new Set(),lastPhase:event.phase,lastOutcome:event.outcome};
          task.stages.set(event.stageId,stage);
        }
        if(stage){
          if(event.invocationId&&stage.invocations.size<TASK_LIMIT.invocations)stage.invocations.add(event.invocationId);
          if(event.phase==='enter'&&(!stage.input||CONTROL_TYPES.has(stage.input.frameType)))stage.input=event;
          if(event.phase==='output'){
            if(!stage.output||!CONTROL_TYPES.has(event.frameType)||CONTROL_TYPES.has(stage.output.frameType))stage.output=event;
            stage.outputs++;task.outputCount++;
            rememberOrigin(key,event.frameId,event.stageId);
          }
          stage.lastPhase=event.phase;stage.lastOutcome=event.outcome;
        }else task.incomplete=true;
      }
      if(event.phase==='async_fail')task.failureRequested=true;
      if(['invocation_error','downstream_error','transport_output_failed','ingress_rejected','output_rejected'].includes(event.phase))task.error=true;
      if(event.phase==='transport_output_written'&&!CONTROL_TYPES.has(event.frameType)){task.output=event;task.produced=true;}
      if(event.phase==='enter'||event.phase==='transport_output_written')replayCandidates.push({taskKey:key,event});
    }
    activity.cursor=data.nextCursor;activity.latest=data.latestSeq;
    for(const item of replayCandidates){
      if(activity.replay.length>=TASK_LIMIT.replay){activity.replay.shift();activity.replaySkipped++;}
      activity.replay.push(item);
    }
    activity.status='已读取服务端活动 · 仅 observe=1 会话记录';
    renderTasks();startScene();
  }
  function taskCard(task,kind,stage=null) {
    const button=element('button',kind==='stage'?'execution-card':kind==='output'?'result-card task-card':'task-card');
    button.type='button';button.dataset.taskKey=task.key;if(stage)button.dataset.stageId=stage.id;
    const key=JSON.stringify([task.key,kind,stage?.id||'']);activity.focusTargets.set(key,button);
    button.dataset.focusKey=key;
    const top=element('div','task-card-top');
    const stageError=stage&&['invocation_error','downstream_error','output_rejected'].includes(stage.lastPhase);
    top.append(element('span','mono task-id',task.correlationId?task.correlationId.slice(0,8):'未关联'),
      element('span','phase '+((stage?stageError:task.error)?'phase-error':(stage?stage.outputs>0:task.produced)?'phase-output':'phase-input'),stage?phaseLabel(stage.lastPhase):taskPhase(task)));
    button.append(top);
    const line=(label,content)=>{const row=element('div','preview-row');row.append(element('span','preview-label',label),element('span','preview-content',content));return row;};
    if(stage){
      button.append(line('输入',stage.input?activityPreview(stage.input):'未观测输入'),line('输出',stage.output?activityPreview(stage.output):'未观测输出'));
      button.append(element('small','card-meta',stage.invocations.size+' 次调用 · '+stage.outputs+' 次输出观测（含控制）'));
    }else{
      const event=kind==='output'?task.output:task.input;
      button.append(element('strong','task-content',event?activityPreview(event):'尚未观测到数据入站'));
      button.append(element('small','card-meta',(event?.frameType||'unknown')+' · seq '+task.lastSeq+(task.incomplete?' · 记录不完整':'')));
    }
    button.classList.toggle('is-replaying',activity.replayFocus?.taskKey===task.key&&(!stage||activity.replayFocus.event.stageId===stage.id));
    button.onclick=()=>openTask(task.key,key);
    return button;
  }
  function renderTasks() {
    if(!scene.nodes.length)return;
    const focused=document.activeElement?.dataset?.focusKey;
    const tasks=[...activity.tasks.values()].reverse();activity.focusTargets.clear();
    for(const node of scene.nodes){
      if(node.resource)continue;
      node.cards.replaceChildren();let visible=[];
      if(node.id==='client-input')visible=tasks.slice(0,6);
      else if(node.id==='client-output')visible=tasks.filter(task=>task.produced).slice(0,4);
      else visible=tasks.filter(task=>task.stages.get(node.id)?.type===node.label).slice(0,4);
      for(const task of visible)node.cards.append(taskCard(task,node.id==='client-input'?'input':node.id==='client-output'?'output':'stage',task.stages.get(node.id)));
      if(!visible.length)node.cards.append(element('div','lane-empty',node.id==='client-input'?'尚无带关联的任务输入':node.id==='client-output'?'尚未观测到写出结果':'等待真实阶段活动'));
      node.value.textContent=node.id==='client-input'?String(tasks.length):node.id==='client-output'?String(tasks.filter(task=>task.produced).length)
        :String(tasks.reduce((total,task)=>total+(task.stages.get(node.id)?.invocations.size||0),0));
    }
    text('task-total',tasks.length);text('task-produced',tasks.filter(task=>task.produced).length);text('task-errors',tasks.filter(task=>task.error).length);
    text('activity-status',activity.status+(activity.unlinked?' · '+activity.unlinked+' 条无关联活动未串成任务':''));
    $('activity-gap').hidden=!activity.incomplete&&!activity.evicted;
    text('activity-gap',(activity.incomplete?'记录存在缺口，丢失 '+activity.lost+' 条':'')+(activity.evicted?' · 本地移出 '+activity.evicted+' 个关联组':''));
    $('overview-empty').hidden=tasks.length>0;
    text('replay-status',activity.replayFocus?'回放 seq '+activity.replayFocus.event.seq+' · 非后台耗时':activity.replay.length?'待回放 '+activity.replay.length+' 次交接':'等待 / 回放已同步');
    if(activity.selectedTask)renderTaskDetails();
    layoutTaskConnections();
    if(focused)activity.focusTargets.get(focused)?.focus({preventScroll:true});
  }
  function openTask(key,focusKey){
    if(!activity.tasks.has(key)){toast('任务已移出本地窗口',true);return;}
    activity.selectedTask=key;activity.returnFocus=focusKey;scene.returnFocusId=null;
    $('overview-details').hidden=false;document.body.classList.add('inspector-open');
    renderTaskDetails();$('overview-close').focus({preventScroll:true});
  }
  function layoutTaskConnections(){
    activity.taskEdges.clear();$('task-paths').replaceChildren();$('task-ports').replaceChildren();
    if(state.view!=='overview')return;
    const world=$('scene-world').getBoundingClientRect();
    function visibleCard(card){
      const list=card.closest('.lane-cards');
      if(!list)return null;
      const bounds=card.getBoundingClientRect(),clip=list.getBoundingClientRect();
      if(bounds.height<=0||bounds.width<=0||bounds.top<clip.top-1||bounds.bottom>clip.bottom+1)return null;
      return bounds;
    }
    function connectTask(task,event,targetKind,targetStage){
      if(activity.taskEdges.size>=24||!event.frameId)return;
      const from=activity.origins.get(originKey(task.key,event.frameId));
      if(!from||from==='ambiguous'||from===targetStage)return;
      const sourceKind=from==='client-input'?'input':'stage';
      const source=activity.focusTargets.get(JSON.stringify([task.key,sourceKind,sourceKind==='input'?'':from]));
      const target=activity.focusTargets.get(JSON.stringify([task.key,targetKind,targetStage||'']));
      if(!source||!target)return;
      const a=visibleCard(source),b=visibleCard(target),scale=scene.zoom||1;
      if(!a||!b)return;
      const fromNode=scene.nodes.find(node=>node.id===from),toNode=scene.nodes.find(node=>node.id===(targetStage||'client-output'));
      if(!fromNode||!toNode)return;
      const p0=[fromNode.x+fromNode.width+6,(a.top+a.height/2-world.top)/scale];
      const p3=[toNode.x-6,(b.top+b.height/2-world.top)/scale];
      const exit=[fromNode.x+fromNode.width+14,p0[1]],entry=[toNode.x-14,p3[1]];
      const line=(a,b)=>({p0:a,p1:[a[0]+(b[0]-a[0])/3,a[1]+(b[1]-a[1])/3],
        p2:[a[0]+(b[0]-a[0])*2/3,a[1]+(b[1]-a[1])*2/3],p3:b});
      const segments=[line(p0,exit)];
      const direct=entry[0]>exit[0]&&toNode.order===fromNode.order+1;
      if(direct){
        const bend=Math.max(16,(entry[0]-exit[0])*.55);
        segments.push({p0:exit,p1:[exit[0]+bend,exit[1]],p2:[entry[0]-bend,entry[1]],p3:entry});
      }else{
        const top=45+(activity.taskEdges.size%4)*4;
        const aboveExit=[exit[0],top],aboveEntry=[entry[0],top];
        segments.push({p0:exit,p1:[exit[0]+8,exit[1]],p2:[exit[0]+8,top],p3:aboveExit},line(aboveExit,aboveEntry),
          {p0:aboveEntry,p1:[entry[0]-8,top],p2:[entry[0]-8,entry[1]],p3:entry});
      }
      segments.push(line(entry,p3));
      const edge={p0,p3,segments};
      const path=svg('path',{class:'task-connection',fill:'none',stroke:'#67c9b7','stroke-width':1.5,opacity:.8,
        d:edgePath(edge),'marker-end':'url(#task-arrow)'});
      path.dataset.taskKey=task.key;path.dataset.eventSeq=String(event.seq);$('task-paths').append(path);
      for(const [point,kind]of [[p0,'out'],[p3,'in']]){
        const port=svg('circle',{class:'task-port task-port-'+kind,cx:point[0],cy:point[1],r:3.4});
        $('task-ports').append(port);
      }
      activity.taskEdges.set(JSON.stringify([task.key,event.seq]),edge);
    }
    for(const task of [...activity.tasks.values()].reverse()){
      for(const stage of task.stages.values())if(stage.input)connectTask(task,stage.input,'stage',stage.id);
      if(task.output)connectTask(task,task.output,'output','');
    }
    for(const name of ['particles','tokens'])scene[name]=scene[name].filter(item=>{
      if(!item.edgeKey)return true;
      const edge=activity.taskEdges.get(item.edgeKey);
      if(!edge){item.node.remove();return false;}
      item.edge=edge;return true;
    });
  }
  function renderTaskDetails(){
    const task=activity.tasks.get(activity.selectedTask);
    if(!task){forgetSelectedTask('任务已移出本地窗口');return;}
    text('overview-title','任务 '+(task.correlationId?.slice(0,8)||'未关联'));
    text('overview-subtitle','任务处理记录 / 活动回放');
    $('overview-fields').replaceChildren();
    for(const [label,value]of [['后台观测',taskPhase(task)],['保留记录',task.events.length+' 条 · '+(task.incomplete?'记录有缺口':'当前窗口')]]){
      const row=element('div');row.append(element('dt','',label),element('dd','',value));$('overview-fields').append(row);
    }
    text('overview-description','查看各阶段实际处理的内容。飞行是活动回放；调用返回和输出写出不代表整项任务完成。');
    const body=$('task-detail'),oldIdentity=body.querySelector('.task-identity');
    const identityOpen=!!oldIdentity?.open,identityFocused=!!oldIdentity&&document.activeElement===oldIdentity.querySelector('summary');
    body.hidden=false;body.replaceChildren(element('h3','','各阶段输入与输出'));
    for(const stage of [...task.stages.values()].sort((a,b)=>a.index-b.index)){
      const section=element('section','task-stage-detail');section.append(element('h4','',String(stage.index+1).padStart(2,'0')+' '+stage.type),
        element('span','phase',phaseLabel(stage.lastPhase)),element('p','', '输入 · '+(stage.input?activityPreview(stage.input):'未观测')),
        element('p','', '输出 · '+(stage.output?activityPreview(stage.output):'未观测输出')),
        element('small','',stage.invocations.size+' 次调用；最后结果 '+(stage.lastOutcome||'未提供')));
      body.append(section);
    }
    const identity=element('details','task-identity'),summary=element('summary','','关联与会话标识');
    identity.open=identityOpen;identity.append(summary);
    const identifiers=element('dl','detail-list');
    for(const [label,value]of [['关联范围','server session + qualified run'],['会话',task.sessionId],['Run',task.runId],
      ['Correlation',task.correlationId],['完整性',task.incomplete?'有缺口或已淘汰':'窗口内记录；不代表事务完整'],['会话收尾',task.sessionOutcome||'未观测']]){
      const row=element('div');row.append(element('dt','',label),element('dd','',value));identifiers.append(row);
    }
    identity.append(identifiers);body.append(identity);
    if(identityFocused)summary.focus({preventScroll:true});
    body.append(element('h3','','服务端事件时间线 · 记录顺序'));
    if(!task.input)body.append(element('p','activity-warning','数据入站尚未在本地窗口观测到，不推断输入起始时间。'));
    const list=element('ol','task-event-list');
    for(const event of task.events){
      const row=element('li');
      let delta='—';try{delta=(Number(BigInt(event.timeNanos)-BigInt(task.firstTime))/1000000).toFixed(3)+' ms';}catch(_){}
      row.append(element('small','mono','#'+event.seq+' · 相对首条记录 '+delta),
        element('strong','',event.stageType+' / '+phaseLabel(event.phase)),
        element('span','',event.frameType+' · '+activityPreview(event)),
        element('small','',event.outcome));
      const refs=element('dl','event-refs');
      for(const [name,value]of [['invocation',event.invocationId],['frame',event.frameId],['causation',event.causationId],['correlation',event.correlationId]]){
        const pair=element('div');pair.append(element('dt','',name),element('dd','',value||'未提供'));refs.append(pair);
      }
      row.append(refs);
      list.append(row);
    }
    body.append(list);
  }
  function stopActivity(){
    activity.epoch++;clearTimeout(activity.timer);clearTimeout(activity.timeout);activity.timer=0;activity.timeout=0;
    activity.controller?.abort();
  }
  async function pollActivity(){
    if(document.hidden||state.disposed||activity.controller||!activity.supported)return;
    const epoch=activity.epoch,after=activity.cursor,controller=new AbortController();activity.controller=controller;
    activity.timeout=setTimeout(()=>controller.abort(),5000);
    let delay=700;
    try{
      const headers=$('token').value?{Authorization:'Bearer '+$('token').value}:{};
      const response=await fetch('/api/activity?after='+after,{headers,signal:controller.signal,cache:'no-store',credentials:'same-origin'});
      if(epoch!==activity.epoch||document.hidden)return;
      if(!response.ok){activity.status=response.status===401?'活动接口需要访问令牌':'活动接口暂不可用 · '+response.status;activity.supported=false;renderTasks();return;}
      const data=normalizeActivity(await response.json());
      if(epoch!==activity.epoch||document.hidden)return;
      if(activity.streamId&&activity.streamId!==data.streamId||data.reset){
        resetActivity('活动流重置，重新读取');activity.streamId=data.streamId;delay=0;renderTasks();return;
      }
      activity.streamId=data.streamId;ingestActivity(data);
      if(data.nextCursor<data.latestSeq)delay=0;
    }catch(_){if(epoch===activity.epoch&&!document.hidden){activity.status='无法读取任务活动；不生成模拟执行';renderTasks();delay=2000;}}
    finally{
      if(activity.controller===controller){clearTimeout(activity.timeout);activity.controller=null;activity.timeout=0;
        if(!document.hidden&&!state.disposed&&activity.supported)activity.timer=setTimeout(pollActivity,epoch===activity.epoch?delay:0);}
    }
  }
  function refreshActivity(force=false){
    stopActivity();activity.supported=force||state.runtime?.activity?.supported===true;
    if(!activity.supported){activity.status='运行时尚未提供任务活动；客户端帧仍可调试';renderTasks();return;}
    if(!document.hidden&&!state.disposed)pollActivity();
  }
  function queueTaskReplay(task){
    activity.replay=task.events.filter(event=>event.phase==='enter'||event.phase==='transport_output_written')
      .slice(-TASK_LIMIT.replay).map(event=>({taskKey:task.key,event}));
    activity.replayFocus=null;activity.replayAt=0;startScene();renderTasks();
  }
  function tickTaskReplay(timestamp){
    if(!activity.replay.length||timestamp<activity.replayAt)return;
    const item=activity.replay.shift();activity.replayAt=timestamp+500;activity.replayFocus=item;
    const task=activity.tasks.get(item.taskKey);if(!task)return;
    const event=item.event;
    let from=event.frameId?activity.origins.get(originKey(task.key,event.frameId)):null;
    const to=event.phase==='enter'?event.stageId:'client-output';
    if(from===to||from==='ambiguous')from=null;
    const target=scene.nodes.find(node=>node.id===to&&!node.resource);
    const origin=scene.nodes.find(node=>node.id===from&&!node.resource);
    if(target){
      let edge=activity.taskEdges.get(JSON.stringify([task.key,event.seq])) || (origin?scene.edges.find(edge=>edge.from===origin&&edge.to===target):null);
      if(!edge&&origin)edge={p0:[origin.x+origin.width,origin.y+45],p1:[origin.x+origin.width+55,origin.y-10],
        p2:[target.x-55,target.y+80],p3:[target.x,target.y+45]};
      if(!edge)edge={p0:[target.x+target.width/2,target.y+40],p1:[target.x+target.width/2,target.y+40],
        p2:[target.x+target.width/2,target.y+40],p3:[target.x+target.width/2,target.y+40]};
      if(scene.particles.length>=LIMIT.particles)scene.particles.shift().node.remove();
      const point=svg('circle',{r:3.2,class:'flow-particle'});$('scene-particles').append(point);
      const edgeKey=activity.taskEdges.has(JSON.stringify([task.key,event.seq]))?JSON.stringify([task.key,event.seq]):null;
      scene.particles.push({node:point,edge,edgeKey,started:timestamp});
      if(scene.tokens.length>=8)scene.tokens.shift().node.remove();
      const token=element('div','flight-token',short(task.correlationId,6)+' · '+event.frameType+' · '+short(event.preview,36)+(origin?'':' · 父事件未关联'));
      $('scene-tokens').append(token);scene.tokens.push({node:token,edge,edgeKey,started:timestamp});
    }
    renderTasks();
  }
  function prepareOfflineExample(){
    const stages=state.runtime?.pipeline?.stages;
    if(!Array.isArray(stages)||!stages.length||stages.some(stage=>!['echo','rms-vad','transcript-gate'].includes(stage.type))){
      toast('该示例仅用于 echo / rms-vad / transcript-gate 配置，请自行选择输入；不会调用未知或模型处理器',true);return;
    }
    navigate('debug');selectMode('text');$('use-envelope').checked=true;
    if(!state.socket)$('observe-activity').checked=true;
    $('message').value='合成任务：传递这条文本，观察每个配置阶段的真实输入、输出和返回事件。';
    text('message-size',$('message').value.length+' 字符');$('message').focus();
    toast('合成文本已准备；请确认配置并主动连接、发送。返回大盘可慢放已记录交接。');
  }
  function buildScene() {
    const stages = state.runtime?.pipeline?.stages || [];
    const signature = JSON.stringify(stages.map(stage => [stage.id, stage.type]));
    if (scene.boardSignature === signature && scene.nodes.length) { sceneMetrics(); startScene(); return; }
    scene.boardSignature = signature; scene.taskMode = true;
    stopScene(); scene.nodes = []; scene.edges = [];
    $('scene-nodes').replaceChildren(); $('scene-paths').replaceChildren();
    const resources = element('div', 'resource-strip');
    const resourceDefinitions = [['kernel-runtime','Java / JVM'],['runtime-heap','Heap'],['runtime-sessions','会话'],
      ['runtime-workers','Workers'],['runtime-threads','平台线程'],['runtime-queue','输出积压'],['runtime-frames','全局帧'],['client-response','首响应']];
    for (const [id,label] of resourceDefinitions) {
      const button = element('button', 'resource-chip graph-node'); button.type = 'button'; button.dataset.nodeId = id;
      const value = element('strong', 'resource-value', '—');
      button.append(element('span', 'resource-label', label), value); button.onclick = () => openOverview(id);
      resources.append(button); scene.nodes.push({ id,label,type:'RUNTIME OBSERVATION',role:id==='client-response'?'client':'metric',
        element:button,value,meta:element('span'),tags:element('div'),x:0,y:0,width:0,height:0,dx:0,dy:0,resource:true });
    }
    $('scene-nodes').append(resources);
    const definitions = [{id:'client-input',label:'任务输入',type:'01 / 任务输入',role:'client',x:24,width:216,y:95},
      ...stages.map((stage,index)=>({id:stage.id,label:stage.type,type:String(index+2).padStart(2,'0')+' / 处理阶段',
        role:'processor',index,x:296+index*286,width:230,y:[60,105,76,120][index%4]})),
      {id:'client-output',label:'任务产出',type:String(stages.length+2).padStart(2,'0')+' / 任务产出',role:'client',x:296+stages.length*286,width:236,y:90}];
    scene.width = 556 + stages.length * 286; scene.height = 680;
    $('scene-world').style.width = scene.width+'px'; $('scene-world').style.height=scene.height+'px';
    $('scene-edges').setAttribute('width',scene.width); $('scene-edges').setAttribute('height',scene.height);
    for (const [order,node] of definitions.entries()) {
      node.order=order;node.defaultX=node.x;node.defaultY=node.y;
      const lane=element('section','task-lane '+(node.id==='client-input'?'input-lane':node.id==='client-output'?'output-lane':'stage-lane'));
      lane.style.left=node.x+'px';lane.style.top=node.y+'px';lane.style.width=node.width+'px';lane.dataset.stageId=node.id;
      const header=element('button','lane-heading graph-node'); header.type='button';header.dataset.nodeId=node.id;
      header.title='拖动标题移动模块；点击查看详情。移动仅调整视图，不改变配置顺序。';
      header.setAttribute('aria-label',node.label+'，拖动移动模块，Shift 加方向键微调，点击查看详情');
      const names=element('div');names.append(element('span','node-type',node.type),element('strong','node-label',node.label));
      const value=element('span','lane-count','0');header.append(names,value);
      if(order>0){const port=element('span','lane-port in');port.setAttribute('role','img');port.setAttribute('aria-label','输入连接点');header.append(port);}
      if(order<definitions.length-1){const port=element('span','lane-port out');port.setAttribute('role','img');port.setAttribute('aria-label','输出连接点');header.append(port);}
      header.onclick=event=>{if(node.suppressClick){node.suppressClick=false;event.preventDefault();return;}openOverview(node.id);};
      const cards=element('div','lane-cards');
      cards.tabIndex=0;cards.setAttribute('aria-label',node.label+'任务列表，可滚动；方向键或 PageDown 查看更多');
      lane.append(header,cards);$('scene-nodes').append(lane);
      Object.assign(node,{element:header,value,meta:element('span'),tags:element('div'),cards,lane,height:72,dx:0,dy:0});
      scene.nodes.push(node);
    }
    const laneNodes=scene.nodes.filter(node=>!node.resource);
    for(let index=0;index<laneNodes.length-1;index++){
      const path=svg('path',{class:'graph-edge data-edge module-connection','marker-end':'url(#module-arrow)'});$('scene-paths').append(path);
      scene.edges.push({from:laneNodes[index],to:laneNodes[index+1],data:true,path,index});
    }
    layoutSceneEdges();sceneMetrics();fitScene(false);startScene();
  }
  function sceneMetrics() {
    if(!scene.nodes.length)return;
    const runtime=state.runtime,metrics=runtime?.metrics,memory=runtime?.memory;
    const values={
      'kernel-runtime':runtime?'Java '+runtime.runtime.javaVersion.split('.')[0]:'—',
      'runtime-heap':bytes(memory?.heapUsedBytes),'runtime-sessions':number(metrics?.activeSessions),
      'runtime-workers':number(metrics?.pipelineWorkers),'runtime-threads':number(runtime?.threads?.live),
      'runtime-queue':bytes(metrics?.pendingOutputBytes),'runtime-frames':number(metrics?.framesInTotal)+' / '+number(metrics?.framesOutTotal),
      'client-response':state.lastRtt===null?'—':state.lastRtt.toFixed(1)+' ms'
    };
    for(const node of scene.nodes){
      if(node.resource)node.value.textContent=values[node.id];
      node.element.classList.toggle('selected',scene.selected===node.id&&!$('overview-details').hidden&&!activity.selectedTask);
    }
    renderTasks();renderOverviewDetails();
  }
  function openOverview(id) {
    activity.selectedTask=null;activity.returnFocus=null;$('task-detail').hidden=true;
    scene.returnFocusId = id;
    scene.selected = id; $('overview-details').hidden = false;
    document.body.classList.add('inspector-open'); renderOverviewDetails(); sceneMetrics();
    $('overview-close').focus({ preventScroll: true });
  }
  function closeOverview(restoreFocus = true) {
    const wasOpen = !$('overview-details').hidden;
    const returnNode = activity.focusTargets.get(activity.returnFocus) || scene.nodes.find(node => node.id === scene.returnFocusId)?.element;
    activity.returnFocus=null;activity.selectedTask=null;$('task-detail').hidden=true;
    scene.returnFocusId = null;
    $('overview-details').hidden = true; document.body.classList.remove('inspector-open'); sceneMetrics();
    if (restoreFocus && wasOpen) returnNode?.focus({ preventScroll: true });
  }
  function renderOverviewDetails() {
    if ($('overview-details').hidden) return;
    if(activity.selectedTask){renderTaskDetails();return;}
    const node = scene.nodes.find(item => item.id === scene.selected);
    if (!node) return;
    const runtime = state.runtime, metrics = runtime?.metrics, memory = runtime?.memory;
    let fields = [['范围', node.role === 'client' ? '当前浏览器 / 客户端观测' : '全运行时 / 配置或聚合快照']];
    if(node.role==='processor'){
      const matchingTasks=[...activity.tasks.values()].filter(task=>task.stages.has(node.id)).length;
      fields=[['范围','服务端可选活动 / 此配置阶段'],['配置类型',node.label],['配置顺序',String(node.index+1)],
        ['本地关联任务数',String(matchingTasks)],['逐节点 CPU','未提供']];
    }
    if (node.id === 'current-pipeline') fields.push(['流程数', runtime ? '1 条配置' : '未知'], ['处理器数', runtime ? runtime.pipeline.stages.length : '—'], ['配置顺序', runtime ? runtime.pipeline.stages.map(item => item.type).join(' → ') : '未读取'], ['逐节点追踪', '未提供']);
    if (node.id === 'kernel-runtime') fields.push(['Java', runtime?.runtime.javaVersion || '—'], ['Worker backend', runtime?.runtime.workerBackend || '—'], ['JVM uptime', known(runtime?.runtime.uptimeMillis) ? Math.floor(runtime.runtime.uptimeMillis / 1000) + ' s' : '—'], ['可用处理器', number(runtime?.runtime.availableProcessors)]);
    if (node.id === 'runtime-heap') fields.push(['Heap used', bytes(memory?.heapUsedBytes)], ['Heap committed', bytes(memory?.heapCommittedBytes)], ['Heap max', bytes(memory?.heapMaxBytes)], ['Non-heap used', bytes(memory?.nonHeapUsedBytes)]);
    if (node.id === 'runtime-sessions') fields.push(['活动会话', number(metrics?.activeSessions)], ['保留准入', number(metrics?.admittedSessions)], ['会话上限', number(runtime?.config?.maxSessions)], ['累计会话', number(metrics?.sessionsTotal)]);
    if (node.id === 'runtime-workers') fields.push(['管线 workers', number(metrics?.pipelineWorkers)], ['Backend', runtime?.runtime.workerBackend || '—']);
    if (node.id === 'runtime-threads') fields.push(['Platform live', number(runtime?.threads?.live)], ['Daemon', number(runtime?.threads?.daemon)], ['Peak', number(runtime?.threads?.peak)], ['Scope', 'platform / 不含 virtual threads']);
    if (node.id === 'runtime-queue') fields.push(['待发送负载', bytes(metrics?.pendingOutputBytes)], ['输出预算 / 会话', bytes(runtime?.config?.maxPendingOutputBytes)], ['入口容量 / 会话', number(runtime?.config?.queueCapacity)], ['入口队列深度', 'API 未提供']);
    if (node.id === 'runtime-frames') fields.push(['Frames in', number(metrics?.framesInTotal)], ['Frames out', number(metrics?.framesOutTotal)], ['Rejected', number(metrics?.rejectedTotal)], ['Failures', number(metrics?.failuresTotal)]);
    if (node.id === 'transport-ingress') fields.push(['连接数', number(metrics?.connections)], ['连接上限', number(runtime?.config?.maxConnections)], ['单帧限制', bytes(runtime?.config?.maxFrameBytes)], ['鉴权', runtime ? runtime.security?.bearerRequired ? 'Bearer required' : '未启用 Bearer' : '未知']);
    if (node.role === 'client') fields.push(['WebSocket', state.socket?.readyState === WebSocket.OPEN ? '已连接' : '未连接'], ['已发送', number(state.sent)], ['已接收', number(state.received)], ['最近关联首响应', state.lastRtt === null ? '—' : state.lastRtt.toFixed(1) + ' ms'], ['等待关联', state.pending.size]);
    text('overview-title', node.label); text('overview-subtitle', node.type);
    $('overview-fields').replaceChildren();
    for (const [name, value] of fields) { const row = element('div'); row.append(element('dt', '', name), element('dd', '', value)); $('overview-fields').append(row); }
    text('overview-description', node.role === 'pipeline' ? '这是当前部署的一条真实管线。大盘展示配置与聚合观测；连接、输入、媒体和中断操作位于独立的流程调试视图。'
      : node.role === 'client' ? '这些数据来自当前浏览器的真实收发。离开调试会停止本地录音与播放；既有连接和服务端在途请求不会被自动重建或中断。'
      : node.role === 'processor' ? '类型与顺序来自运行配置；任务数来自可选活动记录。未提供节点 CPU、队列深度或健康状态。'
      : '来自 /api/runtime 的全运行时快照，不代表某个处理器的 CPU、耗时或健康状态。未提供的数据保持未知。');
    renderRecent();
  }
  function layoutSceneEdges() {
    for (const edge of scene.edges) {
      const from = edge.from, to = edge.to;
      if(scene.taskMode){
        edge.p0=[from.x+from.width*.76,from.y];edge.p3=[to.x+to.width*.24,to.y];
        const channel=45+(edge.index%3)*3;
        edge.p1=[edge.p0[0],Math.min(channel,from.y-18)];
        edge.p2=[edge.p3[0],Math.min(channel,to.y-18)];
        edge.path.setAttribute('d',edgePath(edge));
        continue;
      }
      const a = [from.x + from.width / 2 + from.dx, from.y + from.height / 2 + from.dy];
      const b = [to.x + to.width / 2 + to.dx, to.y + to.height / 2 + to.dy];
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const p0 = Math.abs(dx) > Math.abs(dy) ? [a[0] + Math.sign(dx) * from.width / 2, a[1]] : [a[0], a[1] + Math.sign(dy) * from.height / 2];
      const p3 = Math.abs(dx) > Math.abs(dy) ? [b[0] - Math.sign(dx) * to.width / 2, b[1]] : [b[0], b[1] - Math.sign(dy) * to.height / 2];
      const curve = (edge.index % 2 ? 1 : -1) * (edge.data ? 24 : 65);
      edge.p0 = p0; edge.p3 = p3;
      edge.p1 = [p0[0] + (p3[0] - p0[0]) * .35, p0[1] + (p3[1] - p0[1]) * .15 + curve];
      edge.p2 = [p0[0] + (p3[0] - p0[0]) * .65, p0[1] + (p3[1] - p0[1]) * .85 - curve];
      edge.path.setAttribute('d', 'M' + p0.join(',') + ' C' + edge.p1.join(',') + ' ' + edge.p2.join(',') + ' ' + p3.join(','));
    }
  }
  function edgePath(edge){
    return 'M'+edge.p0.join(',')+(edge.segments||[edge]).map(part=>' C'+part.p1.join(',')+' '+part.p2.join(',')+' '+part.p3.join(',')).join('');
  }
  function scenePoint(edge, t) {
    if(edge.segments){
      const scaled=Math.min(.999999,Math.max(0,t))*edge.segments.length;
      return scenePoint(edge.segments[Math.floor(scaled)],scaled%1);
    }
    const u = 1 - t;
    return [0, 1].map(axis => u * u * u * edge.p0[axis] + 3 * u * u * t * edge.p1[axis] + 3 * u * t * t * edge.p2[axis] + t * t * t * edge.p3[axis]);
  }
  function sceneTransform() {
    const viewport=$('scene-viewport'),width=viewport.clientWidth||1280,height=viewport.clientHeight||620;
    scene.panX=Math.max(96-scene.width*scene.zoom,Math.min(width-96,scene.panX));
    scene.panY=Math.max(80-scene.height*scene.zoom,Math.min(height-80,scene.panY));
    $('scene-world').style.transform = 'translate(' + scene.panX + 'px,' + scene.panY + 'px) scale(' + scene.zoom + ')';
    text('scene-zoom-level', Math.round(scene.zoom * 100) + '%');
  }
  function moveLane(node,x,y){
    node.x=Math.max(12,Math.min(scene.width-node.width-12,x));
    const height=Math.max(100,node.lane.getBoundingClientRect().height/(scene.zoom||1));
    node.y=Math.max(60,Math.min(scene.height-height-12,y));
    node.lane.style.left=node.x+'px';node.lane.style.top=node.y+'px';
    layoutSceneEdges();layoutTaskConnections();
  }
  function fitScene(all = false) {
    const viewport = $('scene-viewport'), width = viewport.clientWidth || 1280, height = viewport.clientHeight || 620;
    const fitted = Math.min(1.15, (width - 24) / scene.width, (height - 38) / scene.height);
    scene.zoom = all ? Math.max(.04,fitted) : Math.max(.8,Math.min(1.05,(width-24)/scene.width));
    scene.panX = !all&&scene.width*scene.zoom>width ? 12 : (width - scene.width * scene.zoom) / 2;
    scene.panY = all ? (height - scene.height * scene.zoom) / 2 + 14 : 12; sceneTransform();
  }
  function zoomScene(factor) {
    const viewport = $('scene-viewport'), old = scene.zoom;
    scene.zoom = Math.max(.15, Math.min(2.2, old * factor));
    scene.panX = viewport.clientWidth / 2 - (viewport.clientWidth / 2 - scene.panX) * scene.zoom / old;
    scene.panY = viewport.clientHeight / 2 - (viewport.clientHeight / 2 - scene.panY) * scene.zoom / old; sceneTransform();
  }
  function stopScene() {
    cancelAnimationFrame(scene.raf); scene.raf = 0;
    scene.ambient.length = 0; scene.particles.length = 0; scene.tokens.length = 0;
    $('scene-ambient').replaceChildren(); $('scene-particles').replaceChildren(); $('scene-tokens').replaceChildren();
  }
  function startScene() {
    if (state.view !== 'overview' || !state.motion || reducedMotion.matches || document.hidden || state.disposed || !scene.nodes.length) return;
    if (state.runtime && !scene.ambient.length) {
      for (let index = 0; index < Math.min(24, scene.edges.length); index++) {
        const node = svg('circle', { r: 1.7, class: 'ambient-particle', opacity: .45 }); $('scene-ambient').append(node);
        scene.ambient.push({ node, edge: scene.edges[index], phase: index * .137 });
      }
    }
    if (!scene.raf && (state.runtime || scene.particles.length)) scene.raf = requestAnimationFrame(animateScene);
  }
  function scenePulse(direction, type) {
    if(scene.taskMode){startScene();return;}
    if (!state.motion || reducedMotion.matches || document.hidden || state.view !== 'overview') return;
    const edges = scene.edges.filter(edge => edge.data && (direction === 'sent' ? edge.to.id !== 'client-output' : edge.to.id === 'client-output'));
    for (let index = 0; index < edges.length; index++) {
      if (scene.particles.length >= LIMIT.particles) scene.particles.shift().node.remove();
      const node = svg('circle', { r: 3.2, class: 'flow-particle' }); $('scene-particles').append(node);
      scene.particles.push({ node, edge: edges[index], started: now() + index * 120 });
    }
    if (edges.length) {
      if (scene.tokens.length >= 8) scene.tokens.shift().node.remove();
      const node = element('div', 'flight-token', short(type, 20).toUpperCase() + ' · ' + (direction === 'sent' ? 'TX ↑' : 'RX ↓'));
      $('scene-tokens').append(node); scene.tokens.push({ node, edge: edges[edges.length - 1], started: now() });
    }
    startScene();
  }
  function animateScene(timestamp) {
    scene.raf = 0;
    if (state.view !== 'overview' || !state.motion || reducedMotion.matches || document.hidden || state.disposed) { stopScene(); return; }
    if (timestamp - scene.renderedAt < 32) { scene.raf = requestAnimationFrame(animateScene); return; }
    scene.renderedAt = timestamp;
    tickTaskReplay(timestamp);
    for (let index = 0; index < scene.nodes.length; index++) {
      const node = scene.nodes[index];
      node.dx = scene.taskMode?0:Math.sin(timestamp / 7400 + index * 1.7) * 4; node.dy = scene.taskMode?0:Math.cos(timestamp / 8700 + index * 1.2) * 5;
      node.element.style.transform = 'translate(' + node.dx.toFixed(2) + 'px,' + node.dy.toFixed(2) + 'px)';
    }
    layoutSceneEdges();
    for (const item of scene.ambient) {
      const point = scenePoint(item.edge, (timestamp / 12000 + item.phase) % 1);
      item.node.setAttribute('cx', point[0]); item.node.setAttribute('cy', point[1]);
    }
    scene.particles = scene.particles.filter(item => {
      const t = (timestamp - item.started) / 1050;
      if (t >= 1) { item.node.remove(); return false; }
      const point = scenePoint(item.edge, Math.max(0, t)); item.node.setAttribute('cx', point[0]); item.node.setAttribute('cy', point[1]);
      item.node.setAttribute('opacity', t < 0 ? 0 : 1); return true;
    });
    scene.tokens = scene.tokens.filter(item => {
      const t = (timestamp - item.started) / 1400;
      if (t >= 1) { item.node.remove(); return false; }
      const point = scenePoint(item.edge, Math.min(1, Math.max(0, t)));
      item.node.style.transform = 'translate(' + point[0].toFixed(1) + 'px,' + (point[1] - 22).toFixed(1) + 'px)';
      item.node.style.opacity = String(Math.min(1, (1 - t) * 3)); return true;
    });
    if(activity.replayFocus&&!activity.replay.length&&!scene.particles.length&&!scene.tokens.length){activity.replayFocus=null;renderTasks();}
    if (state.runtime || scene.particles.length || scene.tokens.length || activity.replay.length) scene.raf = requestAnimationFrame(animateScene);
  }
  function applyView(view) {
    const target = view === 'debug' ? 'debug' : 'overview';
    const previous = state.view;
    if (state.view === 'debug' && target !== 'debug') cancelMedia();
    state.view = target; closeOverview(false);
    $('overview-view').hidden = target !== 'overview'; $('debug-view').hidden = target !== 'debug';
    document.body.classList.toggle('view-overview', target === 'overview');
    document.body.classList.toggle('view-debug', target === 'debug');
    document.body.classList.remove('inputs-open', 'timeline-open');
    $('workspace-tab').classList.toggle('active', target === 'overview'); $('debug-tab').classList.toggle('active', target === 'debug');
    text('view-title', target === 'overview' ? '运行大盘' : '流程调试');
    if (target === 'overview') { clearParticles(); buildScene(); fitScene(false); startScene(); }
    else { stopScene(); topology(state.runtime?.pipeline?.stages || []); fitGraph(); }
    if (previous !== null && previous !== target)
      $(target === 'overview' ? 'workspace-tab' : 'debug-tab').focus({ preventScroll: true });
  }
  function navigate(view) { applyView(view); if (location.hash !== '#' + view) location.hash = view; }
  function setMotion() {
    const enabled = state.motion && !reducedMotion.matches;
    $('motion').setAttribute('aria-pressed', enabled);
    text('motion', reducedMotion.matches ? '减少动态效果' : enabled ? '暂停动效' : '开启动效');
    $('overview-motion').setAttribute('aria-pressed', enabled);
    text('overview-motion', reducedMotion.matches ? '减少动态效果' : enabled ? '暂停动效' : '开启动效');
    document.body.classList.toggle('motion-stopped', !enabled || document.hidden);
    if (!enabled) { clearParticles(); stopScene(); } else startScene();
  }
  $('connect').onclick = connect;
  $('compose').onsubmit = event => {
    event.preventDefault();
    const value = $('message').value;
    if (!value.trim()) { toast('请输入内容', true); return; }
    let frame = { type: 'text', text: value };
    if ($('mode').value === 'transcript') {
      const start = Number($('start-time').value), end = Number($('end-time').value), speaker = $('speaker').value;
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || !speaker.trim()) { toast('请填写有效说话人与非负时间区间', true); return; }
      frame = { type: 'transcript', text: value, speaker, role: 'user', startMillis: start, endMillis: end,
        finalResult: true, confidence: -1, segmentId: unique(), revision: 0 };
    }
    const context = requestContext();
    if (send(frame, context, true)) { send({ type: 'turn_end' }, context); $('message').value = ''; text('message-size', '0 字符'); }
  };
  $('message').oninput = () => text('message-size', $('message').value.length.toLocaleString('en-US') + ' 字符');
  $('message').onkeydown = event => { if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); $('compose').requestSubmit(); } };
  for (const tab of document.querySelectorAll('[data-mode]')) tab.onclick = () => selectMode(tab.dataset.mode);
  $('mode').onchange = () => selectMode($('mode').value);
  $('record').onclick = startRecording;
  $('image').onclick = () => { if (isOpen()) $('file').click(); };
  $('file').onchange = async () => {
    const file = $('file').files?.[0]; $('file').value = '';
    if (!file || !isOpen() || state.view !== 'debug') return;
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > LIMIT.imageBytes) { toast('请选择不超过 600 KB 的 PNG / JPEG / WebP 图片', true); return; }
    const owner = operation(); state.imageOperation = owner; controls();
    try {
      const buffer = await file.arrayBuffer();
      if (!current(owner) || state.imageOperation !== owner) return;
      if (buffer.byteLength > LIMIT.imageBytes) throw new Error('oversize');
      const context = requestContext();
      if (send({ type: 'image', mimeType: file.type, data: base64(new Uint8Array(buffer)) }, context, true)) send({ type: 'turn_end' }, context);
    } catch (_) { if (current(owner)) toast('无法读取图片', true); }
    finally { if (state.imageOperation === owner) { state.imageOperation = null; controls(); } }
  };
  $('interrupt').onclick = () => {
    send({ type: 'interrupt' }); state.pending.clear(); state.lastRtt = null; cancelMedia(); clientMetrics();
    log('LOCAL', 'interrupt', '已请求服务端中断，并停止本地录音和播放。', 'system');
  };
  $('finish').onclick = () => {
    if (send({ type: 'end' })) { state.finishing = true; cancelMedia(); controls(); text('status', '已发送 END，等待服务端排空后关闭'); }
  };
  $('sound').onclick = () => {
    state.sound = !state.sound;
    if (!state.sound) stopPlayback(); else { try { audioContext(); } catch (_) { toast('浏览器不支持音频播放', true); } }
    $('sound').setAttribute('aria-pressed', state.sound);
    $('sound').replaceChildren(document.createTextNode('音频播放：' + (state.sound ? '开启' : '关闭') + ' '), element('span', 'mono', 'PCM OUT'));
  };
  $('clear').onclick = () => { $('feed').replaceChildren(); state.recent.length = 0; renderRecent(); text('count', '0'); toast('时间线与详情记录已清空，会话计数保持不变'); };
  $('refresh-runtime').onclick = refreshSoon;
  $('token').onchange = () => { refreshSoon(); refreshActivity(); };
  $('zoom-in').onclick = () => zoomBy(1.2); $('zoom-out').onclick = () => zoomBy(1 / 1.2); $('fit').onclick = fitGraph;
  $('motion').onclick = () => { state.motion = !state.motion; setMotion(); };
  reducedMotion.addEventListener('change', setMotion);
  let drag = null;
  $('graph-viewport').addEventListener('pointerdown', event => {
    if (event.target.closest('button')) return;
    drag = { x: event.clientX, y: event.clientY, panX: state.panX, panY: state.panY };
    $('graph-viewport').setPointerCapture?.(event.pointerId);
  });
  $('graph-viewport').addEventListener('pointermove', event => { if (drag) { state.panX = drag.panX + event.clientX - drag.x; state.panY = drag.panY + event.clientY - drag.y; graphTransform(); } });
  $('graph-viewport').addEventListener('pointerup', () => { drag = null; });
  $('graph-viewport').addEventListener('pointercancel', () => { drag = null; });
  $('graph-viewport').addEventListener('wheel', event => {
    if (event.ctrlKey || event.metaKey) { event.preventDefault(); zoomBy(event.deltaY > 0 ? .9 : 1.1); }
    else if (state.graphHeight * state.zoom > $('graph-viewport').clientHeight) { event.preventDefault(); state.panY -= event.deltaY; graphTransform(); }
  }, { passive: false });
  $('graph-viewport').addEventListener('keydown', event => {
    if (event.target !== $('graph-viewport')) return;
    if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomBy(1.2); }
    if (event.key === '-') { event.preventDefault(); zoomBy(1 / 1.2); }
    if (event.key === '0') { event.preventDefault(); fitGraph(); }
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
      event.preventDefault();
      state.panX += event.key === 'ArrowLeft' ? 45 : event.key === 'ArrowRight' ? -45 : 0;
      state.panY += event.key === 'ArrowUp' ? 45 : event.key === 'ArrowDown' ? -45 : 0;
      graphTransform();
    }
  });
  window.addEventListener('resize', () => { topology(state.runtime?.pipeline?.stages || []); fitGraph(); fitScene(false); });
  function selectTimeline(metrics) {
    $('metrics-view').hidden = !metrics; $('timeline-view').hidden = metrics;
    $('metrics-tab').classList.toggle('active', metrics); $('timeline-tab').classList.toggle('active', !metrics);
  }
  $('timeline-tab').onclick = () => selectTimeline(false); $('metrics-tab').onclick = () => selectTimeline(true);
  $('config-open').onclick = () => { if (!$('config-draft').value) $('config-draft').value = draft(); $('config-dialog').showModal(); };
  $('workspace-tab').onclick = () => navigate('overview');
  $('debug-tab').onclick = () => navigate('debug');
  $('enter-debug').onclick = () => navigate('debug');
  $('overview-close').onclick = closeOverview;
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !$('overview-details').hidden && !$('config-dialog').open) {
      event.preventDefault(); closeOverview();
    }
  });
  $('drawer-backdrop').onclick = () => { closeOverview(); document.body.classList.remove('inputs-open'); };
  $('overview-action').onclick = refreshSoon;
  $('run-example').onclick = prepareOfflineExample;
  $('activity-refresh').onclick = () => refreshActivity(true);
  $('replay-latest').onclick = () => {
    const task=activity.tasks.get(activity.selectedTask)||[...activity.tasks.values()].sort((a,b)=>b.lastSeq-a.lastSeq)[0];
    if(!task){toast('尚无服务端任务活动，请先在调试中发送内容',true);return;}
    queueTaskReplay(task);
  };
  $('input-drawer-toggle').onclick = () => document.body.classList.toggle('inputs-open');
  $('input-drawer-close').onclick = () => document.body.classList.remove('inputs-open');
  $('inspector-close').onclick = () => document.body.classList.remove('inspector-open');
  $('timeline-toggle').onclick = () => { selectTimeline(false); $('timeline-view').scrollIntoView?.({ behavior: reducedMotion.matches ? 'auto' : 'smooth', block: 'nearest' }); };
  $('node-primary-action').onclick = () => {
    if (state.selected.startsWith('stage-')) $('config-open').click();
    else if (state.selected === 'client-input') $('message').focus();
    else refreshSoon();
  };
  $('overview-motion').onclick = () => { state.motion = !state.motion; setMotion(); };
  $('overview-fit').onclick = () => fitScene(true);
  $('overview-zoom-in').onclick = () => zoomScene(1.2);
  $('overview-zoom-out').onclick = () => zoomScene(1 / 1.2);
  $('overview-reset-layout').onclick=()=>{
    for(const node of scene.nodes)if(!node.resource){node.cards.scrollTop=0;moveLane(node,node.defaultX,node.defaultY);}
    fitScene(false);layoutTaskConnections();toast('已恢复模块布局；配置执行顺序保持不变');
  };
  let sceneDrag = null;
  $('scene-viewport').addEventListener('pointerdown', event => {
    if(event.button!==undefined&&event.button!==0)return;
    const header=event.target.closest('.lane-heading');
    if(header){
      const node=scene.nodes.find(node=>node.element===header);
      if(!node)return;
      event.preventDefault();header.focus({preventScroll:true});
      sceneDrag={node,x:event.clientX,y:event.clientY,nodeX:node.x,nodeY:node.y,zoom:scene.zoom,moved:false,pointerId:event.pointerId};
    }else{
      if(event.target.closest('.lane-cards')||event.target.closest('button'))return;
      sceneDrag = { x: event.clientX, y: event.clientY, panX: scene.panX, panY: scene.panY,pointerId:event.pointerId };
    }
    $('scene-viewport').setPointerCapture?.(event.pointerId);
  });
  $('scene-viewport').addEventListener('pointermove', event => {
    if(!sceneDrag||event.pointerId!==sceneDrag.pointerId)return;
    const dx=event.clientX-sceneDrag.x,dy=event.clientY-sceneDrag.y;
    if(sceneDrag.node){
      if(!sceneDrag.moved&&Math.hypot(dx,dy)<3)return;
      sceneDrag.moved=true;sceneDrag.node.lane.classList.add('is-dragging');
      moveLane(sceneDrag.node,sceneDrag.nodeX+dx/sceneDrag.zoom,sceneDrag.nodeY+dy/sceneDrag.zoom);
    }else{scene.panX=sceneDrag.panX+dx;scene.panY=sceneDrag.panY+dy;sceneTransform();}
  });
  function finishSceneDrag(event){
    if(!sceneDrag||event.pointerId!==sceneDrag.pointerId)return;
    const previous=sceneDrag;sceneDrag=null;
    if(previous.node){
      previous.node.lane.classList.remove('is-dragging');
      if(previous.moved){previous.node.suppressClick=true;setTimeout(()=>{previous.node.suppressClick=false;},0);}
    }
    $('scene-viewport').releasePointerCapture?.(event.pointerId);
  }
  $('scene-viewport').addEventListener('pointerup',finishSceneDrag);
  $('scene-viewport').addEventListener('pointercancel',finishSceneDrag);
  $('scene-viewport').addEventListener('lostpointercapture',finishSceneDrag);
  $('scene-viewport').addEventListener('scroll',event=>{
    if(event.target.closest?.('.lane-cards'))layoutTaskConnections();
  },true);
  $('scene-viewport').addEventListener('wheel', event => {
    if(!event.ctrlKey&&!event.metaKey&&event.target.closest('.lane-cards'))return;
    event.preventDefault();
    if (event.ctrlKey || event.metaKey) zoomScene(event.deltaY > 0 ? .9 : 1.1);
    else { scene.panX -= event.deltaX; scene.panY -= event.deltaY; sceneTransform(); }
  }, { passive: false });
  $('scene-viewport').addEventListener('keydown', event => {
    const header=event.target.closest('.lane-heading');
    if(header&&event.shiftKey&&['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){
      const node=scene.nodes.find(node=>node.element===header);
      if(node){event.preventDefault();moveLane(node,node.x+(event.key==='ArrowLeft'?-12:event.key==='ArrowRight'?12:0),
        node.y+(event.key==='ArrowUp'?-12:event.key==='ArrowDown'?12:0));}
      return;
    }
    if (event.target !== $('scene-viewport')) return;
    if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomScene(1.2); }
    if (event.key === '-') { event.preventDefault(); zoomScene(1 / 1.2); }
    if (event.key === '0') { event.preventDefault(); fitScene(true); }
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
      event.preventDefault(); scene.panX += event.key === 'ArrowLeft' ? 45 : event.key === 'ArrowRight' ? -45 : 0;
      scene.panY += event.key === 'ArrowUp' ? 45 : event.key === 'ArrowDown' ? -45 : 0; sceneTransform();
    }
  });
  $('immersive').onclick = async () => {
    try {
      if (document.fullscreenElement && document.exitFullscreen) await document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      else { document.body.classList.toggle('immersive'); fitScene(false); }
    } catch (_) { toast('浏览器未允许全屏，仍可拖动和缩放画布', true); }
  };
  document.addEventListener('fullscreenchange', () => {
    text('immersive', document.fullscreenElement ? '退出全屏' : '沉浸模式'); fitScene(false);
  });
  window.addEventListener('hashchange', () => applyView(location.hash === '#debug' ? 'debug' : 'overview'));
  $('config-close').onclick = () => $('config-dialog').close();
  $('config-dialog').onclick = event => { if (event.target === $('config-dialog')) $('config-dialog').close(); };
  $('reset-config').onclick = () => { $('config-draft').value = draft(); text('draft-status', '未应用 · 需补齐 options'); };
  $('config-draft').oninput = () => text('draft-status', '本地修改 · 未应用');
  $('copy-config').onclick = async () => { try { await navigator.clipboard.writeText($('config-draft').value); toast('草稿已复制，不会应用到服务端'); } catch (_) { toast('复制不可用，请选择文本手动复制', true); } };
  $('export-config').onclick = () => {
    const value = $('config-draft').value;
    if (!value.trim() || value.length > 64000) { toast('草稿为空或超出长度限制', true); return; }
    const url = URL.createObjectURL(new Blob([value], { type: 'application/yaml;charset=utf-8' }));
    const anchor = element('a'); anchor.href = url; anchor.download = 'ominicat-draft.yaml'; document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0); toast('YAML 已下载；补齐必要配置后重启服务端');
  };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { stopPoll(); stopActivity(); clearParticles(); stopScene(); cancelMedia(); }
    else { refreshSoon(); refreshActivity(); expirePending(); clientMetrics(); startScene(); }
    document.body.classList.toggle('motion-stopped', document.hidden || !state.motion || reducedMotion.matches);
  });
  window.addEventListener('pagehide', () => { state.disposed = true; stopPoll(); stopActivity(); clearParticles(); stopScene(); disconnect(); clearTimeout(toastTimer); });
  window.addEventListener('pageshow', event => {
    if (event.persisted) { state.disposed = false; refreshSoon(); refreshActivity(); startScene(); }
  });
  topology([]); buildScene(); applyView(location.hash === '#debug' ? 'debug' : 'overview'); setMotion(); controls(); refreshSoon();
})();
