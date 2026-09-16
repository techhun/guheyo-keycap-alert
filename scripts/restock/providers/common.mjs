export const ANY_VALUE = '__ANY__';
export const NONE_VALUE = '__NONE__';

export const clean = (value) => String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function uniqueStrings(values = []) {
  return [...new Set(values.map(clean).filter(Boolean))];
}

export function canonicalHttpUrl(value) {
  const url = new URL(clean(value));
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error(`unsupported URL protocol: ${url.protocol}`);
  url.hash = '';
  return url;
}

export function normalizeSelectionList(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((selection) => ({
      key: clean(selection?.key),
      name: clean(selection?.name),
      kind: selection?.kind === 'addon' ? 'addon' : 'variant',
      value: clean(selection?.value),
      label: clean(selection?.label || selection?.value)
    }))
    .filter((selection) => selection.key && selection.value)
    .sort((a, b) => a.key.localeCompare(b.key));
}

export function matchSnapshot(snapshot, selections = []) {
  const normalized = normalizeSelectionList(selections);
  const byKey = new Map(normalized.map((selection) => [selection.key, selection]));
  const groups = new Map((snapshot.optionGroups || []).map((group) => [group.key, group]));

  for (const selection of normalized) {
    const group = groups.get(selection.key);
    if (!group) throw new Error(`option group not found: ${selection.name || selection.key}`);
    if (selection.value === NONE_VALUE && group.required) {
      throw new Error(`required option cannot be unselected: ${group.name}`);
    }
  }

  const variants = (snapshot.variants || []).filter((variant) => {
    return (snapshot.optionGroups || [])
      .filter((group) => group.kind !== 'addon')
      .every((group) => {
        const selection = byKey.get(group.key);
        if (!selection || selection.value === ANY_VALUE) return true;
        if (selection.value === NONE_VALUE) return false;
        return clean(variant.options?.[group.key]).toLowerCase() === selection.value.toLowerCase();
      });
  });

  const availableVariants = variants.filter((variant) => Boolean(variant.available));
  let addonsAvailable = true;
  const selectedAddons = [];

  for (const group of (snapshot.optionGroups || []).filter((item) => item.kind === 'addon')) {
    const selection = byKey.get(group.key);
    if (!selection || selection.value === ANY_VALUE || selection.value === NONE_VALUE) continue;
    const item = (group.values || []).find((candidate) => clean(candidate.value).toLowerCase() === selection.value.toLowerCase());
    if (!item) throw new Error(`option value not found: ${group.name} = ${selection.label || selection.value}`);
    selectedAddons.push({ group, item });
    if (!item.available) addonsAvailable = false;
  }

  return {
    matchingVariants: variants,
    availableVariants,
    selectedAddons,
    available: availableVariants.length > 0 && addonsAvailable
  };
}

export function optionSummary(selections = []) {
  const normalized = normalizeSelectionList(selections);
  if (!normalized.length) return '전체 옵션';
  return normalized.map((selection) => {
    let value = selection.label || selection.value;
    if (selection.value === ANY_VALUE) value = '상관없음';
    if (selection.value === NONE_VALUE) value = '미선택';
    return `• ${selection.name || selection.key}: ${value}`;
  }).join('\n');
}
