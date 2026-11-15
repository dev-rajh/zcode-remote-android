package com.raj.zcoderemote.web

import android.webkit.JavascriptInterface
import com.raj.zcoderemote.notify.ApprovalMonitor

/**
 * JavaScript bridge installed into the WebView as `ZCodeAndroid`.
 *
 * The notification shim (see [NOTIFICATION_SHIM_JS]) replaces
 * `window.Notification` before page scripts run, so the ZCode web app sees
 * "granted" notification permission inside the WebView and every notification
 * it posts (permission requests, input requests, plan approvals, completion,
 * errors) is forwarded to [ApprovalMonitor] instead of the browser.
 */
class StateBridge {

    @JavascriptInterface
    fun onNotification(json: String) {
        ApprovalMonitor.emit(json)
    }

    @JavascriptInterface
    fun onOpenWindow(url: String) {
        android.util.Log.d("ZCodeFlow", "js window.open → $url")
    }

    companion object {
        const val BRIDGE_NAME = "ZCodeAndroid"

        val NOTIFICATION_SHIM_JS = """
            (() => {
              if (window.__zcodeNotificationShimInstalled) return;
              window.__zcodeNotificationShimInstalled = true;
              function ZCodeNotification(title, options) {
                var payload = options || {};
                try {
                  if (window.ZCodeAndroid) {
                    window.ZCodeAndroid.onNotification(JSON.stringify({
                      title: String(title == null ? '' : title),
                      body: String(payload.body == null ? '' : payload.body)
                    }));
                  }
                } catch (e) {}
                this.title = title == null ? '' : String(title);
                this.body = payload.body == null ? '' : String(payload.body);
                this.tag = payload.tag == null ? '' : String(payload.tag);
                this.icon = payload.icon || '';
                this.silent = !!payload.silent;
                this.data = payload.data || null;
                this.lang = '';
                this.dir = 'auto';
                this.badge = '';
                this.timestamp = Date.now();
                this.requireInteraction = false;
                this.actions = [];
                this.onclick = null;
                this.onclose = null;
                this.onerror = null;
                this.onshow = null;
              }
              ZCodeNotification.prototype.close = function() {};
              ZCodeNotification.permission = 'granted';
              ZCodeNotification.maxActions = 2;
              ZCodeNotification.requestPermission = function(callback) {
                if (typeof callback === 'function') callback('granted');
                return Promise.resolve('granted');
              };
              try {
                Object.defineProperty(window, 'Notification', {
                  value: ZCodeNotification,
                  writable: false,
                  configurable: false
                });
              } catch (e) {
                window.Notification = ZCodeNotification;
              }
              try {
                // The SPA only raises its notifications when
                // !document.hasFocus() (to avoid spamming the focused desktop
                // window). Some OEM WebViews keep reporting focus even after
                // the app is backgrounded, which silences every event. Force
                // "unfocused" so chat completion / waiting / approval events
                // always reach the Android notification pipeline.
                document.hasFocus = function() { return false; };
              } catch (e) {}
              try {
                // window.open is handled natively (setSupportMultipleWindows +
                // WebChromeClient.onCreateWindow hosts a real popup WebView, so
                // window.opener/postMessage keep working for the OAuth flow).
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
