import test from 'node:test';
import assert from 'node:assert/strict';

import {
  bulkChangeInfo,
  changeId,
  changeSignature,
  comparableValue,
  parseGbGvizResponse,
  pruneSentChanges,
  validateGbRows,
  validateReleaseRows
} from '../scripts/geonworks-safety.mjs';

function gvizPayload() {
  const cols = [
    { label: 'Product' },
    { label: '숨김 링크' },
    { label: 'Type' },
    { label: 'Note' },
    { label: 'Manufacturer' },
    { label: 'GB Start' },
    { label: 'Status' },
    { label: '숨김 값' },
    { label: 'ETA' },
    { label: 'Update' }
  ];

  const products = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'];
  const rows = products.map((product, index) => ({
    c: [
      { v: product },
      { v: `https://example.com/${index}` },
      { v: index % 2 ? 'Keyboard' : 'Keycap' },
      { v: index === 0 ? 'shipping soon' : '' },
      { v: index % 2 ? 'SENSY' : 'GMK' },
      { v: `2026. 9. ${10 + index}` },
      { v: index === 0 ? 'inspecting' : 'In progress' },
      { v: 'ignored' },
      { v: '2026. 12. 31' },
      { v: '2026. 9. 18' }
    ]
  }));

  return `google.visualization.Query.setResponse(${JSON.stringify({
    status: 'ok',
    table: { cols, rows }
  })});`;
}

test('GViz parser maps by header even when hidden columns are inserted', () => {
  const rows = parseGbGvizResponse(gvizPayload());
  assert.equal(rows.length, 5);
  assert.deepEqual(rows[0], {
    product: 'Alpha',
    gbStart: '2026. 9. 10',
    eta: '2026. 12. 31',
    type: 'Keycap',
    manufacturer: 'GMK',
    status: 'inspecting',
    update: '2026. 9. 18',
    note: 'shipping soon'
  });
});

test('empty placeholders compare as the same value', () => {
  for (const value of ['', ' ', '—', '–', '-']) {
    assert.equal(comparableValue(value), '');
  }
  assert.equal(comparableValue('GMK'), 'GMK');
});

test('GB validation rejects shifted semantic columns', () => {
  const valid = Array.from({ length: 5 }, (_, index) => ({
    product: `Product ${index}`,
    gbStart: '2026. 9. 1',
    eta: '2026. 12. 31',
    type: 'Keyboard',
    manufacturer: 'GMK',
    status: 'In progress',
    update: '2026. 9. 18',
    note: '—'
  }));
  assert.equal(validateGbRows(valid).length, 5);

  const shifted = structuredClone(valid);
  shifted[0].manufacturer = '2026. 5. 18';
  assert.throws(() => validateGbRows(shifted), /manufacturer unexpectedly contains a date/);

  const urlEta = structuredClone(valid);
  urlEta[0].eta = 'https://example.com/product';
  assert.throws(() => validateGbRows(urlEta), /eta unexpectedly contains a URL/);
});

test('Release validation rejects malformed field types', () => {
  const valid = [{
    product: 'Nexus60',
    manufacturer: 'KEZEWA',
    region: 'KR/GLOBAL',
    saleType: 'Group Buy',
    type: 'Keyboard',
    status: 'For Sale',
    fixed: '확정 / Fixed',
    start: '2026-09-21',
    end: '2026-10-04'
  }];
  assert.equal(validateReleaseRows(valid).length, 1);

  const bad = structuredClone(valid);
  bad[0].manufacturer = '2026-09-21';
  assert.throws(() => validateReleaseRows(bad), /manufacturer unexpectedly contains a date/);
});

test('bulk change guard catches large or broad change sets', () => {
  assert.equal(bulkChangeInfo(Array(9).fill({}), 67).bulk, false);
  assert.equal(bulkChangeInfo(Array(10).fill({}), 67).bulk, true);
  assert.equal(bulkChangeInfo(Array(5).fill({}), 20).bulk, true);
  assert.equal(bulkChangeInfo(Array(4).fill({}), 10).bulk, false);
});

test('change signatures normalize empty placeholders', () => {
  const a = [{
    kind: 'changed',
    row: { product: 'RF-8X' },
    fields: [{ field: 'manufacturer', before: '—', after: 'GMK' }]
  }];
  const b = [{
    kind: 'changed',
    row: { product: 'RF-8X' },
    fields: [{ field: 'manufacturer', before: '', after: 'GMK' }]
  }];
  assert.equal(changeSignature(a), changeSignature(b));
});


test('change IDs are stable and distinct for different transitions', () => {
  const base = {
    kind: 'changed',
    row: { product: 'RF-8X' },
    fields: [{ field: 'status', before: 'In progress', after: 'Shipping' }]
  };
  assert.equal(changeId(base), changeId(structuredClone(base)));

  const reversed = structuredClone(base);
  reversed.fields[0] = { field: 'status', before: 'Shipping', after: 'In progress' };
  assert.notEqual(changeId(base), changeId(reversed));
});

test('sent change history expires after seven days', () => {
  const now = Date.parse('2026-09-18T00:00:00Z');
  const sent = {
    fresh: '2026-09-17T00:00:00Z',
    stale: '2026-09-01T00:00:00Z',
    bad: 'not-a-date'
  };
  assert.deepEqual(pruneSentChanges(sent, now), { fresh: '2026-09-17T00:00:00Z' });
});
