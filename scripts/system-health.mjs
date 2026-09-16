import fs from 'node:fs';

const DISCORD_WEBHOOK_URL = (process.env.SYSTEM_DISCORD_WEBHOOK_URL || '').trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function clean(value) {
  return String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();
}

function truncate(value, maxLength = 1000) {
  const text = clean(value) || '—';
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1).trimEnd()}…`;
}

function runUrl() {
  const repository = clean(process.env.GITHUB_REPOSITORY);
  const runId = clean(process.env.GITHUB_RUN_ID);
  return repository && runId ? `https://github.com/${repository}/actions/runs/${runId}` : '';
}

async function postDiscord(embed) {
  if (!DISCORD_WEBHOOK_URL) return false;

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await fetch(DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'Keyboard Alert · System',
        allowed_mentions: { parse: [] },
        embeds: [embed]
      })
    });

    if (response.ok) return true;

    if (response.status === 429 && attempt < 3) {
      let retryAfter = 1;
      try {
        retryAfter = Number((await response.json())?.retry_after) || 1;
      } catch {}
      await sleep(Math.ceil(retryAfter * 1000));
      continue;
    }

    throw new Error(`System Discord webhook failed: ${response.status} ${await response.text()}`);
  }

  return false;
}

function statePath(group) {
  if (group === 'fast') return 'system-health-fast.json';
  if (group === 'slow') return 'system-health-slow.json';
  throw new Error(`Unknown system health group: ${group}`);
}

function loadState(group) {
  const path = statePath(group);
  try {
    const parsed = JSON.parse(fs.readFileSync(path, 'utf8'));
    if (parsed && typeof parsed === 'object') return parsed;
  } catch {}
  return { version: 1, initialized: true, updatedAt: null, sources: {} };
}

function saveState(group, state) {
  fs.writeFileSync(
    statePath(group),
    JSON.stringify({
      version: 1,
      initialized: true,
      updatedAt: new Date().toISOString(),
      sources: state.sources || {}
    }, null, 2) + '\n'
  );
}

function baseEmbed(title) {
  const url = runUrl();
  return {
    title,
    ...(url ? { url } : {}),
    footer: { text: 'keyboard-alert · system monitor' },
    timestamp: new Date().toISOString()
  };
}

async function transition(group, sourceKey, status, label, detail = '') {
  if (!['ok', 'fail'].includes(status)) throw new Error(`Unknown health status: ${status}`);

  const state = loadState(group);
  state.sources ||= {};
  const previousStatus = state.sources[sourceKey]?.status || null;

  if (previousStatus === status) {
    console.log(`[system] ${label}: unchanged (${status})`);
    return;
  }

  const needsNotification = status === 'fail' || previousStatus === 'fail';
  if (needsNotification && !DISCORD_WEBHOOK_URL) {
    console.log(`[system] SYSTEM_DISCORD_WEBHOOK_URL is not configured. ${label} transition ${previousStatus || 'unknown'} -> ${status} remains pending.`);
    return;
  }

  if (status === 'fail') {
    await postDiscord({
      ...baseEmbed(`🚨 수집 오류 · ${label}`),
      description: truncate(detail || '재시도 후에도 수집에 실패했습니다. 기존 검증된 state를 유지합니다.'),
      fields: [
        { name: '그룹', value: group.toUpperCase(), inline: true },
        { name: '상태', value: '오류', inline: true }
      ]
    });
  } else if (previousStatus === 'fail') {
    await postDiscord({
      ...baseEmbed(`✅ 복구 · ${label}`),
      description: '정상 수집이 다시 확인되었습니다.',
      fields: [
        { name: '그룹', value: group.toUpperCase(), inline: true },
        { name: '상태', value: '정상', inline: true }
      ]
    });
  }

  state.sources[sourceKey] = {
    status,
    changedAt: new Date().toISOString()
  };
  saveState(group, state);
  console.log(`[system] ${label}: ${previousStatus || 'unknown'} -> ${status}`);
}

async function incident(label, detail = '') {
  if (!DISCORD_WEBHOOK_URL) {
    console.log(`[system] SYSTEM_DISCORD_WEBHOOK_URL is not configured. Incident not sent: ${label}`);
    return;
  }

  await postDiscord({
    ...baseEmbed(`🚨 워크플로 오류 · ${label}`),
    description: truncate(detail || 'GitHub Actions 워크플로 단계가 실패했습니다.'),
    fields: [{ name: '확인', value: '제목을 눌러 GitHub Actions 실행 로그를 확인하세요.', inline: false }]
  });
  console.log(`[system] Incident sent: ${label}`);
}

const [command, ...args] = process.argv.slice(2);

if (command === 'transition') {
  const [group, sourceKey, status, label, detail = ''] = args;
  await transition(group, sourceKey, status, label, detail);
} else if (command === 'incident') {
  const [label, detail = ''] = args;
  await incident(label, detail);
} else {
  throw new Error('Usage: system-health.mjs transition <fast|slow> <source-key> <ok|fail> <label> [detail] | incident <label> [detail]');
}
