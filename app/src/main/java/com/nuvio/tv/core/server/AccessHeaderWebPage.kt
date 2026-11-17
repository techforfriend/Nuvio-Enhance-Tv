package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.access.AccessSiteRules

/**
 * The phone page of [AccessHeaderServer] (Spec 97 A5, sandbox `s097-x4-x5-network` stage 3; the
 * TMDB key page's type and controls). Site address, the Cloudflare Access button (fills the two
 * header names, D5), then name + value per header, up to [AccessSiteRules.MAX_HEADERS] ("+ Add a
 * header" hides at the cap); values are password fields, masked as typed. Send to TV posts the
 * filled pairs (a pair left blank is skipped) and shows the TV's answer: Saved on the TV, the
 * https frame for an http address (D6), a line under the field at fault, or a line under the button
 * (D15's unreadable list, a full TV, a failed save, an expired code).
 *
 * Inline only (the server's CSP allows nothing else); every text is an app string set through
 * textContent from one escaped JS object, and the server's answer is read only as a status, a
 * problem name and numbers, so nothing is parsed as HTML. The page never holds a saved value: the
 * TV sends none back.
 */
object AccessHeaderWebPage {
    fun html(appContext: Context): String {
        val context = appContext.withAppLocale()
        fun text(id: Int, vararg args: Any): String = context.getString(id, *args).jsEscaped()
        fun jsList(items: List<String>): String = items.joinToString(", ", "[", "]") { "'${it.jsEscaped()}'" }
        val headerLabels = (1..AccessSiteRules.MAX_HEADERS).map { context.getString(R.string.web_access_header_label, it) }
        // The saved line per header count, split around the host so the page sets the host as text.
        val savedParts = (1..AccessSiteRules.MAX_HEADERS).map { count ->
            val line = context.resources.getQuantityString(R.plurals.web_access_saved_body, count, HOST_MARK, count)
            line.substringBefore(HOST_MARK) to line.substringAfter(HOST_MARK, "")
        }
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
  body { font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
  .pg { max-width: 480px; min-height: 100vh; min-height: 100dvh; margin: 0 auto; padding: 20px 18px 24px; display: flex; flex-direction: column; gap: 14px; }
  .brand { font-size: 15px; line-height: 20px; font-weight: 700; color: #fff; }
  section { flex: 1; display: flex; flex-direction: column; gap: 12px; }
  h1 { font-size: 26px; line-height: 32px; font-weight: 700; letter-spacing: -0.3px; }
  .sub { font-size: 14px; line-height: 20px; color: rgba(255,255,255,.5); }
  .c { text-align: center; }
  #intro, #https { display: flex; flex-direction: column; gap: 10px; }
  #form { display: flex; flex-direction: column; gap: 12px; }
  .lbl { font-size: 12px; line-height: 16px; font-weight: 600; letter-spacing: .4px; text-transform: uppercase; color: rgba(255,255,255,.5); margin-top: 4px; }
  .pair { display: flex; flex-direction: column; gap: 8px; }
  .fld { width: 100%; height: 50px; border: 0; border-radius: 16px; background: rgba(255,255,255,.08); box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.35); color: #fff; font: inherit; font-size: 15px; font-weight: 500; padding: 0 14px; outline: none; }
  .fld:focus { box-shadow: inset 0 0 0 1.5px #fff; }
  .fld.bad { box-shadow: inset 0 0 0 1.5px #FF8A80; }
  .fld::placeholder { color: rgba(255,255,255,.4); }
  .fe { font-size: 13px; line-height: 18px; color: #FF8A80; }
  .chip { align-self: flex-start; height: 36px; border: 0; border-radius: 18px; padding: 0 16px; background: rgba(255,255,255,.1); color: #fff; font: inherit; font-size: 14px; font-weight: 600; }
  .chip.on { background: #F38020; color: #000; }
  .lnk { align-self: flex-start; border: 0; background: none; font: inherit; font-size: 14px; line-height: 20px; font-weight: 600; color: #9EC3D8; padding: 2px 0; }
  .conn { display: flex; align-items: center; gap: 8px; font-size: 13px; color: rgba(255,255,255,.5); }
  .conn i { width: 8px; height: 8px; border-radius: 50%; background: #4CAF50; }
  .actions { margin-top: auto; display: flex; flex-direction: column; gap: 10px; padding-top: 6px; }
  .btn { height: 52px; border: 0; border-radius: 26px; background: #fff; color: #000; font: inherit; font-size: 16px; font-weight: 700; }
  .btn.gh { background: rgba(255,255,255,.1); color: #fff; }
  .btn:disabled { opacity: .6; }
  .res { width: 88px; height: 88px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 32px auto 6px; }
  .res svg { width: 46px; height: 46px; }
  .res.ok { background: rgba(76,175,80,.16); }
  .res.ok svg { fill: #7BD27F; }
  .res.bad { background: rgba(255,138,128,.14); margin-top: 8px; }
  .res.bad svg { fill: #FF8A80; }
  #saved .res { margin-top: 48px; }
  .err { min-height: 20px; font-size: 14px; line-height: 20px; color: #FF8A80; text-align: center; }
</style>
</head>
<body>
<main class="pg">
  <div class="brand" data-t="app"></div>
  <section id="enter">
    <div id="intro">
      <h1 data-t="title"></h1>
      <p class="sub" data-t="sub"></p>
    </div>
    <div id="https" hidden>
      <div class="res bad"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"/></svg></div>
      <h1 class="c" data-t="httpsTitle"></h1>
      <p class="sub c" data-t="httpsBody"></p>
    </div>
    <div class="lbl" id="siteLabel" data-t="site"></div>
    <input id="site" class="fld" type="url" inputmode="url" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="https://">
    <p id="siteErr" class="fe" hidden></p>
    <div id="form">
      <button id="cf" type="button" class="chip" data-t="cloudflare"></button>
      <div id="pairs" class="pair"></div>
      <button id="add" type="button" class="lnk" data-t="addHeader"></button>
    </div>
    <div class="actions">
      <div id="conn" class="conn"><i></i><span data-t="connected"></span></div>
      <button id="send" type="button" class="btn" data-t="send"></button>
    </div>
  </section>
  <section id="saved" hidden>
    <div class="res ok"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg></div>
    <h1 class="c" data-t="savedTitle"></h1>
    <p class="sub c"><span id="savedBefore"></span><span id="savedHost"></span><span id="savedAfter"></span></p>
    <div class="actions"><button id="another" type="button" class="btn gh" data-t="another"></button></div>
  </section>
  <p id="err" class="err" role="alert"></p>
</main>
<script>
'use strict';
const T = {
  app: '${text(R.string.app_name)}',
  title: '${text(R.string.web_access_title)}',
  sub: '${text(R.string.web_access_sub)}',
  site: '${text(R.string.web_access_site)}',
  cloudflare: '${text(R.string.web_access_cloudflare)}',
  name: '${text(R.string.web_access_name)}',
  value: '${text(R.string.web_access_value)}',
  addHeader: '${text(R.string.web_access_add_header)}',
  connected: '${text(R.string.web_photo_connected)}',
  send: '${text(R.string.web_photo_send)}',
  sendAgain: '${text(R.string.web_tmdb_send_again)}',
  savedTitle: '${text(R.string.web_tmdb_saved_title)}',
  another: '${text(R.string.web_access_add_another)}',
  httpsTitle: '${text(R.string.web_access_https_title)}',
  httpsBody: '${text(R.string.web_access_https_body)}',
  NOT_A_URL: '${text(R.string.web_access_error_address)}',
  HAS_USERINFO: '${text(R.string.web_access_error_userinfo)}',
  NO_HEADERS: '${text(R.string.web_access_error_no_headers)}',
  TOO_MANY_HEADERS: '${text(R.string.web_access_error_too_many_headers, AccessSiteRules.MAX_HEADERS)}',
  BAD_NAME: '${text(R.string.web_access_error_name)}',
  RESERVED_NAME: '${text(R.string.web_access_error_reserved)}',
  DUPLICATE_NAME: '${text(R.string.web_access_error_duplicate)}',
  BAD_VALUE: '${text(R.string.web_access_error_value, AccessSiteRules.MAX_VALUE_CHARS)}',
  unreadable: '${text(R.string.web_access_error_unreadable)}',
  full: '${text(R.string.web_access_error_full, AccessSiteRules.MAX_SITES)}',
  saveFailed: '${text(R.string.web_access_error_save)}',
  expired: '${text(R.string.web_photo_error_expired)}',
  failed: '${text(R.string.web_access_error_send)}'
};
const HEADER_LABELS = ${jsList(headerLabels)};
const SAVED_BEFORE = ${jsList(savedParts.map { it.first })};
const SAVED_AFTER = ${jsList(savedParts.map { it.second })};
const MAX_HEADERS = ${AccessSiteRules.MAX_HEADERS};
const CF_NAMES = ['CF-Access-Client-Id', 'CF-Access-Client-Secret'];
const SEND_TIMEOUT_MS = 30000;
// Status of an answer that isn't a field problem -> its line under the button.
const STATUS_TEXT = { 503: 'unreadable', 409: 'full', 500: 'saveFailed', 404: 'expired', 410: 'expired' };
const base = location.pathname.endsWith('/') ? location.pathname : location.pathname + '/';
const byId = (id) => document.getElementById(id);
const siteField = byId('site'), pairsBox = byId('pairs'), sendButton = byId('send');
let pairs = [];
let httpsShown = false;

document.title = T.title;
document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });

// No maxLength: a browser would cut a pasted secret silently; the TV refuses an over-long one instead.
function field(type, placeholder) {
  const input = document.createElement('input');
  input.className = 'fld';
  input.type = type;
  input.placeholder = placeholder;
  input.autocomplete = 'off';
  input.setAttribute('autocapitalize', 'none');
  input.setAttribute('autocorrect', 'off');
  input.spellcheck = false;
  input.addEventListener('input', () => { input.classList.remove('bad'); refreshChip(); });
  return input;
}

function addPair(name) {
  if (pairs.length >= MAX_HEADERS) return null;
  const label = document.createElement('div');
  label.className = 'lbl';
  label.textContent = HEADER_LABELS[pairs.length];
  const box = document.createElement('div');
  box.className = 'pair';
  const nameField = field('text', T.name);
  // Values are secrets (D26): masked as typed, never echoed by the TV.
  const valueField = field('password', T.value);
  const error = document.createElement('p');
  error.className = 'fe';
  error.hidden = true;
  nameField.value = name || '';
  box.append(nameField, valueField, error);
  pairsBox.append(label, box);
  const pair = { label, box, nameField, valueField, error };
  pairs.push(pair);
  byId('add').hidden = pairs.length >= MAX_HEADERS;
  return pair;
}

function refreshChip() {
  const on = CF_NAMES.every((n, i) => pairs[i] && pairs[i].nameField.value.trim() === n);
  byId('cf').classList.toggle('on', on);
}

function clearErrors() {
  setError('');
  siteField.classList.remove('bad');
  byId('siteErr').hidden = true;
  pairs.forEach((p) => { p.error.hidden = true; p.nameField.classList.remove('bad'); p.valueField.classList.remove('bad'); });
}

function setError(text) { byId('err').textContent = text || ''; }

function setHttps(on) {
  httpsShown = on;
  ['intro', 'siteLabel', 'form', 'conn'].forEach((id) => { byId(id).hidden = on; });
  byId('https').hidden = !on;
  siteField.classList.toggle('bad', on);
}

function reset() {
  pairs = [];
  pairsBox.textContent = '';
  siteField.value = '';
  addPair();
  refreshChip();
  clearErrors();
  setHttps(false);
  byId('saved').hidden = true;
  byId('enter').hidden = false;
}

byId('cf').addEventListener('click', () => {
  while (pairs.length < CF_NAMES.length) addPair();
  CF_NAMES.forEach((n, i) => { pairs[i].nameField.value = n; pairs[i].nameField.classList.remove('bad'); });
  refreshChip();
  pairs[0].valueField.focus();
});
byId('add').addEventListener('click', () => { const p = addPair(); if (p) p.nameField.focus(); });
byId('another').addEventListener('click', () => { reset(); siteField.focus(); });
siteField.addEventListener('input', () => { siteField.classList.remove('bad'); byId('siteErr').hidden = true; });

sendButton.addEventListener('click', async () => {
  clearErrors();
  // A pair left blank is skipped; the TV's answer indexes the pairs sent.
  const sent = pairs.filter((p) => p.nameField.value.trim() || p.valueField.value);
  const body = JSON.stringify({
    site: siteField.value.trim(),
    headers: sent.map((p) => ({ name: p.nameField.value, value: p.valueField.value }))
  });
  sendButton.disabled = true;
  let status = 0, answer = {};
  try {
    const response = await fetch(base + 'site', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body, cache: 'no-store',
      signal: window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(SEND_TIMEOUT_MS) : undefined
    });
    status = response.status;
    answer = await response.json().catch(() => ({}));
  } catch (e) {
    status = 0;
  }
  sendButton.disabled = false;
  if (status === 200) {
    // Saved: the secrets leave this page now ("Add another site" starts empty).
    pairs.forEach((p) => { p.valueField.value = ''; });
    const count = Math.min(Math.max(Number(answer.headers) || 1, 1), MAX_HEADERS);
    byId('savedBefore').textContent = SAVED_BEFORE[count - 1];
    byId('savedHost').textContent = String(answer.host || '');
    byId('savedAfter').textContent = SAVED_AFTER[count - 1];
    byId('enter').hidden = true;
    byId('saved').hidden = false;
    sendButton.textContent = T.send;
    return;
  }
  const problem = String(answer.problem || '');
  setHttps(status === 422 && problem === 'NOT_HTTPS');
  sendButton.textContent = httpsShown ? T.sendAgain : T.send;
  if (status === 422 && !httpsShown) {
    const pair = Number.isInteger(answer.header) ? sent[answer.header] : null;
    const text = T[problem] || T.failed;
    if (pair) {
      pair.error.textContent = text;
      pair.error.hidden = false;
      (problem === 'BAD_VALUE' ? pair.valueField : pair.nameField).classList.add('bad');
    } else if (problem === 'NOT_A_URL' || problem === 'HAS_USERINFO') {
      byId('siteErr').textContent = text;
      byId('siteErr').hidden = false;
      siteField.classList.add('bad');
    } else {
      setError(text);
    }
  } else if (status !== 422) {
    setError(T[STATUS_TEXT[status]] || T.failed);
  }
});

reset();
</script>
</body>
</html>
""".trimIndent()
    }

    /** Stands in for the host while the saved line is split; never in an app string. */
    private const val HOST_MARK = "\u0001"
}
