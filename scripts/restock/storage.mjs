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
  const text = await response.text();
  if (!response.ok) {
    const error = new Error(`GitHub API ${response.status}: ${text}`);
    error.status = response.status;
    throw error;
  }
  return text ? JSON.parse(text) : {};
}

function contentApiUrl(path) {
  const { owner, repo } = repoParts();
  return `https://api.github.com/repos/${owner}/${repo}/contents/${path}`;
}

export async function readGitHubJson(path, fallback) {
  const branch = clean(process.env.RESTOCK_GITHUB_BRANCH || 'main');
  try {
    const payload = await githubRequest(`${contentApiUrl(path)}?ref=${encodeURIComponent(branch)}`);
    const text = Buffer.from(payload.content || '', 'base64').toString('utf8');
    return { value: JSON.parse(text), sha: payload.sha };
  } catch (error) {
    if (error?.status === 404) return { value: structuredClone(fallback), sha: null };
    throw error;
  }
}

async function putGitHubJson(path, value, message, sha) {
  const branch = clean(process.env.RESTOCK_GITHUB_BRANCH || 'main');
  const body = {
    message,
    branch,
    content: Buffer.from(`${JSON.stringify(value, null, 2)}\n`, 'utf8').toString('base64'),
    ...(sha ? { sha } : {})
  };
  return githubRequest(contentApiUrl(path), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function writeGitHubJson(path, value, message, expectedSha = null) {
  let sha = expectedSha;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      if (!sha) sha = (await readGitHubJson(path, null)).sha;
      return await putGitHubJson(path, value, message, sha);
    } catch (error) {
      if (attempt >= 3 || ![409, 422].includes(error?.status)) throw error;
      await sleep(attempt * 700);
      sha = (await readGitHubJson(path, null)).sha;
    }
  }
  throw new Error(`failed to write GitHub JSON: ${path}`);
}

export async function updateGitHubJson(path, fallback, message, mutator) {
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    const current = await readGitHubJson(path, fallback);
    const draft = structuredClone(current.value ?? fallback);
    const next = await mutator(draft);
    if (next === null || next === undefined) return { changed: false, value: current.value };
    try {
      const result = await putGitHubJson(path, next, message, current.sha);
      return { changed: true, value: next, result };
    } catch (error) {
      if (attempt >= 4 || ![409, 422].includes(error?.status)) throw error;
      await sleep(attempt * 500);
    }
  }
  throw new Error(`failed to atomically update GitHub JSON: ${path}`);
}

export function githubStorageConfigured() {
  return Boolean(token() && clean(process.env.RESTOCK_GITHUB_REPOSITORY || process.env.GITHUB_REPOSITORY));
}
