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

var EVENT_HEADERS = [
  'rowId', 'eventId', 'txnId', 'op', 'lamport', 'deviceId',
  'ownerUid', 'wallClock', 'payload'
];

var LEDGER_HEADERS = [
  'Date', 'Merchant', 'Category', 'Type', 'Amount (INR)',
  'Paid by', 'Counts as', 'Bank', 'Account', 'Reference', 'Original message'
];

function doPost(e) {
  var lock = LockService.getScriptLock();
  // Two phones can post at the same moment. Without a lock, concurrent appends
  // interleave and the row cursor stops being monotonic, which silently skips events.
  lock.waitLock(30000);
  try {
    var req = JSON.parse(e.postData.contents);
    switch (req.action) {
      case 'ping': return reply({ ok: true, sheet: SpreadsheetApp.getActive().getName() });
      case 'push': return reply(push(req));
      case 'pull': return reply(pull(req));
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
      p.bank || '', p.accountTail || '', p.refNo || '',
      p.rawSms || ''
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
