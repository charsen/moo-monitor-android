#!/usr/bin/env node
/**
 * 上传 Android R8/ProGuard 的 mapping.txt 到 Moo cloud 符号库。
 *
 * 直传三步契约（moo-scaffold-cloud 分支 feat/direct-qiniu-uploads）：cloud 只签票据，
 * 文件本体从本机直达七牛，不经过 cloud。
 *   1) POST {endpoint}/api/v1/uploads/tickets   Header: Authorization: Bearer <token>
 *      JSON: {business:"app_symbols", platform:"android", release, build, kind:"mapping",
 *             original_name, file_size, sha256, uuids:[]}
 *      返回: {ok, upload_id, key, region, token, expires_at}
 *   2) POST https://upload.qiniup.com（z0）或 https://upload-<region>.qiniup.com
 *      multipart/form-data: token, key, file（不经 cloud）
 *   3) POST {endpoint}/api/v1/uploads/{upload_id}/confirm   Header: Authorization: Bearer <token>
 *      JSON: {uuids:[]}（mapping 可空），返回 ok=true 即完成。
 *
 * 只读 check（可选）：POST {endpoint}/api/v1/app-errors/symbols/check（能力 app_errors）。
 * token 不打印。--token-section 按「## 标题包含该子串」定位，再取该节第一条非注释行。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// region -> 七牛直传域名：z0 无后缀，其余带区域后缀。
const QINIU_UPLOAD_HOSTS = {
  z0: 'https://upload.qiniup.com',
  z1: 'https://upload-z1.qiniup.com',
  z2: 'https://upload-z2.qiniup.com',
  na0: 'https://upload-na0.qiniup.com',
  as0: 'https://upload-as0.qiniup.com',
};

function args(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) { out[key] = next; i++; } else { out[key] = true; }
  }
  return out;
}

function readToken(opts) {
  if (opts.token) return String(opts.token).trim();
  if (process.env.MOO_MONITOR_TOKEN) return process.env.MOO_MONITOR_TOKEN.trim();
  const file = opts['token-file'];
  const section = opts['token-section'];
  if (!file || !section) return '';
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  let inSection = false;
  for (const raw of lines) {
    const line = raw.trim();
    if (line.startsWith('## ')) { inSection = line.includes(section); continue; }
    if (!inSection) continue;
    if (!line || line.startsWith('#') || line.startsWith('<') || line.startsWith('<!--')) continue;
    return line;
  }
  return '';
}

function authHeaders(token, extra) {
  return { Authorization: `Bearer ${token}`, ...extra };
}

// 错误回显脱敏：把 JSON 里 token/authorization/secret/password 类字段替换为 ***，避免把票据打日志。
const SENSITIVE_KEYS = ['token', 'authorization', 'secret', 'password'];
function redact(obj) {
  if (Array.isArray(obj)) return obj.map(redact);
  if (obj && typeof obj === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(obj)) {
      out[k] = SENSITIVE_KEYS.some((s) => k.toLowerCase().includes(s)) ? '***' : redact(v);
    }
    return out;
  }
  return obj;
}
// 把响应体转成可安全打印的字符串：能解析 JSON 则脱敏后序列化，否则原文。
function safeBody(res) {
  if (res.json && typeof res.json === 'object') return JSON.stringify(redact(res.json));
  return res.text;
}

async function readJson(res) {
  const text = await res.text();
  try { return { text, json: JSON.parse(text) }; } catch { return { text, json: null }; }
}

const opts = args(process.argv);
const endpoint = String(opts.endpoint || process.env.MOO_MONITOR_ENDPOINT || 'https://c.mooeen.com').replace(/\/+$/, '');
const release = String(opts.release || '').trim();
const build = String(opts.build || '').trim();
const kind = 'mapping';
const platform = 'android';
const token = readToken(opts);
if (!token) { console.error('缺少 token（--token / MOO_MONITOR_TOKEN / --token-file + --token-section）'); process.exit(2); }
if (!release) { console.error('缺少 --release（versionName）'); process.exit(2); }
if (!build) { console.error('缺少 --build（versionCode；release+build 是云端定位符号的键）'); process.exit(2); }

async function check() {
  const res = await fetch(`${endpoint}/api/v1/app-errors/symbols/check`, {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json', Accept: 'application/json' }),
    signal: AbortSignal.timeout(60000),
    body: JSON.stringify({ platform, release, build, kind }),
  });
  const { text } = await readJson(res);
  console.log(text);
  process.exit(res.ok ? 0 : 1);
}

// 流式计算 sha256，避免把大文件整体读入内存。
function sha256OfFile(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', (chunk) => h.update(chunk))
      .on('error', reject)
      .on('end', () => resolve(h.digest('hex')));
  });
}

async function upload() {
  const mapping = opts.mapping;
  if (!mapping || !fs.existsSync(mapping)) { console.error('找不到 mapping 文件：' + mapping); process.exit(2); }
  const stat = fs.statSync(mapping);
  if (!stat.size) { console.error('mapping 文件为空：' + mapping); process.exit(2); }
  const sha256 = await sha256OfFile(mapping);
  const originalName = path.basename(String(mapping)) || 'mapping.txt';

  // 1) 签票据：cloud 登记元信息并签发七牛上传 token，不接收文件本体。
  const ticketRes = await fetch(`${endpoint}/api/v1/uploads/tickets`, {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json', Accept: 'application/json' }),
    signal: AbortSignal.timeout(60000),  // 60s 超时，防止请求卡死挂住 CI
    body: JSON.stringify({
      business: 'app_symbols',
      platform,
      release,
      build,
      kind,
      original_name: originalName,
      file_size: stat.size,
      sha256,
      uuids: [],
    }),
  });
  const ticket = await readJson(ticketRes);
  if (!ticketRes.ok || !ticket.json || ticket.json.ok !== true) {
    console.error(`票据申请失败 HTTP ${ticketRes.status}：${safeBody(ticket)}`);
    process.exit(1);
  }
  const { upload_id: uploadId, key, region, token: uploadToken } = ticket.json;
  if (!uploadId || !key || !region || !uploadToken) {
    console.error('票据响应缺少 upload_id/key/region/token：' + safeBody(ticket));
    process.exit(1);
  }
  const uploadHost = QINIU_UPLOAD_HOSTS[region];
  if (!uploadHost) {
    console.error(`未知七牛区域：${region}（可用：${Object.keys(QINIU_UPLOAD_HOSTS).join(', ')}）`);
    process.exit(1);
  }
  console.log(`ticket upload_id=${uploadId} region=${region} host=${uploadHost}`);

  // 2) 直传七牛：multipart，文件字节只经过本机 -> 七牛，不经过 cloud。
  // 用 openAsBlob 从磁盘按需读取，避免整文件载入内存。
  const fileBlob = await fs.openAsBlob(mapping, { type: 'text/plain' });
  const form = new FormData();
  form.append('token', uploadToken);
  form.append('key', key);
  form.append('file', fileBlob, originalName);
  const qiniuRes = await fetch(uploadHost, { method: 'POST', body: form, signal: AbortSignal.timeout(600000) });  // 大文件直传留 600s
  const qiniu = await readJson(qiniuRes);
  if (!qiniuRes.ok || (qiniu.json && qiniu.json.error)) {
    console.error(`七牛直传失败 HTTP ${qiniuRes.status}：${safeBody(qiniu)}`);
    process.exit(1);
  }
  console.log('qiniu upload ok');

  // 3) 确认：mapping 的 uuids 可空；ok=true 即完成。
  const confirmRes = await fetch(`${endpoint}/api/v1/uploads/${encodeURIComponent(uploadId)}/confirm`, {
    method: 'POST',
    headers: authHeaders(token, { 'Content-Type': 'application/json', Accept: 'application/json' }),
    signal: AbortSignal.timeout(60000),  // 60s 超时
    body: JSON.stringify({ uuids: [] }),
  });
  const confirm = await readJson(confirmRes);
  if (!confirmRes.ok || !confirm.json || confirm.json.ok !== true) {
    console.error(`确认失败 HTTP ${confirmRes.status}：${safeBody(confirm)}`);
    process.exit(1);
  }
  console.log(`完成：mapping.txt 已直传并确认（release=${release} build=${build || '-'} upload_id=${uploadId}）`);
  process.exit(0);
}

(opts.check ? check() : upload()).catch((error) => {
  console.error('上传失败：' + (error && error.message || error));
  process.exit(1);
});
