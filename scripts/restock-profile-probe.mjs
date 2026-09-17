import path from 'node:path';

const localAppData = process.env.LOCALAPPDATA;
if (!localAppData) {
  console.error('[probe-profile] LOCALAPPDATA 환경변수를 찾지 못했습니다. Windows에서 실행하세요.');
  process.exit(1);
}

const userDataDir = process.env.RESTOCK_PROBE_USER_DATA_DIR
  || path.join(localAppData, 'Google', 'Chrome', 'User Data');
const profileDirectory = process.env.RESTOCK_PROBE_PROFILE_DIRECTORY || 'Default';

process.env.RESTOCK_PROBE_USER_DATA_DIR = userDataDir;
process.env.RESTOCK_PROBE_PROFILE_DIRECTORY = profileDirectory;

console.log('[probe-profile] 실제 Chrome 프로필 모드');
console.log(`[probe-profile] userDataDir=${userDataDir}`);
console.log(`[probe-profile] profileDirectory=${profileDirectory}`);
console.log('[probe-profile] 실행 전에 Chrome 창을 모두 종료하세요.');

await import('./restock-local-probe.mjs');
