import test from 'node:test';
import assert from 'node:assert/strict';
import { registerExtraRoutes, normalizeFolder, captionFor } from '../src/extra-routes.js';

function fakeApp() {
  const routes = {};
  const reg = (m) => (path, ...h) => { routes[`${m} ${path}`] = h.at(-1); };
  return { routes, get: reg('GET'), post: reg('POST'), put: reg('PUT'), patch: reg('PATCH'), delete: reg('DELETE') };
}
function fakeRes() {
  return { statusCode: 200, body: null, headers: {}, status(c) { this.statusCode = c; return this; }, json(b) { this.body = b; return this; }, setHeader(k, v) { this.headers[k] = v; } };
}
const row = { id: 7, original_name: 'a.pdf', mime_type: 'application/pdf', byte_size: 10, sha256: 'ab'.repeat(32), provider: 'telegram',
  telegram_chat_id: '-1001', telegram_message_id: 55, telegram_file_id: 'FILEID', telegram_file_unique_id: 'U', created_at: '2026-01-01', deleted_at: null, folder_path: null, archive_path: '2026/01/01/07' };

function setup(poolHandler, telegramHandler) {
  const app = fakeApp(); const queries = []; const tgCalls = []; const logs = [];
  const pool = { async query(sql, params = []) { queries.push({ sql, params }); const r = poolHandler(sql, params); return r || { rows: [], rowCount: 0 }; } };
  const telegram = async (method, options) => { const payload = JSON.parse(options.body || '{}'); tgCalls.push({ method, payload }); return telegramHandler(method, payload); };
  registerExtraRoutes(app, { pool, auth: (_q, _r, n) => n(), config: { telegramChatId: '-1001' }, telegram, logActivity: async (...a) => logs.push(a), safeName: (n) => String(n).slice(0, 240) });
  return { app, queries, tgCalls, logs };
}
const call = async (app, key, req) => { const res = fakeRes(); await app.routes[key]({ params: {}, query: {}, body: {}, ...req }, res); return res; };

test('normalizeFolder merapikan dan menolak jalur berbahaya', () => {
  assert.equal(normalizeFolder(' /A//B\\C/ '), 'A/B/C');
  assert.throws(() => normalizeFolder('a/../b'), /tidak valid/);
  assert.throws(() => normalizeFolder('1/2/3/4/5/6/7/8/9'), /terlalu dalam/);
  assert.equal(normalizeFolder(''), '');
});

test('captionFor kompatibel dengan format caption aplikasi', () => {
  assert.equal(captionFor({ folder: 'X/Y', name: 'n.txt', sha256: 'h' }), 'NEXORA CLOUD\nFolder: X/Y\nFile: n.txt\nSHA256: h');
  assert.match(captionFor({ folder: '', name: 'n' }), /Folder: \//);
});

test('salin file memakai file_id yang sama (tanpa upload ulang) dan membuat baris baru', async () => {
  const { app, tgCalls, queries } = setup(
    (sql) => sql.startsWith('SELECT') ? { rows: [row], rowCount: 1 } : sql.startsWith('INSERT') ? { rows: [{ ...row, id: 8, telegram_message_id: 99 }], rowCount: 1 } : null,
    (m) => ({ chat: { id: -1001 }, message_id: 99, document: { file_id: 'NEWID', file_unique_id: 'U2' } })
  );
  const res = await call(app, 'POST /api/v1/files/:id/copy', { params: { id: '7' }, body: { folder: 'Proyek/2026' } });
  assert.equal(res.statusCode, 201);
  assert.equal(res.body.action, 'copied');
  const send = tgCalls.find((c) => c.method === 'sendDocument');
  assert.equal(send.payload.document, 'FILEID');
  assert.match(send.payload.caption, /Folder: Proyek\/2026/);
  assert.ok(queries.some((q) => q.sql.startsWith('INSERT INTO cloud_files')));
});

test('pindah dalam chat yang sama hanya mengubah folder dan caption', async () => {
  const { app, tgCalls, queries } = setup(
    (sql) => sql.startsWith('SELECT') ? { rows: [row], rowCount: 1 } : sql.startsWith('UPDATE') ? { rows: [{ ...row, archive_path: 'Baru' }], rowCount: 1 } : null,
    () => true
  );
  const res = await call(app, 'POST /api/v1/files/:id/move', { params: { id: '7' }, body: { folder: 'Baru' } });
  assert.equal(res.body.action, 'moved_folder');
  assert.ok(queries.some((q) => /SET folder_path=\$2/.test(q.sql) && q.params[1] === 'Baru'));
  assert.equal(tgCalls.filter((c) => c.method === 'sendDocument').length, 0);
  assert.equal(tgCalls.find((c) => c.method === 'editMessageCaption').payload.message_id, 55);
});

test('pindah antar chat menyalin, memperbarui ID pesan, lalu menghapus pesan lama', async () => {
  const { app, tgCalls, queries } = setup(
    (sql) => sql.startsWith('SELECT') ? { rows: [row], rowCount: 1 } : sql.startsWith('UPDATE') ? { rows: [{ ...row, telegram_chat_id: '-2002', telegram_message_id: 12 }], rowCount: 1 } : null,
    (m) => m === 'sendDocument' ? { chat: { id: -2002 }, message_id: 12, document: { file_id: 'N', file_unique_id: 'U' } } : true
  );
  const res = await call(app, 'POST /api/v1/files/:id/move', { params: { id: '7' }, body: { chat_id: '-2002', folder: 'X' } });
  assert.equal(res.body.action, 'moved_chat');
  const upd = queries.find((q) => q.sql.includes('telegram_chat_id=$2'));
  assert.deepEqual([upd.params[1], upd.params[2]], ['-2002', 12]);
  const del = tgCalls.find((c) => c.method === 'deleteMessage');
  assert.deepEqual(del.payload, { chat_id: '-1001', message_id: 55 });
  assert.ok(tgCalls.findIndex((c) => c.method === 'deleteMessage') > tgCalls.findIndex((c) => c.method === 'sendDocument'));
});

test('move tanpa target ditolak', async () => {
  const { app } = setup((sql) => sql.startsWith('SELECT') ? { rows: [row], rowCount: 1 } : null, () => true);
  const res = await call(app, 'POST /api/v1/files/:id/move', { params: { id: '7' }, body: {} });
  assert.equal(res.statusCode, 400);
  assert.equal(res.body.error, 'TARGET_REQUIRED');
});

test('purge menghapus pesan Telegram lalu baris database', async () => {
  const { app, tgCalls, queries } = setup((sql) => sql.startsWith('SELECT') ? { rows: [row], rowCount: 1 } : null, () => true);
  const res = await call(app, 'DELETE /api/v1/files/:id/purge', { params: { id: '7' } });
  assert.equal(res.body.action, 'purged');
  assert.equal(tgCalls[0].method, 'deleteMessage');
  assert.ok(queries.some((q) => q.sql.startsWith('DELETE FROM cloud_files')));
});

test('bulk memvalidasi aksi dan mengembalikan hasil per item', async () => {
  const bad = setup(() => null, () => true);
  assert.equal((await call(bad.app, 'POST /api/v1/files/bulk', { body: { action: 'nuke', ids: [1] } })).statusCode, 400);
  assert.equal((await call(bad.app, 'POST /api/v1/files/bulk', { body: { action: 'trash', ids: [] } })).statusCode, 400);
  const ok = setup((sql, p) => sql.startsWith('UPDATE') && p[0] === 1 ? { rows: [{ id: 1, original_name: 'x' }], rowCount: 1 } : { rows: [], rowCount: 0 }, () => true);
  const res = await call(ok.app, 'POST /api/v1/files/bulk', { body: { action: 'trash', ids: [1, 2] } });
  assert.equal(res.body.succeeded, 1);
  assert.equal(res.body.failed, 1);
  assert.equal(res.body.results[1].error, 'NOT_FOUND');
});

test('folder: buat, tolak jalur tidak valid, dan hapus hanya bila kosong', async () => {
  const { app } = setup((sql) => sql.includes('COUNT(*)') ? { rows: [{ total: '3' }], rowCount: 1 } : null, () => true);
  assert.equal((await call(app, 'POST /api/v1/folders', { body: { path: 'A/B' } })).statusCode, 201);
  assert.equal((await call(app, 'POST /api/v1/folders', { body: { path: '../x' } })).statusCode, 400);
  const del = await call(app, 'DELETE /api/v1/folders', { query: { path: 'A' } });
  assert.equal(del.statusCode, 409);
});

test('storage-chat: validasi format dan izin posting', async () => {
  const { app } = setup(() => null, (m) => m === 'getChat' ? { id: -100777, type: 'channel', title: 'Arsip' } : m === 'getMe' ? { id: 5 } : { status: 'member' });
  assert.equal((await call(app, 'PUT /api/v1/telegram/storage-chat', { body: { chat_id: 'abc' } })).statusCode, 400);
  const denied = await call(app, 'PUT /api/v1/telegram/storage-chat', { body: { chat_id: '-100777' } });
  assert.equal(denied.statusCode, 403);
  const allowed = setup(() => null, (m) => m === 'getChat' ? { id: -100777, type: 'channel', title: 'Arsip' } : m === 'getMe' ? { id: 5 } : { status: 'administrator' });
  const okRes = await call(allowed.app, 'PUT /api/v1/telegram/storage-chat', { body: { chat_id: '-100777' } });
  assert.equal(okRes.body.chat_id, '-100777');
});

test('backup import: format salah ditolak, duplikat dilewati', async () => {
  const { app, queries } = setup((sql) => sql.startsWith('SELECT 1') ? { rows: [{}], rowCount: 1 } : null, () => true);
  assert.equal((await call(app, 'POST /api/v1/backup/import', { body: { format: 'x' } })).statusCode, 400);
  const res = await call(app, 'POST /api/v1/backup/import', { body: { format: 'nexora-backend-backup', version: 1, files: [{ telegram_chat_id: '1', telegram_message_id: 2, telegram_file_id: 'f', original_name: 'a' }, { original_name: 'tanpa-id' }] } });
  assert.equal(res.body.inserted, 0);
  assert.equal(res.body.skipped, 2);
  assert.ok(!queries.some((q) => q.sql.startsWith('INSERT INTO cloud_files')));
});
