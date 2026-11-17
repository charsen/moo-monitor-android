#!/usr/bin/env node
/**
 * 上传 Android R8/ProGuard 的 mapping.txt 到 Moo cloud 符号库。
 *
 * 契约（moo-scaffold-cloud/docs/app-monitor.md §6.1）：
 *   POST {endpoint}/api/v1/app-errors/symbols   multipart/form-data
 *   Header: X-Project-Token: <app_errors 能力 token>
 *   字段: platform=android, release=<versionName>, build=<versionCode>,
 *         kind=mapping, sha256=<可选>, file=<mapping.txt>
 *   check（只读）: POST {endpoint}/api/v1/app-errors/symbols/check  JSON: platform/release/build/kind
 *
 * token 不打印。--token-section 按“## 标题包含该子串”定位，再取该节第一条非注释行。
 */
import fs from 'node:fs';
import crypto from 'node:crypto';

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

const opts = args(process.argv);
const endpoint = String(opts.endpoint || process.env.MOO_MONITOR_ENDPOINT || 'https://c.mooeen.com').replace(/\/+$/, '');
const release = String(opts.release || '').trim();
const build = String(opts.build || '').trim();
const kind = 'mapping';
const platform = 'android';
const token = readToken(opts);
if (!token) { console.error('缺少 token（--token / MOO_MONITOR_TOKEN / --token-file + --token-section）'); process.exit(2); }
if (!release) { console.error('缺少 --release（versionName）'); process.exit(2); }

async function check() {
  const res = await fetch(`${endpoint}/api/v1/app-errors/symbols/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Project-Token': token },
    body: JSON.stringify({ platform, release, build, kind }),
  });
  const body = await res.text();
  console.log(body);
  process.exit(res.ok ? 0 : 1);
}

async function upload() {
  const mapping = opts.mapping;
  if (!mapping || !fs.existsSync(mapping)) { console.error('找不到 mapping 文件：' + mapping); process.exit(2); }
  const bytes = fs.readFileSync(mapping);
  const sha = crypto.createHash('sha256').update(bytes).digest('hex');
  const form = new FormData();
  form.append('platform', platform);
  form.append('release', release);
  if (build) form.append('build', build);
  form.append('kind', kind);
  form.append('sha256', sha);
  form.append('file', new Blob([bytes], { type: 'text/plain' }), 'mapping.txt');
  const res = await fetch(`${endpoint}/api/v1/app-errors/symbols`, {
    method: 'POST',
    headers: { 'Accept': 'application/json', 'X-Project-Token': token },
    body: form,
  });
  const body = await res.text();
  console.log(body);
  process.exit(res.ok ? 0 : 1);
}

(opts.check ? check() : upload()).catch((error) => { console.error('上传失败：' + (error && error.message || error)); process.exit(1); });
