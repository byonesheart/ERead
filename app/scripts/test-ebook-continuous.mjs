// Real Chromium iframe layout/scrolling; no WebView or geometry mocks.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.EREAD_PLAYWRIGHT || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));
const outputAt = process.argv.indexOf('--output');
const output = outputAt >= 0 ? path.resolve(process.argv[outputAt + 1]) : path.join(root, '.tools/ebook-continuous-qa');
await fs.mkdir(output, {recursive:true});
const childScript = await fs.readFile(path.join(root, 'app/src/main/assets/reader/ebook-reader.js'), 'utf8');
const windowScript = await fs.readFile(path.join(root, 'app/src/main/assets/reader/ebook-continuous.js'), 'utf8');
const chapters = Array.from({length:8}, (_, index) => {
  const paragraphs = Array.from({length:16}, (_, n) => `章节${index}的段落${n}：${'风吹过书页，读者沿着原来的位置继续阅读。'.repeat(4)}`);
  const canonical = [`第${index}章`, ...paragraphs, '原书注释[1]', `章节${index}的注释内容`].join('\n\n');
  const body = `<h2 id="shared">第${index}章</h2>${paragraphs.map((p,n)=>`<p id="p${n}" class="same">${p}</p>`).join('')}` +
    `<p>原书注释<a role="doc-noteref" href="#note">[1]</a></p><aside id="note">章节${index}的注释内容</aside>`;
  return {canonical,body,color:index % 2 ? 'rgb(30, 70, 150)' : 'rgb(130, 40, 30)',paragraphs};
});
const config = {mode:'SCROLL',turn:'SLIDE',authorCss:true,fontSize:19,lineHeight:35,letterSpacing:.5,paragraphGap:15,
  left:24,right:24,top:18,bottom:18,color:'#242820',font:'sans-serif',fontUrl:'https://eread.local/font'};
const browser = await chromium.launch({headless:true,executablePath:process.env.EREAD_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const page = await browser.newPage({viewport:{width:392,height:760},hasTouch:true});
const results=[],errors=[];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => { if(message.type()==='error')console.log('BROWSER '+message.text()); });
const requests=[];
await page.exposeFunction('loadNativeChapter',async(index,token)=>{
  requests.push(index); await page.evaluate(({index,token})=>window.EReadContinuous.provide(index,token,`https://eread.local/chapters/${index}.html?token=${token}`),{index,token});
});
await page.addInitScript(()=>{
  if (window !== window.parent) return;
  window.events=[];window.__ERead={convert:text=>JSON.stringify({text}),event:json=>{
    const event=JSON.parse(json);window.events.push(event);
    if(event.type==='chapterRequest')window.loadNativeChapter(event.chapter,event.token);
  }};
});
function envelope(body,script,options,frames=false) {
  const csp=`default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-test'; img-src https://eread.local data:; font-src https://eread.local; frame-src ${frames?'https://eread.local':"'none'"}`;
  return `<html data-eread-loading><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="${csp}"><style>html[data-eread-loading] body{opacity:0!important}</style></head><body>${body}<script nonce="test">${script}\nERead.init(${JSON.stringify(options).replaceAll('<','\\u003c')});</script></body></html>`;
}
await page.route('https://eread.local/**',async route=>{
  const url=new URL(route.request().url()),match=url.pathname.match(/^\/chapters\/(\d+)\.html$/);
  if(url.pathname==='/root.html')return route.fulfill({contentType:'text/html',body:envelope('',windowScript,{
    config,initialNavigation:{type:'navigate',chapter:0,offset:0},chapters:chapters.map((c,i)=>({title:`第${i}章`,length:c.canonical.length}))
  },true)});
  if(match){const index=Number(match[1]),chapter=chapters[index];return route.fulfill({contentType:'text/html',body:envelope(
    `<style>.same{color:${chapter.color}} p{line-height:35px}</style>`+chapter.body,childScript,{canonical:chapter.canonical,
      config:{...config,continuous:true},images:[],noteTargets:['#note'],notes:[],decorations:{annotations:[],search:'',spokenStart:-1,spokenEnd:-1}})});}
  return route.fulfill({status:404,body:''});
});
async function test(name,run){try{const evidence=await run();results.push({name,passed:true,evidence});console.log('PASS '+name)}catch(error){
  results.push({name,passed:false,error:error.stack});console.log('FAIL '+name+': '+error.message);await page.screenshot({path:path.join(output,name+'.png')}).catch(()=>{});
}}
async function ready(index){await page.waitForFunction(index=>window.events.some(e=>e.type==='viewport'&&e.chapter===index),index,{timeout:15000});}
async function jump(index,offset=0){await page.evaluate(({index,offset})=>{window.events=[];ERead.command({type:'navigate',chapter:index,offset});},{index,offset});await ready(index);}
try{
 await page.goto('https://eread.local/root.html');await ready(0);
 await test('independent-css-and-bounded-adjacent-preload',async()=>{
   await page.waitForFunction(()=>document.querySelectorAll('iframe').length===2&&Array.from(document.querySelectorAll('iframe')).every(f=>f.style.opacity==='1'));
   const colors=await page.evaluate(()=>Array.from(document.querySelectorAll('iframe')).map(f=>getComputedStyle(f.contentDocument.querySelector('.same')).color));
   assert.deepEqual(colors,[chapters[0].color,chapters[1].color]);
   const heights=await page.evaluate(()=>Array.from(document.querySelectorAll('section')).slice(0,2).map(s=>s.offsetHeight));
   await page.waitForTimeout(250);assert.deepEqual(await page.evaluate(()=>Array.from(document.querySelectorAll('section')).slice(0,2).map(s=>s.offsetHeight)),heights);
   assert.deepEqual([...new Set(requests)].sort(),[0,1]);return {colors,heights};
 });
 await test('two-chapters-visible-at-the-seam-without-document-reload',async()=>{
   await page.evaluate(()=>{window.original=document.querySelector('iframe');scrollTo(0,document.querySelector('section').offsetHeight-180)});
   await page.waitForTimeout(150);
   const visible=await page.evaluate(()=>Array.from(document.querySelectorAll('section')).filter(s=>{const r=s.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight}).map(s=>Number(s.dataset.chapter)));
   assert.deepEqual(visible,[0,1]);
   await page.mouse.move(200,400);await page.mouse.wheel(0,450);await ready(1);
   assert.equal(await page.evaluate(()=>document.querySelector('iframe')===window.original),true);
   return {visible,wheelCrossedChapter:true};
 });
 await test('distant-navigation-restores-canonical-anchor-and-retires-old-dom',async()=>{
   const offset=chapters[6].canonical.indexOf(chapters[6].paragraphs[10]);await jump(6,offset);
   await page.waitForFunction(()=>document.querySelectorAll('iframe').length===3&&Array.from(document.querySelectorAll('iframe')).every(f=>f.style.opacity==='1'));
   const state=await page.evaluate(()=>window.events.filter(e=>e.type==='viewport').at(-1));
   assert.ok(state.offset<=offset+2&&state.endOffset>offset);
   assert.deepEqual(await page.evaluate(()=>Array.from(document.querySelectorAll('iframe')).map(f=>Number(f.dataset.chapter)).sort()),[5,6,7]);
   assert.equal(await page.evaluate(()=>window.original.isConnected),false);return state;
 });
 await test('child-note-and-selection-keep-chapter-and-window-coordinates',async()=>{
   const offset=chapters[6].canonical.indexOf('原书注释');await jump(6,offset);
   const child=page.frames().find(f=>/\/6\.html/.test(f.url()));
   await child.locator('a').click();
   const note=await page.evaluate(()=>window.events.findLast(e=>e.type==='footnote'));
   assert.equal(note.chapter,6);assert.equal(note.target,'#note');assert.ok(note.top>=0&&note.bottom<=760);
   await child.locator('#p15').evaluate(p=>{const r=document.createRange();r.selectNodeContents(p);getSelection().removeAllRanges();getSelection().addRange(r)});
   await page.waitForFunction(()=>window.events.some(e=>e.type==='selection'));
   const selection=await page.evaluate(()=>window.events.findLast(e=>e.type==='selection'));
   assert.equal(selection.chapter,6);assert.ok(selection.end>selection.start);return {note,selection};
 });
 await test('backward-crossing-retains-the-visible-text-while-previous-height-loads',async()=>{
   const offset=chapters[1].canonical.indexOf(chapters[1].paragraphs[7]);await jump(1,offset);
   await page.waitForFunction(()=>document.querySelectorAll('iframe').length===3&&Array.from(document.querySelectorAll('iframe')).every(f=>f.style.opacity==='1'));
   const state=await page.evaluate(()=>window.events.filter(e=>e.type==='viewport').at(-1));
   assert.equal(state.chapter,1);assert.ok(state.offset<=offset+2&&state.endOffset>offset);return state;
 });
 await test('real-touch-chains-from-child-to-parent-across-the-seam',async()=>{
   await jump(0);
   await page.waitForFunction(()=>document.querySelector('iframe[data-chapter="1"]')?.style.opacity==='1');
   await page.evaluate(()=>scrollTo(0,document.querySelector('section').offsetHeight-460));
   const cdp=await page.context().newCDPSession(page);
   await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:190,y:600}]});
   for(let y=570;y>=90;y-=30){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:190,y}]});await page.waitForTimeout(15);}
   await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await ready(1);await cdp.detach();
   assert.equal(await page.evaluate(()=>events.some(e=>e.type==='chapterStep')),false);return {touchCrossedChapter:true};
 });
 await test('warm-resume-and-offscreen-reflow-preserve-the-canonical-position',async()=>{
   const offset=chapters[4].canonical.indexOf(chapters[4].paragraphs[7]);await jump(4,offset);
   await page.waitForFunction(()=>document.querySelectorAll('iframe').length===3&&Array.from(document.querySelectorAll('iframe')).every(f=>f.style.opacity==='1'));
   const before=await page.evaluate(()=>{window.warmFrame=document.querySelector('iframe[data-chapter="4"]');return events.filter(e=>e.type==='viewport').at(-1)});
   await page.evaluate(options=>{events=[];ERead.command({type:'resume',options})},{config,
     chapters:chapters.map((c,i)=>({title:`第${i}章`,length:c.canonical.length})),preserveViewport:true,initialNavigation:{chapter:4,offset:0}});
   await ready(4);
   assert.equal(await page.evaluate(()=>document.querySelector('iframe[data-chapter="4"]')===window.warmFrame),true);
   await page.evaluate(()=>{const child=document.querySelector('iframe[data-chapter="3"]').contentDocument;const style=child.createElement('style');style.textContent='p{line-height:65px!important}';child.head.appendChild(style)});
   await page.waitForTimeout(350);
   const after=await page.evaluate(()=>events.filter(e=>e.type==='viewport').at(-1));
   assert.equal(after.chapter,4);assert.ok(Math.abs(after.offset-before.offset)<=2);return {before,after};
 });
 await test('repeated-forward-and-back-navigation-stays-within-three-chapter-doms',async()=>{
   for(const index of [7,2,5,0,6,1]){
     await jump(index,chapters[index].canonical.indexOf(chapters[index].paragraphs[5]));
     await page.waitForTimeout(150);
     const live=await page.evaluate(()=>Array.from(document.querySelectorAll('iframe')).map(f=>Number(f.dataset.chapter)));
     assert.ok(live.length<=3);assert.ok(live.every(i=>Math.abs(i-index)<=1));
   }
   return {visited:[7,2,5,0,6,1],maxDomCount:3};
 });
 await test('warm-resume-with-a-new-anchor-sends-ready-before-the-restored-viewport',async()=>{
   const offset=chapters[5].canonical.indexOf(chapters[5].paragraphs[8]);
   await page.evaluate(options=>{events=[];ERead.command({type:'resume',options})},{config,
     chapters:chapters.map((c,i)=>({title:`第${i}章`,length:c.canonical.length})),preserveViewport:false,
     initialNavigation:{type:'navigate',chapter:5,offset}});
   await ready(5);
   const events=await page.evaluate(()=>window.events);
   const readyAt=events.findIndex(e=>e.type==='ready'),viewportAt=events.findIndex(e=>e.type==='viewport'&&e.chapter===5);
   assert.ok(readyAt>=0&&readyAt<viewportAt);
   const state=events[viewportAt];assert.ok(state.offset<=offset+2&&state.endOffset>offset);return state;
 });
 await test('live-conversion-keeps-loaded-chapter-frames-and-the-current-anchor',async()=>{
   const offset=chapters[5].canonical.indexOf(chapters[5].paragraphs[8]);await jump(5,offset);
   await page.waitForFunction(()=>document.querySelectorAll('iframe').length===3&&Array.from(document.querySelectorAll('iframe')).every(frame=>frame.style.opacity==='1'));
   await page.evaluate(()=>{
     window.conversionFrames=Array.from(document.querySelectorAll('iframe'));
     __ERead.convert=text=>{
       let display='';const forward=[0];
       for(let i=0;i<text.length;i++){display+=text[i]==='第'?'第📚':text[i];forward.push(display.length)}
       return JSON.stringify({text:display,forward});
     };
     ERead.command({type:'convert'});
   });
   await page.waitForFunction(()=>document.querySelector('iframe[data-chapter="5"]').contentDocument.body.textContent.includes('第📚'));
   assert.equal(await page.evaluate(()=>window.conversionFrames.every(frame=>frame.isConnected&&frame.style.opacity==='1')),true);
   await page.evaluate(()=>{__ERead.convert=text=>JSON.stringify({text});ERead.command({type:'convert'})});
   await jump(5,offset);
   const state=await page.evaluate(()=>events.filter(e=>e.type==='viewport').at(-1));
   assert.ok(state.offset<=offset+2&&state.endOffset>offset);return {sameFrames:true,state};
 });
 await test('child-taps-report-the-outer-viewport-position',async()=>{
   await jump(3,chapters[3].canonical.indexOf(chapters[3].paragraphs[5]));
   await page.mouse.click(10,300);
   const tap=await page.evaluate(()=>events.findLast(event=>event.type==='tap'));
   assert.ok(tap);assert.equal(tap.chapter,3);
   assert.ok(Math.abs(tap.x-10/392)<.001);assert.ok(Math.abs(tap.y-300/760)<.001);return tap;
 });
 await test('vertical-writing-keeps-continuous-chapter-navigation-and-a-bounded-dom',async()=>{
   await page.evaluate(config=>ERead.command({type:'configure',config}),{...config,authorCss:false,vertical:true});
   const offset=chapters[5].canonical.indexOf(chapters[5].paragraphs[8]);await jump(5,offset);
   const child=page.frames().find(f=>/\/5\.html/.test(f.url()));
   assert.equal(await child.locator('#p8').evaluate(node=>getComputedStyle(node).writingMode),'vertical-rl');
   const state=await page.evaluate(()=>events.filter(e=>e.type==='viewport').at(-1));
   assert.equal(state.chapter,5);assert.ok(state.offset<=offset+2&&state.endOffset>offset);
   assert.ok(await page.evaluate(()=>document.querySelectorAll('iframe').length<=3));return state;
 });
}catch(error){
 console.log(JSON.stringify(await page.evaluate(()=>({events:window.events,scroll:scrollY,frames:Array.from(document.querySelectorAll('iframe')).map(f=>({src:f.src,height:f.offsetHeight,ready:f.contentDocument?.readyState,reader:!!f.contentWindow?.ERead,text:f.contentDocument?.body?.textContent.slice(-180)}))})),null,2));
 throw error;
}finally{
 const report={passed:results.filter(r=>r.passed).length,failed:results.filter(r=>!r.passed).length,errors,results};
 await fs.writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2));await browser.close();
 if(report.failed||errors.length)process.exitCode=1;
}
