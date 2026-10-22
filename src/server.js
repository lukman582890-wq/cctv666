const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const PORT = Number(process.env.PORT || 8787);
const PIN = crypto.randomInt(100000, 1000000).toString();
const PAGE = fs.readFileSync(path.join(__dirname, '..', 'public', 'index.html'));
let ffmpeg = null;
let streamClients = new Set();
let lastChunk = null;
let state = { connected: false, camera: null, message: 'Belum terhubung' };
let cameraSession = null;

function isPrivateClient(req) {
  const ip = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  return ip === '127.0.0.1' || ip === '::1' || /^10\./.test(ip) || /^192\.168\./.test(ip) || /^172\.(1[6-9]|2\d|3[01])\./.test(ip);
}
function send(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
}
function authorized(req, url) {
  return req.headers['x-viewer-pin'] === PIN || url.searchParams.get('pin') === PIN;
}
function xmlEscape(value) {
  return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));
}
function wsse(user, pass) {
  const created = new Date().toISOString();
  const nonce = crypto.randomBytes(16);
  const digest = crypto.createHash('sha1').update(Buffer.concat([nonce, Buffer.from(created), Buffer.from(pass)])).digest('base64');
  return `<wsse:Security soap:mustUnderstand="1" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><wsse:UsernameToken><wsse:Username>${xmlEscape(user)}</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${digest}</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${nonce.toString('base64')}</wsse:Nonce><wsu:Created>${created}</wsu:Created></wsse:UsernameToken></wsse:Security>`;
}
async function soap(endpoint, action, body, user, pass) {
  const envelope = `<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"><s:Header>${wsse(user, pass)}</s:Header><s:Body>${body}</s:Body></s:Envelope>`;
  const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/soap+xml; charset=utf-8; action="' + action + '"' }, body: envelope, signal: AbortSignal.timeout(5000) });
  const text = await response.text();
  if (!response.ok || /<[^>]*Fault\b/i.test(text)) throw new Error('Kamera menolak permintaan ONVIF. Periksa ONVIF, nama pengguna, dan kata sandi.');
  return text;
}
function tag(xml, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = xml.match(new RegExp(`<[^>]*${escaped}[^>]*>([\\s\\S]*?)<\\/[^>]*${escaped}\\s*>`, 'i'));
  return match ? match[1].replace(/<!\[CDATA\[|\]\]>/g, '').trim() : '';
}
function unescapeXml(s) { return s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'"); }
function serviceXaddr(xml, service) {
  const block = xml.match(new RegExp('<[^>]*' + service + '\\b[^>]*>([\\s\\S]*?)<\\/[^>]*' + service + '\\s*>', 'i'));
  return block ? unescapeXml(tag(block[1], 'XAddr')) : '';
}
async function getRtsp(ip, user, pass, port) {
  const candidates = port ? [port] : [80, 8899, 8080];
  let deviceEndpoint, caps;
  for (const p of candidates) {
    const endpoint = `http://${ip}:${p}/onvif/device_service`;
    try {
      const xml = await soap(endpoint, 'http://www.onvif.org/ver10/device/wsdl/GetCapabilities', '<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>', user, pass);
      deviceEndpoint = endpoint;
      caps = xml;
      break;
    } catch (_) {}
  }
  if (!deviceEndpoint) throw new Error('ONVIF belum merespons pada port 80, 8899, atau 8080. Pastikan ONVIF aktif dan PC satu jaringan dengan kamera.');
  let media = unescapeXml(tag(caps, 'XAddr'));
  if (!/^https?:\/\//i.test(media)) media = deviceEndpoint.replace('/onvif/device_service', '/onvif/media_service');
  const profilesXml = await soap(media, 'http://www.onvif.org/ver10/media/wsdl/GetProfiles', '<trt:GetProfiles/>', user, pass);
  const profileMatch = profilesXml.match(/<[^>]*Profiles\b[^>]*\btoken=["']([^"']+)["']/i);
  if (!profileMatch) throw new Error('Kamera merespons ONVIF, tetapi profil videonya tidak ditemukan.');
  const token = xmlEscape(profileMatch[1]);
  const streamXml = await soap(media, 'http://www.onvif.org/ver10/media/wsdl/GetStreamUri', `<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>${token}</trt:ProfileToken></trt:GetStreamUri>`, user, pass);
  let uri = unescapeXml(tag(streamXml, 'Uri'));
  if (!/^rtsps?:\/\//i.test(uri)) return null;
  const parsed = new URL(uri);
  if (!parsed.username) parsed.username = user;
  if (!parsed.password) parsed.password = pass;
  return parsed.toString();
}
async function ptzRequest(operation, innerXml) {
  if (!cameraSession || !cameraSession.ptzEndpoint || !cameraSession.profileToken) {
    throw new Error('Kamera ini belum menyediakan layanan PTZ ONVIF.');
  }
  const profile = xmlEscape(cameraSession.profileToken);
  return soap(
    cameraSession.ptzEndpoint,
    'http://www.onvif.org/ver20/ptz/wsdl/' + operation,
    '<tptz:' + operation + '><tptz:ProfileToken>' + profile + '</tptz:ProfileToken>' + innerXml + '</tptz:' + operation + '>',
    cameraSession.user,
    cameraSession.pass
  );
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; if (raw.length > 16000) reject(new Error('Permintaan terlalu besar.')); });
    req.on('end', () => { try { resolve(JSON.parse(raw || '{}')); } catch { reject(new Error('Data tidak valid.')); } });
    req.on('error', reject);
  });
}
function parsePresets(xml) {
  const result = [];
  const re = /<(?:(?:\\w+):)?Preset\\b([^>]*)>([\\s\\S]*?)<\\/(?:(?:\\w+):)?Preset\\s*>/gi;
  for (const match of xml.matchAll(re)) {
    const token = (match[1].match(/\\btoken=["']([^"']+)["']/i) || [])[1];
    const name = unescapeXml(tag(match[2], 'Name'));
    if (token) result.push({ token: unescapeXml(token), name: name || 'Preset ' + (result.length + 1) });
  }
  return result;
}
function makeRtspUri(ip, user, pass, streamPath) {
  const normalized = streamPath.startsWith('/') ? streamPath : '/' + streamPath;
  const parsed = new URL(`rtsp://${ip}:554${normalized}`);
  if (user) parsed.username = user;
  if (pass) parsed.password = pass;
  return parsed.toString();
}
function stopStream() {
  if (ffmpeg) { ffmpeg.kill('SIGTERM'); ffmpeg = null; }
  for (const client of streamClients) client.end();
  streamClients.clear();
  lastChunk = null;
  state.connected = false;
}
function startStream(uri) {
  stopStream();
  return new Promise((resolve, reject) => {
    const args = ['-hide_banner', '-loglevel', 'error', '-rtsp_transport', 'tcp', '-i', uri, '-an', '-vf', 'fps=8,scale=960:-1', '-q:v', '6', '-f', 'mpjpeg', '-boundary_tag', 'frame', 'pipe:1'];
    const child = spawn(process.env.FFMPEG_PATH || path.join(__dirname, 'ffmpeg.exe'), args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    ffmpeg = child;
    let settled = false;
    let errorText = '';
    const timer = setTimeout(() => fail('Kamera tidak memulai video RTSP. Periksa jalur stream, kata sandi, dan apakah RTSP aktif.'), 9000);
    function fail(message) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (ffmpeg === child) ffmpeg = null;
      child.kill('SIGTERM');
      state.connected = false;
      state.message = message;
      reject(new Error(message));
    }
    child.stderr.on('data', data => { errorText = (errorText + data.toString()).slice(-1500); });
    child.on('error', error => {
      const message = error.code === 'ENOENT' ? 'FFmpeg tidak ditemukan di folder aplikasi.' : 'FFmpeg gagal dijalankan.';
      fail(message);
    });
    child.stdout.on('data', data => {
      if (ffmpeg !== child) return;
      lastChunk = data;
      for (const client of streamClients) client.write(data);
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        state.connected = true;
        state.message = 'Kamera terhubung';
        resolve();
      }
    });
    child.on('close', code => {
      if (!settled) {
        const detail = errorText ? ' Periksa kata sandi atau dukungan RTSP kamera.' : '';
        fail(`Tidak dapat membuka stream RTSP.${detail}`);
      } else if (ffmpeg === child) {
        ffmpeg = null;
        state.connected = false;
        state.message = 'Aliran kamera terputus';
      }
    });
  });
}
function localAddresses() {
  const result = [];
  for (const list of Object.values(os.networkInterfaces())) for (const item of list || []) if (item.family === 'IPv4' && !item.internal) result.push(item.address);
  return result;
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (!isPrivateClient(req)) return send(res, 403, { error: 'Akses hanya diizinkan dari jaringan lokal.' });
  if (url.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }); return res.end(PAGE); }
  if (url.pathname === '/api/status') return send(res, 200, { connected: state.connected, camera: state.camera, message: state.message });
  if (url.pathname === '/api/connect' && req.method === 'POST') {
    if (!authorized(req, url)) return send(res, 401, { error: 'PIN aplikasi salah.' });
    let raw = ''; for await (const chunk of req) raw += chunk;
    let data; try { data = JSON.parse(raw); } catch { return send(res, 400, { error: 'Data pengaturan tidak valid.' }); }
    const ip = String(data.ip || '').trim();
    if (!/^192\.168\.|^10\.|^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return send(res, 400, { error: 'Masukkan alamat IPv4 lokal kamera.' });
    try {
      state.message = 'Mencari alamat video melalui ONVIF/RTSP…';
      const user = String(data.username || 'admin');
      const pass = String(data.password || '');
      const candidates = [];
      try {
        const discovered = await getRtsp(ip, user, pass, Number(data.onvifPort) || null);
        if (discovered) candidates.push(discovered);
      } catch (_) {}
      const manualPath = String(data.rtspPath || '').trim();
      const paths = manualPath ? [manualPath] : ['/live/ch00_1', '/live/ch00_0'];
      for (const streamPath of paths) candidates.push(makeRtspUri(ip, user, pass, streamPath));
      let connected = false;
      for (const uri of [...new Set(candidates)]) {
        try { await startStream(uri); connected = true; break; }
        catch (error) { if (/FFmpeg tidak ditemukan|FFmpeg gagal/i.test(error.message)) throw error; }
      }
      if (!connected) throw new Error('ONVIF tidak memberi alamat video dan jalur RTSP V380 umum gagal. Pastikan RTSP aktif di kamera; jalur umum: /live/ch00_1 atau /live/ch00_0.');
      state.camera = ip;
      return send(res, 200, { ok: true, camera: ip });
    } catch (error) {
      state.connected = false; state.message = error.message;
      return send(res, 502, { error: error.message });
    }
  }
  if (url.pathname.startsWith('/api/ptz/') && req.method !== 'GET') {
    if (!authorized(req, url)) return send(res, 401, { error: 'PIN aplikasi salah.' });
  }
  if (url.pathname === '/api/ptz/presets' && req.method === 'GET') {
    if (!authorized(req, url)) return send(res, 401, { error: 'PIN aplikasi salah.' });
    try {
      const xml = await ptzRequest('GetPresets', '');
      return send(res, 200, { presets: parsePresets(xml) });
    } catch (error) { return send(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/ptz/move' && req.method === 'POST') {
    try {
      const data = await readJson(req);
      const clamp = n => Math.max(-1, Math.min(1, Number.isFinite(Number(n)) ? Number(n) : 0));
      const pan = clamp(data.pan), tilt = clamp(data.tilt), zoom = clamp(data.zoom);
      if (!pan && !tilt && !zoom) return send(res, 400, { error: 'Arah gerakan kosong.' });
      const velocity = '<tptz:Velocity><tt:PanTilt x="' + pan + '" y="' + tilt + '"/><tt:Zoom x="' + zoom + '"/></tptz:Velocity><tptz:Timeout>PT0.8S</tptz:Timeout>';
      await ptzRequest('ContinuousMove', velocity);
      return send(res, 200, { ok: true });
    } catch (error) { return send(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/ptz/stop' && req.method === 'POST') {
    try { await ptzRequest('Stop', '<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom>'); return send(res, 200, { ok: true }); }
    catch (error) { return send(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/ptz/presets' && req.method === 'POST') {
    try {
      const data = await readJson(req);
      const name = String(data.name || '').trim().slice(0, 40);
      if (!name) return send(res, 400, { error: 'Isi nama preset terlebih dahulu.' });
      const xml = await ptzRequest('SetPreset', '<tptz:PresetName>' + xmlEscape(name) + '</tptz:PresetName>');
      const token = unescapeXml(tag(xml, 'PresetToken'));
      return send(res, 200, { ok: true, token });
    } catch (error) { return send(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/ptz/preset/goto' && req.method === 'POST') {
    try {
      const data = await readJson(req);
      const token = String(data.token || '').slice(0, 128);
      if (!token) return send(res, 400, { error: 'Pilih preset terlebih dahulu.' });
      await ptzRequest('GotoPreset', '<tptz:PresetToken>' + xmlEscape(token) + '</tptz:PresetToken>');
      return send(res, 200, { ok: true });
    } catch (error) { return send(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/disconnect' && req.method === 'POST') {
    if (!authorized(req, url)) return send(res, 401, { error: 'PIN aplikasi salah.' });
    stopStream(); cameraSession = null; state.message = 'Kamera terputus'; return send(res, 200, { ok: true });
  }
  if (url.pathname === '/stream.mjpg') {
    if (!authorized(req, url)) { res.writeHead(401); return res.end(); }
    res.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace; boundary=frame', 'Cache-Control': 'no-store', 'Connection': 'close' });
    streamClients.add(res);
    if (lastChunk) res.write(lastChunk);
    req.on('close', () => streamClients.delete(res));
    return;
  }
  send(res, 404, { error: 'Tidak ditemukan.' });
});
server.listen(PORT, '0.0.0.0', () => {
  console.log('\nV380 Web Viewer berjalan di jaringan lokal.');
  for (const ip of localAddresses()) console.log(`  http://${ip}:${PORT}`);
  console.log(`PIN aplikasi: ${PIN}`);
  console.log('Jangan bagikan PIN dan jangan buka port ini ke internet.\n');
});




