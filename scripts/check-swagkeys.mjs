import fs from 'node:fs';

// Stable SWAGKEYS entry point. Keep the validated legacy implementation isolated
// while using the explicit SWAGKEYS state/secret names externally.
const LEGACY_STATE = 'swg-state.json';
const STATE = 'swagkeys-state.json';

if (!process.env.SWG_DISCORD_WEBHOOK_URL && process.env.SWAGKEYS_DISCORD_WEBHOOK_URL) {
  process.env.SWG_DISCORD_WEBHOOK_URL = process.env.SWAGKEYS_DISCORD_WEBHOOK_URL;
}

const legacyBefore = fs.existsSync(LEGACY_STATE) ? fs.readFileSync(LEGACY_STATE, 'utf8') : null;
if (fs.existsSync(STATE)) fs.copyFileSync(STATE, LEGACY_STATE);

try {
  await import('./check-swg.mjs');
} finally {
  if (fs.existsSync(LEGACY_STATE)) fs.copyFileSync(LEGACY_STATE, STATE);
  if (legacyBefore === null) fs.rmSync(LEGACY_STATE, { force: true });
  else fs.writeFileSync(LEGACY_STATE, legacyBefore);
}
