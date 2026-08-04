/**
 * Ours — Google Sheet sync backend.
 *
 * Paste this into script.google.com, bound to a new spreadsheet, then:
 *   Deploy ▸ New deployment ▸ Web app
 *     Execute as:      Me
 *     Who has access:  Anyone
 *
 * Copy the /exec URL it gives you and paste it into Ours ▸ Settings ▸ Sheet sync,
 * on BOTH phones. That URL is the only credential — treat it like a password.
 * Re-deploying issues a new URL and revokes the old one.
 *
 * The spreadsheet itself stays private. Only this script touches it.
 */

var EVENTS = 'events';
var LEDGER = 'ledger';
var RULES  = 'rules';

var EVENT_HEADERS = [
  'rowId', 'eventId', 'txnId', 'op', 'lamport', 'deviceId',
  'ownerUid', 'wallClock', 'payload'
];

// No 'Original message' column. The phones deliberately strip the raw bank text
// before pushing here — this sheet is plaintext, and that text carries account tails
// and running balances. The other transports encrypt each line and do send it.
var LEDGER_HEADERS = [
  'Date', 'Merchant', 'Category', 'Type', 'Amount (INR)',
  'Paid by', 'Counts as', 'Bank', 'Account', 'Reference'
];

/**
 * Rules the phones teach each other, so a fix made on one reaches the other without
 * anybody installing anything.
 *
 * Two kinds, and both have cost this household real money to learn:
 *   sender   | KGBANK    | Kerala Gramin Bank   <- an unknown header is silently ignored,
 *                                                  which once discarded 466 messages
 *   merchant | keecheril | FOOD                 <- one categorisation, taught once
 *
 * Editable by hand. This tab is the one place a person can teach the app something
 * without a new APK, so it is deliberately plain: type, key, value.
 */
var RULE_HEADERS = ['type', 'key', 'value', 'updatedAt', 'deviceId'];

function doPost(e) {
  var lock = LockService.getScriptLock();
  // Two phones can post at the same moment. Without a lock, concurrent appends
  // interleave and the row cursor stops being monotonic, which silently skips events.
  lock.waitLock(30000);
  try {
    var req = JSON.parse(e.postData.contents);
    switch (req.action) {
      case 'ping': return reply({ ok: true, sheet: SpreadsheetApp.getActive().getName() });
      case 'reset': return reply(reset());
      case 'push': return reply(push(req));
      case 'pull': return reply(pull(req));
      case 'pushRules': return reply(pushRules(req));
      case 'pullRules': return reply(pullRules());
      default:     return reply({ ok: false, error: 'Unknown action: ' + req.action });
    }
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function push(req) {
  var sheet = eventsSheet();
  var events = req.events || [];
  if (!events.length) return { ok: true, written: 0 };

  // Idempotency: the app re-pushes after a failed round, and the same event must not
  // land twice. Cheaper to filter here than to de-duplicate a corrupted sheet later.
  var seen = existingEventIds(sheet);
  var rows = [];
  var nextRow = sheet.getLastRow() + 1;

  for (var i = 0; i < events.length; i++) {
    var ev = events[i];
    if (!ev.eventId || seen[ev.eventId]) continue;
    seen[ev.eventId] = true;
    rows.push([
      nextRow + rows.length,
      ev.eventId, ev.txnId, ev.op, ev.lamport,
      ev.deviceId, ev.ownerUid, ev.wallClock,
      ev.payload || ''
    ]);
  }

  if (rows.length) {
    sheet.getRange(nextRow, 1, rows.length, EVENT_HEADERS.length).setValues(rows);
    rebuildLedger();
  }
  return { ok: true, written: rows.length };
}

/**
 * Empties both tabs, keeping their header rows.
 *
 * Called by a phone before it re-uploads its history. The events tab is append-only, so
 * without this a rebuild would stack a second copy of everything beside the first, and
 * any row the phone has since stopped syncing — a retired month, a deleted expense —
 * would linger in the ledger forever. The phone is the source of truth; this lets it
 * say so.
 */
function reset() {
  var cleared = 0;
  [EVENTS, LEDGER].forEach(function (name) {
    var sheet = SpreadsheetApp.getActive().getSheetByName(name);
    if (!sheet) return;
    var last = sheet.getLastRow();
    if (last > 1) {
      sheet.deleteRows(2, last - 1);
      cleared += last - 1;
    }
  });
  return { ok: true, cleared: cleared };
}

function pull(req) {
  var sheet = eventsSheet();
  var last = sheet.getLastRow();
  var since = Number(req.since) || 0;
  var headerRows = 1;
  var first = Math.max(since, headerRows) + 1;

  if (last < first) return { ok: true, events: [], cursor: Math.max(since, last) };

  var values = sheet.getRange(first, 1, last - first + 1, EVENT_HEADERS.length).getValues();
  var out = [];
  for (var i = 0; i < values.length; i++) {
    var r = values[i];
    if (!r[1]) continue; // blank or hand-deleted row
    out.push({
      eventId: String(r[1]), txnId: String(r[2]), op: String(r[3]),
      lamport: Number(r[4]), deviceId: String(r[5]),
      ownerUid: String(r[6]), wallClock: Number(r[7]),
      payload: r[8] ? String(r[8]) : null
    });
  }
  return { ok: true, events: out, cursor: last };
}

/**
 * Rebuilds the readable tab from the event log.
 *
 * Resolution mirrors the app exactly: highest Lamport wins, ties broken by deviceId.
 * If this disagreed with the app, the sheet would show one thing and your phone
 * another — worse than having no ledger tab at all.
 */
function rebuildLedger() {
  var events = pull({ since: 0 }).events;
  var winner = {};
  for (var i = 0; i < events.length; i++) {
    var ev = events[i];
    var cur = winner[ev.txnId];
    if (!cur ||
        ev.lamport > cur.lamport ||
        (ev.lamport === cur.lamport && ev.deviceId > cur.deviceId)) {
      winner[ev.txnId] = ev;
    }
  }

  var rows = [];
  for (var id in winner) {
    var w = winner[id];
    if (w.op === 'DELETE' || !w.payload) continue;
    var p = JSON.parse(w.payload);
    rows.push([
      new Date(p.occurredAt),
      p.merchant, p.category, p.type,
      (p.amountPaise / 100),
      p.ownerName, p.splitType,
      p.bank || '', p.accountTail || '', p.refNo || ''
    ]);
  }
  rows.sort(function (a, b) { return b[0] - a[0]; });

  var sheet = ledgerSheet();
  sheet.clear();
  sheet.getRange(1, 1, 1, LEDGER_HEADERS.length).setValues([LEDGER_HEADERS])
       .setFontWeight('bold');
  if (rows.length) {
    sheet.getRange(2, 1, rows.length, LEDGER_HEADERS.length).setValues(rows);
  }
  sheet.setFrozenRows(1);
  sheet.autoResizeColumns(1, 6);
}

function existingEventIds(sheet) {
  var last = sheet.getLastRow();
  var seen = {};
  if (last < 2) return seen;
  var ids = sheet.getRange(2, 2, last - 1, 1).getValues();
  for (var i = 0; i < ids.length; i++) if (ids[i][0]) seen[String(ids[i][0])] = true;
  return seen;
}

function rulesSheet() { return sheetNamed(RULES, RULE_HEADERS); }

/**
 * Upserts by (type, key). Last writer wins on updatedAt, so a phone that has been
 * offline cannot undo a newer correction by pushing a stale copy of the same rule.
 */
function pushRules(req) {
  var rules = req.rules || [];
  if (!rules.length) return { ok: true, written: 0 };

  var sheet = rulesSheet();
  var last = sheet.getLastRow();
  var existing = last > 1
    ? sheet.getRange(2, 1, last - 1, RULE_HEADERS.length).getValues()
    : [];

  var indexByKey = {};
  for (var i = 0; i < existing.length; i++) {
    indexByKey[existing[i][0] + '\u0000' + existing[i][1]] = i;
  }

  var appended = [], written = 0;
  for (var j = 0; j < rules.length; j++) {
    var r = rules[j];
    if (!r.type || !r.key) continue;
    var row = [r.type, r.key, r.value, Number(r.updatedAt) || 0, r.deviceId || ''];
    var at = indexByKey[r.type + '\u0000' + r.key];
    if (at === undefined) {
      indexByKey[r.type + '\u0000' + r.key] = existing.length;
      existing.push(row);
      appended.push(row);
      written++;
    } else if (row[3] > (Number(existing[at][3]) || 0)) {
      existing[at] = row;
      sheet.getRange(2 + at, 1, 1, RULE_HEADERS.length).setValues([row]);
      written++;
    }
  }
  if (appended.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, appended.length, RULE_HEADERS.length)
      .setValues(appended);
  }
  return { ok: true, written: written };
}

function pullRules() {
  var sheet = rulesSheet();
  var last = sheet.getLastRow();
  if (last < 2) return { ok: true, rules: [] };

  var values = sheet.getRange(2, 1, last - 1, RULE_HEADERS.length).getValues();
  var out = [];
  for (var i = 0; i < values.length; i++) {
    var v = values[i];
    if (!v[0] || !v[1]) continue;
    out.push({
      type: String(v[0]).trim(),
      key: String(v[1]).trim(),
      value: String(v[2]).trim(),
      updatedAt: Number(v[3]) || 0,
      deviceId: String(v[4] || '')
    });
  }
  return { ok: true, rules: out };
}

function eventsSheet() { return sheetNamed(EVENTS, EVENT_HEADERS); }
function ledgerSheet() { return sheetNamed(LEDGER, LEDGER_HEADERS); }

function sheetNamed(name, headers) {
  var ss = SpreadsheetApp.getActive();
  var sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
