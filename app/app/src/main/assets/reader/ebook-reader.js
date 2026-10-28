(function () {
    'use strict';
    let canonical = '', entries = [], characters = [], images = [], config = {}, decorations = {};
    let initialized = false, started = false, manual = false, automatic = false, lastViewport = '', noteTargets = new Set();
    let frame = 0, endTimer = 0, resizeTimer = 0, touch = null, selectionTimer = 0, overlay;
    let navigationAnchor = null;
    let sourceNotes = [];
    let suppressClickUntil = 0;
    let autoScrollFrame = 0, autoScrollSpeed = 0, autoScrollTime = 0, autoScrollRemainder = 0;
    function setAutoScroll(speed) {
        if (autoScrollFrame) automatic = false;
        cancelAnimationFrame(autoScrollFrame); autoScrollFrame = 0; autoScrollTime = 0;
        autoScrollRemainder = 0;
        autoScrollSpeed = Number.isFinite(speed) && config.mode === 'SCROLL' ? Math.max(0, Math.min(160, speed)) : 0;
        if (!autoScrollSpeed) return;
        function tick(now) {
            if (document.hidden || manual || touch) { setAutoScroll(0); return; }
            const elapsed = autoScrollTime ? Math.min(50, now - autoScrollTime) / 1000 : 0;
            autoScrollTime = now;
            const maximum = Math.max(0, document.documentElement.scrollHeight - innerHeight);
            if (scrollY >= maximum - .5) { setAutoScroll(0); send('chapterStep', {direction:1}); return; }
            autoScrollRemainder += autoScrollSpeed * elapsed;
            const distance = Math.floor(autoScrollRemainder); autoScrollRemainder -= distance;
            if (distance) { automatic = true; scrollTo(0, Math.min(maximum, scrollY + distance)); schedule(); }
            autoScrollFrame = requestAnimationFrame(tick);
        }
        autoScrollFrame = requestAnimationFrame(tick);
    }
    let stableOffset = 0;
    let configurationGeneration = 0, activeFont = null;
    const loadedFonts = new Map(), sourceMappings = [];
    const imageCrops = new Map();
    const rangeGeometry = new Map();
    let lastBridgeReport = 0;
    const readerStyle = document.createElement('style');
    readerStyle.id = 'eread-reader-style';
    const pageBoundary = document.createElement('i');
    pageBoundary.id = 'eread-page-boundary';
    let contentPages = 1;
    const nodeEntries = new WeakMap();
    const sourceFontRatios = new Set();
    const textByParent = new Map(), visibleTextParents = new Set();
    let paragraphObserver;
    let pageAnimation = null;
    let pageTransition = null;
    const continuousHost = window.parent !== window ? window.parent.EReadContinuous : null;
    const frameChapter = continuousHost ? Number(window.frameElement.dataset.chapter) : -1;
    const bridge = continuousHost ? {
        event:json => continuousHost.event(frameChapter, JSON.parse(json), window.frameElement),
        convert:text => window.parent.__ERead.convert(text)
    } : window.__ERead;
    function viewBounds() { return continuousHost ? continuousHost.bounds(frameChapter) : {top:0, bottom:innerHeight, height:innerHeight}; }
    function measureChapter() {
        if (continuousHost) continuousHost.height(frameChapter, Math.max(document.body.offsetHeight, document.body.scrollHeight));
    }
    function send(type, values) { bridge.event(JSON.stringify(Object.assign({type: type}, values || {}))); }
    function fail(error) { send('error', {message: '原书排版失败：' + (error.message || String(error))}); }
    function whitespace(c) { return /[\s\u00a0\u200b\ufeff]/.test(c); }
    function markBodyParagraphs() {
        const structural = 'h1,h2,h3,h4,h5,h6,blockquote,pre,table,aside,figure,figcaption,header,footer,nav';
        const candidates = Array.from(document.querySelectorAll('p,div')).filter(node => node.textContent.trim() &&
            !node.closest(structural) && !node.querySelector('p,div,' + structural));
        const sizes = new Map();
        candidates.forEach(node => {
            const style = getComputedStyle(node);
            if (!/^(?:center|right|end|-webkit-center|-webkit-right)$/.test(style.textAlign)) {
                const size = Math.round(parseFloat(style.fontSize) * 100) / 100;
                sizes.set(size, (sizes.get(size) || 0) + node.textContent.trim().length);
            }
        });
        const dominantSize = Array.from(sizes).sort((a, b) => b[1] - a[1])[0];
        const bodySize = dominantSize ? dominantSize[0] : 16;
        const scale = (node, ratio) => {
            const value = String(Math.round(ratio * 10000) / 10000);
            node.dataset.ereadScale = value; sourceFontRatios.add(value);
        };
        document.body.querySelectorAll('*').forEach(node => {
            if (!node.textContent.trim() || node.closest('script,style,noscript,template')) return;
            scale(node, parseFloat(getComputedStyle(node).fontSize) / bodySize);
        });
        candidates.forEach(node => {
            const style = getComputedStyle(node), text = node.textContent.trim(), size = parseFloat(style.fontSize);
            if (!/^(?:center|right|end|-webkit-center|-webkit-right)$/.test(style.textAlign)) node.dataset.ereadParagraph = '1';
            const special = /^(?:center|right|end|-webkit-center|-webkit-right)$/.test(style.textAlign) ||
                style.fontStyle !== 'normal' || Number(style.fontWeight) >= 600 ||
                (text.length <= 80 && (size > bodySize * 1.15 || size < bodySize * .85));
            if (special) return;
            // Keep emphasis and note markers, while ordinary EPUB span/font wrappers
            // inherit the software body size instead of pinning the publisher's size.
            node.dataset.ereadBody = '1';
            scale(node, 1);
            node.querySelectorAll('*').forEach(child => {
                if (child.closest('sup,sub,ruby,rt,code,kbd')) return;
                const childStyle = getComputedStyle(child);
                if (Math.abs(parseFloat(childStyle.fontSize) - size) < .1) {
                    scale(child, 1); child.dataset.ereadBodySize = '1';
                }
                if (childStyle.fontWeight === style.fontWeight) child.dataset.ereadBodyWeight = '1';
            });
        });
    }
    function lowerBound(array, value) {
        let low = 0, high = array.length;
        while (low < high) { const mid = (low + high) >>> 1; if (array[mid] < value) low = mid + 1; else high = mid; }
        return low;
    }
    function pageHorizontal() { return config.mode === 'PAGED' && !config.vertical; }
    function pageStride() { return pageHorizontal() ? innerWidth / (config.dual ? 2 : 1) : viewBounds().height; }
    function pageIndex() { return Math.max(0, Math.round((pageHorizontal() ? scrollX : scrollY) / pageStride())); }
    function pageCount() {
        if (continuousHost) return Math.max(1, Math.ceil(document.body.scrollHeight / pageStride()));
        if (config.mode !== 'SCROLL') return contentPages;
        const size = pageHorizontal() ? document.documentElement.scrollWidth : document.documentElement.scrollHeight;
        return Math.max(1, Math.ceil((size - 1) / pageStride()));
    }
    function fitPageBoundary() {
        pageBoundary.style.display = 'none';
        if (config.mode === 'SCROLL') return;
        const horizontal = pageHorizontal(), stride = pageStride();
        const size = horizontal ? document.documentElement.scrollWidth : document.documentElement.scrollHeight;
        contentPages = Math.max(1, Math.ceil((size - 1) / stride));
        const span = horizontal && config.dual ? 2 : 1;
        const end = Math.ceil(contentPages / span) * span * stride - 1;
        // A partial final column has no trailing column gap. Give the scrollable
        // document its full last viewport, keeping the publisher DOM unchanged.
        pageBoundary.style.cssText = `position:absolute!important;display:block!important;visibility:hidden!important;pointer-events:none!important;` +
            `width:1px!important;height:1px!important;left:${horizontal ? end : 0}px!important;top:${horizontal ? 0 : end}px!important;`;
        if (!pageBoundary.isConnected) document.documentElement.appendChild(pageBoundary);
    }
    function bounds(rect) { return {left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom}; }
    function visible(rect) { const view = viewBounds(); return rect.right > 0 && rect.left < innerWidth && rect.bottom > view.top && rect.top < view.bottom; }
    function imageCropTarget(node) {
        let target = node.closest('svg') || node;
        while (target.ownerSVGElement) target = target.ownerSVGElement;
        return target;
    }
    function imageBounds(image) {
        const target = imageCropTarget(image.node), crop = imageCrops.get(target);
        if (!crop) return image.node.getBoundingClientRect();
        const frame = crop.frame.getBoundingClientRect();
        if (target === image.node) return frame;
        const rect = image.node.getBoundingClientRect();
        const left = Math.max(frame.left, rect.left), right = Math.min(frame.right, rect.right);
        const top = Math.max(frame.top, rect.top), bottom = Math.min(frame.bottom, rect.bottom);
        return right > left && bottom > top ? {left, right, top, bottom, width:right-left, height:bottom-top} :
            {left:0, right:0, top:0, bottom:0, width:0, height:0};
    }
    function applyImageCrop() {
        function restore(node, style) {
            if (style === null) node.removeAttribute('style'); else node.setAttribute('style', style);
        }
        imageCrops.forEach((state, node) => {
            if (state.created) state.frame.replaceWith(node); else restore(state.frame, state.frameStyle);
            restore(node, state.style);
        });
        imageCrops.clear();
        const crop = config.imageCrop;
        if (!crop || !crop.enabled) return;
        const margin = key => Number.isFinite(Number(crop[key])) ? Math.max(0, Math.min(.4, Number(crop[key]))) : 0;
        const left = margin('left'), right = margin('right'), top = margin('top'), bottom = margin('bottom');
        if (!(left || right || top || bottom)) return;
        const widthRatio = 1 - left - right, heightRatio = 1 - top - bottom;
        const maxHeight = Math.max(32, viewBounds().height - config.top - config.bottom);
        // Measure the original layout in one batch before writing any crop frames.
        // Keep img/srcset and SVG nodes intact, so resource loading and text offsets do not change.
        const layout = Array.from(document.body.querySelectorAll('img,svg')).filter(node => !node.parentElement.closest('svg')).map(node => {
            const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
            const height = node.naturalWidth > 0 ? rect.width * node.naturalHeight / node.naturalWidth : rect.height;
            if (!(rect.width > 0 && height > 0)) return null;
            const scale = Math.min(1 / widthRatio, maxHeight / (height * heightRatio));
            const picture = node.parentElement.tagName === 'PICTURE' ? node.parentElement : null;
            const properties = Object.fromEntries(['margin-left', 'margin-right', 'margin-top', 'margin-bottom', 'float',
                'vertical-align', 'align-self', 'justify-self', 'order', 'z-index'].map(key => [key, style.getPropertyValue(key)]));
            properties.display = style.display === 'block' ? 'block' : 'inline-block';
            properties.position = style.position === 'static' ? 'relative' : style.position;
            if (style.position !== 'static') ['top', 'right', 'bottom', 'left'].forEach(key => properties[key] = style.getPropertyValue(key));
            return {node, picture, properties, width:rect.width * scale, height:height * scale};
        }).filter(Boolean);
        layout.forEach(item => {
            const node = item.node, frame = item.picture || document.createElement('span');
            imageCrops.set(node, {frame, created:!item.picture, style:node.getAttribute('style'), frameStyle:frame.getAttribute('style')});
            if (!item.picture) { node.before(frame); frame.appendChild(node); frame.dataset.ereadImageCrop = '1'; }
            Object.entries(Object.assign(item.properties, {overflow:'hidden', width:item.width * widthRatio + 'px',
                height:item.height * heightRatio + 'px', 'max-width':'100%', 'box-sizing':'content-box', 'break-inside':'avoid'}))
                .forEach(([key, value]) => frame.style.setProperty(key, value, 'important'));
            Object.entries({position:'absolute', width:item.width + 'px', height:item.height + 'px',
                left:-item.width * left + 'px', top:-item.height * top + 'px', right:'auto', bottom:'auto',
                'max-width':'none', 'max-height':'none', 'min-width':'0', 'min-height':'0', margin:'0', 'box-sizing':'border-box'})
                .forEach(([key, value]) => node.style.setProperty(key, value, 'important'));
        });
    }
    function domPoint(offset, end) {
        if (!characters.length) return {node: document.body, offset: 0};
        let index = lowerBound(characters, Math.max(0, Math.min(canonical.length, offset)));
        if (end) index--;
        index = Math.max(0, Math.min(characters.length - 1, index));
        const character = mapped[index];
        const entry = entries[character.entry];
        return {node: entry.node, offset: end ? character.end : character.start};
    }
    let mapped = [];
    function rangeFor(start, end) {
        const from = domPoint(start, false), to = domPoint(end, true), range = document.createRange();
        range.setStart(from.node, from.offset);
        range.setEnd(to.node, Math.max(from.node === to.node ? from.offset : 0, to.offset));
        return range;
    }
    function canonicalPoint(node, offset, end) {
        if (node.nodeType !== Node.TEXT_NODE) {
            const child = end ? node.childNodes[Math.max(0, offset - 1)] : node.childNodes[offset];
            if (!child) return null;
            if (child.nodeType === Node.TEXT_NODE) node = child;
            else {
                const walker = document.createTreeWalker(child, NodeFilter.SHOW_TEXT);
                node = walker.nextNode();
                if (end) { let next; while ((next = walker.nextNode())) node = next; }
            }
            offset = node && end ? node.length : 0;
        }
        const entry = node && nodeEntries.get(node);
        if (!entry || !entry.canonical.length) return null;
        let index = lowerBound(entry.display, offset);
        if (end) return index === 0 ? entry.canonical[0] : entry.canonical[index - 1] + 1;
        return index >= entry.canonical.length ? entry.canonical[entry.canonical.length - 1] + 1 : entry.canonical[index];
    }
    function caretLocation(x, y) {
        let node, offset;
        if (document.caretPositionFromPoint) {
            const point = document.caretPositionFromPoint(x, y);
            if (point) { node = point.offsetNode; offset = point.offset; }
        } else if (document.caretRangeFromPoint) {
            const point = document.caretRangeFromPoint(x, y);
            if (point) { node = point.startContainer; offset = point.startOffset; }
        }
        return node ? {node:node, offset:offset} : null;
    }
    function caret(x, y, end, requireVisible, nodes) {
        const point = caretLocation(x, y);
        if (!point) return null;
        const offset = canonicalPoint(point.node, point.offset, end);
        if (offset === null) return null;
        if (nodes) nodes.add(domPoint(offset, end).node);
        if (requireVisible && !Array.from(rangeFor(end ? Math.max(0, offset - 1) : offset,
            end ? offset : Math.min(canonical.length, offset + 1)).getClientRects()).some(visible)) return null;
        return offset;
    }
    function viewport() {
        const points = [], shownImages = [], nodes = new Set();
        if (!continuousHost) visibleTextParents.forEach(parent => textByParent.get(parent).forEach(node => nodes.add(node)));
        // A bounded sample of the visible viewport, never a scan of every page or glyph.
        const left = Math.max(2, config.left || 0), right = innerWidth - Math.max(2, config.right || 0);
        const view = viewBounds();
        const top = Math.max(view.top + 2, config.top || 0), bottom = Math.min(view.bottom - 2, innerHeight - (config.bottom || 0));
        for (let row = 0; row <= 8; row++) for (let column = 0; column <= 4; column++) {
            const x = left + (right - left) * column / 4, y = top + (bottom - top) * row / 8;
            const at = caret(x, y, false, true, nodes), end = caret(x, y, true, true, nodes);
            if (at !== null) points.push(at);
            if (end !== null) points.push(end);
        }
        // Sampling discovers nearby text nodes; their actual line boxes fill gaps
        // between samples without walking every glyph or every page in the chapter.
        nodes.forEach(node => {
            const range = document.createRange(); range.selectNodeContents(node);
            Array.from(range.getClientRects()).filter(visible).forEach(rect => {
                const x = Math.max(1, Math.min(innerWidth - 1, (rect.left + rect.right) / 2));
                const y = Math.max(view.top + 1, Math.min(view.bottom - 1, (rect.top + rect.bottom) / 2));
                const start = caret(config.vertical ? x : Math.max(1, rect.left + .5),
                    config.vertical ? Math.max(view.top + 1, rect.top + .5) : y, false, true);
                const end = caret(config.vertical ? x : Math.min(innerWidth - 1, rect.right - .5),
                    config.vertical ? Math.min(view.bottom - 1, rect.bottom - .5) : y, true, true);
                if (start !== null) points.push(start);
                if (end !== null) points.push(end);
            });
        });
        images.forEach(image => {
            if (visible(imageBounds(image))) {
                shownImages.push(image.path); points.push(image.offset);
            }
        });
        // Whitespace immediately before a sampled glyph belongs to the same paragraph.
        let start = points.length ? Math.min.apply(null, points) : 0;
        let end = points.length ? Math.max.apply(null, points) : canonical.length;
        while (start > 0 && whitespace(canonical[start - 1])) start--;
        while (end < canonical.length && whitespace(canonical[end])) end++;
        const horizontal = pageHorizontal(), position = horizontal ? scrollX : continuousHost ? view.top : scrollY;
        const extent = horizontal ? document.documentElement.scrollWidth : document.documentElement.scrollHeight;
        return {offset: start, endOffset: end, page: continuousHost ? Math.floor(position / pageStride()) : pageIndex(), pages: pageCount(), images: shownImages, scrolling: manual,
            atStart: position <= 1, atEnd: position + (horizontal ? innerWidth : view.height) >= extent - 1};
    }
    function report(force) {
        if (!started || pageAnimation || pageTransition) return;
        const now = performance.now();
        if (!force && now - lastBridgeReport < 80) { drawDecorations(); return; }
        lastBridgeReport = now;
        const state = viewport(), signature = JSON.stringify(state);
        stableOffset = state.offset;
        if (force || signature !== lastViewport) { lastViewport = signature; send('viewport', state); }
        drawDecorations();
    }
    function schedule() {
        if (!frame) frame = requestAnimationFrame(function () { frame = 0; report(false); });
    }
    function beginManual() {
        navigationAnchor = null;
        if (!manual) { manual = true; send('manualStart'); }
        clearTimeout(endTimer);
    }
    function endManual() {
        clearTimeout(endTimer);
        endTimer = setTimeout(function () { manual = false; send('manualEnd'); report(true); }, 140);
    }
    function moveToPage(page) {
        finishPageAnimation();
        const span = pageHorizontal() && config.dual ? 2 : 1;
        const target = Math.floor(Math.max(0, Math.min(pageCount() - 1, page)) / span) * span * pageStride();
        automatic = true;
        scrollTo(pageHorizontal() ? target : 0, pageHorizontal() ? 0 : target);
        requestAnimationFrame(function () { automatic = false; report(true); });
    }
    function navigate(command) {
        finishPageAnimation();
        const targetImage = command.image && images.find(image => image.path === command.image);
        const range = targetImage ? null : rangeFor(command.offset || 0, Math.min(canonical.length, (command.offset || 0) + 1));
        const rect = targetImage ? imageBounds(targetImage) : range.getBoundingClientRect();
        navigationAnchor = {offset: command.offset || 0, image: command.image};
        if (continuousHost) {
            measureChapter();
            const y = command.lastPage ? Math.max(0, document.body.scrollHeight - viewBounds().height) : rect.top + scrollY - (config.top || 0);
            continuousHost.navigateLocal(frameChapter, y, command.ensureVisible); return;
        }
        if (command.ensureVisible && visible(rect)) { report(true); return; }
        if (command.lastPage) { moveToPage(pageCount() - 1); return; }
        if (config.mode === 'SCROLL') {
            automatic = true;
            scrollTo(0, Math.max(0, rect.top + scrollY - (config.top || 0)));
            requestAnimationFrame(function () { automatic = false; report(true); });
        } else {
            const coordinate = pageHorizontal() ? rect.left + scrollX : rect.top + scrollY;
            moveToPage(Math.floor((coordinate + .5) / pageStride()));
        }
    }
    function step(direction) {
        if (config.mode === 'SCROLL') {
            beginManual();
            const maximum = Math.max(0, document.documentElement.scrollHeight - innerHeight);
            const target = Math.max(0, Math.min(maximum, scrollY + direction * innerHeight * .85));
            if ((direction > 0 && scrollY >= maximum - 1) || (direction < 0 && scrollY <= 1)) send('chapterStep', {direction: direction});
            else scrollTo(0, target);
            endManual();
            return;
        }
        const span = pageHorizontal() && config.dual ? 2 : 1;
        const target = (pageTransition ? pageTransition.page : pageIndex()) + direction * span;
        beginManual();
        if (target < 0 || target >= pageCount()) send('chapterStep', {direction: direction});
        else if (config.eink || config.mode === 'VERTICAL_PAGED') moveToPage(target);
        else if (config.turn === 'SLIDE') animatePage(target);
        else transitionPage(target, direction);
        endManual();
    }
    function finishPageAnimation() {
        if (pageTransition) {
            const previous = pageTransition; pageTransition = null;
            previous.animations.forEach(animation => animation.cancel());
            previous.transition.skipTransition(); automatic = false;
        }
        if (!pageAnimation) return;
        const previous = pageAnimation; pageAnimation = null; previous.cancel();
        automatic = false;
        if (overlay) overlay.style.visibility = '';
    }
    function transitionPage(page, direction) {
        finishPageAnimation();
        // Keep both snapshots in Chromium's compositor. Android software WebView
        // readback can still contain the previous GPU frame, even after a visual callback.
        if (!document.startViewTransition) { animatePage(page); return; }
        const state = {page:page, transition:null, animations:[]};
        pageTransition = state; automatic = true;
        state.transition = document.startViewTransition(function () {
            if (pageTransition !== state) return;
            scrollTo(page * pageStride(), 0);
        });
        state.transition.ready.then(function () {
            if (pageTransition !== state) return;
            const curl = config.turn === 'CURL', forward = direction > 0;
            const moving = forward ? 'old' : 'new', fixed = forward ? 'new' : 'old';
            const movingPseudo = '::view-transition-' + moving + '(root)';
            const still = document.documentElement.animate([{zIndex:1},{zIndex:1}],
                {duration:curl ? 280 : 180, fill:'both', pseudoElement:'::view-transition-' + fixed + '(root)'});
            const start = curl ? 'perspective(1400px) rotateY(0deg)' : 'translateX(0)';
            const end = curl ? 'perspective(1400px) rotateY(-110deg)' : 'translateX(-100%)';
            const frames = [
                {transform:start, filter:'brightness(1)', zIndex:2, transformOrigin:'left center'},
                {transform:end, filter:curl ? 'brightness(.65)' : 'brightness(1)', zIndex:2, transformOrigin:'left center'}
            ];
            const animation = document.documentElement.animate(forward ? frames : frames.reverse(),
                {duration:curl ? 280 : 180, easing:'cubic-bezier(.2,.65,.3,1)', fill:'both', pseudoElement:movingPseudo});
            animation.id = still.id = 'eread-page-turn';
            state.animations.push(animation, still);
        }).catch(function () { /* A resize or a newer navigation can skip a transition. */ });
        state.transition.finished.then(function () {
            state.animations.forEach(animation => animation.cancel());
            if (pageTransition !== state) return;
            pageTransition = null; automatic = false; report(true);
        });
    }
    function animatePage(page) {
        finishPageAnimation();
        const horizontal = pageHorizontal(), before = horizontal ? scrollX : scrollY, span = horizontal && config.dual ? 2 : 1;
        const target = Math.floor(Math.max(0, Math.min(pageCount() - 1, page)) / span) * span * pageStride();
        automatic = true;
        scrollTo(horizontal ? target : 0, horizontal ? 0 : target);
        // One existing column flow, composited by Chromium. No bitmap readback,
        // JavaScript-interface roundtrip, or wait for an Android drawing callback.
        const animation = document.body.animate([
            {transform:`translate${horizontal ? 'X' : 'Y'}(${target - before}px)`}, {transform:'translate(0)'}
        ], {duration:180, easing:'cubic-bezier(.2,.65,.3,1)'});
        animation.id = 'eread-page-turn'; pageAnimation = animation;
        if (overlay) overlay.style.visibility = 'hidden';
        animation.onfinish = function () {
            if (pageAnimation !== animation) return;
            pageAnimation = null; automatic = false;
            if (overlay) overlay.style.visibility = '';
            report(true);
        };
    }
    async function configure(next, preserve, force) {
        const generation = ++configurationGeneration;
        if (!force && started && JSON.stringify(next) === JSON.stringify(config)) return;
        let font = null;
        if (next.font === 'eread-selected') {
            if (!loadedFonts.has(next.fontUrl)) {
                const face = new FontFace('eread-selected', `url(${JSON.stringify(next.fontUrl)})`);
                loadedFonts.set(next.fontUrl, face.load().catch(error => { loadedFonts.delete(next.fontUrl); throw error; }));
            }
            font = await loadedFonts.get(next.fontUrl);
            if (generation !== configurationGeneration) return;
        }
        finishPageAnimation();
        const old = preserve && started ? viewport() : null;
        const anchor = old && navigationAnchor && (navigationAnchor.image || Array.from(rangeFor(navigationAnchor.offset,
            Math.min(canonical.length, navigationAnchor.offset + 1)).getClientRects()).some(visible)) ? navigationAnchor : old;
        config = next;
        if (activeFont !== font) {
            if (activeFont) document.fonts.delete(activeFont);
            if (font) document.fonts.add(font);
            activeFont = font;
        }
        while (loadedFonts.size > 3) {
            const key = loadedFonts.keys().next().value;
            if (key === config.fontUrl) { const value = loadedFonts.get(key); loadedFonts.delete(key); loadedFonts.set(key, value); }
            else loadedFonts.delete(key);
        }
        if (config.paperImage) { const paper = new Image(); paper.src = config.paperImage; }
        rangeGeometry.clear();
        const horizontal = pageHorizontal(), scrolling = config.mode === 'SCROLL', vertical = !!config.vertical;
        const column = innerWidth / (config.dual && horizontal ? 2 : 1);
        const width = Math.max(32, column - config.left - config.right);
        const height = Math.max(32, viewBounds().height - config.top - config.bottom);
        const family = config.font === 'eread-selected' ? 'eread-selected' : config.font;
        const software = !config.authorCss ? `
            ${Array.from(sourceFontRatios).map(ratio => `[data-eread-scale="${ratio}"] { font-size:${config.fontSize * Number(ratio)}px!important; }`).join('\n')}
            [data-eread-body] { font-size:${config.fontSize}px!important; line-height:${config.lineHeight}px!important;
                letter-spacing:${config.letterSpacing}px!important; font-weight:${config.bold ? 700 : 400}!important;
                margin-top:0!important; margin-bottom:${config.paragraphGap}px!important; text-indent:2em!important; }
            ${vertical ? `[data-eread-body] { margin-block-start:0!important; margin-block-end:${config.paragraphGap}px!important; margin-inline:0!important; }` : ''}
            [data-eread-body-size] { font-size:inherit!important; line-height:inherit!important; letter-spacing:inherit!important; }
            [data-eread-body-weight] { font-weight:inherit!important; }
        ` : '';
        readerStyle.textContent = `
            ::view-transition-group(root),::view-transition-old(root),::view-transition-new(root) { animation:none; }
            ::view-transition-image-pair(root) { isolation:isolate; }
            ::view-transition-old(root),::view-transition-new(root) { mix-blend-mode:normal; height:100%; object-fit:fill; backface-visibility:hidden;
                background:${config.paper || '#fff'} ${config.paperImage ? `linear-gradient(${config.night ? 'rgba(0,0,0,.72),rgba(0,0,0,.72)' : 'rgba(255,255,255,.72),rgba(255,255,255,.72)'}),url('${config.paperImage}') center/cover` : ''}; }
            html { margin:0!important; padding:0!important; width:100%!important; height:${continuousHost ? 'auto' : '100%'}!important; writing-mode:horizontal-tb!important; scroll-behavior:auto!important; overflow:${scrolling && !continuousHost ? 'auto' : 'hidden'}!important; background:transparent!important; }
            body { box-sizing:content-box!important; margin:0!important; padding:${config.top}px ${config.right}px ${config.bottom}px ${config.left}px!important;
                width:${horizontal ? width : Math.max(32, innerWidth - config.left - config.right)}px!important; min-width:0!important;
                height:${horizontal || vertical ? height + 'px' : 'auto'}!important; min-height:${horizontal || vertical ? '0' : height + 'px'}!important;
                writing-mode:${vertical ? 'vertical-rl' : 'horizontal-tb'}!important; text-orientation:mixed!important;
                column-width:${vertical ? height + 'px' : horizontal ? width + 'px' : 'auto'}!important; column-count:auto!important;
                column-gap:${vertical ? config.top + config.bottom : config.left + config.right}px!important; column-fill:auto!important; background:transparent!important;
                color:${config.color}!important; font-size:${config.fontSize}px; -webkit-text-size-adjust:none!important; overflow:visible!important; }
            body,body * { font-family:${family}!important; }
            ${vertical ? 'body * { writing-mode:inherit!important; text-orientation:inherit!important; }' : ''}
            ${software}
            ${config.night || config.eink ? `body * { color:${config.color}!important; background-color:transparent!important; }` : ''}
            img,svg,image,video { max-width:100%!important; max-height:${height}px; object-fit:contain; break-inside:avoid; }
            table { max-width:100%; } a { color:inherit; } a[href] { text-decoration:underline; }
            #eread-overlays { position:${continuousHost ? 'absolute' : 'fixed'}!important; inset:0!important; padding:0!important; margin:0!important; pointer-events:none!important;
                z-index:2147483646!important; width:100vw!important; height:100vh!important; columns:auto!important; }
            #eread-overlays > i { position:absolute!important; margin:0!important; padding:0!important; pointer-events:none!important; }
            ::selection { background:rgba(104,120,68,.35); }
        `;
        // Apply after publisher inline declarations too; otherwise inline !important
        // margins and author-CSS mode silently defeat the paragraph-gap control.
        document.querySelectorAll('[data-eread-paragraph]').forEach(node => {
            node.style.setProperty('margin-block-start', '0px', 'important');
            node.style.setProperty('margin-block-end', Math.max(0, Number(config.paragraphGap) || 0) + 'px', 'important');
        });
        applyImageCrop();
        fitPageBoundary();
        measureChapter();
        if (anchor && (!continuousHost || continuousHost.isActive(frameChapter)))
            navigate({offset: anchor.offset, image: anchor.image});
    }
    function rectsFor(start, end) {
        if (end <= start || start < 0 || !characters.length) return [];
        const key = start + ':' + end;
        let cached = rangeGeometry.get(key);
        if (!cached) {
            cached = Array.from(rangeFor(start, end).getClientRects()).map(rect => ({
                left:rect.left + scrollX, right:rect.right + scrollX, top:rect.top + scrollY,
                bottom:rect.bottom + scrollY, width:rect.width, height:rect.height
            }));
            if (rangeGeometry.size >= 2048) rangeGeometry.delete(rangeGeometry.keys().next().value);
            rangeGeometry.set(key, cached);
        }
        return cached.map(rect => ({left:rect.left - scrollX, right:rect.right - scrollX,
            top:rect.top - scrollY, bottom:rect.bottom - scrollY, width:rect.width, height:rect.height})).filter(visible);
    }
    function decorationRects() {
        const result = [];
        (decorations.annotations || []).forEach(item => {
            const image = item.image && images.find(image => image.path === item.image);
            const rects = image ? [imageBounds(image)].filter(visible) : rectsFor(item.start, item.end);
            rects.forEach(rect => result.push({item: item, rect: rect}));
        });
        return result;
    }
    function drawDecorations() {
        if (!overlay) return;
        overlay.textContent = '';
        function mark(rect, kind, color, wavy) {
            if (!visible(rect)) return;
            const line = document.createElement('i');
            line.style.cssText = `left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px;`;
            if (kind === 'UNDERLINE' || kind === 'NOTE') {
                if (config.vertical) {
                    line.style.borderRight = `2px solid ${color}`;
                    if (wavy) { line.style.borderRight = '0'; line.style.background = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='4' height='8'%3E%3Cpath d='M2 0 Q0 2 2 4 T2 8' fill='none' stroke='%23${color.replace('#', '')}' stroke-width='1.5'/%3E%3C/svg%3E") top right/4px 8px repeat-y`; }
                } else {
                    line.style.borderBottom = `2px solid ${color}`;
                    if (wavy) { line.style.borderBottom = '0'; line.style.background = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='8' height='4'%3E%3Cpath d='M0 2 Q2 0 4 2 T8 2' fill='none' stroke='%23${color.replace('#', '')}' stroke-width='1.5'/%3E%3C/svg%3E") bottom left/8px 4px repeat-x`; }
                }
            } else { line.style.backgroundColor = color; line.style.opacity = '.3'; }
            overlay.appendChild(line);
        }
        decorationRects().forEach(({item, rect}) => {
            const color = item.color == null ? '#b89a45' : '#' + (Number(item.color) >>> 0).toString(16).padStart(8, '0').slice(-6);
            mark(rect, item.kind, color, item.underline === 'WAVY');
        });
        sourceNotes.forEach(note => rectsFor(note.start, note.end).forEach(rect => mark(rect, 'NOTE', config.color, false)));
        rectsFor(decorations.spokenStart, decorations.spokenEnd).forEach(rect => mark(rect, 'SPEECH', '#e8a75c', false));
        if (decorations.search) {
            let from = 0, found, count = 0;
            while ((found = canonical.indexOf(decorations.search, from)) >= 0 && count++ < 1000) {
                rectsFor(found, found + decorations.search.length).forEach(rect => mark(rect, 'SEARCH', '#e5c33b', false));
                from = found + Math.max(1, decorations.search.length);
            }
        }
    }
    function selection() {
        clearTimeout(selectionTimer);
        selectionTimer = setTimeout(function () {
            const selected = getSelection();
            if (!selected || selected.isCollapsed || !selected.rangeCount) { send('selection', {start:0, end:0}); return; }
            const range = selected.getRangeAt(0), start = canonicalPoint(range.startContainer, range.startOffset, false);
            const end = canonicalPoint(range.endContainer, range.endOffset, true);
            if (start !== null && end !== null) send('selection', Object.assign({start:start, end:end, page:pageIndex()}, bounds(range.getBoundingClientRect())));
        }, 60);
    }
    async function buildMap() {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) { return node.parentElement.closest('script,style,noscript,template,#eread-overlays') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }
        });
        const originals = [];
        let node;
        while ((node = walker.nextNode())) originals.push(node);
        const domChars = [], domLocations = [], wanted = [], wantedLocations = [];
        let position = 0;
        originals.forEach((node, index) => {
            const entry = {node:node, original:node.data, base:position, canonical:[], display:[]};
            entries.push(entry); nodeEntries.set(node, entry);
            for (let offset = 0; offset < node.data.length; offset++) if (!whitespace(node.data[offset])) {
                domChars.push(node.data[offset]); domLocations.push([index, offset]);
            }
            position += node.data.length;
        });
        for (let index = 0; index < canonical.length; index++) if (!whitespace(canonical[index])) { wanted.push(canonical[index]); wantedLocations.push(index); }
        let a = 0, b = 0, missing = 0;
        const pairs = [];
        while (a < wanted.length && b < domChars.length) {
            if (wanted[a] === domChars[b]) { pairs.push([a++, b++]); }
            else {
                // Malformed legacy markup may add a few glyphs. Resynchronise only at a
                // multi-character anchor; never silently align an unrelated document.
                let match = null;
                for (let gap = 1; gap <= 128 && !match; gap++) for (let left = 0; left <= gap; left++) {
                    const right = gap - left;
                    if (a + left + 8 > wanted.length || b + right + 8 > domChars.length) continue;
                    let same = true;
                    for (let k = 0; k < 8; k++) if (wanted[a + left + k] !== domChars[b + right + k]) { same = false; break; }
                    if (same) { match = [left, right]; break; }
                }
                if (!match) throw new Error('原书文字与阅读记录无法定位，请重新导入');
                missing += match[0] + match[1]; a += match[0]; b += match[1];
            }
            if (a % 8192 === 0) await new Promise(resolve => setTimeout(resolve, 0));
        }
        missing += wanted.length - a + domChars.length - b;
        if (missing > Math.max(4, wanted.length * .015)) throw new Error('原书文字与阅读记录不一致，未改动阅读位置');
        pairs.forEach(pair => {
            const location = domLocations[pair[1]];
            sourceMappings.push({source:wantedLocations[pair[0]], entry:location[0], offset:location[1]});
        });
        convertText();
    }
    function convertText() {
        const anchor = started ? navigationAnchor || viewport() : null;
        const all = entries.map(entry => entry.original).join('');
        const converted = JSON.parse(bridge.convert(all));
        const forward = converted.forward;
        const displayOffset = offset => forward ? forward[Math.max(0, Math.min(forward.length - 1, offset))] : offset;
        characters.length = 0; mapped.length = 0;
        entries.forEach(entry => {
            entry.canonical.length = 0; entry.display.length = 0;
            entry.node.data = converted.text.substring(displayOffset(entry.base), displayOffset(entry.base + entry.original.length));
        });
        sourceMappings.forEach(point => {
            const entry = entries[point.entry];
            const start = displayOffset(entry.base + point.offset) - displayOffset(entry.base);
            const end = displayOffset(entry.base + point.offset + 1) - displayOffset(entry.base);
            characters.push(point.source); mapped.push({entry:point.entry, start:start, end:Math.max(start, end)});
            entry.canonical.push(point.source); entry.display.push(start);
        });
        rangeGeometry.clear();
        if (started) {
            fitPageBoundary(); measureChapter();
            if (anchor && (!continuousHost || continuousHost.isActive(frameChapter))) navigate(anchor);
            report(true);
        }
    }
    async function init(options) {
        if (initialized) return;
        initialized = true;
        try {
            canonical = options.canonical; decorations = options.decorations; noteTargets = new Set(options.noteTargets || []);
            sourceNotes = options.notes || [];
            // MOBI 6 encodes paragraph indentation/spacing as HTML attributes rather
            // than CSS. Translate those presentation attributes, retaining the DOM.
            const length = value => /^-?\d+(?:\.\d+)?(?:em|ex|pt|px|%|in|cm|mm)?$/i.test(value || '') ?
                (/^-?\d+(?:\.\d+)?$/.test(value) ? value + 'px' : value) : null;
            document.querySelectorAll('p[width],p[height],div[width],div[height]').forEach(node => {
                const indent = length(node.getAttribute('width')), gap = length(node.getAttribute('height'));
                if (indent && !node.style.textIndent) node.style.textIndent = indent;
                if (gap && !node.style.marginTop) node.style.marginTop = gap;
            });
            document.querySelectorAll('font[size]').forEach(node => {
                const size = Number(node.getAttribute('size'));
                if (Number.isInteger(size) && size >= 1 && size <= 7 && !node.style.fontSize)
                    node.style.fontSize = [.63, .82, 1, 1.13, 1.5, 2, 3][size - 1] + 'em';
            });
            markBodyParagraphs();
            document.head.appendChild(readerStyle);
            await buildMap();
            // Let the browser track sparse paragraphs between sampling rows.
            // Only visible parents contribute text candidates on each report.
            entries.filter(entry => entry.canonical.length).forEach(entry => {
                const parent = entry.node.parentElement;
                if (!textByParent.has(parent)) textByParent.set(parent, []);
                textByParent.get(parent).push(entry.node);
            });
            paragraphObserver = new IntersectionObserver(records => {
                records.forEach(record => record.isIntersecting && record.intersectionRect.width > 0 && record.intersectionRect.height > 0 ?
                    visibleTextParents.add(record.target) : visibleTextParents.delete(record.target));
                if (started) schedule();
            });
            textByParent.forEach((nodes, parent) => paragraphObserver.observe(parent));
            const nodes = Array.from(document.querySelectorAll('img,svg image'));
            images = nodes.map((node, index) => Object.assign({node:node}, options.images[index] || {offset:0, path:''}));
            const resourceReady = function () {
                if (!started) return;
                rangeGeometry.clear();
                applyImageCrop();
                if (continuousHost) { measureChapter(); report(true); return; }
                fitPageBoundary();
                navigate(navigationAnchor || {offset:stableOffset});
            };
            nodes.forEach(node => node.addEventListener('load', resourceReady));
            document.fonts.addEventListener('loadingdone', resourceReady);
            await configure(options.config, false);
            overlay = document.createElement('div'); overlay.id = 'eread-overlays'; document.documentElement.appendChild(overlay);
            await Promise.race([document.fonts.ready, new Promise(resolve => setTimeout(resolve, 1500))]);
            await Promise.all(nodes.map(node => node.complete || node.tagName !== 'IMG' ? Promise.resolve() : new Promise(resolve => {
                node.addEventListener('load', resolve, {once:true}); node.addEventListener('error', resolve, {once:true}); setTimeout(resolve, 1000);
            })));
            requestAnimationFrame(function () {
                try {
                    applyImageCrop();
                    fitPageBoundary();
                    measureChapter();
                    if (options.initialNavigation) navigate(options.initialNavigation);
                    document.documentElement.removeAttribute('data-eread-loading');
                    started = true;
                    send('ready'); report(true);
                }
                catch (error) { fail(error); }
            });
        } catch (error) { fail(error); }
    }
    async function command(value) {
        if (!started) return;
        try {
            if (value.type === 'navigate') navigate(value);
            else if (value.type === 'resume') {
                const options = value.options;
                sourceNotes = options.notes || [];
                decorations = options.decorations;
                await configure(options.config, false);
                if (!options.preserveViewport) navigate(options.initialNavigation || {offset:0});
                requestAnimationFrame(function () { send('ready'); report(true); });
            }
            else if (value.type === 'move') step(value.direction);
            else if (value.type === 'autoScroll') setAutoScroll(value.speed);
            else if (value.type === 'page') moveToPage(value.page);
            else if (value.type === 'clearSelection') getSelection().removeAllRanges();
            else if (value.type === 'configure') await configure(value.config, true);
            else if (value.type === 'convert') convertText();
            else if (value.type === 'decorate') { decorations = value.decorations; drawDecorations(); }
            else if (value.type === 'notes') { sourceNotes = value.notes; drawDecorations(); }
            else if (value.type === 'report') report(true);
            else if (value.type === 'hostViewport') report(true);
            else if (value.type === 'hostResize') await configure(config, true, true);
        } catch (error) { fail(error); }
    }
    document.addEventListener('selectionchange', selection);
    window.addEventListener('pagehide', function () {
        setAutoScroll(0);
        if (paragraphObserver) paragraphObserver.disconnect();
        visibleTextParents.clear(); textByParent.clear();
    });
    window.addEventListener('scroll', function () { if (!automatic && touch) beginManual(); schedule(); if (manual) endManual(); }, {passive:true});
    document.addEventListener('wheel', function () {
        if (config.mode === 'SCROLL') { setAutoScroll(0); beginManual(); endManual(); }
    }, {passive:true});
    window.addEventListener('resize', function () {
        if (continuousHost) return;
        clearTimeout(resizeTimer); resizeTimer = setTimeout(function () { configure(config, true, true).catch(fail); }, 80);
    });
    document.addEventListener('touchstart', function (event) {
        if (!started || event.touches.length !== 1) return;
        if (continuousHost) continuousHost.pauseAutoScroll();
        if (autoScrollSpeed) { setAutoScroll(0); beginManual(); endManual(); }
        finishPageAnimation();
        touch = {x:event.touches[0].clientX, y:event.touches[0].clientY, time:Date.now(), moved:false, image:event.target.closest('img,image,svg'),
            startAtStart: scrollY <= 1, startAtEnd: scrollY + innerHeight >= document.documentElement.scrollHeight - 1};
        clearTimeout(selectionTimer);
        if (touch.image) selectionTimer = setTimeout(function () {
            const image = images.find(image => image.node === touch.image || touch.image.contains(image.node));
            if (image && touch && !touch.moved) { touch.long = true; suppressClickUntil = Date.now() + 1000; send('selection', Object.assign({start:image.offset, end:image.offset, image:image.path, page:pageIndex()}, bounds(imageBounds(image)))); }
        }, 500);
    }, {passive:true});
    document.addEventListener('touchmove', function (event) {
        if (!touch) return;
        const point = event.touches[0];
        if (Math.hypot(point.clientX - touch.x, point.clientY - touch.y) > 10) { touch.moved = true; clearTimeout(selectionTimer); beginManual(); }
        if (config.mode !== 'SCROLL' && getSelection().isCollapsed) event.preventDefault();
    }, {passive:false});
    document.addEventListener('touchend', function (event) {
        if (!touch) return;
        const previous = touch; touch = null; clearTimeout(selectionTimer);
        if (previous.moved && config.mode !== 'SCROLL' && getSelection().isCollapsed) {
            const point = event.changedTouches[0], delta = config.mode === 'PAGED' ? point.clientX - previous.x : point.clientY - previous.y;
            if (Math.abs(delta) > 24) step(delta < 0 ? 1 : -1);
        } else if (previous.moved && config.mode === 'SCROLL' && !continuousHost) {
            const delta = event.changedTouches[0].clientY - previous.y;
            if (previous.startAtStart && scrollY <= 1 && delta > 70) send('chapterStep', {direction:-1});
            else if (previous.startAtEnd && scrollY + innerHeight >= document.documentElement.scrollHeight - 1 && delta < -70) send('chapterStep', {direction:1});
        }
        if (manual) endManual();
    }, {passive:true});
    document.addEventListener('touchcancel', function () {
        touch = null;
        clearTimeout(selectionTimer);
        if (manual) endManual();
    }, {passive:true});
    document.addEventListener('contextmenu', function (event) { if (event.target.closest('img,svg')) event.preventDefault(); });
    document.addEventListener('click', function (event) {
        if (!started) return;
        event.preventDefault();
        if (Date.now() < suppressClickUntil) return;
        const selection = getSelection();
        if (selection && !selection.isCollapsed) return;
        const link = event.target.closest('a[href]');
        if (link) {
            const target = link.getAttribute('href'), id = target && target.split('#')[1];
            const note = id && document.getElementById(decodeURIComponent(id));
            const at = caret(event.clientX, event.clientY, false) || 0;
            const filepos = id && /^eread-filepos-(\d+)$/.exec(id);
            const noteTarget = [target, id && '#' + id, id, filepos && 'filepos:' + Number(filepos[1])].find(value => noteTargets.has(value));
            if (noteTarget || /noteref|doc-noteref/.test((link.getAttribute('epub:type') || '') + ' ' + (link.getAttribute('role') || '')))
                send('footnote', Object.assign({target:noteTarget || target, content:note ? note.textContent : '', label:link.textContent, offset:at}, bounds(link.getBoundingClientRect())));
            else if (note) {
                const walker = document.createTreeWalker(note, NodeFilter.SHOW_TEXT), text = walker.nextNode();
                const offset = text ? canonicalPoint(text, 0, false) : null;
                if (offset !== null) navigate({offset:offset});
            }
            return;
        }
        const noteHit = sourceNotes.map(note => ({note:note, rect:rectsFor(note.start, note.end).find(rect =>
            event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom)})).find(hit => hit.rect);
        if (noteHit) {
            send('footnote', Object.assign({offset:noteHit.note.start, label:noteHit.note.label, content:noteHit.note.content}, bounds(noteHit.rect)));
            return;
        }
        const hits = decorationRects().filter(({rect}) => event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom);
        if (hits.length) send('annotation', Object.assign({ids:Array.from(new Set(hits.map(hit => hit.item.id)))}, bounds(hits[0].rect)));
        else send('tap', {x:event.clientX / innerWidth, y:event.clientY / innerHeight});
    });
    window.ERead = {init:init, command:command};
    if (continuousHost) new ResizeObserver(measureChapter).observe(document.body);
})();
