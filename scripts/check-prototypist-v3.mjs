import fs from 'node:fs';
import { chromium } from 'playwright';

const STATE_PATH = 'prototypist-state.json';
const DISCORD_WEBHOOK_URL = (process.env.PROTOTYPIST_DISCORD_WEBHOOK_URL || '').trim();
const STATUSES = ['In Group Buy','In Manufacturing','Shipping to Prototypist','In Quality Control','Shipping to Customers','Completed'];
const SOURCES = [
  { id:'keyboard', label:'Keyboard', emoji:'⌨️', url:'https://prototypist.notion.site/57d8169dada041f6b52b1d091e3e2c97?v=449c85bd9a29455fa8d1330e23680c5a' },
  { id:'gmk', label:'GMK', emoji:'🇩🇪', url:'https://prototypist.notion.site/dfedac384a2b494c9137be7638b11e93?v=11769ac8c9764aafb907fe3d65fb0c50' },
  { id:'keyset', label:'Keyset', emoji:'🧢', url:'https://prototypist.notion.site/119c46c4a89140e98b295ed0102eb94e?v=a13cd09931964dd596b125ea0013f5c6' }
];

const clean = (v) => String(v ?? '').replace(/\r/g,'').replace(/\s+/g,' ').trim();
const truncate = (v,n=1000) => { const t=clean(v)||'—'; return t.length<=n?t:`${t.slice(0,n-1).trimEnd()}…`; };
function normalizeUrl(v){ try{const u=new URL(v);u.search='';u.hash='';return u.toString();}catch{return clean(v);} }
function parseCardText(v){
  const text=clean(v);
  const m=text.match(/\s+((?:Late\s+)?Q[1-4](?:\s*-\s*Q[1-4])?\s+\d{4}|Q[1-4]-Q[1-4]\s+\d{4}|TBC)$/i);
  return m?{product:clean(text.slice(0,m.index)),expectedShipping:clean(m[1])}:{product:text,expectedShipping:''};
}

async function extractPage(page, source){
  await page.goto(source.url,{waitUntil:'domcontentloaded',timeout:60000});
  await page.waitForLoadState('networkidle',{timeout:10000}).catch(()=>{});
  await page.waitForTimeout(12000);

  return page.evaluate((statuses)=>{
    const cleanText=(v)=>String(v??'').replace(/\s+/g,' ').trim();
    const visible=(el)=>{const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';};
    const headers=[];
    for(const status of statuses){
      const candidates=[...document.querySelectorAll('*')]
        .filter(el=>visible(el)&&cleanText(el.innerText)===status)
        .sort((a,b)=>{
          const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
          const ap=a.getAttribute('role')==='button'?0:1, bp=b.getAttribute('role')==='button'?0:1;
          return ap!==bp?ap-bp:(ar.width*ar.height)-(br.width*br.height);
        });
      if(!candidates[0]) continue;
      const r=candidates[0].getBoundingClientRect();
      headers.push({status,centerX:r.left+r.width/2});
    }
    const currentPath=location.pathname.replace(/\/$/,'');
    const anchors=[...document.querySelectorAll('a')].map(a=>{
      const r=a.getBoundingClientRect();
      return {text:cleanText(a.innerText),href:a.href||'',centerX:r.left+r.width/2,width:r.width,height:r.height};
    }).filter(x=>x.text&&x.width>0&&x.height>0).filter(x=>{
      try{const u=new URL(x.href);return u.hostname==='prototypist.notion.site'&&u.pathname.replace(/\/$/,'')!==currentPath&&!u.hash;}catch{return false;}
    });
    const rows=anchors.map(row=>{
      let nearest=null;
      for(const h of headers){const d=Math.abs(row.centerX-h.centerX);if(!nearest||d<nearest.distance)nearest={...h,distance:d};}
      return {...row,status:nearest?.status||'',statusDistance:nearest?.distance??9999};
    });
    return {title:document.title,headerCount:headers.length,anchorCount:anchors.length,rows};
  },STATUSES);
}

async function fetchSource(browser,source){
  let lastError;
  for(let attempt=1;attempt<=3;attempt++){
    const page=await browser.newPage({viewport:{width:2200,height:1600},locale:'en-GB',userAgent:'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'});
    try{
      const extracted=await extractPage(page,source);
      if(extracted.headerCount<4) throw new Error(`${source.label} rendered only ${extracted.headerCount} status headers; anchors=${extracted.anchorCount}; title=${extracted.title}`);
      const deduped=new Map();
      for(const raw of extracted.rows){
        if(!STATUSES.includes(raw.status)||raw.statusDistance>180) continue;
        const itemUrl=normalizeUrl(raw.href);
        const {product,expectedShipping}=parseCardText(raw.text);
        if(!itemUrl||!product) continue;
        deduped.set(itemUrl,{sourceId:source.id,sourceLabel:source.label,sourceEmoji:source.emoji,sourceUrl:source.url,itemUrl,product,status:raw.status,expectedShipping});
      }
      const rows=[...deduped.values()];
      if(rows.length<5) throw new Error(`${source.label} returned too few rows: ${rows.length}; anchors=${extracted.anchorCount}; headers=${extracted.headerCount}`);
      console.log(`Proto[Typist] ${source.label} rows: ${rows.length} (headers ${extracted.headerCount}, anchors ${extracted.anchorCount})`);
      console.log(`Proto[Typist] ${source.label} sample:`,JSON.stringify(rows.slice(0,3),null,2));
      return rows;
    }catch(e){
      lastError=e;
      console.warn(`Proto[Typist] ${source.label} attempt ${attempt} failed: ${e?.message||e}`);
      if(attempt<3) await new Promise(r=>setTimeout(r,2500));
    }finally{await page.close();}
  }
  throw lastError;
}

async function fetchRows(){
  const browser=await chromium.launch({headless:true,channel:'chrome'});
  try{const all=[];for(const source of SOURCES)all.push(...await fetchSource(browser,source));return all;}finally{await browser.close();}
}
function loadState(){try{const p=JSON.parse(fs.readFileSync(STATE_PATH,'utf8'));return p&&typeof p==='object'?p:null;}catch{return null;}}
function saveState(rows){fs.writeFileSync(STATE_PATH,JSON.stringify({version:1,initialized:true,updatedAt:new Date().toISOString(),rows},null,2)+'\n');}
function buildMap(rows){const m=new Map();for(const r of rows)m.set(normalizeUrl(r.itemUrl)||`${r.sourceId}|${clean(r.product).toLowerCase()}`,r);return m;}
function sourceCounts(rows){return Object.fromEntries(SOURCES.map(s=>[s.id,rows.filter(r=>r.sourceId===s.id).length]));}
function diffRows(previousRows,currentRows){
  const previous=buildMap(previousRows),current=buildMap(currentRows),changes=[];
  for(const [key,row] of current){
    const before=previous.get(key);
    if(!before){changes.push({kind:'added',row});continue;}
    const fields=[];
    if(clean(before.status)!==clean(row.status))fields.push({label:'상태',before:before.status||'—',after:row.status||'—'});
    if(clean(before.expectedShipping)!==clean(row.expectedShipping))fields.push({label:'예상 고객 배송',before:before.expectedShipping||'—',after:row.expectedShipping||'—'});
    if(clean(before.product)!==clean(row.product))fields.push({label:'제품명',before:before.product||'—',after:row.product||'—'});
    if(fields.length)changes.push({kind:'changed',row,before,fields});
  }
  for(const [key,row] of previous)if(!current.has(key))changes.push({kind:'removed',row});
  return changes;
}
async function postDiscord(embed){
  for(let attempt=1;attempt<=3;attempt++){
    const response=await fetch(DISCORD_WEBHOOK_URL,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'Proto[Typist] Updates',allowed_mentions:{parse:[]},embeds:[embed]})});
    if(response.ok)return;
    if(response.status===429&&attempt<3){let retryAfter=1;try{const b=await response.json();retryAfter=Number(b?.retry_after)||1;}catch{}await new Promise(r=>setTimeout(r,Math.ceil(retryAfter*1000)));continue;}
    throw new Error(`Discord webhook failed: ${response.status} ${await response.text()}`);
  }
}
function baseEmbed(row,title){return{title:truncate(title,250),url:row.itemUrl||row.sourceUrl,footer:{text:`Proto[Typist] · ${row.sourceLabel} Updates`},timestamp:new Date().toISOString()};}
function addedEmbed(row){return{...baseEmbed(row,`🆕 ${row.sourceEmoji} ${row.sourceLabel} 추가 · ${row.product}`),fields:[{name:'상태',value:truncate(row.status),inline:true},{name:'예상 고객 배송',value:truncate(row.expectedShipping||'—'),inline:true}]};}
function changedEmbed(c){return{...baseEmbed(c.row,`🔄 ${c.row.sourceEmoji} ${c.row.sourceLabel} 업데이트 · ${c.row.product}`),fields:c.fields.map(f=>({name:f.label,value:truncate(`이전: ${f.before}\n현재: ${f.after}`),inline:false}))};}
function removedEmbed(row){return{...baseEmbed(row,`➖ ${row.sourceEmoji} ${row.sourceLabel} 목록에서 제거 · ${row.product}`),description:'Proto[Typist]의 현재 업데이트 보드에서 사라졌습니다.',fields:[{name:'마지막 상태',value:truncate(row.status),inline:true},{name:'예상 고객 배송',value:truncate(row.expectedShipping||'—'),inline:true}]};}
async function notify(c){if(c.kind==='added')return postDiscord(addedEmbed(c.row));if(c.kind==='changed')return postDiscord(changedEmbed(c));return postDiscord(removedEmbed(c.row));}

const rows=await fetchRows();
console.log(`Proto[Typist] total rows: ${rows.length}`);
console.log('Proto[Typist] counts:',JSON.stringify(sourceCounts(rows)));
const state=loadState();
const previousRows=Array.isArray(state?.rows)?state.rows:[];
if(!state?.initialized||previousRows.length===0){console.log(`Baseline initialization: storing ${rows.length} Proto[Typist] rows without notifying.`);saveState(rows);process.exit(0);}
const previousCounts=sourceCounts(previousRows),currentCounts=sourceCounts(rows);
for(const source of SOURCES){const before=previousCounts[source.id]||0,after=currentCounts[source.id]||0;if(before>0&&after<Math.max(1,Math.floor(before*0.5)))throw new Error(`Proto[Typist] ${source.label} row count dropped unexpectedly: ${before} -> ${after}. State was not updated.`);}
const changes=diffRows(previousRows,rows);
console.log(`Proto[Typist] changes: ${changes.length}`);
for(const c of changes)console.log(c.kind==='changed'?'Changed:':`${c.kind}:`,c.row.sourceLabel,c.row.product,c.kind==='changed'?c.fields.map(x=>x.label).join(', '):'');
if(changes.length===0){console.log('No Proto[Typist] state update needed.');process.exit(0);}
if(!DISCORD_WEBHOOK_URL){console.log('[discord] PROTOTYPIST_DISCORD_WEBHOOK_URL is not configured. Changes remain pending; state was not updated.');process.exit(0);}
for(const c of changes)await notify(c);
saveState(rows);
console.log(`Sent ${changes.length} Proto[Typist] notification(s) and updated state.`);
