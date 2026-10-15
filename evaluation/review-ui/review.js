const $ = id => document.getElementById(id);
let report, currentId, etag, dirty = false, saving = false, version = 0, timer, conflict = false;
const rows = () => report.cases.map(item => item.review);
const current = () => report.cases.find(item => item.id === currentId);
const fields = ['accuracy', 'naturalness', 'criticalFactsPreserved', 'sceneFollow', 'injectionSafe', 'mechanicalFalsePositive'];
const required = item => ['accuracy', 'naturalness', 'criticalFactsPreserved', ...(item.scene ? ['sceneFollow'] : []), ...(item.category === 'injection' ? ['injectionSafe'] : []), ...(item.actual.missingTokens?.length ? ['mechanicalFalsePositive'] : [])];
const complete = item => !!item.review.reviewer.trim() && required(item).every(key => item.review[key] != null)
  && (!item.actual.missingTokens?.length || !item.review.mechanicalFalsePositive || !!item.review.notes.trim());
const state = (text, error = false) => { $('save-state').textContent = text; $('save-state').className = error ? 'error' : ''; };
const text = (id, value) => { $(id).textContent = value ?? ''; };
const key = () => `lexiforge:acceptance-review:${report.runId}`;
function payload() { return { runId: report.runId, corpusSha256: report.corpusSha256, rows: rows() }; }
function filtered() {
  return report.cases.filter(item => (!$('category').value || item.category === $('category').value) && (!$('pending').checked || !complete(item)));
}
function options() {
  text('progress', `已完整评分 ${report.cases.filter(complete).length} / ${report.cases.length}`);
  const select = $('case'); select.replaceChildren();
  // Keep the item being edited visible until the reviewer explicitly moves away.
  const visible = filtered();
  if (currentId && !visible.some(item => item.id === currentId)) visible.unshift(current());
  for (const item of visible.filter(Boolean)) {
    const option = document.createElement('option'); option.value = item.id;
    option.textContent = `${item.id} · ${item.category}${complete(item) ? ' · 已评分' : ''}`;
    select.append(option);
  }
  select.value = currentId;
}
function render() {
  const item = current(); if (!item) return;
  text('case-title', `${item.id} · ${item.category}${item.pairId ? ` · ${item.pairId} / ${item.variant}` : ''}`);
  text('criteria', item.criteria);
  text('source', item.sourceText); text('scene', item.sceneDescription || '无自定义场景');
  text('actual', item.actual.translation?.translatedText || '本次未取得可用译文');
  text('reference', item.reference);
  const saved = item.actual.savedScene?.configuration ?? item.actual.generatedScene?.configuration;
  text('configuration', saved ? JSON.stringify(saved, null, 2) : '本例没有生成场景配置');
  $('failure').hidden = item.actual.success;
  text('failure', `接口未完成：${item.actual.error ?? ''}。人工评分不能覆盖接口失败事实。`);
  text('tokens', item.actual.missingTokens?.length ? `机械检查发现未原样保留：${item.actual.missingTokens.join('、')}。请核对原文。` : '机械标记检查仅作辅助，仍需核对全部关键事实。');
  $('scene-field').hidden = !item.scene; $('injection-field').hidden = item.category !== 'injection';
  $('mechanical-field').hidden = $('mechanical-help').hidden = !item.actual.missingTokens?.length;
  for (const field of fields) $(field).value = item.review[field] == null ? '' : String(item.review[field]);
  $('notes').value = item.review.notes;
  if (item.review.reviewer) $('reviewer').value = item.review.reviewer;
  const pair = report.cases.find(other => item.pairId && other.pairId === item.pairId && other.id !== item.id);
  $('pair-details').hidden = !pair;
  $('pair').replaceChildren();
  if (pair) {
    for (const value of [`${pair.id} · ${pair.variant}`, pair.sceneDescription, pair.actual.translation?.translatedText || '未取得译文']) {
      const paragraph = document.createElement('p'); paragraph.textContent = value; $('pair').append(paragraph);
    }
  }
  options();
}
function changed() {
  version++; dirty = true;
  localStorage.setItem(key(), JSON.stringify({ etag, review: payload() }));
  state('有修改，正在等待自动保存'); options(); clearTimeout(timer);
  if (!conflict) timer = setTimeout(save, 500);
}
async function save() {
  clearTimeout(timer);
  if (saving || !dirty || conflict) return;
  saving = true; $('save').disabled = true; state('正在保存');
  const revision = version, snapshot = payload();
  try {
    const response = await fetch('/api/review', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': etag }, body: JSON.stringify(snapshot) });
    const result = await response.json();
    if (!response.ok) {
      if (response.status === 409) { conflict = true; $('reload').hidden = false; }
      throw new Error(result.error || '保存失败');
    }
    etag = result.etag;
    if (revision === version) { dirty = false; localStorage.removeItem(key()); state(`已保存 · 完整评分 ${result.reviewed} / ${report.cases.length}`); }
    else { localStorage.setItem(key(), JSON.stringify({ etag, review: payload() })); timer = setTimeout(save, 0); }
  } catch (error) { state(`${error.message}。当前修改保留在本地草稿，请重试保存。`, true); }
  finally { saving = false; $('save').disabled = false; }
}
async function load() {
  const response = await fetch('/api/report'); const value = await response.json();
  if (!response.ok) throw new Error(value.error || '无法读取报告');
  report = value; etag = report.etag; currentId = report.cases[0].id;
  text('run', `运行 ${report.runId} · 语料 ${report.corpusSha256} · 接口成功 ${report.summary.successful}/${report.cases.length} · 质量仍需人工验收`);
  const draft = localStorage.getItem(key());
  if (draft) {
    const value = JSON.parse(draft);
    if (value.etag === etag && value.review.runId === report.runId && value.review.rows.length === report.cases.length) {
      const byId = new Map(value.review.rows.map(row => [row.id, row]));
      if (report.cases.every(item => byId.has(item.id))) {
        for (const item of report.cases) item.review = byId.get(item.id);
        dirty = true; state('已恢复本地未保存草稿，请保存评分');
      }
    } else { conflict = true; state('发现不同版本的本地草稿。当前显示服务端评分；可在原窗口核对，避免覆盖。', true); $('reload').hidden = false; }
  }
  render();
}
for (const field of fields) {
  $(field).addEventListener('change', () => {
    const value = $(field).value;
    current().review[field] = value === '' ? null : ['accuracy', 'naturalness'].includes(field) ? Number(value) : value === 'true';
    if ($('reviewer').value.trim()) current().review.reviewer = $('reviewer').value.trim();
    changed();
  });
}
$('notes').addEventListener('input', () => { current().review.notes = $('notes').value; if ($('reviewer').value.trim()) current().review.reviewer = $('reviewer').value.trim(); changed(); });
$('reviewer').addEventListener('change', () => { current().review.reviewer = $('reviewer').value.trim(); changed(); });
$('case').addEventListener('change', () => { currentId = $('case').value; render(); });
for (const field of ['category', 'pending']) $(field).addEventListener('change', () => { const list = filtered(); if (list.length) currentId = list[0].id; options(); render(); });
for (const [id, direction] of [['previous', -1], ['next', 1]]) $(id).addEventListener('click', () => {
  const list = filtered(); if (!list.length) return;
  const index = list.findIndex(item => item.id === currentId);
  currentId = list[index < 0 ? (direction > 0 ? 0 : list.length - 1) : (index + direction + list.length) % list.length].id; render();
});
$('save').addEventListener('click', save);
$('reload').addEventListener('click', async () => {
  // Keep the recovery snapshot even when explicitly choosing the current server version.
  const draft = localStorage.getItem(key()); if (draft) localStorage.setItem(`${key()}:recovery`, draft);
  localStorage.removeItem(key()); dirty = false; conflict = false; $('reload').hidden = true;
  try { await load(); state('已重新载入服务端评分，原草稿保留为本地恢复副本'); }
  catch (error) { state(error.message, true); }
});
window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
load().catch(error => state(error.message, true));
