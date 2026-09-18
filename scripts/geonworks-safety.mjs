export function clean(value) {
  return String(value ?? '')
    .replace(/\r/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function comparableValue(value) {
  const text = clean(value);
  if (!text || /^(?:—|–|-)$/u.test(text)) return '';
  return text;
}

export function normalizeKey(value) {
  return comparableValue(value).toLocaleLowerCase('en-US');
}

const isUrl = (value) => /^https?:\/\//i.test(comparableValue(value));
const isDateLike = (value) => /^\d{4}\s*[.\-/]\s*\d{1,2}\s*[.\-/]\s*\d{1,2}/.test(comparableValue(value));

function assertRowFields(rows, rules, label) {
  for (const [index, row] of rows.entries()) {
    for (const [field, rule] of Object.entries(rules)) {
      const value = comparableValue(row?.[field]);
      if (!value) continue;
      if (rule.noUrl && isUrl(value)) {
        throw new Error(`${label} row ${index + 1} field ${field} unexpectedly contains a URL: ${value.slice(0, 120)}`);
      }
      if (rule.noDate && isDateLike(value)) {
        throw new Error(`${label} row ${index + 1} field ${field} unexpectedly contains a date: ${value}`);
      }
      if (rule.dateOrBlank && !isDateLike(value)) {
        throw new Error(`${label} row ${index + 1} field ${field} was not a date: ${value}`);
      }
    }
  }
}

export function validateGbRows(rows) {
  if (!Array.isArray(rows) || rows.length < 5) {
    throw new Error(`GEONWORKS returned suspiciously few rows: ${Array.isArray(rows) ? rows.length : 0}`);
  }

  assertRowFields(rows, {
    product: { noUrl: true, noDate: true },
    gbStart: { noUrl: true },
    eta: { noUrl: true },
    type: { noUrl: true, noDate: true },
    manufacturer: { noUrl: true, noDate: true },
    status: { noUrl: true, noDate: true },
    update: { noUrl: true }
  }, 'GEONWORKS GB');

  return rows;
}

export function validateReleaseRows(rows) {
  if (!Array.isArray(rows) || rows.length < 1) {
    throw new Error('GEONWORKS Release returned no rows');
  }

  assertRowFields(rows, {
    product: { noUrl: true, noDate: true },
    manufacturer: { noUrl: true, noDate: true },
    region: { noUrl: true, noDate: true },
    saleType: { noUrl: true, noDate: true },
    type: { noUrl: true, noDate: true },
    status: { noUrl: true, noDate: true },
    fixed: { noUrl: true, noDate: true },
    start: { noUrl: true, dateOrBlank: true },
    end: { noUrl: true, dateOrBlank: true }
  }, 'GEONWORKS Release');

  return rows;
}

function gvizCellValue(cell) {
  if (!cell) return '';
  if (cell.f !== undefined && cell.f !== null) return clean(cell.f);
  if (cell.v !== undefined && cell.v !== null) return clean(cell.v);
  return '';
}

function normalizeColumnLabel(value) {
  return clean(value).toLowerCase().replace(/[|/]/g, ' ').replace(/[^a-z0-9가-힣]+/g, '');
}

function findColumnIndex(columns, aliases) {
  const normalizedAliases = aliases.map(normalizeColumnLabel);
  return columns.findIndex((column) => normalizedAliases.includes(normalizeColumnLabel(column?.label || '')));
}

export function parseGbGvizResponse(text) {
  const match = String(text ?? '').match(/google\.visualization\.Query\.setResponse\((.*)\);?\s*$/s);
  if (!match) throw new Error('GEONWORKS gviz response wrapper was not recognized');

  const payload = JSON.parse(match[1]);
  if (payload?.status && payload.status !== 'ok') {
    throw new Error(`GEONWORKS gviz returned status ${payload.status}`);
  }

  const columns = payload?.table?.cols || [];
  const indexes = {
    product: findColumnIndex(columns, ['제품명', 'Product']),
    gbStart: findColumnIndex(columns, ['GB시작일', 'GB Start']),
    eta: findColumnIndex(columns, ['예상 발송일', 'ETA']),
    type: findColumnIndex(columns, ['분류', 'Type']),
    manufacturer: findColumnIndex(columns, ['제조사', 'Manufacturer']),
    status: findColumnIndex(columns, ['상태', 'Status']),
    update: findColumnIndex(columns, ['갱신 일자', 'Update']),
    note: findColumnIndex(columns, ['비고', 'Note'])
  };

  const missing = Object.entries(indexes).filter(([, index]) => index < 0).map(([key]) => key);
  if (missing.length > 0) {
    const labels = columns.map((column) => clean(column?.label)).filter(Boolean);
    throw new Error(`GEONWORKS gviz columns did not match table schema; missing=${missing.join(',')}; labels=${labels.join(' | ')}`);
  }

  const rows = (payload?.table?.rows || [])
    .map((row) => row?.c || [])
    .map((cells) => ({
      product: gvizCellValue(cells[indexes.product]),
      gbStart: gvizCellValue(cells[indexes.gbStart]),
      eta: gvizCellValue(cells[indexes.eta]),
      type: gvizCellValue(cells[indexes.type]),
      manufacturer: gvizCellValue(cells[indexes.manufacturer]),
      status: gvizCellValue(cells[indexes.status]),
      update: gvizCellValue(cells[indexes.update]),
      note: gvizCellValue(cells[indexes.note]) || '—'
    }))
    .filter((row) => row.product);

  return validateGbRows(rows);
}

export function bulkChangeInfo(changes, previousRowCount) {
  const count = Array.isArray(changes) ? changes.length : 0;
  const base = Math.max(1, Number(previousRowCount) || 0);
  const ratio = count / base;
  const bulk = count >= 10 || (count >= 5 && ratio >= 0.2);
  return { bulk, count, ratio };
}

function stableRow(row) {
  return Object.fromEntries(Object.entries(row || {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => [key, comparableValue(value)]));
}

export function changeSignature(changes) {
  return (changes || []).map((change) => {
    const product = comparableValue(change?.row?.product);
    if (change.kind === 'changed') {
      const fields = (change.fields || [])
        .map((field) => ({
          field: field.field || field.label || '',
          before: comparableValue(field.before),
          after: comparableValue(field.after)
        }))
        .sort((a, b) => a.field.localeCompare(b.field));
      return JSON.stringify({ kind: change.kind, product, fields });
    }
    return JSON.stringify({ kind: change.kind, product, row: stableRow(change.row) });
  }).sort().join('\n');
}
