(function () {
    if (window.__stAppExtInit) return;
    window.__stAppExtInit = true;

    // Pure string ops only — never insert untrusted message HTML into the DOM
    // (innerHTML on AI content is an XSS vector, e.g. <img onerror>).
    function stripText(s) {
        if (!s) return '';
        var text = String(s)
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;/g, ' ')
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/\s+/g, ' ')
            .trim();
        return text.length > 100 ? text.slice(0, 100) + '…' : text;
    }

    function lastAiMessage(chat) {
        if (!Array.isArray(chat)) return '';
        for (var i = chat.length - 1; i >= 0; i--) {
            var m = chat[i];
            if (m && !m.is_user && !m.is_system) return m.mes || '';
        }
        return '';
    }

    function onGenerationEnded() {
        try {
            if (!document.hidden || !window.STAndroid) return;
            var ctx = window.SillyTavern.getContext();
            var name = ctx.name2 || 'SillyTavern';
            var preview = stripText(lastAiMessage(ctx.chat));
            if (!preview) return;
            window.STAndroid.notifyAiResponded(name + ' replied', preview);
        } catch (e) { /* ignore */
        }
    }

    function injectSettings() {
        try {
            if (!window.STAndroid || document.getElementById('st_app_settings')) return;
            var container = document.getElementById('user-settings-block-content');
            if (!container) return;
            var block = document.createElement('div');
            block.id = 'st_app_settings';
            block.className = 'flex-container flexFlowColumn flex1';
            block.innerHTML =
                '<h4><span>Android App</span></h4>' +
                '<div class="flex-container alignItemsBaseline">' +
                '<span>Auto-exit after idle (minutes, 0 = never):</span>' +
                '<input id="st_app_idle_min" class="text_pole widthNatural flex1" type="number" min="0" step="1" />' +
                '</div>';
            container.appendChild(block);
            var input = block.querySelector('#st_app_idle_min');
            input.value = String(window.STAndroid.getIdleTimeoutMinutes());
            input.addEventListener('change', function () {
                var v = parseInt(input.value, 10);
                if (isNaN(v) || v < 0) v = 0;
                input.value = String(v);
                window.STAndroid.setIdleTimeoutMinutes(v);
            });
        } catch (e) { /* ignore */
        }
    }

    var tries = 0;
    var timer = setInterval(function () {
        tries++;
        var ready = window.SillyTavern && typeof window.SillyTavern.getContext === 'function';
        if (ready) {
            var ctx = null;
            try {
                ctx = window.SillyTavern.getContext();
            } catch (e) {
                ctx = null;
            }
            if (ctx && ctx.eventSource && ctx.eventTypes) {
                clearInterval(timer);
                ctx.eventSource.on(ctx.eventTypes.GENERATION_ENDED, onGenerationEnded);
                injectSettings();
                return;
            }
        }
        if (tries > 60) clearInterval(timer); // give up after ~30s
    }, 500);
})();
