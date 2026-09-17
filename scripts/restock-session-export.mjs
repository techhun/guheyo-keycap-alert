import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const userDataDir = process.env.RESTOCK_PROBE_USER_DATA_DIR
  || path.resolve('restock-browser-profile');
const jsonPath = path.resolve('.restock-storage-state.json');
const b64Path = path.resolve('.restock-storage-state.b64');
const targetUrl = 'https://m.smartstore.naver.com/swagkey/products/12348949592';

console.log('[session-export] 전용 Chrome 프로필의 세션을 추출합니다.');
console.log(`[session-export] userDataDir=${userDataDir}`);

let context;
try {
  context = await chromium.launchPersistentContext(userDataDir, {
    channel: 'chrome',
    headless: false,
    viewport: { width: 1280, height: 900 },
    locale: 'ko-KR'
  });
} catch (error) {
  console.error('[session-export] Chrome 실행 실패:', error?.message || error);
  console.error('[session-export] restock-browser-profile을 사용하는 Chrome이 열려 있으면 모두 닫고 다시 실행하세요.');
  process.exit(1);
}

try {
  const page = context.pages()[0] || await context.newPage();
  const response = await page.goto(targetUrl, {
    waitUntil: 'domcontentloaded',
    timeout: 45000
  });
  await page.waitForTimeout(3000);

  const state = await context.storageState();
  const json = JSON.stringify(state);
  fs.writeFileSync(jsonPath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  fs.writeFileSync(b64Path, Buffer.from(json, 'utf8').toString('base64'), 'utf8');

  const naverCookies = state.cookies.filter((cookie) => /(^|\.)naver\.com$/i.test(cookie.domain.replace(/^\./, '')) || cookie.domain.includes('naver.com'));
  console.log('[session-export]', JSON.stringify({
    pageStatus: response?.status() ?? null,
    finalUrl: page.url(),
    totalCookies: state.cookies.length,
    naverCookies: naverCookies.length,
    origins: state.origins.length
  }));
  console.log(`[session-export] JSON 저장: ${jsonPath}`);
  console.log(`[session-export] GitHub Secret용 Base64 저장: ${b64Path}`);
  console.log('[session-export] 파일 내용은 로그인 세션이므로 채팅/커밋/공개 저장소에 올리지 마세요.');
} finally {
  await context.close();
}
