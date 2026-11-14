package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.SavedStreamFilters

/**
 * The phone page of [SavedFiltersConfigServer] (sandbox `spec-65-stream-filters` stage 9, the house
 * web-page style): one row per saved filter with a drag handle (reorder), Rename (the name edited
 * in place, Delete beside Done while it is), then Save to TV, which posts the whole list; the
 * answer is the list as the TV stored it. Values are set only on the TV (D14), so they show as a
 * line of words. Inline only (the server's CSP allows nothing else); every text, the saved names
 * included, is set through textContent, so none is parsed as HTML.
 */
object SavedFiltersWebPage {
    fun html(appContext: Context): String {
        val context = appContext.withAppLocale()
        fun text(id: Int): String = context.getString(id).jsEscaped()
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title></title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  [hidden] { display: none !important; }
  html, body { background: #000; color: #fff; min-height: 100%; }
  body { font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.5; }
  .hd { text-align: center; padding: 36px 0 20px; border-bottom: 1px solid rgba(255,255,255,.06); }
  .hd b { display: block; font-size: 24px; line-height: 28px; font-weight: 800; letter-spacing: 4px; text-transform: uppercase; }
  .hd p { font-size: 14px; font-weight: 300; color: rgba(255,255,255,.42); margin-top: 4px; }
  .pg { max-width: 600px; margin: 0 auto; padding: 20px 20px 48px; }
  h1 { font-size: 17px; line-height: 22px; font-weight: 700; margin-bottom: 3px; }
  .sub { font-size: 14px; line-height: 20px; font-weight: 300; color: rgba(255,255,255,.44); margin-bottom: 16px; }
  .rw { display: flex; gap: 10px; align-items: center; border: 1px solid rgba(255,255,255,.12); border-radius: 16px; padding: 12px 12px 12px 4px; background: rgba(255,255,255,.02); margin-bottom: 10px; }
  .rw.ed { border-color: rgba(255,255,255,.42); }
  .rw.drag { border-color: #fff; background: rgba(255,255,255,.08); }
  .hdl { flex: 0 0 auto; width: 36px; height: 44px; display: flex; align-items: center; justify-content: center; color: rgba(255,255,255,.35); font-size: 17px; font-weight: 700; letter-spacing: -7px; padding-right: 7px; touch-action: none; cursor: grab; user-select: none; -webkit-user-select: none; }
  .tx { flex: 1; min-width: 0; }
  .tx b { display: block; font-size: 16px; line-height: 21px; font-weight: 600; overflow-wrap: anywhere; }
  .tx span { display: block; font-size: 13px; line-height: 18px; color: rgba(255,255,255,.48); overflow-wrap: anywhere; }
  .in { width: 100%; background: transparent; border: 1px solid rgba(255,255,255,.42); border-radius: 10px; padding: 5px 9px; margin-bottom: 4px; color: #fff; font: inherit; font-size: 16px; line-height: 21px; font-weight: 500; outline: none; }
  .in:focus { border-color: #fff; }
  .mb { flex: 0 0 auto; background: transparent; border: 1px solid rgba(255,255,255,.2); border-radius: 100px; padding: 6px 12px; color: #fff; font: inherit; font-size: 13px; line-height: 17px; font-weight: 500; }
  .mb.del { color: rgba(207,102,121,.95); border-color: rgba(207,102,121,.5); }
  .bt { display: block; width: 100%; background: transparent; border: 1px solid rgba(255,255,255,.2); border-radius: 100px; padding: 13px; margin-top: 8px; color: #fff; font: inherit; font-size: 15px; line-height: 20px; font-weight: 500; }
  .bt:disabled { opacity: .6; }
  .st { min-height: 20px; font-size: 14px; line-height: 20px; font-weight: 300; text-align: center; margin-top: 14px; color: rgba(135,239,172,.95); }
  .st.bad { color: rgba(207,102,121,.95); }
</style>
</head>
<body>
<div class="hd"><b data-t="app"></b><p data-t="title"></p></div>
<main class="pg">
  <h1 data-t="title"></h1>
  <p class="sub" id="intro"></p>
  <div id="list"></div>
  <button id="save" type="button" class="bt" data-t="save" hidden></button>
  <p id="status" class="st" role="status"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(R.string.web_filters_title)}',
  intro: '${text(R.string.web_filters_intro)}',
  empty: '${text(R.string.web_filters_empty)}',
  rename: '${text(R.string.web_filters_rename)}',
  done: '${text(R.string.web_filters_done)}',
  remove: '${text(R.string.action_delete)}',
  reorder: '${text(R.string.web_filters_reorder)}',
  name: '${text(R.string.web_filters_name)}',
  save: '${text(R.string.web_filters_save)}',
  saving: '${text(R.string.web_filters_saving)}',
  saved: '${text(R.string.web_filters_saved)}',
  saveError: '${text(R.string.web_filters_error_save)}',
  loadError: '${text(R.string.web_filters_error_load)}'
};
const MAX_NAME = ${SavedStreamFilters.MAX_NAME_LENGTH}, SEND_TIMEOUT_MS = 15000;
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const list = byId('list'), saveButton = byId('save');
// rows: what the TV holds, as edited here; deleted: ids removed here; editing: the id being renamed.
let rows = [], deleted = [], editing = null;
// While a save is on its way the list is not changed here: the TV's answer replaces it.
let saving = false;

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function button(className, text, onClick) {
  const node = el('button', className, text);
  node.type = 'button';
  node.addEventListener('click', onClick);
  return node;
}
function setStatus(text, bad) {
  const status = byId('status');
  status.textContent = text || '';
  status.className = bad ? 'st bad' : 'st';
}
function timeout() { return window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined; }
// The TV answers with an array of {id, name, summary}; anything else is not a list.
function asRows(body) {
  if (!Array.isArray(body)) throw new Error('not a list');
  return body.map((row) => ({ id: String(row.id), name: String(row.name), summary: String(row.summary || '') }));
}

function commitEdit() {
  if (editing === null) return;
  const row = rows.find((r) => r.id === editing);
  const field = list.querySelector('.in');
  // A name left empty keeps the one it had.
  const name = field ? field.value.replace(/\s+/g, ' ').trim().slice(0, MAX_NAME) : '';
  if (row && name) row.name = name;
  editing = null;
}

function render() {
  byId('intro').textContent = rows.length ? T.intro : T.empty;
  // Shown while there is something to send: rows, or deletions not sent yet.
  saveButton.hidden = !rows.length && !deleted.length;
  list.replaceChildren(...rows.map((row) => {
    const isEditing = row.id === editing;
    const node = el('div', isEditing ? 'rw ed' : 'rw');
    node.dataset.id = row.id;
    const handle = el('span', 'hdl', '\u22EE\u22EE');
    handle.setAttribute('role', 'img');
    handle.setAttribute('aria-label', T.reorder);
    handle.addEventListener('pointerdown', (event) => startDrag(event, node));
    const text = el('div', 'tx');
    node.append(handle, text);
    if (isEditing) {
      const field = el('input', 'in');
      field.type = 'text';
      field.maxLength = MAX_NAME;
      field.value = row.name;
      field.setAttribute('aria-label', T.name);
      field.autocomplete = 'off';
      field.enterKeyHint = 'done';
      field.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') { event.preventDefault(); commitEdit(); render(); }
      });
      text.append(field, el('span', '', row.summary));
      node.append(
        button('mb del', T.remove, () => {
          if (saving) return;
          editing = null;
          rows = rows.filter((r) => r.id !== row.id);
          deleted.push(row.id);
          render();
        }),
        button('mb', T.done, () => { commitEdit(); render(); })
      );
    } else {
      text.append(el('b', '', row.name), el('span', '', row.summary));
      node.append(button('mb', T.rename, () => {
        if (saving) return;
        commitEdit();
        editing = row.id;
        setStatus('');
        render();
        const field = list.querySelector('.in');
        if (field) { field.focus(); field.select(); }
      }));
    }
    return node;
  }));
}

// Drag by the handle: the row moves to where the finger is, among the other rows. The moves are
// followed on the document, since re-inserting the row drops the handle's own pointer capture.
function startDrag(event, node) {
  event.preventDefault();
  if (saving) return;
  commitEdit();
  node.classList.add('drag');
  const pointer = event.pointerId;
  const move = (e) => {
    if (e.pointerId !== pointer) return;
    const others = Array.from(list.children).filter((row) => row !== node);
    const next = others.find((row) => {
      const box = row.getBoundingClientRect();
      return e.clientY < box.top + box.height / 2;
    }) || null;
    if (node.nextElementSibling !== next) list.insertBefore(node, next);
  };
  const end = (e) => {
    if (e.pointerId !== pointer) return;
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', end);
    document.removeEventListener('pointercancel', end);
    const order = Array.from(list.children).map((row) => row.dataset.id);
    rows.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
    setStatus('');
    render();
  };
  document.addEventListener('pointermove', move);
  document.addEventListener('pointerup', end);
  document.addEventListener('pointercancel', end);
}

saveButton.addEventListener('click', async () => {
  commitEdit();
  render();
  setStatus('');
  saving = true;
  saveButton.disabled = true;
  saveButton.textContent = T.saving;
  try {
    const response = await fetch(base + 'save', {
      method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, cache: 'no-store',
      body: JSON.stringify({ order: rows.map((row) => ({ id: row.id, name: row.name })), deleted: deleted }),
      signal: timeout()
    });
    if (!response.ok) throw new Error('not saved');
    rows = asRows(await response.json());
    deleted = [];
    render();
    setStatus(T.saved);
  } catch (e) {
    // The QR closed on the TV, the TV left the Wi-Fi, or the TV could not store it.
    setStatus(T.saveError, true);
  } finally {
    saving = false;
    saveButton.disabled = false;
    saveButton.textContent = T.save;
  }
});

(async () => {
  try {
    const response = await fetch(base + 'filters', { cache: 'no-store', signal: timeout() });
    if (!response.ok) throw new Error('not loaded');
    rows = asRows(await response.json());
    render();
  } catch (e) {
    setStatus(T.loadError, true);
  }
})();
</script>
</body>
</html>
""".trimIndent()
    }
}
