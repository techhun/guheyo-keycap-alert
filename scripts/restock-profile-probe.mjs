import path from 'node:path';

const userDataDir = process.env.RESTOCK_PROBE_USER_DATA_DIR
  || path.resolve('restock-browser-profile');

process.env.RESTOCK_PROBE_USER_DATA_DIR = userDataDir;
process.env.RESTOCK_PROBE_PROFILE_DIRECTORY = process.env.RESTOCK_PROBE_PROFILE_DIRECTORY || '';
process.env.RESTOCK_PROBE_HOLD_MS = process.env.RESTOCK_PROBE_HOLD_MS || '120000';

console.log('[probe-profile] 전용 Chrome 프로필 모드');
console.log(`[probe-profile] userDataDir=${userDataDir}`);
console.log('[probe-profile] 실제 Chrome 기본 프로필은 사용하지 않습니다.');
console.log('[probe-profile] 첫 실행이면 열린 Chrome에서 필요 시 네이버 로그인 후 그대로 두세요. 다음 실행에도 세션이 유지됩니다.');

await import('./restock-local-probe.mjs');
