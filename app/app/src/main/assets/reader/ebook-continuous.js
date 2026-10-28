(function () {
    'use strict';
    const bridge = window.__ERead;
    let options, active = -1, serial = 0, ready = false, frame = 0, manual = false, endTimer;
    let pending = null, automatic = 0, last = null;
    let autoScrollFrame = 0, autoScrollSpeed = 0, autoScrollTime = 0, autoScrollRemainder = 0;
    function setAutoScroll(speed) {
        cancelAnimationFrame(autoScrollFrame); autoScrollFrame = 0; autoScrollTime = 0;
        autoScrollRemainder = 0;
        autoScrollSpeed = Number.isFinite(speed) ? Math.max(0, Math.min(160, speed)) : 0;
        if (!autoScrollSpeed) return;
        function tick(now) {
            if (document.hidden || manual) { setAutoScroll(0); return; }
            const elapsed = autoScrollTime ? Math.min(50, now - autoScrollTime) / 1000 : 0;
            autoScrollTime = now;
            // Pause at an unprepared seam rather than scrolling through estimated placeholders.
            const next = rows[active]?.getBoundingClientRect().bottom > innerHeight + 1 ? active : active + 1;
            if (active === rows.length - 1 && scrollY + innerHeight >= document.documentElement.scrollHeight - 1) {
                setAutoScroll(0); schedule(); return;
            }
            if (!pending && loaded.get(active)?.ready && (next >= rows.length || loaded.get(next)?.ready)) {
                autoScrollRemainder += autoScrollSpeed * elapsed;
                const distance = Math.floor(autoScrollRemainder); autoScrollRemainder -= distance;
                if (distance) {
                    automatic++;
                    scrollBy(0, distance);
                    requestAnimationFrame(() => automatic--);
                    schedule();
                }
            }
            autoScrollFrame = requestAnimationFrame(tick);
        }
        autoScrollFrame = requestAnimationFrame(tick);
    }
    function pauseAutoScroll() {
        if (!autoScrollSpeed) return;
        setAutoScroll(0); manual = true; send('manualStart');
        clearTimeout(endTimer); endTimer = setTimeout(() => { manual = false; send('manualEnd'); schedule(); }, 140);
    }
    function beginManual() {
        setAutoScroll(0); pending = null;
        if (!manual) { manual = true; send('manualStart'); }
        clearTimeout(endTimer);
        endTimer = setTimeout(() => { manual = false; send('manualEnd'); schedule(); }, 140);
    }
    let lastReport = -Infinity, reportTimer = 0;
    const rows = [], loaded = new Map();
    function send(type, data) { bridge.event(JSON.stringify(Object.assign({type:type}, data || {}))); }
    function commandChild(index, command) { loaded.get(index)?.frame?.contentWindow?.ERead?.command(command); }
    function request(index) {
        if (index < 0 || index >= rows.length || loaded.has(index)) return;
        const token = ++serial;
        loaded.set(index, {token:token}); send('chapterRequest', {chapter:index, token:token});
    }
    function retain(center) {
        active = center;
        for (const [index, value] of loaded) if (Math.abs(index - center) > 1) {
            value.frame?.remove(); loaded.delete(index); send('chapterDiscard', {chapter:index, token:value.token});
        }
        request(center);
        // Only adjacent chapters are prefetched; the rest remain height/index placeholders.
        if (loaded.get(center)?.ready) { request(center + 1); request(center - 1); }
    }
    function bounds(index) {
        const rect = rows[index].getBoundingClientRect();
        return {top:Math.max(0, -rect.top), bottom:Math.min(rect.height, innerHeight - rect.top), height:innerHeight};
    }
    function height(index, pixels) {
        const row = rows[index], before = row.getBoundingClientRect(), next = Math.max(1, Math.ceil(pixels));
        const delta = next - before.height;
        if (Math.abs(delta) < 1) return;
        row.style.height = next + 'px';
        const value = loaded.get(index);
        if (value?.frame) value.frame.style.height = next + 'px';
        // Disable native scroll anchoring; compensate exactly once when a preceding
        // placeholder is replaced or its fonts/images change the chapter height.
        if (before.bottom <= 1 && delta) { automatic++; scrollBy(0, delta); requestAnimationFrame(() => automatic--); }
        schedule();
    }
    function report() {
        frame = 0;
        let low = 0, high = rows.length;
        while (low < high) { const mid = (low + high) >>> 1; if (rows[mid].getBoundingClientRect().bottom <= 1) low = mid + 1; else high = mid; }
        let index = Math.min(rows.length - 1, low);
        if (index < 0) return;
        if (pending) index = pending.chapter;
        if (index !== active) retain(index);
        const wait = 80 - (performance.now() - lastReport);
        if (wait > 0) { clearTimeout(reportTimer); reportTimer = setTimeout(schedule, wait); return; }
        lastReport = performance.now();
        commandChild(index, {type:'hostViewport'});
    }
    function schedule() { if (!frame) frame = requestAnimationFrame(report); }
    function navigate(value) {
        const chapter = Math.max(0, Math.min(rows.length - 1, value.chapter ?? active));
        pending = Object.assign({}, value, {chapter:chapter}); retain(chapter);
        if (loaded.get(chapter)?.ready) commandChild(chapter, pending);
    }
    function navigateLocal(index, y, ensureVisible) {
        const row = rows[index], rect = row.getBoundingClientRect();
        const target = rect.top + scrollY + Math.max(0, y);
        if (!ensureVisible || target < scrollY || target >= scrollY + innerHeight) {
            automatic++; scrollTo(0, target); requestAnimationFrame(() => { automatic--; schedule(); });
        }
        pending = null;
        lastReport = -Infinity;
        document.documentElement.removeAttribute('data-eread-loading');
        if (!ready) { ready = true; send('ready'); }
        schedule();
    }
    function event(index, value, sourceFrame) {
        const item = loaded.get(index);
        if (!item || sourceFrame && item.frame !== sourceFrame) return;
        if (value.type === 'ready') {
            item.ready = true; item.frame.style.opacity = '1';
            const navigation = pending?.chapter === index ? pending : null;
            commandChild(index, {type:'configure', config:Object.assign({}, options.config, {continuous:true})});
            if (navigation) commandChild(index, navigation);
            else if (index === active && !ready) navigateLocal(index, 0, false);
            if (index === active) retain(index);
            schedule(); return;
        }
        if (value.type === 'viewport') {
            if (!ready || index !== active || pending) return;
            last = Object.assign({}, value, {chapter:index, scrolling:manual || value.scrolling});
            bridge.event(JSON.stringify(last)); return;
        }
        if (value.type === 'manualStart') { setAutoScroll(0); manual = true; pending = null; clearTimeout(endTimer); }
        if (value.type === 'manualEnd') {
            clearTimeout(endTimer); endTimer = setTimeout(() => { manual = false; send('manualEnd'); schedule(); }, 140); return;
        }
        if (value.type === 'error' && index !== active) {
            item.frame?.remove(); loaded.delete(index); send('chapterDiscard', {chapter:index, token:item.token}); return;
        }
        if (value.left !== undefined) {
            const rect = rows[index].getBoundingClientRect(); value.top += rect.top; value.bottom += rect.top;
        }
        if (value.type === 'tap') value.y = (value.y * item.frame.clientHeight + rows[index].getBoundingClientRect().top) / innerHeight;
        bridge.event(JSON.stringify(Object.assign({}, value, {chapter:index})));
    }
    function provide(index, token, url) {
        const item = loaded.get(index);
        if (!item || item.token !== token) return;
        if (!item.frame) {
            const child = document.createElement('iframe');
            child.dataset.chapter = index; child.title = options.chapters[index].title;
            child.style.cssText = 'display:block;border:0;width:100%;opacity:0;height:' + rows[index].offsetHeight + 'px';
            item.frame = child; rows[index].appendChild(child); child.src = url;
        } else commandChild(index, {type:'hostViewport'});
    }
    function init(value) {
        if (options) return;
        options = value;
        document.documentElement.style.cssText = 'overflow-anchor:none;scroll-behavior:auto;background:transparent';
        document.body.style.cssText = 'margin:0;padding:0;background:transparent;overflow-anchor:none';
        const width = Math.max(32, innerWidth - value.config.left - value.config.right);
        value.chapters.forEach((chapter, index) => {
            const row = document.createElement('section'); row.dataset.chapter = index;
            row.style.height = Math.max(innerHeight, Math.ceil(chapter.length * value.config.fontSize / width) * value.config.lineHeight + value.config.top + value.config.bottom) + 'px';
            rows.push(row); document.body.appendChild(row);
        });
        navigate(value.initialNavigation);
    }
    function command(value) {
        if (value.type === 'navigate') navigate(value);
        else if (value.type === 'report') schedule();
        else if (value.type === 'clearSelection') loaded.forEach(item => item.frame?.contentWindow?.getSelection()?.removeAllRanges());
        else if (value.type === 'autoScroll') setAutoScroll(value.speed);
        else if (value.type === 'move') {
            manual = true; send('manualStart'); scrollBy(0, value.direction * innerHeight * .85);
            clearTimeout(endTimer); endTimer = setTimeout(() => { manual = false; send('manualEnd'); schedule(); }, 140);
        } else if (value.type === 'resume') {
            options = value.options;
            for (const [index, item] of loaded) send('chapterRequest', {chapter:index, token:item.token});
            if (!value.options.preserveViewport) { ready = false; navigate(options.initialNavigation); }
            else { ready = true; send('ready'); schedule(); }
        } else if (value.type === 'configure') {
            options.config = value.config;
            loaded.forEach((item, index) => commandChild(index, {type:'configure', config:Object.assign({}, value.config, {continuous:true})}));
        } else if (value.type === 'convert') {
            loaded.forEach((item, index) => commandChild(index, {type:'convert'}));
        } else if (value.type === 'decorateChapter') commandChild(value.chapter, {type:'decorate', decorations:value.decorations});
        else if (value.type === 'notesChapter') commandChild(value.chapter, {type:'notes', notes:value.notes});
    }
    addEventListener('scroll', () => {
        // Layout/anchor corrections also emit scroll. Only real user input may
        // pause narration; keep fling events manual until scrolling settles.
        if (!automatic && ready && manual) {
            clearTimeout(endTimer); endTimer = setTimeout(() => { manual = false; send('manualEnd'); schedule(); }, 140);
        }
        schedule();
    }, {passive:true});
    addEventListener('resize', () => { loaded.forEach((item, index) => commandChild(index, {type:'hostResize'})); schedule(); });
    addEventListener('touchstart', pauseAutoScroll, {passive:true});
    addEventListener('touchmove', beginManual, {passive:true});
    addEventListener('wheel', beginManual, {passive:true});
    addEventListener('pagehide', () => setAutoScroll(0));
    window.EReadContinuous = {event:event, bounds:bounds, height:height, navigateLocal:navigateLocal, provide:provide,
        isActive:index => index === active, pauseAutoScroll:pauseAutoScroll};
    window.ERead = {init:init, command:command};
})();
