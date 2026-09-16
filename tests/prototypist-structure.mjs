import { chromium } from 'playwright';

const url = 'https://prototypist.notion.site/dfedac384a2b494c9137be7638b11e93?v=11769ac8c9764aafb907fe3d65fb0c50';
const statuses = ['In Group Buy','In Manufacturing','Shipping to Prototypist','In Quality Control','Shipping to Customers','Completed'];
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1800, height: 1400 } });
await page.goto(url,{waitUntil:'domcontentloaded',timeout:60000});
await page.waitForTimeout(10000);
const out = await page.evaluate((statuses) => {
  const clean = s => (s||'').replace(/\s+/g,' ').trim();
  const result=[];
  for (const status of statuses) {
    const all=[...document.querySelectorAll('*')].filter(el => clean(el.textContent)===status);
    const el=all.sort((a,b)=>a.children.length-b.children.length)[0];
    if(!el){result.push({status,found:false});continue;}
    let cur=el;
    const levels=[];
    for(let i=0;i<8 && cur;i++,cur=cur.parentElement){
      const links=[...cur.querySelectorAll('a')].map(a=>clean(a.innerText)).filter(Boolean);
      levels.push({i,tag:cur.tagName,role:cur.getAttribute('role'),cls:cur.className?.toString().slice(0,120),text:clean(cur.innerText).slice(0,300),links:links.slice(0,20),linkCount:links.length,childCount:cur.children.length});
    }
    result.push({status,found:true,levels});
  }
  return result;
}, statuses);
console.log(JSON.stringify(out,null,2));
await browser.close();
