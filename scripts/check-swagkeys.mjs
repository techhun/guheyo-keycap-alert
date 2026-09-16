import fs from 'node:fs';

// Stable SWAGKEYS entry point. The validated core still expects the historical
// swg-state.json filename, so this wrapper supplies it only inside the runner.
const LEGACY_STATE = 'swg-state.json';
const STATE = 'swagkeys-state.json';

if (!process.env.SWG_DISCORD_WEBHOOK_URL && process.env.SWAGKEYS_DISCORD_WEBHOOK_URL) {
  process.env.SWG_DISCORD_WEBHOOK_URL = process.env.SWAGKEYS_DISCORD_WEBHOOK_URL;
}

if (fs.existsSync(STATE)) fs.copyFileSync(STATE, LEGACY_STATE);

try {
  await import('./check-swagkeys-core.mjs');
} finally {
  if (fs.existsSync(LEGACY_STATE)) fs.copyFileSync(LEGACY_STATE, STATE);
  fs.rmSync(LEGACY_STATE, { force: true });
}
