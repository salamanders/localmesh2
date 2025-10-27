# Feature: Push-based UI Updates

## Goal

To replace the current polling mechanism in the JavaScript frontend with a push-based system, where the Android backend can proactively send status updates to the UI. This will improve efficiency and reduce latency.

## Design

The implementation will consist of the following changes:

1.  **`main.js`**:
    *   A new global function, `window.onStatusUpdate(statusString)`, will be created to receive and process status updates from the Android side.
    *   The existing `setInterval` polling mechanism will be removed.
    *   An initial call to `Android.getStatus()` will be made to fetch the initial state.

2.  **`WebAppActivity.kt`**:
    *   A `lateinit var webView: WebView` property will be added to hold a reference to the `WebView` instance.
    *   A public method, `sendToJs(message: String)`, will be created to execute JavaScript in the `WebView` using `evaluateJavascript()`.

3.  **`WebView.kt`**:
    *   The `FullScreenWebView` composable will be modified to accept the `WebAppActivity` instance as a parameter.
    *   This instance will be used to initialize the `webView` property in `WebAppActivity` and to pass the activity reference to the `JavaScriptInjectedAndroid` constructor.

4.  **`JavaScriptInjectedAndroid.kt`**:
    *   The constructor will be updated to accept the `WebAppActivity` instance.
    *   The `getStatus()` method will be modified to call `webAppActivity.sendToJs()` with the status JSON, effectively pushing the update to the JavaScript side.

## Plan

- [x] Create `docs/FEATURE_PushJavascript.md`.
- [ ] Modify `main.js` to use a callback function instead of polling.
- [ ] Modify `WebAppActivity.kt` to hold a reference to the `WebView` and expose a method to send data to it.
- [ ] Modify `WebView.kt` to pass the `WebAppActivity` instance to the `JavaScriptInjectedAndroid` bridge.
- [ ] Modify `JavaScriptInjectedAndroid.kt` to call the new method in `WebAppActivity` to push updates to the JavaScript frontend.
- [ ] Build and test the new push-based update mechanism.
- [ ] Delete `docs/FEATURE_PushJavascript.md`.
