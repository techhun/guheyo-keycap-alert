import fs from 'node:fs';
import { clean, sleep } from './providers/common.mjs';

export function readLocalJson(path, fallback) {
  try {
    const parsed = JSON.parse(fs.readFileSync(path, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : fallback;
  } catch {
    return structuredClone(fallback);
  }
}

export function writeLocalJson(path, value) {
  fs.writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function repoParts() {
  const repository = clean(process.env.RESTOCK_GITHUB_REPOSITORY || process.env.GITHUB_REPOSITORY);
  const match = repository.match(/^([^/]+)\/([^/]+)$/);
  if (!match) throw new Error('RESTOCK_GITHUB_REPOSITORY must be owner/repo');
  return { owner: match[1], repo: match[2] };
}

function token() {
  return clean(process.env.RESTOCK_GITHUB_TOKEN);
}

async function githubRequest(url, options = {}) {
  const auth = token();
  if (!auth) throw new Error('RESTOCK_GITHUB_TOKEN is not configured');
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${auth}`,
      'X-GitHub-Api-Version': '2022-11-28',
      ...(options.headers || {})
    },
    signal: AbortSignal.timeout(20000)
  });
  if (!response.ok) throw new Error(`GitHub API ${response.status}: ${await response.text()}`);
  return response.json();
}

export async function readGitHubJson(path, fallback) {
  const { owner, repo } = repoParts();
  const branch = clean(process.env.RESTOCK_GITHUB_BRANCH || 'main');
  const url = `https://api.github.com/repos/${owner}/${repo}/contents/${path}?ref=${encodeURIComponent(branch)}`;
  try {
    const payload = await githubRequest(url);
    const text = Buffer.from(payload.content || '', 'base64').toString('utf8');
    return { value: JSON.parse(text), sha: payload.sha };
  } catch (error) {
    if (/GitHub API 404/.test(String(error?.message))) return { value: structuredClone(fallback), sha: null };
    throw error;
  }
}

export async function writeGitHubJson(path, value, message, expectedSha = null) {
  const { owner, repo } = repoParts();
  const branch = clean(process.env.RESTOCK_GITHUB_BRANCH || 'main');
  const url = `https://api.github.com/repos/${owner}/${repo}/contents/${path}`;
  let sha = expectedSha;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      if (!sha) {
        const current = await readGitHubJson(path, null);
        sha = current.sha;
      }
      const body = {
        message,
        branch,
        content: Buffer.from(`${JSON.stringify(value, null, 2)}\n`, 'utf8').toString('base64'),
        ...(sha ? { sha } : {})
      };
      return await githubRequest(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
    } catch (error) {
      if (attempt >= 3 || !/409|422/.test(String(error?.message))) throw error;
      await sleep(attempt * 700);
      const latest = await readGitHubJson(path, null);
      sha = latest.sha;
    }
  }
  throw new Error(`failed to write GitHub JSON: ${path}`);
}

export function githubStorageConfigured() {
  return Boolean(token() && clean(process.env.RESTOCK_GITHUB_REPOSITORY || process.env.GITHUB_REPOSITORY));
}
