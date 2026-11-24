// Endpoint tambahan Nexora backend (aditif): rename/pindah/salin/hapus permanen, folder virtual,
// pengaturan, pemilihan chat penyimpanan, dan backup/restore indeks. Tidak mengubah endpoint lama.

export function normalizeFolder(raw) {
  const segments = String(raw ?? '').replace(/\\/g, '/').split('/').map((s) => s.trim()).filter(Boolean);
  if (segments.length > 8) throw httpError(400, 'FOLDER_TOO_DEEP', 'Folder terlalu dalam (maksimal 8 level).');
  for (const s of segments) {
    if (s === '.' || s === '..') throw httpError(400, 'FOLDER_INVALID', 'Nama folder tidak valid.');
    // eslint-disable-next-line no-control-regex
    if (/[\x00-\x1f]/.test(s)) throw httpError(400, 'FOLDER_INVALID', 'Nama folder mengandung karakter kontrol.');
  }
  const joined = segments.join('/');
  if (joined.length > 150) throw httpError(400, 'FOLDER_TOO_LONG', 'Jalur folder terlalu panjang (maksimal 150 karakter).');
  return joined;
}

function httpError(status, code, message) {
  const error = new Error(message);
  error.status = status; error.code = code;
  return error;
}

export function captionFor({ folder, name, sha256, trashedAt }) {
  const lines = ['NEXORA CLOUD', `Folder: ${folder ? folder : '/'}`, `File: ${name}`];
  if (sha256) lines.push(`SHA256: ${String(sha256).trim()}`);
  if (trashedAt) lines.push(`Trash: ${trashedAt}`);
  return lines.join('\n');
}

const FILE_COLUMNS = `id, original_name, mime_type, byte_size, sha256, provider, telegram_chat_id, telegram_message_id,
  telegram_file_id, telegram_file_unique_id, created_at, deleted_at, folder_path,
  COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) AS archive_path`;

export async function runExtraMigrations(pool) {
  await pool.query('ALTER TABLE cloud_files ADD COLUMN IF NOT EXISTS folder_path TEXT');
  await pool.query('CREATE TABLE IF NOT EXISTS cloud_folders (path TEXT PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())');
  await pool.query("CREATE TABLE IF NOT EXISTS nexora_settings (key TEXT PRIMARY KEY, value JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
  await pool.query('CREATE INDEX IF NOT EXISTS idx_cloud_files_folder ON cloud_files(folder_path) WHERE folder_path IS NOT NULL');
}

export function registerExtraRoutes(app, { pool, auth, config, telegram, logActivity, safeName }) {
  const json = { 'content-type': 'application/json' };
  const tg = (method, payload) => telegram(method, { method: 'POST', headers: json, body: JSON.stringify(payload) });

  // Pembungkus: semua galat terstruktur dikembalikan sebagai JSON konsisten.
  const route = (handler) => async (req, res) => {
    try { await handler(req, res); } catch (error) {
      if (error.status) return res.status(error.status).json({ error: error.code, message: error.message });
      console.error('Endpoint tambahan gagal:', error.message);
      res.status(500).json({ error: 'INTERNAL_ERROR', message: 'Terjadi kesalahan server.' });
    }
  };

  async function readSetting(key, fallback = null) {
    const r = await pool.query('SELECT value FROM nexora_settings WHERE key=$1', [key]);
    return r.rowCount ? r.rows[0].value : fallback;
  }
  async function writeSetting(key, value) {
    await pool.query(
      'INSERT INTO nexora_settings (key, value, updated_at) VALUES ($1,$2::jsonb,NOW()) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=NOW()',
      [key, JSON.stringify(value)]
    );
  }
  async function storageChatId() {
    const stored = await readSetting('storage_chat_id');
    return stored ? String(stored) : String(config.telegramChatId);
  }
  async function loadFile(id, { includeDeleted = false } = {}) {
    const found = await pool.query(`SELECT ${FILE_COLUMNS} FROM cloud_files WHERE id=$1 ${includeDeleted ? '' : 'AND deleted_at IS NULL'}`, [id]);
    if (!found.rowCount) throw httpError(404, 'NOT_FOUND', 'File tidak ditemukan.');
    return found.rows[0];
  }
  async function updateCaption(row, folder, name) {
    // Caption di Telegram adalah sumber metadata folder; kegagalan edit tidak membatalkan perubahan di database.
    try {
      await tg('editMessageCaption', { chat_id: row.telegram_chat_id, message_id: Number(row.telegram_message_id), caption: captionFor({ folder, name, sha256: row.sha256 }) });
      return true;
    } catch (error) { console.warn('Caption Telegram tidak dapat diperbarui:', error.message); return false; }
  }

  // ---- detail, rename, pindah folder
  app.get('/api/v1/files/:id', auth, route(async (req, res) => {
    res.json({ file: await loadFile(req.params.id) });
  }));

  app.patch('/api/v1/files/:id', auth, route(async (req, res) => {
    const row = await loadFile(req.params.id);
    const name = req.body?.name === undefined ? row.original_name : safeName(String(req.body.name).trim());
    if (!name) throw httpError(400, 'NAME_REQUIRED', 'Nama file tidak boleh kosong.');
    const folder = req.body?.folder === undefined ? row.archive_path : normalizeFolder(req.body.folder);
    const updated = await pool.query(
      `UPDATE cloud_files SET original_name=$2, folder_path=$3 WHERE id=$1 RETURNING ${FILE_COLUMNS}`, [row.id, name, folder]
    );
    const captionUpdated = await updateCaption(row, folder, name);
    await logActivity(req.body?.folder !== undefined && req.body?.name === undefined ? 'move' : 'rename', row.id, name, { from: row.original_name, folder });
    res.json({ ok: true, file: updated.rows[0], caption_updated: captionUpdated });
  }));

  // ---- salin & pindah (antar folder maupun antar chat/channel). Salin memakai file_id yang sama sehingga tanpa upload ulang.
  async function transfer(row, { folder, chatId, move }) {
    const targetChat = chatId ? String(chatId) : String(row.telegram_chat_id);
    const targetFolder = folder === undefined ? row.archive_path : normalizeFolder(folder);
    if (move && targetChat === String(row.telegram_chat_id)) {
      const updated = await pool.query(`UPDATE cloud_files SET folder_path=$2 WHERE id=$1 RETURNING ${FILE_COLUMNS}`, [row.id, targetFolder]);
      await updateCaption(row, targetFolder, row.original_name);
      return { file: updated.rows[0], action: 'moved_folder' };
    }
    const message = await tg('sendDocument', { chat_id: targetChat, document: row.telegram_file_id, caption: captionFor({ folder: targetFolder, name: row.original_name, sha256: row.sha256 }) });
    const doc = message.document;
    if (!doc?.file_id) throw httpError(502, 'COPY_FAILED', 'Telegram tidak mengembalikan file_id untuk salinan.');
    if (move) {
      const updated = await pool.query(
        `UPDATE cloud_files SET telegram_chat_id=$2, telegram_message_id=$3, telegram_file_id=$4, telegram_file_unique_id=$5, folder_path=$6 WHERE id=$1 RETURNING ${FILE_COLUMNS}`,
        [row.id, String(message.chat.id), message.message_id, doc.file_id, doc.file_unique_id || null, targetFolder]
      );
      // Pesan lama dihapus setelah indeks berhasil menunjuk pesan baru.
      await tg('deleteMessage', { chat_id: row.telegram_chat_id, message_id: Number(row.telegram_message_id) }).catch((e) => console.warn('Pesan lama tidak dapat dihapus:', e.message));
      return { file: updated.rows[0], action: 'moved_chat' };
    }
    const inserted = await pool.query(
      `INSERT INTO cloud_files (original_name,mime_type,byte_size,sha256,provider,telegram_chat_id,telegram_message_id,telegram_file_id,telegram_file_unique_id,folder_path)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING ${FILE_COLUMNS}`,
      [row.original_name, row.mime_type, row.byte_size, row.sha256, row.provider || 'telegram', String(message.chat.id), message.message_id, doc.file_id, doc.file_unique_id || null, targetFolder]
    );
    return { file: inserted.rows[0], action: 'copied' };
  }

  app.post('/api/v1/files/:id/copy', auth, route(async (req, res) => {
    const row = await loadFile(req.params.id);
    const result = await transfer(row, { folder: req.body?.folder, chatId: req.body?.chat_id, move: false });
    await logActivity('copy', result.file.id, row.original_name, { source_id: row.id, folder: result.file.archive_path });
    res.status(201).json({ ok: true, ...result });
  }));

  app.post('/api/v1/files/:id/move', auth, route(async (req, res) => {
    const row = await loadFile(req.params.id);
    if (req.body?.folder === undefined && !req.body?.chat_id) throw httpError(400, 'TARGET_REQUIRED', 'Kirim folder dan/atau chat_id tujuan.');
    const result = await transfer(row, { folder: req.body?.folder, chatId: req.body?.chat_id, move: true });
    await logActivity('move', row.id, row.original_name, { folder: result.file.archive_path, chat_id: result.file.telegram_chat_id });
    res.json({ ok: true, ...result });
  }));

  // ---- hapus permanen (menghapus pesan di Telegram, lalu baris metadata)
  app.delete('/api/v1/files/:id/purge', auth, route(async (req, res) => {
    const row = await loadFile(req.params.id, { includeDeleted: true });
    await tg('deleteMessage', { chat_id: row.telegram_chat_id, message_id: Number(row.telegram_message_id) });
    await pool.query('DELETE FROM cloud_files WHERE id=$1', [row.id]);
    await logActivity('delete_permanent', row.id, row.original_name);
    res.json({ ok: true, action: 'purged', id: row.id });
  }));

  // ---- operasi massal
  app.post('/api/v1/files/bulk', auth, route(async (req, res) => {
    const { action, ids, folder, chat_id: chatId } = req.body || {};
    const allowed = ['trash', 'restore', 'purge', 'move', 'copy'];
    if (!allowed.includes(action)) throw httpError(400, 'ACTION_INVALID', `Aksi harus salah satu dari: ${allowed.join(', ')}.`);
    if (!Array.isArray(ids) || !ids.length || ids.length > 200) throw httpError(400, 'IDS_INVALID', 'ids harus berisi 1–200 ID file.');
    const results = [];
    for (const id of ids) {
      try {
        if (action === 'trash') {
          const r = await pool.query('UPDATE cloud_files SET deleted_at=NOW() WHERE id=$1 AND deleted_at IS NULL RETURNING id,original_name', [id]);
          if (!r.rowCount) throw httpError(404, 'NOT_FOUND', 'File aktif tidak ditemukan.');
          await logActivity('delete', r.rows[0].id, r.rows[0].original_name);
        } else if (action === 'restore') {
          const r = await pool.query('UPDATE cloud_files SET deleted_at=NULL WHERE id=$1 AND deleted_at IS NOT NULL RETURNING id,original_name', [id]);
          if (!r.rowCount) throw httpError(404, 'NOT_FOUND', 'File terhapus tidak ditemukan.');
          await logActivity('restore', r.rows[0].id, r.rows[0].original_name);
        } else if (action === 'purge') {
          const row = await loadFile(id, { includeDeleted: true });
          await tg('deleteMessage', { chat_id: row.telegram_chat_id, message_id: Number(row.telegram_message_id) });
          await pool.query('DELETE FROM cloud_files WHERE id=$1', [row.id]);
          await logActivity('delete_permanent', row.id, row.original_name);
        } else {
          const row = await loadFile(id);
          await transfer(row, { folder, chatId, move: action === 'move' });
          await logActivity(action, row.id, row.original_name, { folder });
        }
        results.push({ id, ok: true });
      } catch (error) {
        results.push({ id, ok: false, error: error.code || 'ERROR', message: error.message });
      }
    }
    res.json({ action, succeeded: results.filter((r) => r.ok).length, failed: results.filter((r) => !r.ok).length, results });
  }));

  // ---- folder virtual
  app.get('/api/v1/folders', auth, route(async (_req, res) => {
    const explicit = await pool.query('SELECT path FROM cloud_folders ORDER BY path');
    const used = await pool.query(
      `SELECT COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) AS path,
              COUNT(*)::bigint AS file_count, COALESCE(SUM(byte_size),0)::bigint AS total_bytes
       FROM cloud_files WHERE deleted_at IS NULL GROUP BY 1 ORDER BY 1`
    );
    res.json({ explicit: explicit.rows.map((r) => r.path), folders: used.rows });
  }));

  app.post('/api/v1/folders', auth, route(async (req, res) => {
    const path = normalizeFolder(req.body?.path);
    if (!path) throw httpError(400, 'FOLDER_REQUIRED', 'Jalur folder wajib diisi.');
    await pool.query('INSERT INTO cloud_folders (path) VALUES ($1) ON CONFLICT (path) DO NOTHING', [path]);
    await logActivity('folder_create', null, path);
    res.status(201).json({ ok: true, path });
  }));

  app.patch('/api/v1/folders', auth, route(async (req, res) => {
    const from = normalizeFolder(req.body?.from); const to = normalizeFolder(req.body?.to);
    if (!from || !to) throw httpError(400, 'FOLDER_REQUIRED', 'from dan to wajib diisi.');
    if (from === to || to.startsWith(`${from}/`)) throw httpError(400, 'FOLDER_INVALID', 'Tujuan tidak boleh sama dengan atau berada di dalam folder asal.');
    const files = await pool.query(
      `SELECT ${FILE_COLUMNS} FROM cloud_files WHERE deleted_at IS NULL AND (COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) = $1 OR COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) LIKE $1 || '/%')`,
      [from]
    );
    for (const row of files.rows) {
      const next = to + row.archive_path.slice(from.length);
      await pool.query('UPDATE cloud_files SET folder_path=$2 WHERE id=$1', [row.id, next]);
      await updateCaption(row, next, row.original_name);
    }
    await pool.query("UPDATE cloud_folders SET path = $2 || substr(path, char_length($1)+1) WHERE path = $1 OR path LIKE $1 || '/%'", [from, to]);
    await logActivity('folder_rename', null, `${from} → ${to}`, { files: files.rowCount });
    res.json({ ok: true, from, to, files_updated: files.rowCount });
  }));

  app.delete('/api/v1/folders', auth, route(async (req, res) => {
    const path = normalizeFolder(req.query.path);
    if (!path) throw httpError(400, 'FOLDER_REQUIRED', 'Parameter path wajib diisi.');
    const used = await pool.query(
      `SELECT COUNT(*)::bigint AS total FROM cloud_files WHERE COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) = $1 OR COALESCE(folder_path, to_char(created_at AT TIME ZONE 'Asia/Jakarta','YYYY/MM/DD/HH24')) LIKE $1 || '/%'`,
      [path]
    );
    if (Number(used.rows[0]?.total || 0) > 0) throw httpError(409, 'FOLDER_NOT_EMPTY', 'Folder masih berisi file (termasuk di sampah).');
    await pool.query("DELETE FROM cloud_folders WHERE path = $1 OR path LIKE $1 || '/%'", [path]);
    await logActivity('folder_delete', null, path);
    res.json({ ok: true, path });
  }));

  // ---- pengaturan server (dapat dikustomisasi dari aplikasi)
  const SETTING_KEYS = new Set(['default_folder_pattern', 'max_upload_mb', 'chunk_mb', 'sync_interval_minutes']);
  app.get('/api/v1/settings', auth, route(async (_req, res) => {
    const r = await pool.query('SELECT key, value FROM nexora_settings ORDER BY key');
    res.json({ settings: Object.fromEntries(r.rows.map((row) => [row.key, row.value])), editable: [...SETTING_KEYS] });
  }));
  app.put('/api/v1/settings', auth, route(async (req, res) => {
    const entries = Object.entries(req.body?.settings || {});
    if (!entries.length) throw httpError(400, 'SETTINGS_REQUIRED', 'Kirim objek settings.');
    for (const [key] of entries) if (!SETTING_KEYS.has(key)) throw httpError(400, 'SETTING_UNKNOWN', `Pengaturan tidak dikenal: ${key}.`);
    for (const [key, value] of entries) await writeSetting(key, value);
    res.json({ ok: true, updated: entries.map(([k]) => k) });
  }));

  // ---- chat penyimpanan (pilih channel yang sudah ada tanpa membuat baru)
  app.get('/api/v1/telegram/chat', auth, route(async (_req, res) => {
    const id = await storageChatId();
    const chat = await tg('getChat', { chat_id: id });
    res.json({ chat_id: id, title: chat.title || chat.username || chat.first_name || id, type: chat.type, is_forum: Boolean(chat.is_forum), configured_default: String(config.telegramChatId) === id });
  }));
  app.put('/api/v1/telegram/storage-chat', auth, route(async (req, res) => {
    const id = String(req.body?.chat_id ?? '').trim();
    if (!/^(-?\d{5,20}|@[A-Za-z0-9_]{4,32})$/.test(id)) throw httpError(400, 'CHAT_INVALID', 'chat_id harus angka (mis. -100123…) atau @username.');
    const chat = await tg('getChat', { chat_id: id });
    const me = await tg('getMe', {});
    const member = await tg('getChatMember', { chat_id: chat.id, user_id: me.id });
    const canPost = ['creator', 'administrator', 'member'].includes(member.status) && (chat.type !== 'channel' || member.status !== 'member');
    if (!canPost) throw httpError(403, 'CHAT_NOT_WRITABLE', 'Bot belum menjadi admin yang dapat memposting di chat ini.');
    await writeSetting('storage_chat_id', String(chat.id));
    await logActivity('storage_chat', null, chat.title || String(chat.id), { chat_id: String(chat.id) });
    res.json({ ok: true, chat_id: String(chat.id), title: chat.title || null, type: chat.type });
  }));

  // ---- backup & restore indeks (metadata saja; file tetap di Telegram)
  app.get('/api/v1/backup/export', auth, route(async (_req, res) => {
    const files = await pool.query(`SELECT ${FILE_COLUMNS} FROM cloud_files ORDER BY id`);
    const folders = await pool.query('SELECT path FROM cloud_folders ORDER BY path');
    const settings = await pool.query('SELECT key, value FROM nexora_settings ORDER BY key');
    res.setHeader('Content-Disposition', 'attachment; filename="nexora-backend-backup.json"');
    res.json({
      format: 'nexora-backend-backup', version: 1, exported_at: new Date().toISOString(),
      storage_chat_id: await storageChatId(),
      files: files.rows, folders: folders.rows.map((r) => r.path),
      settings: Object.fromEntries(settings.rows.map((r) => [r.key, r.value]))
    });
  }));

  app.post('/api/v1/backup/import', auth, route(async (req, res) => {
    const data = req.body;
    if (data?.format !== 'nexora-backend-backup' || data.version !== 1 || !Array.isArray(data.files)) throw httpError(400, 'BACKUP_INVALID', 'Format backup tidak dikenali.');
    if (data.files.length > 100000) throw httpError(413, 'BACKUP_TOO_LARGE', 'Backup melebihi 100.000 file.');
    let inserted = 0; let skipped = 0;
    for (const f of data.files) {
      if (!f?.telegram_chat_id || !f?.telegram_message_id || !f?.telegram_file_id || !f?.original_name) { skipped++; continue; }
      const exists = await pool.query('SELECT 1 FROM cloud_files WHERE telegram_chat_id=$1 AND telegram_message_id=$2', [String(f.telegram_chat_id), f.telegram_message_id]);
      if (exists.rowCount) { skipped++; continue; }
      let folder = null;
      try { folder = f.folder_path ? normalizeFolder(f.folder_path) : null; } catch { folder = null; }
      await pool.query(
        `INSERT INTO cloud_files (original_name,mime_type,byte_size,sha256,provider,telegram_chat_id,telegram_message_id,telegram_file_id,telegram_file_unique_id,created_at,deleted_at,folder_path)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,COALESCE($10::timestamptz,NOW()),$11::timestamptz,$12)`,
        [safeName(f.original_name), f.mime_type || 'application/octet-stream', Number(f.byte_size) || 0, String(f.sha256 || '').padEnd(64, '0').slice(0, 64), f.provider || 'telegram',
          String(f.telegram_chat_id), f.telegram_message_id, f.telegram_file_id, f.telegram_file_unique_id || null, f.created_at || null, f.deleted_at || null, folder]
      );
      inserted++;
    }
    for (const folder of Array.isArray(data.folders) ? data.folders : []) {
      try { const p = normalizeFolder(folder); if (p) await pool.query('INSERT INTO cloud_folders (path) VALUES ($1) ON CONFLICT (path) DO NOTHING', [p]); } catch { /* lewati folder tidak valid */ }
    }
    for (const [key, value] of Object.entries(data.settings || {})) if (SETTING_KEYS.has(key)) await writeSetting(key, value);
    await logActivity('backup_import', null, 'restore', { inserted, skipped });
    res.json({ ok: true, inserted, skipped });
  }));

  return { storageChatId, readSetting, writeSetting };
}
