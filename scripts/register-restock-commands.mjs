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
