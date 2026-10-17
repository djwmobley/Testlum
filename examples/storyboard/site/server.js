const http = require('http');
const { Pool } = require('pg');

const pool = new Pool({ connectionString: process.env.DATABASE_URL || 'postgres://storyboard:storyboard@localhost:5432/storyboard' });
const STATUSES = ['todo', 'doing', 'done'];
const COLUMNS = 'id, title, status, points, assignee';

const page = `<!doctype html>
<title>Storyboard</title>
<h1>Storyboard</h1>
<table border="1" cellpadding="4"><thead><tr><th>#</th><th>Title</th><th>Status</th><th>Points</th><th>Owner</th></tr></thead><tbody id="rows"></tbody></table>
<form id="add"><input name="title" placeholder="New story" required> <input name="points" type="number" min="1" max="13" value="1"> <button>Add</button></form>
<script>
async function load() {
  const stories = await (await fetch('/api/stories')).json();
  document.getElementById('rows').innerHTML = stories.map(s =>
    '<tr><td>' + s.id + '</td><td>' + s.title.replace(/</g, '&lt;') + '</td><td>' + s.status + '</td><td>' + s.points + '</td><td>' + (s.assignee || '') + '</td></tr>').join('');
}
document.getElementById('add').onsubmit = async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  await fetch('/api/stories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title: f.get('title'), points: Number(f.get('points')) }) });
  e.target.reset();
  load();
};
load();
</script>`;

function send(res, code, body) {
  if (body === undefined) {
    res.writeHead(code).end();
    return;
  }
  res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', c => (raw += c));
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (e) {
        reject(new Error('body is not valid JSON'));
      }
    });
  });
}

async function route(req, res) {
  const url = new URL(req.url, 'http://x');
  const m = url.pathname.match(/^\/api\/stories(?:\/(\d+))?$/);

  if (req.method === 'GET' && url.pathname === '/') {
    return res.writeHead(200, { 'Content-Type': 'text/html' }).end(page);
  }
  if (req.method === 'GET' && url.pathname === '/api/health') {
    await pool.query('select 1');
    return send(res, 200, { status: 'ok' });
  }
  if (!m) return send(res, 404, { error: 'Not found' });
  const id = m[1] && Number(m[1]);

  if (req.method === 'GET' && !id) {
    const status = url.searchParams.get('status');
    if (status && !STATUSES.includes(status)) return send(res, 400, { error: 'status must be todo, doing or done' });
    const r = await pool.query(`select ${COLUMNS} from stories ${status ? 'where status = $1' : ''} order by id`, status ? [status] : []);
    return send(res, 200, r.rows);
  }
  if (req.method === 'GET') {
    const r = await pool.query(`select ${COLUMNS} from stories where id = $1`, [id]);
    return r.rows[0] ? send(res, 200, r.rows[0]) : send(res, 404, { error: 'Story not found' });
  }
  if (req.method === 'POST' && !id) {
    const b = await readJson(req);
    if (typeof b.title !== 'string' || !b.title.trim()) return send(res, 400, { error: 'title is required' });
    const points = b.points === undefined ? 1 : b.points;
    if (!Number.isInteger(points) || points < 1 || points > 13) return send(res, 400, { error: 'points must be between 1 and 13' });
    const r = await pool.query(`insert into stories (title, points, assignee) values ($1, $2, $3) returning ${COLUMNS}`, [b.title.trim(), points, b.assignee || null]);
    return send(res, 201, r.rows[0]);
  }
  if (req.method === 'PATCH' && id) {
    const b = await readJson(req);
    if (!STATUSES.includes(b.status)) return send(res, 400, { error: 'status must be todo, doing or done' });
    const r = await pool.query(`update stories set status = $2 where id = $1 returning ${COLUMNS}`, [id, b.status]);
    return r.rows[0] ? send(res, 200, r.rows[0]) : send(res, 404, { error: 'Story not found' });
  }
  if (req.method === 'DELETE' && id) {
    const r = await pool.query('delete from stories where id = $1', [id]);
    return r.rowCount ? send(res, 204) : send(res, 404, { error: 'Story not found' });
  }
  return send(res, 405, { error: 'Method not allowed' });
}

http.createServer((req, res) => {
  route(req, res).catch(e => send(res, e.message === 'body is not valid JSON' ? 400 : 500, { error: e.message }));
}).listen(process.env.PORT || 8080, () => console.log('storyboard on', process.env.PORT || 8080));
