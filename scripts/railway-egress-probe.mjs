const url = 'https://smartstore.naver.com/swagkey/products/12348949592';
const response = await fetch(url, {
  redirect: 'follow',
  headers: {
    'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140 Safari/537.36',
    'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'accept-language': 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7'
  }
});
const text = await response.text();
const title = (text.match(/<title[^>]*>([^<]*)<\/title>/i)?.[1] || '').trim().replace(/\s+/g, ' ');
console.log(JSON.stringify({ status: response.status, finalUrl: response.url, title, len: text.length }));
setTimeout(() => process.exit(0), 30000);
