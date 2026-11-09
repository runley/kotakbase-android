// Stand-in for the desktop's ui/i18n.js, which anilist-sync.js and room.js import: that one needs Intl, which QuickJS
// lacks. In the Watch Together WebView the app's own translations answer (__t); elsewhere, English with the
// {placeholders} filled.
export const t = (text, vars = {}) => globalThis.__t ? globalThis.__t(text, vars) : text.replace(/\{(\w+)\}/g, (all, k) => vars[k] ?? all)
export const language = () => 'en'
