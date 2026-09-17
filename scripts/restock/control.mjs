const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const TIME_ZONE = 'Asia/Seoul';

export const CONTROL_PATH = 'restock-monitor-control.json';
export const CONTROL_FALLBACK = {
  version: 1,
  mode: 'off',
  schedule: null,
  updatedAt: null,
  updatedBy: null
};

export function normalizeControl(value) {
  const source = value && typeof value === 'object' ? value : {};
  const mode = ['off', 'manual', 'scheduled'].includes(source.mode) ? source.mode : 'off';
  const schedule = source.schedule && typeof source.schedule === 'object'
    ? {
        startAt: typeof source.schedule.startAt === 'string' ? source.schedule.startAt : null,
        endAt: typeof source.schedule.endAt === 'string' ? source.schedule.endAt : null,
        timezone: TIME_ZONE
      }
    : null;
  return {
    version: 1,
    mode,
    schedule,
    updatedAt: typeof source.updatedAt === 'string' ? source.updatedAt : null,
    updatedBy: source.updatedBy ?? null
  };
}

function currentKstYear(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: TIME_ZONE,
    year: 'numeric'
  }).formatToParts(now);
  return Number(parts.find((part) => part.type === 'year')?.value);
}

export function parseKstDateTime(value, now = new Date()) {
  const text = String(value ?? '').trim().replace(/[./]/g, '-').replace(/\s+/g, ' ');
  let match = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})$/);
  let year;
  let month;
  let day;
  let hour;
  let minute;

  if (match) {
    [, year, month, day, hour, minute] = match.map(Number);
  } else {
    match = text.match(/^(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})$/);
    if (!match) throw new Error('시간 형식은 `YYYY-MM-DD HH:mm` 또는 `MM-DD HH:mm`으로 입력해주세요.');
    year = currentKstYear(now);
    [, month, day, hour, minute] = match.map(Number);
  }

  if (month < 1 || month > 12 || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
    throw new Error('올바른 날짜/시간을 입력해주세요.');
  }
  const maxDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (day < 1 || day > maxDay) throw new Error('올바른 날짜를 입력해주세요.');

  const utcMs = Date.UTC(year, month - 1, day, hour, minute) - KST_OFFSET_MS;
  return new Date(utcMs).toISOString();
}

export function formatKstDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date);
  const map = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${map.year}-${map.month}-${map.day} ${map.hour}:${map.minute}`;
}

export function monitoringStatus(value, now = Date.now()) {
  const control = normalizeControl(value);
  if (control.mode === 'manual') {
    return { active: true, mode: 'manual', reason: '수동 감시 중', control };
  }
  if (control.mode !== 'scheduled' || !control.schedule?.startAt || !control.schedule?.endAt) {
    return { active: false, mode: 'off', reason: '감시 중지', control };
  }

  const startMs = Date.parse(control.schedule.startAt);
  const endMs = Date.parse(control.schedule.endAt);
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) {
    return { active: false, mode: 'scheduled', reason: '예약 정보 오류', control };
  }
  if (now < startMs) return { active: false, mode: 'scheduled', reason: '예약 대기', control };
  if (now >= endMs) return { active: false, mode: 'scheduled', reason: '예약 종료', control };
  return { active: true, mode: 'scheduled', reason: '예약 감시 중', control };
}
