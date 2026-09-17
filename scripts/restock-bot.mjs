import crypto from 'node:crypto';
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  Client,
  Events,
  GatewayIntentBits,
  PermissionFlagsBits,
  StringSelectMenuBuilder
} from 'discord.js';
import { checkRestock } from './restock/engine.mjs';
import { inspectProduct } from './restock/providers/index.mjs';
import {
  CONTROL_FALLBACK,
  CONTROL_PATH,
  formatKstDateTime,
  monitoringStatus,
  parseKstDateTime
} from './restock/control.mjs';
import {
  ANY_VALUE,
  NONE_VALUE,
  clean,
  matchSnapshot,
  normalizeSelectionList,
  optionSummary
} from './restock/providers/common.mjs';
import {
  githubStorageConfigured,
  readGitHubJson,
  readLocalJson,
  updateGitHubJson,
  writeGitHubJson,
  writeLocalJson
} from './restock/storage.mjs';

const WATCHLIST_PATH = 'restock-watchlist.json';
const STATE_PATH = 'restock-bot-state.json';
const WATCHLIST_FALLBACK = { version: 2, items: [] };
const STATE_FALLBACK = { version: 2, initialized: true, updatedAt: null, items: {} };
const BOT_TOKEN = clean(process.env.DISCORD_BOT_TOKEN);
const MANAGE_CHANNEL_ID = clean(process.env.RESTOCK_MANAGE_CHANNEL_ID);
const ALERT_CHANNEL_ID = clean(process.env.RESTOCK_ALERT_CHANNEL_ID);
const ALLOWED_USER_IDS = new Set(clean(process.env.RESTOCK_ALLOWED_USER_IDS).split(',').map(clean).filter(Boolean));
const POLL_SECONDS = Math.max(30, Number(process.env.RESTOCK_POLL_SECONDS) || 60);
const BOT_MONITOR_ENABLED = clean(process.env.RESTOCK_BOT_MONITOR_ENABLED).toLowerCase() === 'true';
const SESSION_TTL_MS = 15 * 60 * 1000;
const sessions = new Map();
let monitorRunning = false;
let localWriteQueue = Promise.resolve();

if (!BOT_TOKEN) throw new Error('DISCORD_BOT_TOKEN is not configured');

function short(value, max = 95) {
  const text = clean(value);
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

function sessionId() {
  return crypto.randomUUID().replaceAll('-', '').slice(0, 12);
}

function authorized(interaction) {
  if (MANAGE_CHANNEL_ID && interaction.channelId !== MANAGE_CHANNEL_ID) return false;
  if (ALLOWED_USER_IDS.size) return ALLOWED_USER_IDS.has(interaction.user.id);
  return Boolean(interaction.memberPermissions?.has(PermissionFlagsBits.ManageGuild));
}

async function rejectUnauthorized(interaction) {
  const reason = MANAGE_CHANNEL_ID && interaction.channelId !== MANAGE_CHANNEL_ID
    ? '입고 관리 채널에서만 사용할 수 있습니다.'
    : '입고 감시 목록을 관리할 권한이 없습니다.';
  if (interaction.deferred || interaction.replied) await interaction.followUp({ content: reason, ephemeral: true });
  else await interaction.reply({ content: reason, ephemeral: true });
}

function cleanupSessions() {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (now - session.createdAt > SESSION_TTL_MS) sessions.delete(id);
  }
}
setInterval(cleanupSessions, 60_000).unref();

async function readStored(path, fallback) {
  if (githubStorageConfigured()) return readGitHubJson(path, fallback);
  return { value: readLocalJson(path, fallback), sha: null };
}

async function updateStored(path, fallback, message, mutator) {
  if (githubStorageConfigured()) return updateGitHubJson(path, fallback, message, mutator);
  localWriteQueue = localWriteQueue.then(async () => {
    const current = readLocalJson(path, fallback);
    const next = await mutator(structuredClone(current));
    if (next !== null && next !== undefined) writeLocalJson(path, next);
    return { changed: next !== null && next !== undefined, value: next ?? current };
  });
  return localWriteQueue;
}

async function updateWatchlist(message, mutator) {
  return updateStored(WATCHLIST_PATH, WATCHLIST_FALLBACK, message, mutator);
}

async function updateControl(message, mutator) {
  return updateStored(CONTROL_PATH, CONTROL_FALLBACK, message, mutator);
}

function groupPageRows(session, groupIndex, pageIndex = 0) {
  const group = session.snapshot.optionGroups[groupIndex];
  const specialCount = group.required ? 1 : 2;
  const pageSize = 25 - specialCount;
  const values = group.values || [];
  const pageCount = Math.max(1, Math.ceil(values.length / pageSize));
  const page = Math.max(0, Math.min(Number(pageIndex) || 0, pageCount - 1));
  const slice = values.slice(page * pageSize, (page + 1) * pageSize);
  const options = [
    { label: '상관없음', value: ANY_VALUE, description: '이 옵션 값은 무엇이든 허용' }
  ];
  if (!group.required) options.push({ label: '미선택', value: NONE_VALUE, description: '이 선택사항을 추가하지 않음' });
  for (let index = 0; index < slice.length; index += 1) {
    const item = slice[index];
    options.push({
      label: short(item.label || item.value, 100),
      value: `VALUE:${page * pageSize + index}`,
      ...(item.available === false ? { description: '현재 품절' } : {})
    });
  }

  const select = new StringSelectMenuBuilder()
    .setCustomId(`restock|select|${session.id}|${groupIndex}|${page}`)
    .setPlaceholder(`${groupIndex + 1}/${session.snapshot.optionGroups.length} · ${short(group.name, 75)}`)
    .addOptions(options);
  const rows = [new ActionRowBuilder().addComponents(select)];

  if (pageCount > 1) {
    rows.push(new ActionRowBuilder().addComponents(
      new ButtonBuilder()
        .setCustomId(`restock|page|${session.id}|${groupIndex}|${Math.max(0, page - 1)}`)
        .setLabel('이전')
        .setStyle(ButtonStyle.Secondary)
        .setDisabled(page === 0),
      new ButtonBuilder()
        .setCustomId(`restock|page|${session.id}|${groupIndex}|${Math.min(pageCount - 1, page + 1)}`)
        .setLabel(`다음 (${page + 1}/${pageCount})`)
        .setStyle(ButtonStyle.Secondary)
        .setDisabled(page === pageCount - 1),
      new ButtonBuilder()
        .setCustomId(`restock|cancel|${session.id}`)
        .setLabel('취소')
        .setStyle(ButtonStyle.Danger)
    ));
  } else {
    rows.push(new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId(`restock|cancel|${session.id}`).setLabel('취소').setStyle(ButtonStyle.Danger)
    ));
  }
  return rows;
}

function groupMessage(session, groupIndex, pageIndex = 0) {
  const group = session.snapshot.optionGroups[groupIndex];
  const required = group.required ? '필수' : '선택';
  return {
    content: `**${session.snapshot.title}**\n${groupIndex + 1}/${session.snapshot.optionGroups.length} · **${group.name}** (${required})를 선택하세요.`,
    components: groupPageRows(session, groupIndex, pageIndex)
  };
}

function confirmationMessage(session) {
  const match = matchSnapshot(session.snapshot, session.selections);
  return {
    content: [
      `**${session.snapshot.title}**`,
      '',
      optionSummary(session.selections),
      '',
      `현재 상태: **${match.available ? '🟢 구매 가능' : '🔴 품절'}**`,
      '',
      '이 조합을 재입고 감시에 등록할까요?'
    ].join('\n'),
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId(`restock|confirm|${session.id}`).setLabel('이 조합 감시').setStyle(ButtonStyle.Success),
      new ButtonBuilder().setCustomId(`restock|cancel|${session.id}`).setLabel('취소').setStyle(ButtonStyle.Danger)
    )]
  };
}

function sessionFor(interaction, id) {
  const session = sessions.get(id);
  if (!session || session.userId !== interaction.user.id || Date.now() - session.createdAt > SESSION_TTL_MS) return null;
  return session;
}

async function handleAdd(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  await interaction.deferReply({ ephemeral: true });
  const url = interaction.options.getString('url', true);
  try {
    const snapshot = await inspectProduct(url);
    const id = sessionId();
    const session = {
      id,
      userId: interaction.user.id,
      sourceUrl: url,
      snapshot,
      selections: [],
      createdAt: Date.now()
    };
    sessions.set(id, session);
    if (!snapshot.optionGroups.length) await interaction.editReply(confirmationMessage(session));
    else await interaction.editReply(groupMessage(session, 0));
  } catch (error) {
    const blocked = error?.code === 'NAVER_SMARTSTORE_BLOCKED'
      ? '\n네이버 스마트스토어가 현재 이 봇 실행 환경의 접속을 제한했습니다. SmartStore 상품 등록은 worker 환경에서 처리해야 합니다.'
      : '';
    await interaction.editReply({ content: `상품 정보를 읽지 못했습니다.\n\`${short(error?.message || error, 1600)}\`${blocked}`, components: [] });
  }
}

async function handleSelect(interaction, parts) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const [, , id, groupIndexText] = parts;
  const session = sessionFor(interaction, id);
  if (!session) return interaction.reply({ content: '선택 세션이 만료되었습니다. `/입고추가`부터 다시 실행해주세요.', ephemeral: true });
  const groupIndex = Number(groupIndexText);
  const group = session.snapshot.optionGroups[groupIndex];
  if (!group) return interaction.reply({ content: '옵션 정보를 찾을 수 없습니다.', ephemeral: true });

  const raw = interaction.values[0];
  let value = raw;
  let label = raw;
  if (raw.startsWith('VALUE:')) {
    const item = group.values[Number(raw.slice(6))];
    if (!item) return interaction.reply({ content: '선택한 옵션 값이 만료되었습니다.', ephemeral: true });
    value = item.value;
    label = item.label || item.value;
  } else if (raw === ANY_VALUE) label = '상관없음';
  else if (raw === NONE_VALUE) label = '미선택';

  session.selections = session.selections.filter((selection) => selection.key !== group.key);
  session.selections.push({ key: group.key, name: group.name, kind: group.kind, value, label });
  const nextIndex = groupIndex + 1;
  await interaction.update(nextIndex < session.snapshot.optionGroups.length
    ? groupMessage(session, nextIndex)
    : confirmationMessage(session));
}

async function handlePage(interaction, parts) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const [, , id, groupIndexText, pageText] = parts;
  const session = sessionFor(interaction, id);
  if (!session) return interaction.reply({ content: '선택 세션이 만료되었습니다.', ephemeral: true });
  await interaction.update(groupMessage(session, Number(groupIndexText), Number(pageText)));
}

function sameSelections(a, b) {
  return JSON.stringify(normalizeSelectionList(a)) === JSON.stringify(normalizeSelectionList(b));
}

async function handleConfirm(interaction, parts) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const session = sessionFor(interaction, parts[2]);
  if (!session) return interaction.reply({ content: '선택 세션이 만료되었습니다.', ephemeral: true });
  const selections = normalizeSelectionList(session.selections);
  const newItem = {
    id: crypto.randomUUID(),
    enabled: true,
    monitor: 'worker',
    provider: session.snapshot.provider,
    url: session.snapshot.canonicalUrl,
    canonicalUrl: session.snapshot.canonicalUrl,
    productKey: session.snapshot.productKey,
    title: session.snapshot.title,
    selections,
    createdAt: new Date().toISOString()
  };
  let duplicate = false;
  await updateWatchlist(`Add restock watch: ${short(session.snapshot.title, 60)}`, (watchlist) => {
    watchlist.version = 2;
    watchlist.items = Array.isArray(watchlist.items) ? watchlist.items : [];
    duplicate = watchlist.items.some((item) => item.enabled !== false
      && clean(item.canonicalUrl || item.url) === newItem.canonicalUrl
      && sameSelections(item.selections, selections));
    if (duplicate) return null;
    watchlist.items.push(newItem);
    return watchlist;
  });
  sessions.delete(session.id);
  await interaction.update({
    content: duplicate
      ? `이미 같은 조건을 감시 중입니다.\n\n${optionSummary(selections)}`
      : `✅ **재입고 감시 등록**\n${session.snapshot.title}\n\n${optionSummary(selections)}`,
    components: []
  });
  if (!duplicate && BOT_MONITOR_ENABLED) void runMonitorOnce();
}

async function handleCancel(interaction, parts) {
  const session = sessionFor(interaction, parts[2]);
  if (session) sessions.delete(session.id);
  await interaction.update({ content: '재입고 감시 등록을 취소했습니다.', components: [] });
}

function listRows(items) {
  const rows = [];
  const shown = items.slice(0, 20);
  for (let start = 0; start < shown.length; start += 5) {
    const row = new ActionRowBuilder();
    for (let index = start; index < Math.min(shown.length, start + 5); index += 1) {
      row.addComponents(new ButtonBuilder()
        .setCustomId(`restock|delete|${shown[index].id}`)
        .setLabel(`삭제 ${index + 1}`)
        .setStyle(ButtonStyle.Danger));
    }
    rows.push(row);
  }
  return rows;
}

async function handleList(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const { value: watchlist } = await readStored(WATCHLIST_PATH, WATCHLIST_FALLBACK);
  const items = (Array.isArray(watchlist.items) ? watchlist.items : []).filter((item) => item.enabled !== false);
  if (!items.length) return interaction.reply({ content: '현재 재입고 감시 상품이 없습니다.', ephemeral: true });
  const lines = items.slice(0, 20).map((item, index) => `${index + 1}. **${short(item.title || item.url, 80)}**\n${optionSummary(item.selections).replaceAll('\n', ' · ')}`);
  if (items.length > 20) lines.push(`\n외 ${items.length - 20}개 — 삭제는 20개씩 표시됩니다.`);
  await interaction.reply({ content: lines.join('\n\n'), components: listRows(items), ephemeral: true });
}

async function handleDelete(interaction, parts) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const itemId = parts[2];
  let removed = null;
  await updateWatchlist('Remove restock watch', (watchlist) => {
    watchlist.items = Array.isArray(watchlist.items) ? watchlist.items : [];
    const index = watchlist.items.findIndex((item) => item.id === itemId);
    if (index < 0) return null;
    [removed] = watchlist.items.splice(index, 1);
    watchlist.version = 2;
    return watchlist;
  });
  await interaction.update({
    content: removed ? `🗑️ 감시 삭제: **${removed.title || removed.url}**` : '이미 삭제된 감시 항목입니다.',
    components: []
  });
}

function updater(interaction) {
  return {
    id: interaction.user.id,
    name: interaction.user.globalName || interaction.user.username || interaction.user.id
  };
}

async function handleMonitorStart(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  await updateControl('Start restock monitoring', (control) => {
    control.version = 1;
    control.mode = 'manual';
    control.schedule = null;
    control.updatedAt = new Date().toISOString();
    control.updatedBy = updater(interaction);
    return control;
  });
  await interaction.reply({ content: '🟢 **재입고 감시 ON**\n지금부터 수동 감시 모드입니다.', ephemeral: true });
}

async function handleMonitorStop(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  await updateControl('Stop restock monitoring', (control) => {
    control.version = 1;
    control.mode = 'off';
    control.schedule = null;
    control.updatedAt = new Date().toISOString();
    control.updatedBy = updater(interaction);
    return control;
  });
  await interaction.reply({ content: '⚪ **재입고 감시 OFF**\n등록된 예약도 해제했습니다.', ephemeral: true });
}

async function handleMonitorSchedule(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const startText = interaction.options.getString('시작', true);
  const endText = interaction.options.getString('종료', true);
  let startAt;
  let endAt;
  try {
    startAt = parseKstDateTime(startText);
    endAt = parseKstDateTime(endText);
  } catch (error) {
    return interaction.reply({ content: `예약 시간을 확인해주세요.\n${error.message}`, ephemeral: true });
  }
  if (Date.parse(endAt) <= Date.parse(startAt)) {
    return interaction.reply({ content: '종료 시간은 시작 시간보다 뒤여야 합니다.', ephemeral: true });
  }
  if (Date.parse(endAt) <= Date.now()) {
    return interaction.reply({ content: '이미 종료된 시간으로는 예약할 수 없습니다.', ephemeral: true });
  }

  await updateControl('Schedule restock monitoring', (control) => {
    control.version = 1;
    control.mode = 'scheduled';
    control.schedule = { startAt, endAt, timezone: 'Asia/Seoul' };
    control.updatedAt = new Date().toISOString();
    control.updatedBy = updater(interaction);
    return control;
  });

  const status = monitoringStatus({ mode: 'scheduled', schedule: { startAt, endAt } });
  await interaction.reply({
    content: [
      '⏰ **재입고 감시 예약**',
      `시작: **${formatKstDateTime(startAt)}**`,
      `종료: **${formatKstDateTime(endAt)}**`,
      `현재: **${status.active ? '🟢 감시 중' : '🟡 예약 대기'}**`
    ].join('\n'),
    ephemeral: true
  });
}

async function handleMonitorStatus(interaction) {
  if (!authorized(interaction)) return rejectUnauthorized(interaction);
  const [{ value: control }, { value: watchlist }] = await Promise.all([
    readStored(CONTROL_PATH, CONTROL_FALLBACK),
    readStored(WATCHLIST_PATH, WATCHLIST_FALLBACK)
  ]);
  const status = monitoringStatus(control);
  const count = (Array.isArray(watchlist.items) ? watchlist.items : []).filter((item) => item.enabled !== false).length;
  const modeText = status.mode === 'manual' ? '수동' : status.mode === 'scheduled' ? '예약' : '중지';
  const lines = [
    '📡 **재입고 감시 상태**',
    `상태: **${status.active ? '🟢 ON' : '⚪ OFF'}** · ${status.reason}`,
    `모드: **${modeText}**`,
    `등록 상품: **${count}개**`
  ];
  if (status.control.schedule?.startAt && status.control.schedule?.endAt) {
    lines.push(`예약: **${formatKstDateTime(status.control.schedule.startAt)} ~ ${formatKstDateTime(status.control.schedule.endAt)}**`);
  }
  await interaction.reply({ content: lines.join('\n'), ephemeral: true });
}

async function sendRestock({ item, snapshot, match }) {
  if (!ALERT_CHANNEL_ID) throw new Error('RESTOCK_ALERT_CHANNEL_ID is not configured; restock transition remains pending');
  const channel = await client.channels.fetch(ALERT_CHANNEL_ID);
  if (!channel?.isTextBased()) throw new Error('RESTOCK_ALERT_CHANNEL_ID is not a text channel');
  const variants = match.availableVariants.slice(0, 8).map((variant) => `• ${variant.title || variant.id}`).join('\n');
  const more = match.availableVariants.length > 8 ? `\n외 ${match.availableVariants.length - 8}개` : '';
  await channel.send({
    embeds: [{
      title: `📦 재입고 · ${snapshot.title}`,
      url: snapshot.canonicalUrl,
      fields: [
        { name: '감시 조건', value: optionSummary(item.selections), inline: false },
        { name: '구매 가능한 조합', value: `${variants || '구매 가능'}${more}`, inline: false },
        { name: '링크', value: `[🔗 상품 페이지](${snapshot.canonicalUrl})`, inline: false }
      ],
      footer: { text: `${snapshot.provider} · Restock Watch` },
      timestamp: new Date().toISOString()
    }]
  });
}

async function saveBotState(value, sha) {
  if (githubStorageConfigured()) return writeGitHubJson(STATE_PATH, value, 'Update restock bot state', sha);
  writeLocalJson(STATE_PATH, value);
  return null;
}

async function runMonitorOnce() {
  if (!BOT_MONITOR_ENABLED || monitorRunning || !client.isReady()) return;
  monitorRunning = true;
  try {
    const [controlRecord, { value: watchlist }, stateRecord] = await Promise.all([
      readStored(CONTROL_PATH, CONTROL_FALLBACK),
      readStored(WATCHLIST_PATH, WATCHLIST_FALLBACK),
      readStored(STATE_PATH, STATE_FALLBACK)
    ]);
    if (!monitoringStatus(controlRecord.value).active) return;
    const botWatchlist = {
      ...watchlist,
      items: (Array.isArray(watchlist.items) ? watchlist.items : []).filter((item) => ['bot', 'worker'].includes(item?.monitor))
    };
    const result = await checkRestock({ watchlist: botWatchlist, state: stateRecord.value, onRestock: sendRestock });
    if (result.changed) await saveBotState(result.state, stateRecord.sha);
    if (result.errors.length) {
      console.error(`[restock-bot] ${result.errors.length} item(s) failed during monitoring.`);
    }
  } catch (error) {
    console.error(`[restock-bot] monitor failed: ${error?.stack || error}`);
  } finally {
    monitorRunning = false;
  }
}

const client = new Client({ intents: [GatewayIntentBits.Guilds] });

client.once(Events.ClientReady, (readyClient) => {
  console.log(`[restock-bot] logged in as ${readyClient.user.tag}`);
  console.log(`[restock-bot] storage=${githubStorageConfigured() ? 'github' : 'local'} role=${BOT_MONITOR_ENABLED ? `manager+monitor(${POLL_SECONDS}s)` : 'manager-only'}`);
  if (BOT_MONITOR_ENABLED) {
    void runMonitorOnce();
    setInterval(() => void runMonitorOnce(), POLL_SECONDS * 1000).unref();
  }
});

client.on(Events.InteractionCreate, async (interaction) => {
  try {
    if (interaction.isChatInputCommand()) {
      if (interaction.commandName === '입고추가') return await handleAdd(interaction);
      if (interaction.commandName === '입고목록') return await handleList(interaction);
      if (interaction.commandName === '감시시작') return await handleMonitorStart(interaction);
      if (interaction.commandName === '감시중지') return await handleMonitorStop(interaction);
      if (interaction.commandName === '감시상태') return await handleMonitorStatus(interaction);
      if (interaction.commandName === '감시예약') return await handleMonitorSchedule(interaction);
    }
    if (interaction.isStringSelectMenu() && interaction.customId.startsWith('restock|select|')) {
      return await handleSelect(interaction, interaction.customId.split('|'));
    }
    if (interaction.isButton() && interaction.customId.startsWith('restock|')) {
      const parts = interaction.customId.split('|');
      if (parts[1] === 'page') return await handlePage(interaction, parts);
      if (parts[1] === 'confirm') return await handleConfirm(interaction, parts);
      if (parts[1] === 'cancel') return await handleCancel(interaction, parts);
      if (parts[1] === 'delete') return await handleDelete(interaction, parts);
    }
  } catch (error) {
    console.error(`[restock-bot] interaction failed: ${error?.stack || error}`);
    const message = `처리 중 오류가 발생했습니다.\n\`${short(error?.message || error, 1500)}\``;
    if (interaction.deferred || interaction.replied) await interaction.followUp({ content: message, ephemeral: true }).catch(() => {});
    else await interaction.reply({ content: message, ephemeral: true }).catch(() => {});
  }
});

await client.login(BOT_TOKEN);
