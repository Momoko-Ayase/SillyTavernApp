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

(function () {
    if (window.__stExportInit) return;
    window.__stExportInit = true;
    // Browser (no native bridge): leave default download behavior alone.
    if (!window.STAndroid || typeof window.STAndroid.saveFile !== 'function') return;

    // Retain blobs created via createObjectURL so a synchronous revoke (as in
    // ST's download() helper) can't free them before we read the bytes.
    var blobs = Object.create(null);
    var origCreate = URL.createObjectURL;
    var origRevoke = URL.revokeObjectURL;

    URL.createObjectURL = function (obj) {
        var u = origCreate.call(URL, obj);
        try {
            if (obj instanceof Blob) blobs[u] = obj;
        } catch (e) { /* ignore */
        }
        return u;
    };
    URL.revokeObjectURL = function (u) {
        if (blobs[u]) {
            // Defer; our click handler reads it synchronously right after.
            setTimeout(function () {
                delete blobs[u];
                try {
                    origRevoke.call(URL, u);
                } catch (e) { /* ignore */
                }
            }, 15000);
            return;
        }
        return origRevoke.call(URL, u);
    };

    function readAndSave(blob, name) {
        var r = new FileReader();
        r.onload = function () {
            var s = String(r.result);
            var i = s.indexOf(',');
            var b64 = i >= 0 ? s.slice(i + 1) : s;
            try {
                window.STAndroid.saveFile(name || 'download', b64,
                    blob.type || 'application/octet-stream');
            } catch (e) { /* ignore */
            }
        };
        r.readAsDataURL(blob);
    }

    function handleHref(href, name) {
        if (href.indexOf('blob:') === 0) {
            var blob = blobs[href];
            if (blob) {
                readAndSave(blob, name);
                return true;
            }
            try {
                fetch(href).then(function (resp) {
                    return resp.blob();
                })
                    .then(function (b) {
                        readAndSave(b, name);
                    })
                    .catch(function () { /* ignore */
                    });
                return true;
            } catch (e) {
                return false;
            }
        }
        if (href.indexOf('data:') === 0) {
            var comma = href.indexOf(',');
            var meta = href.slice(5, comma);
            var mime = meta.split(';')[0] || 'application/octet-stream';
            var data = href.slice(comma + 1);
            var b64 = /;base64/i.test(meta) ? data
                : btoa(unescape(decodeURIComponent(data)));
            try {
                window.STAndroid.saveFile(name || 'download', b64, mime);
            } catch (e) { /* ignore */
            }
            return true;
        }
        return false;
    }

    // ST's download() uses a detached anchor + a.click(); a click event never
    // reaches the document, so override the method to catch programmatic clicks.
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        try {
            if (this.hasAttribute('download')) {
                var href = this.getAttribute('href') || this.href || '';
                if (handleHref(href, this.getAttribute('download'))) return;
            }
        } catch (e) { /* ignore */
        }
        return origClick.apply(this, arguments);
    };

    // Also catch user clicks on in-DOM a[download] links (capture phase).
    document.addEventListener('click', function (ev) {
        var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
        if (!a) return;
        var href = a.getAttribute('href') || '';
        if (handleHref(href, a.getAttribute('download'))) {
            ev.preventDefault();
            ev.stopPropagation();
        }
    }, true);
})();
