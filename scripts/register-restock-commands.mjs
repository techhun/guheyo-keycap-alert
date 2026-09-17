const token = (process.env.DISCORD_BOT_TOKEN || '').trim();
const applicationId = (process.env.DISCORD_APPLICATION_ID || '').trim();
const guildId = (process.env.DISCORD_GUILD_ID || '').trim();

if (!token) throw new Error('DISCORD_BOT_TOKEN is not configured');
if (!applicationId) throw new Error('DISCORD_APPLICATION_ID is not configured');

const commands = [
  {
    name: '입고추가',
    description: '상품 URL에서 옵션을 선택해 재입고 감시를 추가합니다.',
    options: [
      {
        type: 3,
        name: 'url',
        description: '감시할 상품 URL',
        required: true
      }
    ]
  },
  {
    name: '입고목록',
    description: '현재 재입고 감시 목록을 확인하고 삭제합니다.'
  },
  {
    name: '감시시작',
    description: '재입고 감시를 지금부터 수동으로 시작합니다.'
  },
  {
    name: '감시중지',
    description: '재입고 감시를 중지하고 예약을 해제합니다.'
  },
  {
    name: '감시상태',
    description: '현재 재입고 감시 상태와 예약 시간을 확인합니다.'
  },
  {
    name: '감시예약',
    description: '한국시간 기준으로 재입고 감시 시작/종료 시간을 예약합니다.',
    options: [
      {
        type: 3,
        name: '시작',
        description: '예: 2026-09-20 19:50 또는 09-20 19:50',
        required: true
      },
      {
        type: 3,
        name: '종료',
        description: '예: 2026-09-20 22:00 또는 09-20 22:00',
        required: true
      }
    ]
  }
];

const endpoint = guildId
  ? `https://discord.com/api/v10/applications/${applicationId}/guilds/${guildId}/commands`
  : `https://discord.com/api/v10/applications/${applicationId}/commands`;

const response = await fetch(endpoint, {
  method: 'PUT',
  headers: {
    Authorization: `Bot ${token}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(commands)
});
if (!response.ok) throw new Error(`Discord command registration failed: ${response.status} ${await response.text()}`);
const registered = await response.json();
console.log(`Registered ${registered.length} restock command(s)${guildId ? ` for guild ${guildId}` : ' globally'}.`);
