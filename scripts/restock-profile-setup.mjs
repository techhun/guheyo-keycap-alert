import path from 'node:path';
import { chromium } from 'playwright';

const userDataDir = process.env.RESTOCK_PROBE_USER_DATA_DIR
  || path.resolve('restock-browser-profile');

console.log('[profile-setup] 전용 Chrome 프로필을 엽니다.');
console.log(`[profile-setup] userDataDir=${userDataDir}`);
console.log('[profile-setup] 열린 Chrome에서 네이버에 로그인한 뒤 Chrome 창을 직접 닫으면 설정이 저장됩니다.');

let context;
try {
  context = await chromium.launchPersistentContext(userDataDir, {
    channel: 'chrome',
    headless: false,
    viewport: { width: 1280, height: 900 },
    locale: 'ko-KR'
  });
} catch (error) {
  console.error('[profile-setup] Chrome 실행 실패:', error?.message || error);
  process.exit(1);
}

const page = context.pages()[0] || await context.newPage();
await page.goto('https://www.naver.com/', {
  waitUntil: 'domcontentloaded',
  timeout: 45000
});

await new Promise((resolve) => context.on('close', resolve));
console.log('[profile-setup] 프로필 저장 완료');
