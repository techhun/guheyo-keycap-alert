import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Stable SWAGKEYS entry point. The validated core still expects the historical
// swg-state.json filename, so this wrapper supplies it only inside the runner.
const LEGACY_STATE = 'swg-state.json';
const STATE = 'swagkeys-state.json';
const CORE_PATH = fileURLToPath(new URL('./check-swagkeys-core.mjs', import.meta.url));

if (fs.existsSync(STATE)) fs.copyFileSync(STATE, LEGACY_STATE);

const env = {
  ...process.env,
  SWG_DISCORD_WEBHOOK_URL:
    process.env.SWAGKEYS_DISCORD_WEBHOOK_URL
    || process.env.SWG_DISCORD_WEBHOOK_URL
    || ''
};

let result;
try {
  result = spawnSync(process.execPath, [CORE_PATH], {
    stdio: 'inherit',
    env
  });
} finally {
  if (fs.existsSync(LEGACY_STATE)) fs.copyFileSync(LEGACY_STATE, STATE);
  fs.rmSync(LEGACY_STATE, { force: true });
}

if (result?.error) throw result.error;
if (result?.status !== 0) process.exit(result?.status ?? 1);
