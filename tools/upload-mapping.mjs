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
 * --r8-build-key 将 --build 解析为 Android 原始 versionCode，并追加当前 mapping 的标识。
 * 此模式下 --check 也要传 --mapping，保证查询与上传的 Cloud 构建键一致。
 * --token-section 按「## 标题包含该子串」定位，再取该节第一条非注释行。
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

async function readJson(res) {
  try { return await res.json(); } catch { return null; }
}

// Read only R8's bounded metadata header. Keep the Android versionCode intact.
function r8BuildKey(mapping, build) {
  if (!mapping || !/^\d{1,10}$/.test(build)) return null;
  const header = Buffer.alloc(4096);
  const fd = fs.openSync(mapping, 'r');
  try {
    const size = fs.readSync(fd, header);
    const hash = header.subarray(0, size).toString('utf8')
      .split(/\r?\n/).slice(0, 16).map((line) =>
        /^# pg_map_hash: SHA-256 ([a-f0-9]{64})$/.exec(line)?.[1]
      ).find(Boolean);
    return hash ? `${build}-${hash.slice(0, 20)}` : null;
  } finally {
    fs.closeSync(fd);
  }
}

const opts = args(process.argv);
const endpoint = String(opts.endpoint || process.env.MOO_MONITOR_ENDPOINT || 'https://c.mooeen.com').replace(/\/+$/, '');
const release = String(opts.release || '').trim();
const versionBuild = String(opts.build || '').trim();
const kind = 'mapping';
const platform = 'android';
let token;
try { token = readToken(opts); } catch { console.error('无法读取符号上传凭据'); process.exit(2); }
if (!token) { console.error('缺少 token（--token / MOO_MONITOR_TOKEN / --token-file + --token-section）'); process.exit(2); }
if (!release) { console.error('缺少 --release（versionName）'); process.exit(2); }
if (!versionBuild) { console.error('缺少 --build（versionCode；release+build 是云端定位符号的键）'); process.exit(2); }

async function check(build) {
  const res = await fetch(`${endpoint}/api/v1/app-errors/symbols/check`, {
    method: 'POST',
    redirect: 'error',
    headers: authHeaders(token, { 'Content-Type': 'application/json', Accept: 'application/json' }),
    signal: AbortSignal.timeout(60000),
    body: JSON.stringify({ platform, release, build, kind }),
  });
  const result = await readJson(res);
  if (!res.ok || result?.ok !== true || typeof result.exists !== 'boolean') {
    console.error(`符号查询失败 HTTP ${res.status}`);
    process.exitCode = 1;
    return;
  }
  // Never echo upstream bodies or arbitrary string values into CI logs.
  const safe = { ok: true, exists: result.exists };
  if (result.exists && typeof result.sha256 === 'string' && /^[a-f0-9]{64}$/.test(result.sha256)) {
    safe.sha256 = result.sha256;
  }
  if (typeof result.uploaded_at === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(result.uploaded_at)) {
    safe.uploaded_at = result.uploaded_at;
  }
  console.log(JSON.stringify(safe));
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

async function upload(build) {
  const mapping = opts.mapping;
  if (!mapping || !fs.existsSync(mapping)) { console.error('找不到 mapping 文件'); process.exit(2); }
  const stat = fs.statSync(mapping);
  if (!stat.size) { console.error('mapping 文件为空'); process.exit(2); }
  const sha256 = await sha256OfFile(mapping);
  // Sign and upload the same file. openAsBlob also rejects a file changed during streaming.
  const fileBlob = await fs.openAsBlob(mapping, { type: 'text/plain' });
  const current = fs.statSync(mapping);
  const fields = ['dev', 'ino', 'size', 'mtimeMs', 'ctimeMs'];
  if (fields.some((field) => stat[field] !== current[field]) ||
      (opts['r8-build-key'] && build !== r8BuildKey(mapping, versionBuild))) {
    console.error('mapping 文件在读取期间发生变化');
    process.exitCode = 1;
    return;
  }
  const originalName = path.basename(String(mapping)) || 'mapping.txt';

  // 1) 签票据：cloud 登记元信息并签发七牛上传 token，不接收文件本体。
  const ticketRes = await fetch(`${endpoint}/api/v1/uploads/tickets`, {
    method: 'POST',
    redirect: 'error',
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
  if (!ticketRes.ok || ticket?.ok !== true) {
    console.error(`票据申请失败 HTTP ${ticketRes.status}`);
    process.exit(1);
  }
  const { upload_id: uploadId, key, region, token: uploadToken } = ticket;
  if (!uploadId || !key || !region || !uploadToken) {
    console.error('票据响应缺少上传参数');
    process.exit(1);
  }
  const uploadHost = Object.hasOwn(QINIU_UPLOAD_HOSTS, region) ? QINIU_UPLOAD_HOSTS[region] : null;
  if (!uploadHost) {
    console.error('票据返回未知七牛区域');
    process.exit(1);
  }
  console.log('已取得上传票据');

  // 2) 直传七牛：multipart，文件字节只经过本机 -> 七牛，不经过 cloud。
  const form = new FormData();
  form.append('token', uploadToken);
  form.append('key', key);
  form.append('file', fileBlob, originalName);
  const qiniuRes = await fetch(uploadHost, { method: 'POST', redirect: 'error', body: form, signal: AbortSignal.timeout(600000) });  // 大文件直传留 600s
  const qiniu = await readJson(qiniuRes);
  if (!qiniuRes.ok || qiniu?.error) {
    console.error(`七牛直传失败 HTTP ${qiniuRes.status}`);
    process.exit(1);
  }
  console.log('qiniu upload ok');

  // 3) 确认：mapping 的 uuids 可空；ok=true 即完成。
  const confirmRes = await fetch(`${endpoint}/api/v1/uploads/${encodeURIComponent(uploadId)}/confirm`, {
    method: 'POST',
    redirect: 'error',
    headers: authHeaders(token, { 'Content-Type': 'application/json', Accept: 'application/json' }),
    signal: AbortSignal.timeout(60000),  // 60s 超时
    body: JSON.stringify({ uuids: [] }),
  });
  const confirm = await readJson(confirmRes);
  if (!confirmRes.ok || confirm?.ok !== true) {
    console.error(`确认失败 HTTP ${confirmRes.status}`);
    process.exit(1);
  }
  console.log('完成：mapping.txt 已直传并确认');
  process.exit(0);
}

async function main() {
  const build = opts['r8-build-key'] ? r8BuildKey(opts.mapping, versionBuild) : versionBuild;
  if (!build) {
    console.error('R8 构建键需要 mapping 文件中的 pg_map_hash 和数字构建号');
    process.exitCode = 2;
    return;
  }
  return opts.check ? check(build) : upload(build);
}

main().catch((error) => {
  console.error(error?.name === 'TimeoutError' ? '符号操作超时' : '符号操作失败（本地文件或网络异常）');
  process.exitCode = 1;
});
