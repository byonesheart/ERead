// Actual Chromium DOM/CSS/selection tests. No Android WebView or geometry mocks.
// Run with node app/scripts/test-ebook-reader.mjs [--output <directory>].
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
let playwright;
try { playwright = require(process.env.EREAD_PLAYWRIGHT || 'playwright-core'); }
catch (error) {
  if (process.env.EREAD_PLAYWRIGHT) throw error;
  playwright = require(path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));
}
const browserPath = process.env.EREAD_BROWSER || path.join(process.env['ProgramFiles(x86)'] || 'C:/Program Files (x86)', 'Microsoft/Edge/Application/msedge.exe');
const outputArgument = process.argv.indexOf('--output');
const outputDir = path.resolve(outputArgument >= 0 ? process.argv[outputArgument + 1] : path.join(scriptDir, '../.tools/ebook-dom-qa'));
const filterArgument = process.argv.indexOf('--filter');
const testFilter = filterArgument >= 0 ? new RegExp(process.argv[filterArgument + 1]) : null;
const manifestsArgument = process.argv.indexOf('--source-manifests');
const manifestsDir = manifestsArgument >= 0 ? path.resolve(process.argv[manifestsArgument + 1]) : null;
const resourcesArgument = process.argv.indexOf('--source-resources');
const resourcesDir = resourcesArgument >= 0 ? path.resolve(process.argv[resourcesArgument + 1]) : null;
const screenshots = process.argv.includes('--screenshots');
const startupArgument = process.argv.indexOf('--startup-manifest');
const startupManifest = startupArgument >= 0 ? path.resolve(process.argv[startupArgument + 1]) : null;
await fs.mkdir(outputDir, { recursive: true });
const fixtureDir = path.join(scriptDir, 'fixtures/ebook-reader');
const readerScript = await fs.readFile(path.join(scriptDir, '../app/src/main/assets/reader/ebook-reader.js'), 'utf8');
const template = await fs.readFile(path.join(fixtureDir, 'chapter.html'), 'utf8');
const lines = Array.from({ length: 40 }, (_, i) => `第${i + 1}段。${'春风吹过安静的书店，窗边的读者继续翻阅手中的故事。'.repeat(3)}段落编号${i + 1}结束。`);
const html = template.replace('<!-- LONG_CONTENT -->', lines.map((text, i) => `<p id="paragraph-${i}">${text}</p>`).join('\n'));
// This is deliberately independent of the production mapping implementation.
const canonicalParts = [
  '第一章 原书结构', '小明说：“今天要验证原书结构。”[1]',
  '引用仍应保留原书的对齐、颜色和缩进。', '带有注音的阅读yuè dú也应保留。',
  '人物地点小明书店', '图片前的内容。', '跳转第三十一段', '普通正文包装加粗强调斜体强调', ...lines, '注释一：这里的注释须可点击显示。'
];
const canonical = canonicalParts.join('\n\n');
const baseConfig = {
  mode: 'SCROLL', turn: 'SLIDE', authorCss: true, dual: false, eink: false,
  fontSize: 19, lineHeight: 35.15, letterSpacing: .5, paragraphGap: 15.2,
  bold: false, night: false, color: '#242820', left: 24, right: 24, top: 18, bottom: 18,
  font: 'sans-serif', fontUrl: 'https://eread.local/reader-font'
};
const imageIdentity = '/private/book/inline-image-0.svg';
const browser = await playwright.chromium.launch({ executablePath: browserPath, headless: true });
const results = [];
const consoleErrors = [];
let page;

async function open(config = {}, options = {}) {
  if (page) await page.close();
  page = await browser.newPage({ viewport: options.viewport || { width: 392, height: 760 }, hasTouch: true, deviceScaleFactor: 1 });
  page.on('pageerror', error => consoleErrors.push(error.message));
  await page.addInitScript(conversion => {
    window.events = [];
    window.pageTransitions = [];
    if (document.startViewTransition) {
      const start = document.startViewTransition.bind(document);
      document.startViewTransition = update => {
        const transition = start(update); window.pageTransitions.push(transition.finished); return transition;
      };
    }
    window.__ERead = {
      event: json => {
        const event = JSON.parse(json); window.events.push(event);
        // Compatibility with an older native-turn envelope. Current page turns
        // must remain entirely inside Chromium and never use this acknowledgement.
        if (event.type === 'turn') requestAnimationFrame(() => window.ERead.command({ type: 'page', page: event.page }));
      },
      convert: text => {
        if (!conversion) return JSON.stringify({ text });
        let display = ''; const forward = [0];
        for (let index = 0; index < text.length; index++) {
          display += conversion[text[index]] || text[index]; forward.push(display.length);
        }
        return JSON.stringify({ text: display, forward });
      }
    };
  }, options.conversion);
  await page.route('https://eread.local/**', async route => {
    const url = new URL(route.request().url());
    if (url.href === (options.source?.baseUrl || 'https://eread.local/book/archive/chapter.html')) {
      const nonce = 'actual-browser-test';
      const csp = `default-src 'none'; img-src https://eread.local data:; style-src https://eread.local 'unsafe-inline'; font-src https://eread.local data:; script-src 'nonce-${nonce}'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'`;
      const head = `<meta http-equiv="Content-Security-Policy" content="${csp}"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">`;
      const publisherHtml = (options.source?.html || html).replace(/<\/head\s*>/i, match => `${options.extraPublisherCss ? `<style>${options.extraPublisherCss}</style>` : ''}${match}`);
      const secured = publisherHtml.replace(/<head(?:\s[^>]*)?>/i, match => `${match}${head}`) + `<script nonce="${nonce}">${readerScript}</script>`;
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: secured });
    }
    if (url.pathname.endsWith('/publisher.css')) return route.fulfill({ contentType: 'text/css', body: await fs.readFile(path.join(fixtureDir, 'publisher.css')) });
    if (url.pathname.endsWith('/illustration.svg')) return route.fulfill({ contentType: 'image/svg+xml', body: await fs.readFile(path.join(fixtureDir, 'illustration.svg')) });
    if (url.pathname === '/reader-font' && ['reader_song.otf', 'reader_hei.otf'].includes(url.searchParams.get('font')))
      return route.fulfill({ contentType: 'font/otf', body: await fs.readFile(path.join(scriptDir, '../app/src/main/res/font', url.searchParams.get('font'))) });
    if (options.source && resourcesDir && url.pathname.includes('/archive/')) {
      const relative = decodeURIComponent(url.pathname.split('/archive/')[1]);
      const resourcePath = path.resolve(resourcesDir, relative);
      if (resourcePath.startsWith(`${resourcesDir}${path.sep}`)) {
        try {
          const contentType = { '.css': 'text/css', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif', '.otf': 'font/otf', '.ttf': 'font/ttf' }[path.extname(resourcePath).toLowerCase()] || 'application/octet-stream';
          return route.fulfill({ contentType, body: await fs.readFile(resourcePath) });
        } catch (error) { if (error.code !== 'ENOENT') throw error; }
      }
    }
    return route.fulfill({ status: 404, body: '' });
  });
  await page.goto(options.source?.baseUrl || 'https://eread.local/book/archive/chapter.html');
  await page.evaluate(options => { window.readerInitOptions = options; return window.ERead.init(options); }, {
    canonical: options.source?.canonicalText ?? canonical, config: { ...baseConfig, ...config },
    decorations: { annotations: [], search: '', spokenStart: -1, spokenEnd: -1 },
    images: options.source?.images || [{ offset: canonical.indexOf('图片前的内容。') + '图片前的内容。'.length, path: imageIdentity }],
    noteTargets: options.source ? Object.keys(options.source.noteTargets) : ['#note-1'],
    initialNavigation: options.initialNavigation
  });
  await page.waitForFunction(() => window.events.some(event => event.type === 'ready' || event.type === 'error'));
  const errors = await page.evaluate(() => window.events.filter(event => event.type === 'error'));
  assert.deepEqual(errors, [], `Initialisation errors: ${JSON.stringify(errors)}`);
  if (!options.noInitialReport) await command({ type: 'report' });
}

async function command(value) {
  await page.evaluate(value => window.ERead.command(value), value);
  await page.evaluate(() => Promise.all(window.pageTransitions));
    await page.evaluate(() => Promise.all(document.getAnimations().filter(animation => animation.id === 'eread-page-turn')
      .map(animation => animation.finished.catch(() => {}))));
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function viewport() {
  await command({ type: 'report' });
  return page.evaluate(() => window.events.filter(event => event.type === 'viewport').at(-1));
}

async function textRect(id, at = 0) {
  return page.evaluate(({ id, at }) => {
    const node = document.getElementById(id).firstChild;
    const range = document.createRange(); range.setStart(node, at); range.setEnd(node, at + 1);
    const rect = range.getBoundingClientRect();
    return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
  }, { id, at });
}

function onScreen(rect, width = 392, height = 760) {
  return rect.right > 0 && rect.left < width && rect.bottom > 0 && rect.top < height;
}

async function test(name, callback) {
  if (testFilter && !testFilter.test(name)) return;
  try {
    const evidence = await callback(); results.push({ name, passed: true, evidence }); console.log(`PASS ${name}`);
    if (screenshots && page) await page.screenshot({ path: path.join(outputDir, `${name}.png`) });
  }
  catch (error) {
    results.push({ name, passed: false, error: error.stack }); console.log(`FAIL ${name}: ${error.message}`);
    if (page) await page.screenshot({ path: path.join(outputDir, `${name}.png`) }).catch(() => {});
  }
}

try {
  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) await test(`publication-background-and-protected-ink-${mode}`, async () => {
    const paragraphs = Array.from({ length: 25 }, (_, i) => `第${i}段原书画布和插图配色应保持完整。`.repeat(5));
    const text = paragraphs.join('\n\n');
    await open({ mode, authorCss: true, overrideInk: true, paper: '#44474c', color: '#bfc0c4' }, { source: {
      baseUrl: 'https://eread.local/book/archive/chapter.html', canonicalText: text, images: [], noteTargets: {},
      html: '<!doctype html><html><head><style>html,body{height:95%}body{background:linear-gradient(#183b28,#183b28);color:white}</style></head><body>' +
        paragraphs.map(p => `<p>${p}</p>`).join('') + '</body></html>'
    } });
    const evidence = await page.evaluate(() => {
      const canvas = document.getElementById('eread-publication-background');
      return { canvas: canvas && { width: canvas.getBoundingClientRect().width, height: canvas.getBoundingClientRect().height,
        image: getComputedStyle(canvas).backgroundImage }, bodyImage: getComputedStyle(document.body).backgroundImage,
        ink: getComputedStyle(document.querySelector('p')).color };
    });
    assert.equal(evidence.canvas?.width, 392); assert.equal(evidence.canvas?.height, 760);
    assert.ok(evidence.canvas.image.includes('gradient')); assert.equal(evidence.bodyImage, 'none');
    assert.equal(evidence.ink, 'rgb(255, 255, 255)');
    await page.screenshot({ path: path.join(outputDir, `publication-background-${mode}.png`) });
    await command({ type: 'configure', config: { ...baseConfig, mode, authorCss: false } });
    assert.equal(await page.locator('#eread-publication-background').evaluate(node => getComputedStyle(node).display), 'none');
    return evidence;
  });
  await test('reader-palette-overrides-inline-ink-without-flattening-local-cards', async () => {
    await open({ mode: 'SCROLL', authorCss: true, overrideInk: true, color: '#bfc0c4' }, { source: {
      baseUrl: 'https://eread.local/book/archive/chapter.html', canonicalText: '普通正文\n\n卡片红字', images: [], noteTargets: {},
      html: '<!doctype html><html><head></head><body><p id="plain" style="color:black!important;-webkit-text-fill-color:black!important">普通正文</p>' +
        '<div style="background:white;color:#222"><span id="card" style="color:red;-webkit-text-fill-color:red">卡片红字</span></div></body></html>'
    } });
    assert.equal(await page.locator('#plain').evaluate(node => getComputedStyle(node).color), 'rgb(191, 192, 196)');
    assert.equal(await page.locator('#card').evaluate(node => getComputedStyle(node).webkitTextFillColor), 'rgb(255, 0, 0)');
    await command({ type: 'configure', config: { ...baseConfig, authorCss: true, color: '#404040', overrideInk: true } });
    assert.equal(await page.locator('#plain').evaluate(node => getComputedStyle(node).color), 'rgb(64, 64, 64)');
    await command({ type: 'configure', config: { ...baseConfig, authorCss: true, overrideInk: false } });
    assert.equal(await page.locator('#plain').evaluate(node => node.style.getPropertyValue('color')), 'black');
    assert.equal(await page.locator('#plain').evaluate(node => node.style.getPropertyPriority('color')), 'important');
    await page.locator('#card').evaluate(node => node.parentElement.style.background = 'black');
    await command({ type: 'configure', config: { ...baseConfig, authorCss: true, eink: true, color: '#000000' } });
    assert.equal(await page.locator('#card').evaluate(node => getComputedStyle(node).color), 'rgb(0, 0, 0)');
    assert.equal(await page.locator('#card').evaluate(node => getComputedStyle(node.parentElement).backgroundColor), 'rgba(0, 0, 0, 0)');
    return { inlineInkOverride: true, cardColorsPreserved: true, originalInlineRestored: true, einkBackgroundCleared: true };
  });
  await test('authored-title-page-breaks-are-preserved', async () => {
    await open({ mode: 'PAGED', authorCss: true }, { source: {
      baseUrl: 'https://eread.local/book/archive/chapter.html', canonicalText: '章节标题\n\n正文', images: [], noteTargets: {},
      html: '<!doctype html><html><head><style>h2{break-after:page}</style></head><body><h2>章节标题</h2><p>正文</p></body></html>'
    } });
    assert.equal(await page.locator('h2').evaluate(node => getComputedStyle(node).breakAfter), 'page');
    await command({ type: 'configure', config: { ...baseConfig, mode: 'PAGED', authorCss: false } });
    assert.equal(await page.locator('h2').evaluate(node => getComputedStyle(node).breakAfter), 'avoid');
    return { authoredPageBreakPreserved: true, softwareTitleKeptWithBody: true };
  });
  await test('auto-scroll-speed-pause-and-single-chapter-step', async () => {
    await open({ mode: 'SCROLL' });
    await page.evaluate(() => { scrollTo(0, 0); window.events = []; ERead.command({ type: 'autoScroll', speed: 8 }); });
    await page.waitForTimeout(1300);
    const slow = await page.evaluate(() => scrollY);
    assert.ok(slow >= 5 && slow <= 15, `Low speed must accumulate subpixel frames: ${slow}`);
    assert.equal(await page.evaluate(() => events.filter(e => e.type === 'manualStart').length), 0);
    await command({ type: 'autoScroll', speed: 0 });
    const stopped = await page.evaluate(() => scrollY);
    await page.waitForTimeout(250);
    assert.equal(await page.evaluate(() => scrollY), stopped);
    await command({ type: 'autoScroll', speed: 128 });
    await page.waitForTimeout(650);
    const fast = await page.evaluate(() => scrollY);
    assert.ok(fast - stopped >= 35 && fast - stopped <= 100, `Selected speed must take effect: ${fast - stopped}`);
    await page.touchscreen.tap(190, 450);
    const touched = await page.evaluate(() => scrollY);
    await page.waitForTimeout(250);
    assert.ok(await page.evaluate(() => scrollY) > touched + 10, 'A tap must keep automatic scrolling running');
    assert.equal(await page.evaluate(() => events.filter(e => e.type === 'manualStart').length), 0);
    await page.mouse.wheel(0, 80);
    await page.waitForTimeout(180);
    assert.ok(await page.evaluate(() => events.some(e => e.type === 'manualStart')));
    await command({ type: 'navigate', lastPage: true, offset: 0 });
    await page.evaluate(() => { window.events = []; ERead.command({ type: 'autoScroll', speed: 64 }); });
    await page.waitForTimeout(300);
    assert.equal(await page.evaluate(() => events.filter(e => e.type === 'chapterStep' && e.direction === 1).length), 1);
    return { slow, stopped, fast, touched };
  });
  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
    for (const chapter of [
      { name: 'blank', body: '', text: '', images: [] },
      { name: 'cover', body: '<img src="illustration.svg" alt=""/>', text: '',
        images: [{ offset: 0, path: imageIdentity }] },
      { name: 'text', body: '<p>正文初始化后应主动报告当前页面。</p>', text: '正文初始化后应主动报告当前页面。', images: [] }
    ]) await test(`initial-viewport-without-native-command-${mode.toLowerCase()}-${chapter.name}`, async () => {
      const source = { baseUrl: 'https://eread.local/book/archive/chapter.html',
        html: `<html><head></head><body>${chapter.body}</body></html>`, canonicalText: chapter.text,
        noteTargets: {}, images: chapter.images };
      await open({ mode }, { source, noInitialReport: true });
      // Only init/ready have run: a native controller has not issued report or
      // navigation. Blank and cover chapters have no text observer to rescue it.
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const events = await page.evaluate(() => window.events);
      const ready = events.findIndex(event => event.type === 'ready');
      const viewport = events.findIndex(event => event.type === 'viewport');
      assert.ok(ready >= 0, 'The reader must acknowledge initialisation');
      assert.ok(viewport > ready, 'Initialisation must report its viewport without a second native command');
      const state = events[viewport];
      assert.ok(Number.isInteger(state.page) && state.page >= 0);
      assert.ok(Number.isInteger(state.pages) && state.pages > state.page);
      assert.ok(state.offset >= 0 && state.endOffset >= state.offset && state.endOffset <= chapter.text.length);
      if (chapter.name === 'cover') assert.deepEqual(state.images, [imageIdentity]);
      if (chapter.name === 'text') assert.ok(state.endOffset > state.offset);
      return { mode, chapter: chapter.name, state };
    });
  }

  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
    for (const anchor of ['offset', 'last-page', 'image']) {
      await test(`initial-navigation-without-native-command-${mode.toLowerCase()}-${anchor}`, async () => {
        const text = lines.join('\n\n');
        const imageOffset = text.indexOf(lines[20]);
        const target = text.indexOf(lines[30]);
        const source = { baseUrl: 'https://eread.local/book/archive/chapter.html', canonicalText: text,
          html: '<html><head></head><body>' + lines.map((line, index) =>
            `${index === 20 ? '<img src="illustration.svg" alt=""/>' : ''}<p>${line}</p>`).join('') + '</body></html>',
          noteTargets: {}, images: [{ offset: imageOffset, path: imageIdentity }] };
        const initialNavigation = anchor === 'offset' ? { type: 'navigate', offset: target } :
          anchor === 'last-page' ? { type: 'navigate', offset: 0, lastPage: true } :
          { type: 'navigate', offset: imageOffset, image: imageIdentity };
        await open({ mode }, { source, noInitialReport: true, initialNavigation });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const events = await page.evaluate(() => window.events);
        const ready = events.findIndex(event => event.type === 'ready');
        const firstViewport = events.findIndex(event => event.type === 'viewport');
        assert.ok(firstViewport > ready, 'The first viewport must follow ready and the initial anchor');
        const state = events[firstViewport];
        assert.ok(state.offset > 0, 'Restoring a later anchor must never publish the chapter start');
        if (anchor === 'offset') assert.ok(state.offset <= target + 2 && state.endOffset > target,
          'The restored canonical anchor must be in the first reported viewport');
        if (anchor === 'last-page') {
          assert.equal(state.atEnd, true);
          assert.equal(state.page, mode === 'SCROLL' ? await page.evaluate(() => Math.round(scrollY / innerHeight)) : state.pages - 1);
        }
        if (anchor === 'image') assert.ok(state.images.includes(imageIdentity), 'The restored illustration must be visible');
        await page.evaluate(() => window.ERead.init(window.readerInitOptions));
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert.equal(await page.evaluate(() => window.events.filter(event => event.type === 'ready').length), 1,
          'A repeated bootstrap must not initialise the chapter twice');
        return { mode, anchor, target: initialNavigation, state };
      });
    }
  }

  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
    for (const preserveViewport of [true, false]) {
      await test(`resume-cached-dom-${mode.toLowerCase()}-${preserveViewport ? 'retain' : 'navigate'}`, async () => {
        await open({ mode });
        const target = canonical.indexOf(lines[30]);
        await command({ type: 'navigate', offset: canonical.indexOf(lines[20]) });
        const before = await viewport();
        await page.evaluate(() => { window.originalParagraph = document.getElementById('paragraph-20'); });
        await command({ type: 'resume', options: {
          config: { ...baseConfig, mode }, preserveViewport,
          initialNavigation: { offset: target }, notes: [],
          decorations: { annotations: [], search: '', spokenStart: -1, spokenEnd: -1 }
        } });
        const after = await viewport();
        assert.equal(await page.evaluate(() => document.getElementById('paragraph-20') === window.originalParagraph), true);
        assert.equal(await page.evaluate(() => window.events.filter(event => event.type === 'ready').length), 2);
        if (preserveViewport) {
          assert.equal(after.offset, before.offset); assert.equal(after.page, before.page);
        } else assert.ok(after.offset <= target + 2 && after.endOffset > target);
        return { mode, preserveViewport, before, after };
      });
    }
  }

  await test('publisher-css-structure', async () => {
    await open();
    const source = await page.evaluate(() => {
      const heading = getComputedStyle(document.getElementById('chapter-heading'));
      const quote = getComputedStyle(document.getElementById('quote'));
      const table = document.getElementById('data-table').getBoundingClientRect();
      return { headingSize: heading.fontSize, headingAlign: heading.textAlign, quoteAlign: quote.textAlign,
        quoteColor: quote.color, quoteItalic: quote.fontStyle, tableWidth: table.width, tableCells: document.querySelectorAll('#data-table td,#data-table th').length,
        rubyCount: document.querySelectorAll('ruby rt').length, font: heading.fontFamily };
    });
    assert.equal(source.headingSize, '32px'); assert.equal(source.headingAlign, 'center');
    assert.equal(source.quoteAlign, 'right'); assert.equal(source.quoteColor, 'rgb(63, 93, 125)');
    assert.equal(source.quoteItalic, 'italic');
    assert.equal(source.tableCells, 4); assert.equal(source.rubyCount, 1); assert.ok(source.tableWidth > 300);
    assert.ok(onScreen(await textRect('chapter-heading')));
    await command({ type: 'configure', config: { ...baseConfig, authorCss: false } });
    const body = await page.evaluate(() => ({ font: getComputedStyle(document.body).fontSize,
      paragraph: getComputedStyle(document.getElementById('intro')).fontSize,
      headingAlign: getComputedStyle(document.getElementById('chapter-heading')).textAlign,
      headingSize: getComputedStyle(document.getElementById('chapter-heading')).fontSize,
      quoteAlign: getComputedStyle(document.getElementById('quote')).textAlign,
      quoteItalic: getComputedStyle(document.getElementById('quote')).fontStyle,
      cellBorder: getComputedStyle(document.querySelector('#data-table td')).borderLeftWidth,
      margin: getComputedStyle(document.getElementById('intro')).marginBottom }));
    assert.equal(body.font, '19px'); assert.equal(body.paragraph, '19px'); assert.equal(body.margin, '15.2px');
    assert.equal(body.headingAlign, 'center'); assert.equal(body.quoteAlign, 'right'); assert.equal(body.quoteItalic, 'italic');
    assert.ok(Math.abs(parseFloat(body.headingSize) - 19 * 32 / 22) < .02, 'Special heading keeps its source/body size ratio');
    assert.equal(body.cellBorder, '1px');
    assert.ok(onScreen(await textRect('chapter-heading')), 'CSS settings must preserve the top text anchor even when an image is visible lower on screen');
    return { source, software: body };
  });

  await test('software-body-retains-special-source-format', async () => {
    await open({}, { extraPublisherCss: `
      p { font-size:28px; line-height:84px; letter-spacing:4px; margin-top:90px; margin-bottom:140px; text-indent:5em; text-align:justify; }
      .body-wrapper { font-size:28px; line-height:84px; letter-spacing:4px; }
      blockquote { font-size:24px; text-indent:3em; }
      #image-caption { font-size:32px; text-align:center; color:rgb(125,63,93); margin-bottom:12px; }
    ` });
    const measure = () => page.evaluate(() => {
      const style = id => getComputedStyle(document.getElementById(id));
      const intro = style('intro'), heading = style('chapter-heading'), quote = style('quote'), caption = style('image-caption');
      return { bodySize: intro.fontSize, bodyLine: intro.lineHeight, bodyGap: intro.marginBottom,
        bodyTop: intro.marginTop, bodyIndent: intro.textIndent, bodyTracking: intro.letterSpacing,
        headingSize: heading.fontSize, headingAlign: heading.textAlign,
        quoteSize: quote.fontSize, quoteAlign: quote.textAlign, quoteItalic: quote.fontStyle,
        quoteColor: quote.color, captionSize: caption.fontSize, captionAlign: caption.textAlign,
        captionColor: caption.color, captionGap: caption.marginBottom,
        wrapperSize: getComputedStyle(document.querySelector('.body-wrapper')).fontSize,
        strongWeight: getComputedStyle(document.querySelector('#wrapped strong')).fontWeight,
        emphasis: getComputedStyle(document.querySelector('#wrapped em')).fontStyle,
        tableBorder: getComputedStyle(document.querySelector('#data-table td')).borderLeftWidth };
    });
    const original = await measure(); assert.equal(original.bodySize, '28px'); assert.equal(original.headingSize, '32px');
    assert.equal(original.bodyGap, `${baseConfig.paragraphGap}px`, 'The paragraph-gap control also applies with author CSS');
    await command({ type: 'configure', config: { ...baseConfig, authorCss: false } });
    const software = await measure();
    assert.equal(software.bodySize, '19px'); assert.equal(software.bodyLine, '35.15px'); assert.equal(software.bodyGap, '15.2px');
    assert.equal(software.bodyTop, '0px'); assert.equal(software.bodyIndent, '38px'); assert.equal(software.bodyTracking, '0.5px');
    assert.ok(Math.abs(parseFloat(software.headingSize) - 19 * 32 / 28) < .02);
    assert.ok(Math.abs(parseFloat(software.quoteSize) - 19 * 24 / 28) < .02);
    assert.ok(Math.abs(parseFloat(software.captionSize) - 19 * 32 / 28) < .02);
    assert.equal(software.headingAlign, 'center'); assert.equal(software.quoteAlign, 'right'); assert.equal(software.quoteItalic, 'italic');
    assert.equal(software.captionAlign, 'center'); assert.equal(software.captionGap, '12px');
    assert.equal(software.quoteColor, original.quoteColor); assert.equal(software.captionColor, original.captionColor);
    assert.equal(software.wrapperSize, '19px'); assert.equal(software.strongWeight, '700'); assert.equal(software.emphasis, 'italic');
    assert.equal(software.tableBorder, '1px');
    await command({ type: 'configure', config: baseConfig });
    assert.deepEqual(await measure(), original, 'Returning to source CSS restores exact source typography');
    return { original, software };
  });

  await test('paragraph-gap-overrides-inline-important-margins-in-both-css-and-writing-modes', async () => {
    const paragraphs = ['第一段正文。', '第二段正文。', '第三段正文。'];
    const source = { baseUrl:'https://eread.local/book/archive/chapter.html', canonicalText:paragraphs.join('\n\n'),
      html:`<html><head></head><body>${paragraphs.map((text,index) => `<p id="gap-${index}" style="margin:0!important;font-weight:700">${text}</p>`).join('')}</body></html>`,
      images:[], noteTargets:{} };
    const evidence = [];
    for (const authorCss of [false, true]) for (const vertical of [false, true]) {
      await open({mode:'SCROLL', authorCss, vertical}, {source});
      for (const gap of [0, 9, 18, 36]) {
        await command({type:'configure', config:{...baseConfig, mode:'SCROLL', authorCss, vertical, paragraphGap:gap}});
        const measured = await page.evaluate(vertical => {
          const first = document.getElementById('gap-0'), second = document.getElementById('gap-1');
          const a = first.getBoundingClientRect(), b = second.getBoundingClientRect();
          return {actual:vertical ? a.left-b.right : b.top-a.bottom,
            css:parseFloat(getComputedStyle(first).marginBlockEnd), text:document.body.textContent};
        }, vertical);
        assert.ok(Math.abs(measured.actual-gap)<.1, `Actual paragraph separation ${measured.actual} must equal ${gap}`);
        assert.equal(measured.css, gap); assert.ok(measured.text.includes(paragraphs[1]));
        evidence.push({authorCss,vertical,gap,actual:measured.actual});
      }
    }
    return evidence;
  });

  await test('ordinary-taps-report-normalized-viewport-coordinates-without-navigating', async () => {
    await open();
    await page.evaluate(() => { window.events = []; });
    await page.mouse.click(12, 200);
    const tap = await page.evaluate(() => window.events.findLast(event => event.type === 'tap'));
    assert.ok(tap); assert.ok(Math.abs(tap.x-12/392)<.001); assert.ok(Math.abs(tap.y-200/760)<.001);
    assert.equal(await page.evaluate(() => scrollY), 0);
    return tap;
  });

  await test('footnote-and-selection-offsets', async () => {
    await open();
    await page.locator('#note-link').click();
    const note = await page.evaluate(() => window.events.findLast(event => event.type === 'footnote'));
    assert.equal(note.target, '#note-1'); assert.match(note.content, /注释一/); assert.equal(note.label, '[1]');
    const quote = canonicalParts[1].substring(3, 12);
    await page.evaluate(() => {
      const text = document.getElementById('intro').firstChild, range = document.createRange();
      range.setStart(text, 3); range.setEnd(text, 12); getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await page.waitForFunction(() => window.events.some(event => event.type === 'selection' && event.end > event.start));
    const selection = await page.evaluate(() => window.events.findLast(event => event.type === 'selection' && event.end > event.start));
    assert.equal(selection.start, canonical.indexOf(canonicalParts[1]) + 3);
    assert.equal(selection.end, canonical.indexOf(canonicalParts[1]) + 12);
    assert.equal(canonical.substring(selection.start, selection.end), quote);
    assert.ok(selection.bottom > selection.top);
    await command({ type: 'clearSelection' });
    assert.equal(await page.evaluate(() => getSelection().isCollapsed), true);
    return { note, selection };
  });

  await test('scroll-navigate-image-and-decorations', async () => {
    await open();
    const index = canonical.indexOf(lines[20]);
    await command({ type: 'navigate', offset: index });
    assert.ok(onScreen(await textRect('paragraph-20')), 'Requested canonical paragraph must be on screen');
    const state = await viewport(); assert.ok(state.offset <= index + 2 && state.endOffset > index);
    await command({ type: 'decorate', decorations: { annotations: [{ id: 'note-a', start: index, end: index + 15, kind: 'UNDERLINE', underline: 'WAVY', color: 0xff194a75 }], spokenStart: index + 15, spokenEnd: index + 25, search: lines[20].slice(0, 7) } });
    const overlay = await page.evaluate(() => Array.from(document.querySelectorAll('#eread-overlays > i')).map(node => ({ width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height, style: node.getAttribute('style') })));
    assert.ok(overlay.length >= 3, `Expected annotation, speech and search highlights, found ${overlay.length}`);
    assert.ok(overlay.every(item => item.width > 0 && item.height > 0));
    const marker = await page.locator('#eread-overlays > i').first().boundingBox();
    await page.mouse.click(marker.x + marker.width / 2, marker.y + marker.height / 2);
    const clicked = await page.evaluate(() => window.events.findLast(event => event.type === 'annotation'));
    assert.ok(clicked.ids.includes('note-a'), 'Clicking the rendered annotation must return its stored identity');
    await command({ type: 'navigate', image: imageIdentity, offset: 0 });
    const imageState = await viewport(); assert.ok(imageState.images.includes(imageIdentity));
    assert.ok(!imageState.images.some(path => path.startsWith('https:')));
    return { state, imageState, overlayCount: overlay.length };
  });

  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) for (const kind of ['image', 'picture', 'svg']) {
    await test(`image-crop-${mode.toLowerCase()}-${kind}`, async () => {
      const drawing = '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="240"><rect width="320" height="240" fill="red"/><rect x="40" y="24" width="200" height="168" fill="#00ff00"/></svg>';
      const uri = 'data:image/svg+xml,' + encodeURIComponent(drawing);
      const graphic = kind === 'image' ? `<img id="crop-image" style="display:block;width:320px;height:240px" src="${uri}">` :
        kind === 'picture' ? `<picture><source srcset="${uri}"><img id="crop-image" style="display:block;width:320px;height:240px" src="${uri}"></picture>` :
        `<svg id="crop-image" style="display:block;width:320px;height:240px" viewBox="0 0 320 240"><image width="320" height="240" href="${uri}"/></svg>`;
      const source = { baseUrl:'https://eread.local/book/archive/crop.html', canonicalText:'图片之前\n\n图片之后',
        html:`<!doctype html><html><head><style>p{margin:0}</style></head><body><p>图片之前</p><p style="line-height:0">${graphic}</p><p id="crop-after">图片之后</p></body></html>`,
        images:[{offset:4,path:imageIdentity}],noteTargets:{} };
      const config = {...baseConfig,mode,paragraphGap:0};
      const crop = {enabled:true,left:.125,right:.25,top:.1,bottom:.2};
      await open(config,{source});
      const original = await page.locator('#crop-image').getAttribute('style');
      async function geometry() { return page.evaluate(() => {
        const image=document.getElementById('crop-image'),frame=image.parentElement,r=frame.getBoundingClientRect();
        return {width:r.width,height:r.height,top:r.top,bottom:r.bottom,after:document.getElementById('crop-after').getBoundingClientRect().top,
          scrollWidth:document.documentElement.scrollWidth,viewportWidth:innerWidth,
          pictureSources:document.querySelectorAll('picture > source').length,pictureImage:!!document.querySelector('picture > img')};
      }); }
      await command({type:'configure',config:{...config,imageCrop:crop}});
      const retained=await geometry();
      assert.ok(Math.abs(retained.width-320)<1,'Cropping must retain the original fitted width');
      assert.ok(Math.abs(retained.height-268.8)<1,'The retained pixels must determine the new layout height');
      assert.ok(Math.abs(retained.after-retained.bottom)<2,'Cropping must not leave the old image space behind');
      assert.equal((await viewport()).pages,1,'Clipped source pixels must not create blank pages');
      assert.ok((await viewport()).images.includes(imageIdentity),'The cropped image must retain its stored identity');
      if(kind==='picture') { assert.equal(retained.pictureSources,1);assert.equal(retained.pictureImage,true); }
      await command({type:'decorate',decorations:{annotations:[{id:'crop-mark',image:imageIdentity,start:4,end:4,kind:'HIGHLIGHT',color:0xff194a75}]}});
      const mark=await page.locator('#eread-overlays > i').first().boundingBox();
      assert.ok(Math.abs(mark.width-retained.width)<1 && Math.abs(mark.height-retained.height)<1,'Image marks must follow the cropped surface');
      await command({type:'decorate',decorations:{annotations:[]}});
      await page.screenshot({path:path.join(outputDir,`crop-retained-${mode.toLowerCase()}-${kind}.png`)});
      await command({type:'configure',config:{...config,authorCss:false,imageCrop:crop}});
      assert.ok(Math.abs((await geometry()).height-retained.height)<1,'Repeated configuration must not crop the image twice');
      await command({type:'configure',config:{...config,imageCrop:{...crop,enabled:false}}});
      assert.equal(await page.locator('#crop-image').getAttribute('style'),original,'Disabling crop must restore publisher styles');
      const full=await page.locator('#crop-image').boundingBox();
      assert.equal(full.width,320);assert.equal(full.height,240);
      await open({...config,imageCrop:crop},{source});
      assert.ok(Math.abs((await geometry()).height-retained.height)<1,'Initial loading must apply crop before showing the chapter');
      return {retained,restored:full,kind,mode};
    });
  }

  await test('image-crop-svg-composition-only-reports-visible-images', async () => {
    const uri='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="green"/></svg>');
    const source={baseUrl:'https://eread.local/book/archive/svg-crop.html',canonicalText:'',noteTargets:{},
      images:[{offset:0,path:'/hidden.svg'},{offset:0,path:'/visible.svg'}],
      html:`<!doctype html><html><head></head><body><svg style="display:block;width:320px;height:240px" viewBox="0 0 320 240"><image x="0" width="64" height="240" preserveAspectRatio="none" href="${uri}"/><image x="64" width="256" height="240" preserveAspectRatio="none" href="${uri}"/></svg></body></html>`};
    await open({imageCrop:{enabled:true,left:.2,right:0,top:0,bottom:0}},{source});
    const state=await viewport();
    assert.deepEqual(state.images,['/visible.svg'],'Fully cropped SVG components must not be reported as visible');
    await command({type:'decorate',decorations:{annotations:[{id:'visible-crop',image:'/visible.svg',start:0,end:0,kind:'HIGHLIGHT',color:0xff194a75}]}});
    const rect=await page.locator('#eread-overlays > i').first().boundingBox();
    assert.ok(Math.abs(rect.width-320)<1 && Math.abs(rect.height-300)<1,'The visible SVG component must use the retained frame bounds');
    const session=await page.context().newCDPSession(page);
    await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:100,y:100}]});
    await page.waitForFunction(()=>events.some(event=>event.type==='selection'));
    await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
    const selected=await page.evaluate(()=>events.findLast(event=>event.type==='selection'));
    assert.equal(selected.image,'/visible.svg','Long pressing must select the visible SVG component');
    return {state,rect,selected};
  });

  for (const mode of ['PAGED', 'VERTICAL_PAGED']) {
    await test(`${mode.toLowerCase()}-partial-last-page-keeps-margins`, async () => {
      await open({ mode, authorCss: false, left: 31, right: 47, top: 23, bottom: 37 });
      await command({ type: 'navigate', lastPage: true });
      const state = await viewport();
      const geometry = await page.evaluate(() => ({ x: scrollX, y: scrollY, width: innerWidth, height: innerHeight }));
      const actual = mode === 'PAGED' ? geometry.x : geometry.y;
      const expected = state.page * (mode === 'PAGED' ? geometry.width : geometry.height);
      assert.ok(Math.abs(actual - expected) < 1, `Last page must remain on its page grid: ${JSON.stringify({state,geometry,expected})}`);
      return { state, geometry };
    });
    await test(`${mode.toLowerCase()}-geometry-and-offset`, async () => {
      await open({ mode });
      const initial = await viewport(); assert.ok(initial.pages > 3, `${mode} must have actual multiple pages`);
      const offset = canonical.indexOf(lines[24]);
      await command({ type: 'navigate', offset });
      const rect = await textRect('paragraph-24'), state = await viewport();
      assert.ok(onScreen(rect), `${mode} navigate must show requested glyph: ${JSON.stringify(rect)}`);
      assert.ok(state.page > 0); assert.ok(state.page < state.pages);
      assert.ok(state.offset <= offset + 2 && state.endOffset > offset, `Visible canonical interval misses requested glyph: ${JSON.stringify(state)}`);
      assert.ok(state.offset >= offset - 500, `Viewport must not include invisible early chapter text: ${JSON.stringify(state)}`);
      const before = state.page; await command({ type: 'move', direction: 1 });
      assert.equal((await viewport()).page, before + 1);
      return { initial, navigated: state, rect };
    });
  }

  await test('dual-page-geometry', async () => {
    await open({ mode: 'PAGED', dual: true }, { viewport: { width: 1024, height: 760 } });
    const offset = canonical.indexOf(lines[28]); await command({ type: 'navigate', offset });
    const rect = await textRect('paragraph-28'), state = await viewport();
    assert.ok(onScreen(rect, 1024, 760), `Dual-page requested glyph is outside viewport: ${JSON.stringify(rect)}`);
    assert.equal(state.page % 2, 0); assert.ok(state.pages > 3);
    const before = state.page; await command({ type: 'move', direction: 1 }); assert.equal((await viewport()).page, before + 2);
    return { state, rect };
  });

  await test('mode-change-preserves-anchor', async () => {
    await open();
    const offset = canonical.indexOf(lines[18]); await command({ type: 'navigate', offset });
    const initial = await viewport();
    for (const mode of ['PAGED', 'VERTICAL_PAGED', 'SCROLL']) {
      await command({ type: 'configure', config: { ...baseConfig, mode } });
      const rect = await textRect('paragraph-18');
      assert.ok(onScreen(rect), `SCROLL → PAGED → VERTICAL → SCROLL lost anchor at ${mode}: ${JSON.stringify(rect)}`);
      const state = await viewport(); assert.ok(state.offset <= offset + 2 && state.endOffset > offset);
    }
    return { initial, final: await viewport() };
  });

  await test('manual-paging-events', async () => {
    await open({ mode: 'PAGED' });
    await command({ type: 'move', direction: 1 });
    await page.waitForFunction(() => window.events.some(event => event.type === 'manualEnd'));
    const events = await page.evaluate(() => window.events.filter(event => event.type === 'manualStart' || event.type === 'manualEnd'));
    assert.deepEqual(events.map(event => event.type), ['manualStart', 'manualEnd']);
    await command({ type: 'navigate', lastPage: true }); await command({ type: 'move', direction: 1 });
    const last = await page.evaluate(() => window.events.findLast(event => event.type === 'chapterStep'));
    assert.equal(last.direction, 1);
    return { events, last };
  });

  await test('ordinary-link-is-navigation', async () => {
    await open();
    await page.locator('#ordinary-link').click();
    const notes = await page.evaluate(() => window.events.filter(event => event.type === 'footnote'));
    assert.deepEqual(notes, [], 'A normal chapter link is not a footnote');
    assert.ok(onScreen(await textRect('paragraph-30')), 'Ordinary internal link must show target');
    return await viewport();
  });

  await test('real-touch-paged-and-scroll', async () => {
    const touch = async (session, type, x, y) => {
      await session.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
    };
    await open({ mode: 'PAGED' });
    let session = await page.context().newCDPSession(page);
    await touch(session, 'touchStart', 320, 380);
    for (const x of [280, 220, 160, 80]) await touch(session, 'touchMove', x, 380);
    await touch(session, 'touchEnd', 80, 380);
    await page.waitForFunction(() => window.events.some(event => event.type === 'manualEnd'));
    const paged = await viewport(); assert.equal(paged.page, 1);
    const pagedEvents = await page.evaluate(() => window.events.map(event => event.type));
    assert.ok(pagedEvents.includes('manualStart') && pagedEvents.includes('manualEnd'));

    await open(); session = await page.context().newCDPSession(page);
    await touch(session, 'touchStart', 200, 650);
    for (const y of [600, 510, 400, 250, 120]) await touch(session, 'touchMove', 200, y);
    await touch(session, 'touchEnd', 200, 120);
    await page.waitForFunction(() => window.events.some(event => event.type === 'manualEnd'));
    const scroll = await viewport(), scrollY = await page.evaluate(() => window.scrollY);
    assert.ok(scrollY > 200, `Real touch must scroll, actual scrollY=${scrollY}`);
    const scrollEvents = await page.evaluate(() => window.events.map(event => event.type));
    assert.ok(scrollEvents.includes('manualStart') && scrollEvents.includes('manualEnd'));
    return { paged, scroll, scrollY };
  });

  await test('large-font-resize-and-navigation', async () => {
    await open({ authorCss: false });
    const offset = canonical.indexOf(lines[22]); await command({ type: 'navigate', offset });
    for (const font of ['serif', 'sans-serif', 'monospace']) {
      await command({ type: 'configure', config: { ...baseConfig, mode: 'PAGED', authorCss: false, font,
        fontSize: 38, lineHeight: 70.3, letterSpacing: 1, paragraphGap: 30.4 } });
      await command({ type: 'navigate', offset });
      assert.ok(onScreen(await textRect('paragraph-22')), `${font} 2× text failed canonical navigation`);
      const state = await viewport(); assert.ok(state.pages > 5);
      const size = await page.locator('#paragraph-22').evaluate(node => getComputedStyle(node).fontSize);
      assert.equal(size, '38px');
    }
    await page.setViewportSize({ width: 430, height: 800 });
    await page.waitForTimeout(180); // Production resize debounce is 80 ms.
    await command({ type: 'navigate', offset });
    const rect = await textRect('paragraph-22'); assert.ok(onScreen(rect, 430, 800));
    return { state: await viewport(), rect };
  });

  await test('image-long-press-keeps-local-identity', async () => {
    await open(); await command({ type: 'navigate', image: imageIdentity });
    const box = await page.locator('#illustration').boundingBox();
    const session = await page.context().newCDPSession(page);
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.x + box.width / 2, y: box.y + box.height / 2 }] });
    await page.waitForFunction(() => window.events.some(event => event.type === 'selection' && event.image));
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    const selected = await page.evaluate(() => window.events.findLast(event => event.type === 'selection' && event.image));
    assert.equal(selected.image, imageIdentity); assert.equal(selected.start, selected.end);
    assert.ok(selected.right > selected.left && selected.bottom > selected.top);
    return selected;
  });

  await test('conversion-preserves-canonical-selection', async () => {
    // Deliberately expand a canonical glyph into a surrogate pair plus a glyph.
    // Display indices then differ from the persisted UTF-16 indices.
    await open({}, { conversion: { '书': '冊📘' } });
    const text = canonicalParts[1], display = text.replaceAll('书', '冊📘');
    const needle = '原书结构';
    const start = canonical.indexOf(text) + text.indexOf(needle), end = start + needle.length;
    await page.evaluate(({ displayStart, displayEnd }) => {
      const node = document.getElementById('intro').firstChild, range = document.createRange();
      range.setStart(node, displayStart); range.setEnd(node, displayEnd);
      getSelection().removeAllRanges(); getSelection().addRange(range);
    }, { displayStart: display.indexOf('原冊📘结构'), displayEnd: display.indexOf('原冊📘结构') + '原冊📘结构'.length });
    await page.waitForFunction(() => window.events.some(event => event.type === 'selection' && event.end > event.start));
    const selection = await page.evaluate(() => window.events.findLast(event => event.type === 'selection' && event.end > event.start));
    assert.equal(selection.start, start); assert.equal(selection.end, end);
    assert.equal(canonical.substring(selection.start, selection.end), needle);
    return selection;
  });

  await test('font-switch-keeps-old-metrics-until-the-new-face-is-loaded', async () => {
    await open({authorCss:false, font:'serif'});
    let requested = false;
    await page.route('**/reader-font?slow=1', async route => {
      requested = true;
      await new Promise(resolve => setTimeout(resolve, 700));
      await route.fulfill({contentType:'font/otf', body:await fs.readFile(path.join(scriptDir, '../app/src/main/res/font/reader_song.otf'))});
    });
    await page.evaluate(config => { window.fontChange = ERead.command({type:'configure', config}); },
      {...baseConfig, authorCss:false, font:'eread-selected', fontUrl:'https://eread.local/reader-font?slow=1', fontSize:23});
    await page.waitForTimeout(100);
    assert.ok(requested);
    const pending = await page.locator('#intro').evaluate(node => ({size:getComputedStyle(node).fontSize, family:getComputedStyle(node).fontFamily}));
    assert.deepEqual(pending, {size:'19px', family:'serif'});
    await page.evaluate(() => window.fontChange);
    await page.waitForFunction(() => getComputedStyle(document.querySelector('#intro')).fontSize === '23px');
    return {pending, final:await page.locator('#intro').evaluate(node => getComputedStyle(node).fontFamily)};
  });

  await test('background-switch-reuses-the-loaded-font-face', async () => {
    const config = {...baseConfig, font:'eread-selected', fontUrl:'https://eread.local/reader-font?font=reader_hei.otf'};
    await open(config);
    await page.evaluate(() => { window.savedFaces = [...document.fonts]; });
    for (const paper of ['#e5ebd9', '#ffffff', '#f6f6ef']) await command({type:'configure', config:{...config, paper}});
    assert.equal(await page.evaluate(() => [...document.fonts].every((face, index) => face === window.savedFaces[index])), true);
    return {fontReused:true};
  });

  await test('live-conversion-keeps-the-document-and-canonical-selection', async () => {
    await open();
    await page.evaluate(() => {
      window.savedBody = document.body;
      window.__ERead.convert = text => {
        let display = ''; const forward = [0];
        for (const char of text) { display += char === '书' ? '冊📘' : char; forward.push(display.length); }
        return JSON.stringify({text:display, forward});
      };
    });
    await command({type:'convert'});
    const bodyText = await page.locator('#intro').textContent();
    assert.ok(bodyText.includes('原冊📘结构'));
    assert.equal(await page.evaluate(() => document.body === window.savedBody), true);
    await page.evaluate(() => {
      const node = document.querySelector('#intro').firstChild, start = node.data.indexOf('原冊📘结构'), range = document.createRange();
      range.setStart(node, start); range.setEnd(node, start + '原冊📘结构'.length);
      getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await page.waitForFunction(() => window.events.some(e => e.type === 'selection' && e.end > e.start));
    const selection = await page.evaluate(() => window.events.findLast(e => e.type === 'selection' && e.end > e.start));
    assert.equal(canonical.substring(selection.start, selection.end), '原书结构');
    await page.evaluate(() => { window.__ERead.convert = text => JSON.stringify({text}); });
    await command({type:'convert'});
    assert.ok((await page.locator('#intro').textContent()).includes('原书结构'));
    return {sameBody:true, selection};
  });

  for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) for (const authorCss of [false, true]) {
    await test(`vertical-writing-${mode}-${authorCss}`, async () => {
      await open({mode, authorCss, vertical:true});
      assert.equal(await page.locator('#paragraph-0').evaluate(node => getComputedStyle(node).writingMode), 'vertical-rl');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
      const offset = canonical.indexOf(lines[22]);
      await command({type:'navigate', offset});
      assert.ok(onScreen(await textRect('paragraph-22')));
      const current = await viewport();
      assert.ok(current.offset <= offset && current.endOffset > offset, JSON.stringify(current));
      if (screenshots) await page.screenshot({path:path.join(outputDir, `vertical-layout-${mode}-${authorCss}.png`)});
      if (mode !== 'SCROLL') {
        await command({type:'move', direction:1});
        const next = await viewport();
        assert.equal(next.page, current.page + 1);
        await command({type:'navigate', lastPage:true});
        assert.equal((await viewport()).atEnd, true);
      }
      await command({type:'configure', config:{...baseConfig, mode, authorCss, vertical:false}});
      assert.equal(await page.locator('#paragraph-0').evaluate(node => getComputedStyle(node).writingMode), 'horizontal-tb');
      return {mode, authorCss, pages:current.pages};
    });
  }

  await test('bundled-font-loading-and-switching', async () => {
    const evidence = [];
    for (const font of ['reader_song.otf', 'reader_hei.otf']) {
      await open({ font: 'eread-selected', fontUrl: `https://eread.local/reader-font?font=${font}` });
      const loaded = await page.evaluate(async () => {
        await document.fonts.load('19px eread-selected');
        const heading = getComputedStyle(document.getElementById('chapter-heading'));
        return { loaded: document.fonts.check('19px eread-selected'), family: heading.fontFamily, headingSize: heading.fontSize };
      });
      assert.equal(loaded.loaded, true); assert.equal(loaded.family, 'eread-selected');
      assert.equal(loaded.headingSize, '32px', 'Replacing font must preserve original heading size');
      const offset = canonical.indexOf(lines[10]); await command({ type: 'navigate', offset });
      assert.ok(onScreen(await textRect('paragraph-10')));
      evidence.push({ font, ...loaded });
    }
    return evidence;
  });

  await test('last-page-has-final-text', async () => {
    const evidence = [];
    for (const [mode, dual, width] of [['PAGED', false, 392], ['PAGED', true, 1024], ['VERTICAL_PAGED', false, 392]]) {
      await open({ mode, dual }, { viewport: { width, height: 760 } });
      await command({ type: 'navigate', lastPage: true });
      const rect = await page.locator('#note-1 p').evaluate(node => {
        const range = document.createRange(), text = node.firstChild;
        range.setStart(text, text.length - 1); range.setEnd(text, text.length);
        const rect = range.getBoundingClientRect(); return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
      });
      assert.ok(onScreen(rect, width, 760), `Final glyph missing in ${mode}/${dual}: ${JSON.stringify(rect)}`);
      evidence.push({ mode, dual, state: await viewport(), rect });
    }
    return evidence;
  });

  await test('nested-element-selection-boundaries', async () => {
    await open();
    const expected = canonicalParts[3], start = canonical.indexOf(expected), end = start + expected.length;
    await page.evaluate(() => {
      const range = document.createRange(); range.selectNodeContents(document.getElementById('ruby'));
      getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await page.waitForFunction(() => window.events.some(event => event.type === 'selection' && event.end > event.start));
    const selection = await page.evaluate(() => window.events.findLast(event => event.type === 'selection' && event.end > event.start));
    assert.equal(selection.start, start); assert.equal(selection.end, end);
    assert.equal(canonical.substring(selection.start, selection.end), expected);
    return selection;
  });

  await test('page-turn-style-handshake', async () => {
    const evidence = [];
    for (const style of ['COVER', 'SLIDE', 'CURL']) {
      await open({ mode: 'PAGED', turn: style });
      await command({ type: 'move', direction: 1 });
      assert.equal((await viewport()).page, 1);
      await command({ type: 'move', direction: -1 });
      assert.equal((await viewport()).page, 0);
      const turns = await page.evaluate(() => window.events.filter(event => event.type === 'turn'));
      assert.deepEqual(turns, [], 'Page animation must not wait for an Android snapshot acknowledgement');
      evidence.push({ style, turns });
    }
    for (const config of [{ mode: 'PAGED', eink: true }, { mode: 'VERTICAL_PAGED' }]) {
      await open(config); await command({ type: 'move', direction: 1 });
      assert.equal((await viewport()).page, 1);
      assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'turn')), []);
    }
    return evidence;
  });

  await test('cover-and-curl-retire-compositor-snapshots-without-showing-the-old-page-again', async () => {
    const evidence = [];
    for (const style of ['COVER', 'CURL']) {
      await open({mode:'PAGED', turn:style});
      assert.equal(await page.evaluate(()=>typeof document.startViewTransition), 'function');
      const before = await page.screenshot();
      await page.evaluate(()=>ERead.command({type:'move',direction:1}));
      await page.waitForFunction(()=>document.getAnimations().some(a=>a.id==='eread-page-turn'));
      const middle = await page.evaluate(()=>document.getAnimations().filter(a=>a.id==='eread-page-turn')
        .map(a=>({pseudo:a.effect.pseudoElement,frames:a.effect.getKeyframes()})));
      await page.evaluate(()=>document.getAnimations().filter(a=>a.id==='eread-page-turn').forEach(a=>a.finish()));
      await page.evaluate(()=>Promise.all(window.pageTransitions));
      const completed = await page.screenshot();
      await page.waitForTimeout(100);
      assert.deepEqual(await page.screenshot(), completed, 'Old page must never reappear after the snapshot is released');
      assert.notDeepEqual(completed, before);
      assert.equal((await viewport()).page, 1);
      await command({type:'page',page:1});
      assert.deepEqual(await page.screenshot(), completed, 'Final frame must exactly match the live target page');
      // Multiple requests can arrive before the asynchronous snapshot callback.
      await page.evaluate(()=>{ERead.command({type:'move',direction:1});ERead.command({type:'move',direction:1});});
      await page.evaluate(()=>Promise.all(window.pageTransitions));
      assert.equal((await viewport()).page, 3);
      await command({type:'move',direction:-1});
      assert.equal((await viewport()).page, 2);
      await page.evaluate(()=>{ERead.command({type:'move',direction:1});ERead.command({type:'configure',config:{...window.readerInitOptions.config,mode:'SCROLL'}});});
      await page.evaluate(()=>Promise.all(window.pageTransitions));
      assert.equal(await page.evaluate(()=>document.getAnimations().filter(a=>a.id==='eread-page-turn').length), 0);
      evidence.push({style,middle});
    }
    return evidence;
  });

  await test('older-webviews-use-a-composited-fallback-without-native-readback', async () => {
    await open({mode:'PAGED',turn:'CURL'});
    await page.evaluate(()=>document.startViewTransition=undefined);
    await command({type:'move',direction:1});
    assert.equal((await viewport()).page,1);
    assert.deepEqual(await page.evaluate(()=>events.filter(e=>e.type==='turn')),[]);
    return {page:1};
  });

  await test('page-snapshots-have-opaque-paper-in-light-and-night-themes', async () => {
    const evidence = [];
    for (const paper of ['#f1ead8', '#141914']) {
      await open({mode:'PAGED',turn:'COVER',paper,night:paper==='#141914'});
      await page.evaluate(()=>ERead.command({type:'move',direction:1}));
      await page.waitForFunction(()=>document.getAnimations().some(a=>a.id==='eread-page-turn'));
      const backgrounds = await page.evaluate(()=>['old','new'].map(which=>
        getComputedStyle(document.documentElement,'::view-transition-'+which+'(root)').backgroundColor));
      assert.ok(backgrounds.every(color=>color!=='rgba(0, 0, 0, 0)'), 'Transparent old snapshots would show target text through the old text');
      await page.evaluate(()=>Promise.all(window.pageTransitions));
      assert.equal((await viewport()).page,1);
      evidence.push({paper,backgrounds});
    }
    return evidence;
  });

  await test('slide-starts-without-native-ack-and-rapid-turns-retire-the-old-animation', async () => {
    await open({ mode:'PAGED', turn:'SLIDE' });
    const start = await page.evaluate(() => {
      ERead.command({type:'move',direction:1});
      const animation = document.getAnimations().find(a => a.id === 'eread-page-turn');
      return {scroll:scrollX,width:innerWidth,animation:animation?.playState,turns:events.filter(e=>e.type==='turn').length};
    });
    assert.equal(start.scroll,start.width); assert.equal(start.animation,'running'); assert.equal(start.turns,0);
    await page.waitForTimeout(60);
    const midway = await page.evaluate(() => new DOMMatrix(getComputedStyle(document.body).transform).m41);
    assert.ok(midway>0&&midway<start.width);
    await page.evaluate(() => { ERead.command({type:'move',direction:1}); ERead.command({type:'move',direction:1}); });
    assert.equal(await page.evaluate(()=>document.getAnimations().filter(a=>a.id==='eread-page-turn').length),1);
    await page.waitForFunction(()=>!document.getAnimations().some(a=>a.id==='eread-page-turn'));
    assert.equal((await viewport()).page,3);
    assert.equal(await page.evaluate(()=>getComputedStyle(document.body).transform),'none');
    await page.evaluate(() => ERead.command({type:'move',direction:-1}));
    await command({type:'configure',config:{...baseConfig,mode:'SCROLL'}});
    assert.equal(await page.evaluate(()=>document.getAnimations().filter(a=>a.id==='eread-page-turn').length),0);
    assert.equal(await page.evaluate(()=>getComputedStyle(document.body).transform),'none');
    return {start,midway,finalPageBeforeModeChange:3};
  });

  await test('canonical-note-metadata-without-link', async () => {
    await open();
    const start = canonical.indexOf(canonicalParts[2]);
    await command({ type: 'notes', notes: [{ start, end: start + 2, label: '引用', content: '从本地索引恢复的原书注释。' }] });
    const rect = await textRect('quote');
    const underlines = await page.locator('#eread-overlays > i').count();
    assert.ok(underlines > 0, 'A canonical fallback note must be visibly marked');
    await page.mouse.click((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2);
    const note = await page.evaluate(() => window.events.findLast(event => event.type === 'footnote'));
    assert.equal(note.offset, start); assert.equal(note.label, '引用');
    assert.equal(note.content, '从本地索引恢复的原书注释。');
    await command({ type: 'notes', notes: [] });
    assert.equal(await page.locator('#eread-overlays > i').count(), 0);
    return note;
  });

  await test('viewport-includes-last-fully-visible-glyph', async () => {
    const evidence = [];
    for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
      await open({ mode }); await command({ type: 'navigate', offset: canonical.indexOf(lines[20]) });
      const visibleEnds = await page.evaluate(({ lines, canonical }) => {
        const ends = [];
        for (let index = 17; index < 27; index++) {
          const node = document.getElementById(`paragraph-${index}`).firstChild;
          for (let offset = 0; offset < node.length; offset++) {
            const range = document.createRange(); range.setStart(node, offset); range.setEnd(node, offset + 1);
            const rect = range.getBoundingClientRect();
            if (rect.left >= 23.5 && rect.right <= innerWidth - 23.5 && rect.top >= 17.5 && rect.bottom <= innerHeight - 17.5)
              ends.push(canonical.indexOf(lines[index]) + offset + 1);
          }
        }
        return ends;
      }, { lines, canonical });
      assert.ok(visibleEnds.length > 0);
      const state = await viewport(), last = Math.max(...visibleEnds);
      assert.ok(state.endOffset >= last, `The actual final visible glyph is omitted in ${mode}: last=${last}, viewport=${JSON.stringify(state)}`);
      evidence.push({ mode, lastVisibleExclusiveEnd: last, state });
    }
    return evidence;
  });

  await test('geometry-end-with-hidden-tail-and-partial-last-page', async () => {
    const source = { baseUrl: 'https://eread.local/short/archive/book.html', noteTargets: {}, images: [],
      canonicalText: '起点\n\n终点\n\n隐藏注释',
      html: '<html><head><style>p{margin:0} #spacer{height:1712px} #hidden{display:none}</style></head><body><p>起点</p><div id="spacer"></div><p>终点</p><p id="hidden">隐藏注释</p></body></html>' };
    const evidence = [];
    for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
      await open({ mode }, { source });
      const initial = await viewport(); assert.equal(initial.atStart, true); assert.equal(initial.atEnd, false);
      await command({ type: 'navigate', lastPage: true }); const last = await viewport();
      assert.equal(last.atEnd, true); assert.equal(last.atStart, false);
      assert.ok(last.endOffset < source.canonicalText.length, 'Hidden trailing definitions must remain outside the visible text interval');
      if (mode === 'SCROLL') assert.ok(last.page < last.pages - 1, 'Keep the top-anchor page number at a partial final viewport');
      evidence.push({ mode, initial, last });
    }
    return evidence;
  });

  const shortScrollSource = { baseUrl: 'https://eread.local/short-scroll/archive/book.html', noteTargets: {}, images: [],
    canonicalText: '起点\n\n终点',
    html: '<html><head><style>p{margin:0} #spacer{height:920px}</style></head><body><p>起点</p><div id="spacer"></div><p>终点</p></body></html>' };
  await test('scroll-step-shows-tail-before-crossing-chapter', async () => {
    await open({}, { source: shortScrollSource });
    const maximum = await page.evaluate(() => document.documentElement.scrollHeight - innerHeight);
    assert.ok(maximum > 0 && maximum < 760 * .85, 'Exercise a chapter shorter than two viewports');
    await command({ type: 'move', direction: 1 });
    assert.equal((await viewport()).atEnd, true);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'chapterStep')), [], 'First step must reveal the remaining tail');
    await command({ type: 'move', direction: 1 });
    assert.equal(await page.evaluate(() => window.events.findLast(event => event.type === 'chapterStep').direction), 1);
    await page.evaluate(maximum => { window.events = []; scrollTo(0, maximum / 2); }, maximum);
    await command({ type: 'move', direction: -1 });
    assert.equal((await viewport()).atStart, true);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'chapterStep')), [], 'First reverse step must reveal the chapter beginning');
    await command({ type: 'move', direction: -1 });
    assert.equal(await page.evaluate(() => window.events.findLast(event => event.type === 'chapterStep').direction), -1);
    return { maximum };
  });

  await test('touch-crosses-chapter-only-after-starting-at-edge', async () => {
    await open({}, { source: shortScrollSource });
    const session = await page.context().newCDPSession(page);
    const swipe = async (from, to) => {
      const previousEnds = await page.evaluate(() => window.events.filter(event => event.type === 'manualEnd').length);
      await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: from }] });
      for (let step = 1; step <= 5; step++) {
        await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 200, y: from + (to - from) * step / 5 }] });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
      }
      await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await page.waitForFunction(count => window.events.filter(event => event.type === 'manualEnd').length > count, previousEnds);
    };
    await swipe(650, 100);
    assert.equal((await viewport()).atEnd, true);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'chapterStep')), [], 'A normal swipe reaching the tail must not skip it');
    await swipe(650, 100);
    assert.equal(await page.evaluate(() => window.events.findLast(event => event.type === 'chapterStep').direction), 1);
    await page.evaluate(() => { window.events = []; });
    await swipe(100, 650);
    assert.equal((await viewport()).atStart, true);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'chapterStep')), [], 'A reverse swipe reaching the beginning must not skip it');
    await swipe(100, 650);
    assert.equal(await page.evaluate(() => window.events.findLast(event => event.type === 'chapterStep').direction), -1);
    return { state: await viewport() };
  });

  await test('cancelled-touch-releases-manual-reading-and-long-press', async () => {
    await open({ mode: 'PAGED' });
    const session = await page.context().newCDPSession(page);
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 320, y: 380 }] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 200, y: 380 }] });
    await page.waitForFunction(() => window.events.some(event => event.type === 'manualStart'));
    await session.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
    await page.waitForFunction(() => window.events.some(event => event.type === 'manualEnd'));
    assert.equal((await viewport()).scrolling, false);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'turn')), []);
    await open();
    await command({ type: 'navigate', image: imageIdentity });
    const box = await page.locator('img').first().boundingBox();
    const imageSession = await page.context().newCDPSession(page);
    await imageSession.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.x + box.width / 2, y: box.y + box.height / 2 }] });
    await imageSession.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
    await page.waitForTimeout(600);
    assert.deepEqual(await page.evaluate(() => window.events.filter(event => event.type === 'selection')), []);
    return { cancelledManual: true, cancelledImageSelection: true };
  });

  await test('repeated-typography-changes-keep-the-same-reading-anchor-and-override-inline-metrics', async () => {
    const evidence = [];
    for (const authorCss of [false, true]) {
      await open({ mode:'PAGED', authorCss });
      const offset = canonical.indexOf(lines[23]) + 15;
      await command({type:'navigate', offset});
      const initial = await viewport();
      await page.evaluate(() => { const p = document.getElementById('paragraph-23');
        p.style.setProperty('line-height', '7px', 'important'); p.style.setProperty('letter-spacing', '-2px', 'important'); });
      for (let i = 0; i < 20; i++) {
        const alternate = i % 2 === 0;
        await command({type:'configure', config:{...baseConfig, mode:'PAGED', authorCss,
          lineHeight:alternate ? 42 : baseConfig.lineHeight, letterSpacing:alternate ? 2 : baseConfig.letterSpacing,
          indent:alternate ? 3 : 2}});
        const state = await viewport();
        assert.ok(state.offset <= offset && state.endOffset > offset, `Lost the reflow anchor at ${i}: ${JSON.stringify(state)}`);
      }
      const final = await viewport();
      assert.equal(final.offset, initial.offset, 'Restoring identical typography must restore the same page without drift');
      const metrics = await page.evaluate(() => { const s = getComputedStyle(document.getElementById('paragraph-23'));
        return {line:parseFloat(s.lineHeight), tracking:parseFloat(s.letterSpacing), indent:parseFloat(s.textIndent)}; });
      assert.equal(metrics.line, baseConfig.lineHeight); assert.equal(metrics.tracking, baseConfig.letterSpacing);
      assert.ok(metrics.indent > 0);
      evidence.push({authorCss, initial:initial.offset, final:final.offset, metrics});
    }
    return evidence;
  });

  await test('paper-edge-drag-tracks-the-finger-commits-and-cancels-without-extra-turns', async () => {
    const evidence = [];
    for (const turn of ['SLIDE','COVER','CURL']) {
      await open({mode:'PAGED', turn, authorCss:false});
      await command({type:'page', page:3});
      const session = await page.context().newCDPSession(page);
      const touch = (type, x) => session.send('Input.dispatchTouchEvent', {type,
        touchPoints:x === undefined ? [] : [{x, y:380}]});
      await touch('touchStart', 386); await touch('touchMove', 235);
      const offset = () => page.evaluate(() => { const a=document.getAnimations().find(a=>a.playState==='paused'); return a ? -Number(a.currentTime)/Number(a.effect.getTiming().duration)*innerWidth : new DOMMatrix(getComputedStyle(document.body).transform).m41; });
      await page.waitForFunction(()=>document.getAnimations().some(a=>a.playState==='paused'));
      const beforeRelease = await offset();
      assert.ok(Math.abs(beforeRelease + 151) < 2, 'Live paper tracks the finger pixel for pixel');
      await touch('touchMove', 190);
      assert.ok(Math.abs((await offset()) + 196) < 2, 'Subsequent moves keep tracking');
      await touch('touchMove', 270);
      assert.ok(Math.abs((await offset()) + 116) < 2, 'Reversing the finger moves the same paper back');
      assert.ok(await page.evaluate(() => document.getAnimations().some(a=>a.playState==='paused')), 'The selected turn effect follows the held gesture');
      await page.screenshot({path:path.join(outputDir, 'held-' + turn + '.png')});
      await touch('touchEnd');
      await page.waitForFunction(() => !document.getAnimations().length);
      assert.equal((await viewport()).page, 4);
      await touch('touchStart', 6); await touch('touchMove', 14); await touch('touchEnd');
      await page.waitForFunction(() => !document.getAnimations().length);
      assert.equal((await viewport()).page, 4, 'A short drag must spring back');
      await touch('touchStart', 6); await touch('touchMove', 170); await touch('touchCancel');
      await page.waitForFunction(() => !document.getAnimations().length);
      assert.equal((await viewport()).page, 4, 'A cancelled grab must retain the original page');
      await touch('touchStart', 6); await touch('touchMove', 170);
      await page.waitForFunction(()=>document.getAnimations().some(a=>a.playState==='paused'));
      assert.ok(Math.abs((await offset()) + 164) < 2, 'The current paper also moves right when returning');
      await touch('touchEnd');
      await page.waitForFunction(() => !document.getAnimations().length);
      assert.equal((await viewport()).page, 3, 'Dragging back must reveal the previous page');
      assert.deepEqual(await page.evaluate(() => window.events.filter(e => e.type === 'tap')), []);
      evidence.push({turn, beforeRelease, finalPage:3});
      await session.detach();
    }
    return evidence;
  });

  await test('center-short-drag-and-long-press-have-distinct-behavior', async () => {
    await open({mode:'PAGED', turn:'COVER'});
    await command({type:'page', page:2});
    const session = await page.context().newCDPSession(page);
    const touch = (type,x) => session.send('Input.dispatchTouchEvent',{type,touchPoints:x===undefined?[]:[{x,y:380}]});
    await touch('touchStart',260); await touch('touchMove',120);
    await page.waitForFunction(()=>document.getAnimations().some(a=>a.playState==='paused'));
    assert.ok(await page.evaluate(()=>document.getAnimations().some(a=>a.playState==='paused' && Math.abs(Number(a.currentTime)/Number(a.effect.getTiming().duration)-140/innerWidth)<.02)));
    await touch('touchEnd'); await page.waitForFunction(()=>!document.getAnimations().length);
    assert.equal((await viewport()).page,3);
    await touch('touchStart',260); await page.waitForTimeout(550); await touch('touchMove',120); await touch('touchEnd');
    await page.waitForTimeout(100);
    assert.equal((await viewport()).page,3,'Long press must never become a page turn');
    assert.equal(await page.evaluate(()=>document.body.style.transform),'');
    await session.detach();
  });

  await test('cross-page-selection-snaps-native-auto-scroll-to-a-full-page', async () => {
    for (const dual of [false,true]) {
      await open({mode:'PAGED',turn:'COVER',dual});
      const quote = await page.evaluate(()=>{
        const paragraphs=[...document.querySelectorAll('p')].filter(p=>p.textContent.trim());
        const range=document.createRange();range.setStart(paragraphs[0],0);range.setEndAfter(paragraphs.at(-1));
        const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
        scrollTo(innerWidth*2.37,0); return selection.toString();
      });
      assert.ok(quote.length>0);
      await page.waitForFunction(()=>Math.abs(scrollX/innerWidth-Math.round(scrollX/innerWidth))<.001);
      assert.equal(await page.evaluate(()=>getSelection().toString()),quote,'Snapping must preserve selected text');
      await command({type:'clearSelection'});
      assert.equal(await page.evaluate(()=>document.body.style.transform),'');
      assert.ok(await page.evaluate(()=>Math.abs(scrollX/innerWidth-Math.round(scrollX/innerWidth))<.001));
    }
  });

  await test('programmatic-auto-turns-do-not-emit-manual-interruption', async () => {
    for (const mode of ['PAGED','VERTICAL_PAGED']) {
      await open({mode,turn:'COVER'});
      for(let i=0;i<3;i++) await command({type:'move',direction:1});
      assert.equal((await viewport()).page,3);
      assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='manualStart').length),0);
    }
  });

  await test('selection-menu-reports-after-releasing-the-long-press', async () => {
    await open({mode:'PAGED'});
    const session = await page.context().newCDPSession(page);
    await session.send('Input.dispatchTouchEvent', {type:'touchStart',touchPoints:[{x:120,y:180}]});
    await page.evaluate(() => {
      window.events=[];
      const text=document.getElementById('intro').firstChild, range=document.createRange();
      range.setStart(text,3); range.setEnd(text,12); getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await session.send('Input.dispatchTouchEvent', {type:'touchEnd',touchPoints:[]});
    await page.waitForFunction(()=>events.some(e=>e.type==='selection' && e.end>e.start));
    const selected=await page.evaluate(()=>events.findLast(e=>e.type==='selection' && e.end>e.start));
    assert.equal(canonical.substring(selected.start,selected.end),canonicalParts[1].substring(3,12));
    await session.detach();
  });

  if (startupManifest) await test('provided-actual-import-trusted-document-startup', async () => {
    const source = JSON.parse(await fs.readFile(startupManifest, 'utf8'));
    if (page) await page.close();
    page = await browser.newPage({ viewport: { width: 392, height: 760 }, hasTouch: true });
    page.on('pageerror', error => consoleErrors.push(error.message));
    await page.addInitScript(() => {
      window.events = [];
      window.__ERead = { event: json => window.events.push(JSON.parse(json)), convert: text => JSON.stringify({ text }) };
    });
    await page.route('https://eread.local/**', route => route.request().isNavigationRequest()
      ? route.fulfill({ contentType: 'text/html; charset=utf-8', body: source.trustedHtml })
      : route.fulfill({ status: 404, body: '' }));
    const started = performance.now();
    await page.goto(source.baseUrl);
    await page.waitForFunction(() => window.events.some(event => event.type === 'viewport' || event.type === 'error'), null, { timeout: 10_000 });
    const events = await page.evaluate(() => window.events);
    assert.deepEqual(events.filter(event => event.type === 'error'), []);
    assert.ok(events.some(event => event.type === 'ready'));
    assert.ok(events.some(event => event.type === 'viewport'));
    const first = events.find(event => event.type === 'viewport');
    const anchor = Math.floor(source.canonicalText.length / 2);
    if (anchor > 0) assert.ok(first.offset <= anchor + 2 && first.endOffset > anchor,
      'The production envelope must restore the saved anchor before publishing a viewport');
    assert.equal(await page.evaluate(() => document.documentElement.hasAttribute('data-eread-loading')), false);
    assert.notEqual(await page.locator('body').evaluate(node => getComputedStyle(node).opacity), '0');
    return { elapsedMs: Math.round(performance.now() - started), canonicalLength: source.canonicalText.length, events };
  });

  if (manifestsDir) for (const format of ['epub', 'mobi']) {
    await test(`provided-${format}-canonical-and-footnote`, async () => {
      const source = JSON.parse(await fs.readFile(path.join(manifestsDir, `${format}.json`), 'utf8'));
      await open({}, { source });
      assert.ok(source.canonicalText.length > 8000);
      if (screenshots) {
        await page.screenshot({ path: path.join(outputDir, `provided-${format}-chapter-start.png`) });
        await command({ type: 'configure', config: { ...baseConfig, authorCss: false } });
        await command({ type: 'navigate', offset: 0 });
        const ordinarySize = await page.locator('[data-eread-body]').first().evaluate(node => getComputedStyle(node).fontSize);
        assert.equal(ordinarySize, '19px');
        await page.screenshot({ path: path.join(outputDir, `provided-${format}-software-body.png`) });
        await command({ type: 'configure', config: baseConfig }); await command({ type: 'navigate', offset: 0 });
      }
      if (format === 'epub' && resourcesDir) {
        const styles = await page.evaluate(() => Array.from(document.querySelectorAll('link[rel~="stylesheet"]')).map(node => ({ href: node.href, rules: node.sheet?.cssRules.length || 0 })));
        assert.ok(styles.length > 0 && styles.every(style => style.rules > 0), 'Real EPUB original stylesheet files must be loaded');
      }
      const noteLink = page.locator('body a[href]').filter({ hasText: '[1]' }).first();
      await noteLink.click();
      const note = await page.evaluate(() => window.events.findLast(event => event.type === 'footnote'));
      assert.ok(note, 'The original first note reference must open a note instead of moving the page');
      assert.equal(note.label, '[1]'); assert.ok(Object.hasOwn(source.noteTargets, note.target), 'The emitted note target must resolve in the original source catalog');
      const checkpoints = [];
      for (const mode of ['SCROLL', 'PAGED', 'VERTICAL_PAGED']) {
        await command({ type: 'configure', config: { ...baseConfig, mode } });
        const target = Math.floor(source.canonicalText.length * .6);
        await command({ type: 'navigate', offset: target });
        const state = await viewport();
        assert.ok(state.offset <= target + 2 && state.endOffset > target, `Original ${format}/${mode} canonical target not in visible interval`);
        assert.ok(state.offset >= target - 600, `Original ${format}/${mode} state includes invisible early text`);
        checkpoints.push({ mode, ...state });
      }
      return { utf16Length: source.canonicalText.length, noteTargetCount: Object.keys(source.noteTargets).length, checkpoints };
    });
  }
} finally {
  await browser.close();
  const report = { browser: browserPath, fixture: 'ebook-reader/chapter.html', utf16Length: canonical.length,
    scriptSha256: createHash('sha256').update(readerScript).digest('hex'),
    filter: testFilter?.source || null,
    passed: results.filter(result => result.passed).length, failed: results.filter(result => !result.passed).length,
    consoleErrors, results };
  await fs.writeFile(path.join(outputDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(`Report: ${path.join(outputDir, 'report.json')}`);
  if (report.failed || consoleErrors.length) process.exitCode = 1;
}
