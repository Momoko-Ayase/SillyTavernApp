(function () {
    if (window.__stBusyInit) return;
    window.__stBusyInit = true;
    window.__ST_BUSY = 0;

    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function () {
            window.__ST_BUSY++;
            var done = false;
            var dec = function () {
                if (!done) {
                    done = true;
                    window.__ST_BUSY = Math.max(0, window.__ST_BUSY - 1);
                }
            };
            return origFetch.apply(this, arguments).then(function (resp) {
                try {
                    if (resp && resp.body && resp.body.getReader) {
                        var reader = resp.body.getReader();
                        var stream = new ReadableStream({
                            start: function (controller) {
                                (function pump() {
                                    return reader.read().then(function (r) {
                                        if (r.done) {
                                            controller.close();
                                            dec();
                                            return;
                                        }
                                        controller.enqueue(r.value);
                                        return pump();
                                    }).catch(function (e) {
                                        controller.error(e);
                                        dec();
                                    });
                                })();
                            },
                            // Consumer aborted (e.g. user stopped generation): the pump
                            // may never reach r.done, so release the busy count here too.
                            cancel: function (reason) {
                                dec();
                                return reader.cancel(reason);
                            }
                        });
                        return new Response(stream, {
                            headers: resp.headers,
                            status: resp.status,
                            statusText: resp.statusText
                        });
                    }
                    dec();
                    return resp;
                } catch (e) {
                    dec();
                    return resp;
                }
            }).catch(function (e) {
                dec();
                throw e;
            });
        };
    }

    var OrigXHR = window.XMLHttpRequest;
    window.XMLHttpRequest = function () {
        var xhr = new OrigXHR();
        xhr.addEventListener('loadstart', function () {
            window.__ST_BUSY++;
        });
        var d = function () {
            window.__ST_BUSY = Math.max(0, window.__ST_BUSY - 1);
        };
        xhr.addEventListener('loadend', d);
        return xhr;
    };
})();
