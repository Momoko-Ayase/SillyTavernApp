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

    var generating = false;

    function notify(title, body) {
        try {
            if (window.STAndroid && window.STAndroid.notifyAi) {
                window.STAndroid.notifyAi(title, body);
            }
        } catch (e) { /* ignore */
        }
    }

    function charName() {
        try {
            return window.SillyTavern.getContext().name2 || 'SillyTavern';
        } catch (e) {
            return 'SillyTavern';
        }
    }

    function onGenerationStarted() {
        generating = true;
        if (document.hidden) notify(charName(), 'Waiting for AI response…');
    }

    function onGenerationEnded() {
        generating = false;
        if (!document.hidden) return;
        try {
            var ctx = window.SillyTavern.getContext();
            var name = ctx.name2 || 'SillyTavern';
            var preview = stripText(lastAiMessage(ctx.chat));
            if (preview) notify(name + ' replied', preview);
            else notify(name, 'AI didn’t respond normally');
        } catch (e) {
            notify('SillyTavern', 'AI didn’t respond normally');
        }
    }

    function onGenerationStopped() {
        if (!generating) return;
        generating = false;
        if (document.hidden) notify(charName(), 'AI did not respond normally');
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
                '</div>' +
                '<div class="flex-container alignItemsBaseline">' +
                '<input id="st_app_test_notif" class="menu_button" type="button" value="Send test notification" />' +
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
            var testBtn = block.querySelector('#st_app_test_notif');
            testBtn.addEventListener('click', function () {
                notify('SillyTavern', 'Test notification ✅');
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
                ctx.eventSource.on(ctx.eventTypes.GENERATION_STARTED, onGenerationStarted);
                ctx.eventSource.on(ctx.eventTypes.GENERATION_ENDED, onGenerationEnded);
                ctx.eventSource.on(ctx.eventTypes.GENERATION_STOPPED, onGenerationStopped);
                document.addEventListener('visibilitychange', function () {
                    // Covers: started while visible, then backgrounded mid-generation.
                    if (document.hidden && generating) {
                        notify(charName(), 'Waiting for AI response…');
                    }
                });
                injectSettings();
                return;
            }
        }
        if (tries > 60) clearInterval(timer); // give up after ~30s
    }, 500);
})();
