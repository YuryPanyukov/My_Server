package Code_SyS.nas_server

/**
 * HTML-шаблоны веб-интерфейса. Вынесены отдельно, чтобы правки внешнего вида
 * не усложняли [HttpRequestHandler]. Плейсхолдеры заполняются обработчиком:
 *
 *  * [PAGE] — `<!--FILES-->` и `<!--FILE_COUNT-->`;
 *  * [GALLERY] — `<!--TABS-->` и `<!--SECTIONS-->`;
 *  * [VIEWER] — `__TITLE__`, `__GALLERY__`, `__NAME__`, `__MEDIA__`, `__SRC__`, `__ID__`;
 *  * [OPEN] — `__ADDRESS__`, `__BROWSER__`, `__DEEP_LINK_ATTR__`, `__DEEP_LINK_JS__`.
 *
 * Тема оформляется переменными CSS: тёмная задана по умолчанию, светлая —
 * через `data-theme="light"`. Выбор хранится в `localStorage` и применяется
 * до отрисовки, поэтому страница не мигает при загрузке. Стили встроены:
 * сервер живёт в локальной сети и может быть открыт без интернета.
 */
internal object WebTemplates {

    /** Тема до первой отрисовки: без этого светлая тема «вспыхивала» бы тёмной. */
    private const val THEME_BOOT = """
<meta name="theme-color" content="#0a0c11" id="theme-color">
<script>
(function () {
  var saved = null;
  try { saved = localStorage.getItem('nas-theme'); } catch (e) {}
  var light = saved ? saved === 'light' : !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);
  document.documentElement.setAttribute('data-theme', light ? 'light' : 'dark');
})();
</script>"""

    /** Общие токены оформления и кнопка переключения темы. */
    private const val THEME_CSS = """
  :root {
    color-scheme: dark;
    --bg: #0a0c11;
    --bg-soft: #07090d;
    --panel: rgba(255, 255, 255, .035);
    --panel-hover: rgba(255, 255, 255, .06);
    --line: rgba(255, 255, 255, .08);
    --line-strong: rgba(255, 255, 255, .16);
    --text: #eef2f7;
    --strong: #c6cedb;
    --muted: #98a2b3;
    --faint: #6f7887;
    --accent: #7aa2ff;
    --accent-2: #b98cff;
    --on-accent: #081018;
    --green: #5ee2a0;
    --red: #ff8a8a;
    --field: rgba(255, 255, 255, .03);
    --bar: rgba(255, 255, 255, .08);
    --drop-bg: rgba(255, 255, 255, .018);
    --thumb-bg: #0d1117;
    --thumb-fg: #4e5968;
    --cap-bg: linear-gradient(transparent, rgba(5, 7, 10, .92));
    --cap-text: #e8edf5;
    --glass: rgba(10, 12, 17, .8);
    --glass-strong: rgba(15, 19, 26, .72);
    --glass-hover: rgba(35, 43, 58, .92);
    --badge-bg: rgba(6, 8, 12, .72);
    --badge-text: #ffffff;
    --card-bg: linear-gradient(180deg, rgba(255, 255, 255, .045), rgba(255, 255, 255, .018));
    --glow-1: rgba(122, 162, 255, .16);
    --glow-2: rgba(185, 140, 255, .13);
    --shadow: 0 24px 60px rgba(0, 0, 0, .32);
    --shadow-strong: 0 16px 34px rgba(0, 0, 0, .4);
    --accent-soft: rgba(122, 162, 255, .12);
    --accent-soft-2: rgba(185, 140, 255, .12);
    --accent-line: rgba(122, 162, 255, .2);
    --accent-border: rgba(122, 162, 255, .65);
    --accent-hover: rgba(122, 162, 255, .07);
    --accent-glow: rgba(122, 162, 255, .32);
    --err-soft: rgba(255, 138, 138, .07);
    --err-line: rgba(255, 138, 138, .3);
    --stage-bg: #000000;
    --stage-glow: rgba(40, 50, 70, .32);
  }
  :root[data-theme="light"] {
    color-scheme: light;
    --bg: #f5f7fb;
    --bg-soft: #eef1f7;
    --panel: rgba(16, 24, 40, .035);
    --panel-hover: rgba(16, 24, 40, .06);
    --line: rgba(16, 24, 40, .1);
    --line-strong: rgba(16, 24, 40, .2);
    --text: #101828;
    --strong: #344054;
    --muted: #667085;
    --faint: #98a2b3;
    --accent: #3b6ef6;
    --accent-2: #8a5cf6;
    --on-accent: #ffffff;
    --green: #067647;
    --red: #d92d20;
    --field: rgba(16, 24, 40, .03);
    --bar: rgba(16, 24, 40, .09);
    --drop-bg: rgba(16, 24, 40, .02);
    --thumb-bg: #e9edf5;
    --thumb-fg: #98a2b3;
    --cap-bg: linear-gradient(transparent, rgba(8, 12, 20, .82));
    --cap-text: #ffffff;
    --glass: rgba(255, 255, 255, .82);
    --glass-strong: rgba(255, 255, 255, .86);
    --glass-hover: rgba(255, 255, 255, .98);
    --badge-bg: rgba(255, 255, 255, .88);
    --badge-text: #101828;
    --card-bg: linear-gradient(180deg, #ffffff, #fafbff);
    --glow-1: rgba(59, 110, 246, .1);
    --glow-2: rgba(138, 92, 246, .09);
    --shadow: 0 18px 44px rgba(16, 24, 40, .1);
    --shadow-strong: 0 16px 34px rgba(16, 24, 40, .14);
    --accent-soft: rgba(59, 110, 246, .1);
    --accent-soft-2: rgba(138, 92, 246, .1);
    --accent-line: rgba(59, 110, 246, .22);
    --accent-border: rgba(59, 110, 246, .5);
    --accent-hover: rgba(59, 110, 246, .06);
    --accent-glow: rgba(59, 110, 246, .26);
    --err-soft: rgba(217, 45, 32, .06);
    --err-line: rgba(217, 45, 32, .24);
    --stage-bg: #eef1f7;
    --stage-glow: rgba(120, 140, 180, .22);
  }
  .theme-btn {
    flex: 0 0 auto;
    width: 34px;
    height: 34px;
    padding: 0;
    display: grid;
    place-items: center;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--muted);
    cursor: pointer;
    transition: background .18s, border-color .18s, color .18s;
  }
  .theme-btn:hover { background: var(--panel-hover); border-color: var(--line-strong); color: var(--text); }
  .theme-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .theme-btn svg { width: 16px; height: 16px; }
  .theme-btn .i-moon { display: none; }
  :root[data-theme="light"] .theme-btn .i-sun { display: none; }
  :root[data-theme="light"] .theme-btn .i-moon { display: block; }
  :root[data-theme="light"] .ftype[data-kind="image"] { color: #b45309; background: rgba(245, 158, 11, .12); border-color: rgba(245, 158, 11, .28); }
  :root[data-theme="light"] .ftype[data-kind="video"] { color: #6d28d9; background: rgba(139, 92, 246, .12); border-color: rgba(139, 92, 246, .26); }
  :root[data-theme="light"] .ftype[data-kind="audio"] { color: #0f766e; background: rgba(13, 148, 136, .12); border-color: rgba(13, 148, 136, .26); }
  :root[data-theme="light"] .ftype[data-kind="doc"] { color: #1d4ed8; background: rgba(59, 130, 246, .12); border-color: rgba(59, 130, 246, .26); }
  :root[data-theme="light"] .ftype[data-kind="archive"] { color: #b42318; background: rgba(244, 63, 94, .1); border-color: rgba(244, 63, 94, .24); }
  /* Атрибут hidden должен побеждать display: flex у панелей и очередей. */
  [hidden] { display: none !important; }
"""

    /** Кнопка темы: в тёмной теме показывает солнце, в светлой — луну. */
    private const val THEME_BUTTON = """
      <button class="theme-btn" id="theme-toggle" type="button" title="Светлая тема" aria-label="Светлая тема">
        <svg class="i-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.9 4.9 1.4 1.4"/><path d="m17.7 17.7 1.4 1.4"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m4.9 19.1 1.4-1.4"/><path d="m17.7 6.3 1.4-1.4"/></svg>
        <svg class="i-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>
      </button>"""

    /** Переключение и запоминание темы; системная тема — только пока выбор не сделан. */
    private const val THEME_SCRIPT = """
<script>
(function () {
  var root = document.documentElement;
  var button = document.getElementById('theme-toggle');
  var meta = document.getElementById('theme-color');

  function sync() {
    var light = root.getAttribute('data-theme') === 'light';
    if (button) {
      var label = light ? 'Тёмная тема' : 'Светлая тема';
      button.title = label;
      button.setAttribute('aria-label', label);
    }
    if (meta) meta.setAttribute('content', light ? '#f5f7fb' : '#0a0c11');
  }

  sync();

  if (button) {
    button.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('nas-theme', next); } catch (e) {}
      sync();
    });
  }

  if (window.matchMedia) {
    var query = window.matchMedia('(prefers-color-scheme: light)');
    var followSystem = function () {
      var saved = null;
      try { saved = localStorage.getItem('nas-theme'); } catch (e) {}
      if (saved) return;
      root.setAttribute('data-theme', query.matches ? 'light' : 'dark');
      sync();
    };
    if (query.addEventListener) query.addEventListener('change', followSystem);
  }
})();
</script>"""

    val PAGE = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${THEME_BOOT}
<title>LanDrop — файлы по Wi-Fi</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    padding: 40px 20px 56px;
    display: flex;
    justify-content: center;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
    background-color: var(--bg);
    background-image:
      radial-gradient(900px 480px at 12% -8%, var(--glow-1), transparent 62%),
      radial-gradient(760px 460px at 108% 2%, var(--glow-2), transparent 58%);
    background-attachment: fixed;
    -webkit-font-smoothing: antialiased;
  }
  main { width: min(760px, 100%); }
  .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .logo {
    flex: 0 0 auto;
    width: 46px;
    height: 46px;
    border-radius: 15px;
    display: grid;
    place-items: center;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 10px 26px var(--accent-glow);
  }
  .logo svg { width: 22px; height: 22px; }
  .brand-text { min-width: 0; }
  h1 { margin: 0; font-size: 18px; font-weight: 650; letter-spacing: -.01em; }
  .brand-text p { margin: 3px 0 0; font-size: 12px; color: var(--muted); }
  .top-actions { display: flex; align-items: center; gap: 8px; }
  .pill {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 7px 11px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    font-size: 12px;
    color: var(--muted);
    white-space: nowrap;
  }
  .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 4px var(--accent-soft); }
  .ghost-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    font-size: 12px;
    text-decoration: none;
    white-space: nowrap;
    cursor: pointer;
    transition: background .18s, border-color .18s;
  }
  .ghost-btn:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .btn-small { padding: 6px 10px; font-size: 11.5px; }
  .card {
    border: 1px solid var(--line);
    border-radius: 22px;
    background: var(--card-bg);
    box-shadow: var(--shadow);
    backdrop-filter: blur(14px);
  }
  .upload-card { padding: 14px; }
  #drop {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 8px;
    padding: 44px 24px;
    border: 1.5px dashed var(--line-strong);
    border-radius: 16px;
    background: var(--drop-bg);
    cursor: pointer;
    outline: none;
    transition: border-color .2s, background .2s, transform .2s;
  }
  #drop:hover, #drop.hover { border-color: var(--accent-border); background: var(--accent-hover); transform: translateY(-1px); }
  #drop:focus-visible { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-line); }
  .drop-icon {
    width: 62px;
    height: 62px;
    margin-bottom: 6px;
    border-radius: 20px;
    display: grid;
    place-items: center;
    color: var(--accent);
    border: 1px solid var(--accent-line);
    background: linear-gradient(135deg, var(--accent-soft), var(--accent-soft-2));
  }
  .drop-icon svg { width: 28px; height: 28px; }
  #drop strong { font-size: 16px; font-weight: 600; }
  .drop-hint { font-size: 13px; color: var(--muted); }
  .fake-btn {
    margin-top: 12px;
    padding: 9px 16px;
    border-radius: 10px;
    font-size: 13px;
    font-weight: 600;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 6px 18px var(--accent-glow);
    transition: transform .18s;
  }
  #drop:hover .fake-btn { transform: translateY(-1px); }
  .queue-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin: 14px 2px 0; }
  .queue-sum { font-size: 11.5px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .upload-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .upload-list:not(:empty) { margin-top: 8px; }
  li.upload-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 11px 12px;
    border: 1px solid var(--line);
    border-radius: 13px;
    background: var(--panel);
  }
  li.upload-item.err { border-color: var(--err-line); background: var(--err-soft); }
  li.upload-item.cancelled { opacity: .62; }
  li.upload-item.done .fill { background: var(--green); }
  .u-thumb { position: relative; flex: 0 0 auto; width: 42px; height: 42px; }
  .u-thumb .ftype { position: absolute; inset: 0; width: 100%; height: 100%; }
  .u-thumb-img {
    position: absolute;
    inset: 0;
    z-index: 1;
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--thumb-bg);
  }
  .file-main { display: flex; flex-direction: column; gap: 3px; flex: 1; min-width: 0; }
  .uname { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { font-size: 11px; color: var(--muted); font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .bar { display: block; height: 4px; border-radius: 999px; background: var(--bar); overflow: hidden; }
  .fill {
    display: block;
    width: 0;
    height: 100%;
    border-radius: inherit;
    background: linear-gradient(90deg, var(--accent), var(--accent-2));
    transition: width .2s ease;
  }
  .err .fill { background: var(--red); }
  .cancelled .fill { background: var(--line-strong); }
  .state { min-width: 62px; text-align: right; font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .ok { color: var(--green); }
  .bad { color: var(--red); }
  .cancel {
    flex: 0 0 auto;
    width: 28px;
    height: 28px;
    padding: 0;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--faint);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    transition: color .18s, background .18s, border-color .18s;
  }
  .cancel:hover { color: var(--red); background: var(--err-soft); border-color: var(--err-line); }
  .ftype {
    flex: 0 0 auto;
    width: 42px;
    height: 42px;
    border-radius: 12px;
    display: grid;
    place-items: center;
    font-size: 9px;
    font-weight: 700;
    letter-spacing: .06em;
    color: var(--accent);
    background: var(--accent-soft);
    border: 1px solid var(--accent-line);
  }
  .ftype[data-kind="image"] { color: #ffc078; background: rgba(255, 192, 120, .1); border-color: rgba(255, 192, 120, .2); }
  .ftype[data-kind="video"] { color: #c4a2ff; background: rgba(196, 162, 255, .1); border-color: rgba(196, 162, 255, .2); }
  .ftype[data-kind="audio"] { color: #6ee7d8; background: rgba(110, 231, 216, .1); border-color: rgba(110, 231, 216, .2); }
  .ftype[data-kind="doc"] { color: #8ab4f8; background: rgba(138, 180, 248, .1); border-color: rgba(138, 180, 248, .2); }
  .ftype[data-kind="archive"] { color: #ffb4a2; background: rgba(255, 180, 162, .1); border-color: rgba(255, 180, 162, .2); }
  .section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 30px 0 12px; }
  h2 { margin: 0; font-size: 15px; font-weight: 600; }
  .count {
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    font-size: 11px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  /* Отступ от панели действий такой же, как от строки поиска до списка файлов. */
  .search { position: relative; margin: 12px 0 0; }
  .search svg {
    position: absolute;
    left: 14px;
    top: 50%;
    width: 16px;
    height: 16px;
    transform: translateY(-50%);
    color: var(--muted);
    pointer-events: none;
  }
  #q {
    width: 100%;
    padding: 12px 14px 12px 42px;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--field);
    color: var(--text);
    font-size: 13px;
    outline: none;
    transition: border-color .18s, background .18s;
  }
  #q::placeholder { color: var(--faint); }
  #q:focus { border-color: var(--accent-border); background: var(--panel-hover); }
  ul.files { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  li.file-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px 10px 10px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
    transition: background .18s, border-color .18s;
  }
  li.file-item:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .file-thumb { position: relative; flex: 0 0 auto; width: 42px; height: 42px; }
  .file-thumb .ftype { position: absolute; inset: 0; width: 100%; height: 100%; }
  .fthumb {
    position: absolute;
    inset: 0;
    z-index: 1;
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--thumb-bg);
  }
  .file-thumb .play {
    position: absolute;
    right: 3px;
    bottom: 3px;
    z-index: 2;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    background: var(--badge-bg);
    color: var(--badge-text);
    font-size: 8px;
  }
  a.file { color: var(--text); text-decoration: none; font-size: 13.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  a.file:hover { color: var(--accent); }
  .meta { color: var(--muted); font-size: 11.5px; white-space: nowrap; }
  .del {
    flex: 0 0 auto;
    width: 32px;
    height: 32px;
    border-radius: 9px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--faint);
    font-size: 18px;
    line-height: 1;
    cursor: pointer;
    transition: color .18s, background .18s, border-color .18s;
  }
  .del:hover { color: var(--red); background: var(--err-soft); border-color: var(--err-line); }
  .ren {
    flex: 0 0 auto;
    width: 30px;
    height: 30px;
    border-radius: 9px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--faint);
    font-size: 14px;
    line-height: 1;
    cursor: pointer;
    transition: color .18s, background .18s, border-color .18s;
  }
  .ren:hover { color: var(--accent); background: var(--accent-soft); border-color: var(--accent-line); }
  .file-actions { display: flex; gap: 2px; flex: 0 0 auto; }
  .crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin: 26px 0 0; font-size: 12px; }
  .crumb { color: var(--muted); text-decoration: none; padding: 3px 6px; border-radius: 7px; }
  .crumb:hover { color: var(--text); background: var(--panel-hover); }
  .crumb.current { color: var(--text); font-weight: 600; }
  .crumb-sep { color: var(--faint); }
  .toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 10px 0 0; }
  .view-switch { display: flex; margin-left: auto; border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
  .view-switch button { border: 0; background: transparent; color: var(--muted); font: inherit; font-size: 11.5px; padding: 6px 10px; cursor: pointer; }
  .view-switch button:hover { background: var(--panel-hover); color: var(--text); }
  .view-switch button.on { background: var(--accent-soft); color: var(--accent); }
  .sel-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin: 10px 0 0;
    padding: 9px 11px;
    border: 1px solid var(--accent-line);
    border-radius: 12px;
    background: var(--accent-soft);
    font-size: 12px;
  }
  .sel-bar span { font-weight: 600; margin-right: auto; }
  .sel-bar .danger { color: var(--red); border-color: var(--err-line); }
  .sel-bar .danger:hover { background: var(--err-soft); border-color: var(--err-line); }
  li.file-item { position: relative; }
  li.file-item.selected { border-color: var(--accent-border); background: var(--accent-soft); }
  .pick {
    position: absolute;
    top: 7px;
    left: 7px;
    z-index: 3;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 7px;
    border: 1px solid var(--line-strong);
    background: var(--glass-strong);
    cursor: pointer;
  }
  .pick input { margin: 0; width: 14px; height: 14px; accent-color: var(--accent); cursor: pointer; }
  li.file-item.folder .file-thumb { border: 1px solid var(--accent-line); background: var(--accent-soft); }
  .ftype[data-kind="folder"] { width: 100%; height: 100%; color: var(--accent); background: transparent; border-color: transparent; font-size: 10px; }
  ul.files.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: 10px; }
  ul.files.grid li.file-item { flex-direction: column; align-items: stretch; padding: 9px; }
  ul.files.grid .pick { top: 14px; left: 14px; }
  ul.files.grid .file-thumb { width: 100%; height: auto; aspect-ratio: 1 / 1; }
  ul.files.grid .file-main { padding: 7px 2px 0; }
  ul.files.grid a.file { font-size: 12.5px; }
  ul.files.grid .meta { font-size: 10.5px; }
  ul.files.grid .file-actions {
    position: absolute;
    top: 12px;
    right: 12px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: var(--glass-strong);
  }
  ul.files.grid .file-actions .ren, ul.files.grid .file-actions .del { width: 26px; height: 26px; font-size: 13px; }
  .dlg {
    width: min(380px, calc(100vw - 32px));
    padding: 0;
    border: 1px solid var(--line-strong);
    border-radius: 16px;
    background: var(--bg);
    color: var(--text);
    box-shadow: var(--shadow-strong);
  }
  .dlg::backdrop { background: rgba(0, 0, 0, .55); }
  .dlg form { display: flex; flex-direction: column; gap: 10px; padding: 18px; }
  .dlg h3 { margin: 0; font-size: 15px; }
  .dlg p { margin: 0; font-size: 12px; line-height: 1.45; color: var(--muted); }
  .dlg input, .dlg select {
    width: 100%;
    padding: 10px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--field);
    color: var(--text);
    font: inherit;
    font-size: 13px;
    outline: none;
  }
  .dlg input:focus, .dlg select:focus { border-color: var(--accent-border); }
  .dlg-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
  .ghost-btn.primary {
    color: var(--on-accent);
    border-color: transparent;
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
  }
  li.empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 26px 16px;
    border: 1px dashed var(--line);
    border-radius: 14px;
    color: var(--muted);
    font-size: 13px;
    text-align: center;
  }
  li.empty small { color: var(--faint); font-size: 11.5px; }
  .undo-bar {
    position: fixed;
    left: 50%;
    bottom: 22px;
    transform: translateX(-50%);
    z-index: 20;
    display: flex;
    align-items: center;
    gap: 10px;
    max-width: calc(100vw - 32px);
    padding: 9px 10px 9px 16px;
    border: 1px solid var(--line-strong);
    border-radius: 12px;
    background: var(--glass-strong);
    backdrop-filter: blur(10px);
    box-shadow: var(--shadow-strong);
    font-size: 12.5px;
  }
  .undo-bar span { color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .undo-bar button {
    flex: 0 0 auto;
    padding: 4px 8px;
    border: 0;
    border-radius: 7px;
    background: transparent;
    color: var(--accent);
    font: inherit;
    font-size: 12.5px;
    font-weight: 600;
    cursor: pointer;
  }
  .undo-bar button:hover { background: var(--accent-soft); }
  .undo-bar #undo-close { color: var(--muted); font-weight: 400; }
  .empty-icon {
    width: 38px;
    height: 38px;
    margin-bottom: 2px;
    border-radius: 12px;
    display: grid;
    place-items: center;
    background: var(--panel);
    border: 1px solid var(--line);
    font-size: 16px;
    color: var(--muted);
  }
  .note {
    margin: 18px 0 0;
    padding: 12px 14px;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--drop-bg);
    color: var(--muted);
    font-size: 11.5px;
    line-height: 1.5;
  }
  .note b { color: var(--strong); font-weight: 600; }
  @media (max-width: 600px) {
    body { padding: 20px 14px 40px; }
    .brand-text p { display: none; }
    .pill { display: none; }
    .logo { width: 42px; height: 42px; border-radius: 13px; }
    h1 { font-size: 16px; }
    #drop { padding: 34px 16px; }
    .ftype, .u-thumb, .file-thumb { width: 38px; height: 38px; border-radius: 11px; }
    .meta { font-size: 11px; }
    .state { min-width: 52px; }
    ul.files.grid { grid-template-columns: repeat(auto-fill, minmax(128px, 1fr)); }
    .toolbar .ghost-btn { font-size: 11.5px; }
    /* На телефоне «Выбрано N» — своей строкой, действия — одной компактной
       строкой: иначе «Отмена» одна уезжает на третью строку. */
    .sel-bar { gap: 6px; padding: 8px 9px; }
    .sel-bar span { flex-basis: 100%; margin-right: 0; }
    .sel-bar .btn-small { padding: 6px 8px; }
  }
  /* Узкие экраны: название рядом с логотипом уже налезает на кнопки. */
  @media (max-width: 350px) {
    .brand-text { display: none; }
  }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<main>
  <header class="topbar">
    <div class="brand">
      <div class="logo" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>
      </div>
      <div class="brand-text">
        <h1>LanDrop</h1>
        <p>Передача файлов по локальной сети</p>
      </div>
    </div>
    <div class="top-actions">
      <span class="pill"><span class="dot"></span>Онлайн</span>
      ${THEME_BUTTON}
      <a class="ghost-btn" href="/library">Видеотека</a>
      <a class="ghost-btn" href="/gallery">Галерея</a>
    </div>
  </header>

  <section class="card upload-card" aria-label="Загрузка файлов">
    <div id="drop" role="button" tabindex="0" aria-label="Выбрать файлы или перетащить их сюда">
      <div class="drop-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5-5 5 5"/><path d="M12 5v11"/></svg>
      </div>
      <strong>Перетащите файлы сюда</strong>
      <span class="drop-hint">или нажмите, чтобы выбрать на устройстве</span>
      <span class="fake-btn">Выбрать файлы</span>
    </div>
    <input id="f" type="file" multiple hidden>
    <div id="queue" hidden>
      <div class="queue-head">
        <span id="queue-sum" class="queue-sum" aria-live="polite"></span>
        <button id="cancel-all" class="ghost-btn btn-small" type="button" hidden>Отменить всё</button>
      </div>
      <ul id="list" class="upload-list"></ul>
    </div>
  </section>

  <nav class="crumbs" aria-label="Текущая папка"><!--BREADCRUMBS--></nav>
  <input id="current-path" type="hidden" value="<!--CURRENT_PATH-->">

  <div class="section-head">
    <h2>Файлы и папки</h2>
    <span class="count"><!--FILE_COUNT--></span>
  </div>
  <div class="toolbar">
    <button class="ghost-btn btn-small" id="new-folder" type="button">＋ Папка</button>
    <a class="ghost-btn btn-small" id="zip-link" href="<!--ZIP_LINK-->">Скачать ZIP</a>
    <!--TRASH_LINK-->
    <div class="view-switch" role="group" aria-label="Вид списка">
      <button id="view-grid" type="button" title="Показать сеткой">Сетка</button>
      <button id="view-list" type="button" title="Показать списком">Список</button>
    </div>
  </div>
  <div class="sel-bar" id="sel-bar" hidden>
    <span id="sel-count">Выбрано 0</span>
    <button class="ghost-btn btn-small" id="sel-all" type="button">Все</button>
    <button class="ghost-btn btn-small" id="sel-move" type="button">Переместить</button>
    <button class="ghost-btn btn-small danger" id="sel-delete" type="button">Удалить</button>
    <button class="ghost-btn btn-small" id="sel-cancel" type="button">Отмена</button>
  </div>
  <div class="search">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
    <input id="q" type="search" placeholder="Поиск по имени файла или папки" autocomplete="off" aria-label="Поиск по имени файла или папки">
  </div>
  <ul class="files" id="files"><!--FILES--></ul>

  <dialog class="dlg" id="mkdir-dialog">
    <form id="mkdir-form">
      <h3>Новая папка</h3>
      <p>Она появится внутри текущей папки.</p>
      <input id="mkdir-name" name="name" type="text" placeholder="Например, Отпуск" maxlength="120" required autocomplete="off">
      <div class="dlg-actions">
        <button class="ghost-btn" id="mkdir-cancel" type="button">Отмена</button>
        <button class="ghost-btn primary" type="submit">Создать</button>
      </div>
    </form>
  </dialog>

  <dialog class="dlg" id="move-dialog">
    <form id="move-form">
      <h3>Переместить</h3>
      <p>Выбранные файлы и папки переедут в выбранную папку.</p>
      <select id="move-target" aria-label="Куда переместить"></select>
      <div class="dlg-actions">
        <button class="ghost-btn" id="move-cancel" type="button">Отмена</button>
        <button class="ghost-btn primary" type="submit">Переместить</button>
      </div>
    </form>
  </dialog>

  <p class="note"><b>Нет звука у видео?</b> Браузер не поддерживает его аудиокодек (например, DTS/AC3 в MKV) — скачайте файл и откройте в VLC.</p>
</main>
<div class="undo-bar" id="undo-bar" role="status" hidden>
  <span id="undo-text">Удалено</span>
  <button id="undo-btn" type="button">Отменить</button>
  <button id="undo-close" type="button" aria-label="Закрыть">×</button>
</div>
${THEME_SCRIPT}
<script>
(function () {
  var drop = document.getElementById('drop');
  var input = document.getElementById('f');
  var list = document.getElementById('list');
  var queue = document.getElementById('queue');
  var summary = document.getElementById('queue-sum');
  var cancelAll = document.getElementById('cancel-all');
  var currentInput = document.getElementById('current-path');
  var current = currentInput ? currentInput.value : '';

  // Подсказка сразу показывает, в какую папку попадут файлы.
  if (current) {
    var dropHint = document.querySelector('.drop-hint');
    if (dropHint) dropHint.textContent = 'Файлы попадут в папку ' + current;
  }

  // Три одновременные передачи: мелкие файлы перестают ждать друг друга,
  // а Wi-Fi не захлёбывается очередью запросов.
  var MAX_PARALLEL = 3;

  // Расширения приходят из той же таблицы, что и значки в приложении.
  var IMAGE = ${FileKind.IMAGE.jsArray};
  var VIDEO = ${FileKind.VIDEO.jsArray};
  var AUDIO = ${FileKind.AUDIO.jsArray};
  var DOC = ${FileKind.DOC.jsArray};
  var ARCHIVE = ${FileKind.ARCHIVE.jsArray};

  var items = [];
  var pending = [];
  var running = 0;
  var finished = 0;
  var succeeded = 0;
  var failed = 0;
  var cancelled = 0;
  var summaryTimer = 0;
  var reloadTimer = 0;

  function now() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }

  function typeOf(name) {
    var dot = name.lastIndexOf('.');
    var ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
    var kind = 'other';
    if (IMAGE.indexOf(ext) >= 0) kind = 'image';
    else if (VIDEO.indexOf(ext) >= 0) kind = 'video';
    else if (AUDIO.indexOf(ext) >= 0) kind = 'audio';
    else if (DOC.indexOf(ext) >= 0) kind = 'doc';
    else if (ARCHIVE.indexOf(ext) >= 0) kind = 'archive';
    return { label: ext ? ext.slice(0, 4).toUpperCase() : 'FILE', kind: kind };
  }

  function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' Б';
    var units = ['КБ', 'МБ', 'ГБ', 'ТБ'];
    var value = bytes / 1024;
    var unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    var text = value >= 100 ? String(Math.round(value)) : value.toFixed(1).replace('.', ',');
    return text + ' ' + units[unit];
  }

  function formatSpeed(bytesPerSecond) {
    if (!isFinite(bytesPerSecond) || bytesPerSecond <= 0) return '';
    return formatSize(Math.round(bytesPerSecond)) + '/с';
  }

  function formatEta(seconds) {
    if (!isFinite(seconds) || seconds <= 0) return '';
    if (seconds < 1) return 'меньше секунды';
    if (seconds < 60) return '≈ ' + Math.ceil(seconds) + ' с';
    return '≈ ' + Math.ceil(seconds / 60) + ' мин';
  }

  function releaseThumb(item) {
    if (!item.objectUrl) return;
    try { URL.revokeObjectURL(item.objectUrl); } catch (e) {}
    item.objectUrl = null;
  }

  function buildRow(item) {
    var type = typeOf(item.file.name);
    var li = document.createElement('li');
    li.className = 'upload-item';

    var thumb = document.createElement('span');
    thumb.className = 'u-thumb';
    var icon = document.createElement('span');
    icon.className = 'ftype';
    icon.setAttribute('data-kind', type.kind);
    icon.textContent = type.label;
    thumb.appendChild(icon);
    if (type.kind === 'image' && window.URL && window.URL.createObjectURL) {
      var objectUrl = URL.createObjectURL(item.file);
      item.objectUrl = objectUrl;
      var preview = document.createElement('img');
      preview.className = 'u-thumb-img';
      preview.alt = '';
      preview.src = objectUrl;
      preview.addEventListener('error', function () {
        if (preview.parentNode) preview.parentNode.removeChild(preview);
      });
      thumb.appendChild(preview);
    }

    var main = document.createElement('span');
    main.className = 'file-main';
    var name = document.createElement('span');
    name.className = 'uname';
    name.textContent = item.file.name;
    var bar = document.createElement('span');
    bar.className = 'bar';
    var fill = document.createElement('span');
    fill.className = 'fill';
    bar.appendChild(fill);
    var sub = document.createElement('span');
    sub.className = 'sub';
    sub.textContent = formatSize(item.size);
    main.appendChild(name);
    main.appendChild(bar);
    main.appendChild(sub);

    var state = document.createElement('span');
    state.className = 'state';
    state.textContent = '0%';

    var cancel = document.createElement('button');
    cancel.className = 'cancel';
    cancel.type = 'button';
    cancel.title = 'Отменить';
    cancel.setAttribute('aria-label', 'Отменить загрузку ' + item.file.name);
    cancel.textContent = '×';
    cancel.addEventListener('click', function () { cancelItem(item); });

    li.appendChild(thumb);
    li.appendChild(main);
    li.appendChild(state);
    li.appendChild(cancel);
    list.appendChild(li);

    item.el = { li: li, state: state, fill: fill, sub: sub, cancel: cancel };
  }

  function start(item) {
    item.state = 'running';
    item.running = true;
    running++;

    var xhr = new XMLHttpRequest();
    item.xhr = xhr;
    var lastTime = now();
    var lastLoaded = 0;
    var speed = 0;

    xhr.open('POST', '/upload?name=' + encodeURIComponent(item.file.name) +
      '&path=' + encodeURIComponent(current));
    xhr.upload.onprogress = function (e) {
      if (!e.lengthComputable) return;
      // 100% показываем только после ответа сервера: файл может не сохраниться.
      var percent = Math.min(99, Math.round(e.loaded / e.total * 100));
      item.el.state.textContent = percent + '%';
      item.el.fill.style.width = percent + '%';
      var stamp = now();
      var elapsed = (stamp - lastTime) / 1000;
      if (elapsed >= 0.3) {
        var instant = (e.loaded - lastLoaded) / elapsed;
        speed = speed > 0 ? speed * 0.6 + instant * 0.4 : instant;
        lastLoaded = e.loaded;
        lastTime = stamp;
      }
      item.loaded = e.loaded;
      item.speed = speed;
      var parts = [formatSize(e.total)];
      if (speed > 0) {
        parts.push(formatSpeed(speed));
        parts.push(formatEta((e.total - e.loaded) / speed));
      }
      item.el.sub.textContent = parts.filter(Boolean).join(' · ');
      scheduleSummary();
    };
    xhr.onload = function () {
      var ok = false;
      try { ok = JSON.parse(xhr.responseText).ok === true; } catch (err) { ok = false; }
      if (ok) doneItem(item); else failItem(item, 'ошибка');
    };
    xhr.onerror = function () { failItem(item, 'сеть'); };
    xhr.onabort = function () { markCancelled(item); };
    xhr.ontimeout = function () { failItem(item, 'таймаут'); };
    xhr.send(item.file);
  }

  function pump() {
    while (running < MAX_PARALLEL && pending.length > 0) {
      start(pending.shift());
    }
  }

  function doneItem(item) {
    item.state = 'done';
    item.speed = 0;
    item.loaded = item.size;
    item.el.li.className = 'upload-item done';
    item.el.state.textContent = 'готово';
    item.el.state.className = 'state ok';
    item.el.fill.style.width = '100%';
    item.el.sub.textContent = formatSize(item.size);
    item.el.cancel.style.visibility = 'hidden';
    succeeded++;
    settle(item);
  }

  function failItem(item, label) {
    if (item.state === 'done' || item.state === 'failed' || item.state === 'cancelled') return;
    item.state = 'failed';
    item.speed = 0;
    item.el.li.className = 'upload-item err';
    item.el.state.textContent = label;
    item.el.state.className = 'state bad';
    item.el.cancel.style.visibility = 'hidden';
    releaseThumb(item);
    failed++;
    settle(item);
  }

  function markCancelled(item) {
    if (item.state === 'cancelled') return;
    item.state = 'cancelled';
    item.speed = 0;
    item.xhr = null;
    item.el.li.className = 'upload-item cancelled';
    item.el.state.textContent = 'отменено';
    item.el.state.className = 'state';
    item.el.fill.className = 'fill';
    item.el.cancel.style.visibility = 'hidden';
    releaseThumb(item);
    cancelled++;
    settle(item);
  }

  function settle(item) {
    if (item.running) {
      item.running = false;
      running = Math.max(0, running - 1);
    }
    finished++;
    pump();
    updateSummary();
    if (finished >= items.length) complete();
  }

  function cancelItem(item) {
    if (item.state === 'done' || item.state === 'failed' || item.state === 'cancelled') return;
    if (item.xhr) {
      try { item.xhr.abort(); } catch (e) { markCancelled(item); }
      return;
    }
    var index = pending.indexOf(item);
    if (index >= 0) pending.splice(index, 1);
    markCancelled(item);
  }

  // setTimeout, а не requestAnimationFrame: сводка должна обновляться и в
  // фоновой вкладке, где анимационные кадры браузер не рисует.
  function scheduleSummary() {
    if (summaryTimer) return;
    summaryTimer = setTimeout(function () {
      summaryTimer = 0;
      updateSummary();
    }, 100);
  }

  function updateSummary() {
    if (!items.length) {
      queue.hidden = true;
      return;
    }
    var total = 0;
    var loaded = 0;
    var speed = 0;
    var doneCount = 0;
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      total += item.size;
      if (item.state === 'done') {
        loaded += item.size;
        doneCount++;
      } else if (item.state === 'running' || item.state === 'pending') {
        loaded += Math.min(item.loaded || 0, item.size);
        speed += item.speed || 0;
      }
    }
    var percent = total > 0 ? Math.round(loaded / total * 100) : 0;
    var parts = [doneCount + ' из ' + items.length, percent + '%'];
    if (speed > 0) {
      parts.push(formatSpeed(speed));
      if (total > loaded) parts.push(formatEta((total - loaded) / speed));
    }
    summary.textContent = parts.join(' · ');
  }

  function complete() {
    cancelAll.hidden = true;
    updateSummary();
    if (succeeded > 0) {
      reloadTimer = setTimeout(function () { location.reload(); }, 900);
    } else {
      summary.textContent = cancelled > 0 ? 'Передача отменена' : 'Загрузить не удалось';
    }
  }

  function startBatch(fileList) {
    if (!fileList || !fileList.length) return;
    if (reloadTimer) {
      clearTimeout(reloadTimer);
      reloadTimer = 0;
    }
    queue.hidden = false;
    cancelAll.hidden = false;
    for (var i = 0; i < fileList.length; i++) {
      var file = fileList[i];
      var item = {
        file: file,
        size: file.size || 0,
        loaded: 0,
        speed: 0,
        state: 'pending',
        xhr: null,
        running: false,
        objectUrl: null,
        el: null
      };
      items.push(item);
      pending.push(item);
      buildRow(item);
    }
    updateSummary();
    pump();
  }

  if (cancelAll) {
    cancelAll.addEventListener('click', function () {
      var snapshot = items.slice();
      for (var i = 0; i < snapshot.length; i++) cancelItem(snapshot[i]);
      cancelAll.hidden = true;
    });
  }

  drop.addEventListener('click', function () { input.click(); });
  drop.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); }
  });
  input.addEventListener('change', function () {
    if (input.files && input.files.length) {
      startBatch(Array.prototype.slice.call(input.files));
    }
    input.value = '';
  });

  var dragDepth = 0;
  drop.addEventListener('dragenter', function (e) {
    e.preventDefault();
    dragDepth++;
    drop.classList.add('hover');
  });
  drop.addEventListener('dragover', function (e) {
    e.preventDefault();
  });
  drop.addEventListener('dragleave', function () {
    dragDepth--;
    if (dragDepth <= 0) { dragDepth = 0; drop.classList.remove('hover'); }
  });
  drop.addEventListener('drop', function (e) {
    e.preventDefault();
    dragDepth = 0;
    drop.classList.remove('hover');
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
      startBatch(Array.prototype.slice.call(e.dataTransfer.files));
    }
  });
  document.addEventListener('dragover', function (e) { e.preventDefault(); });
  document.addEventListener('drop', function (e) { e.preventDefault(); });

  // Миниатюры полученных файлов: если превью не построилось, показываем значок типа.
  var thumbs = document.querySelectorAll('img.fthumb');
  for (var t = 0; t < thumbs.length; t++) {
    thumbs[t].addEventListener('error', function () {
      if (this.parentNode) this.parentNode.removeChild(this);
    });
  }

  var filesList = document.getElementById('files');
  var selBar = document.getElementById('sel-bar');
  var selCount = document.getElementById('sel-count');
  var movingSelection = [];

  function selectedPaths() {
    if (!filesList) return [];
    var boxes = filesList.querySelectorAll('input.sel');
    var paths = [];
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].checked) paths.push(boxes[i].getAttribute('data-path') || '');
    }
    return paths.filter(function (path) { return path !== ''; });
  }

  function updateSelection() {
    if (!filesList || !selBar) return;
    var paths = selectedPaths();
    selBar.hidden = paths.length === 0;
    if (selCount) selCount.textContent = 'Выбрано ' + paths.length;
    var rows = filesList.querySelectorAll('li.file-item');
    for (var i = 0; i < rows.length; i++) {
      var box = rows[i].querySelector('input.sel');
      rows[i].classList.toggle('selected', !!(box && box.checked));
    }
  }

  function postDelete(paths) {
    return fetch('/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: paths.join('\n')
    })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .then(function (data) { return data || { ok: false }; })
      .catch(function () { return { ok: false }; });
  }

  // Корзина: удалённое можно вернуть кнопкой внизу в течение 12 секунд
  // (и позже — со страницы /trash).
  var undoBar = document.getElementById('undo-bar');
  var undoText = document.getElementById('undo-text');
  var undoBtn = document.getElementById('undo-btn');
  var undoClose = document.getElementById('undo-close');
  var undoIds = [];
  var undoTimer = 0;

  function hideUndo() {
    if (!undoBar) return;
    undoIds = [];
    undoBar.hidden = true;
    if (undoTimer) { clearTimeout(undoTimer); undoTimer = 0; }
  }

  function bumpTrashLink(count) {
    var link = document.getElementById('trash-link');
    if (!link || !count) return;
    var match = /\((\d+)\)/.exec(link.textContent);
    var total = (match ? parseInt(match[1], 10) : 0) + count;
    link.textContent = total > 0 ? 'Корзина (' + total + ')' : 'Корзина';
  }

  function showUndo(ids, count) {
    if (!undoBar || !ids || !ids.length) return;
    undoIds = ids;
    if (undoText) {
      undoText.textContent = count > 1 ? 'Удалено: ' + count : 'Удалено';
    }
    undoBar.hidden = false;
    bumpTrashLink(ids.length);
    if (undoTimer) clearTimeout(undoTimer);
    undoTimer = setTimeout(hideUndo, 12000);
  }

  function restoreUndo() {
    var ids = undoIds.slice();
    hideUndo();
    if (!ids.length) return;
    fetch('/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: ids.join('\n')
    })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .then(function (data) {
        if (data && data.ok) location.reload();
        else alert('Не удалось восстановить: элемент уже удалён окончательно');
      })
      .catch(function () { alert('Ошибка сети'); });
  }

  if (undoBtn) undoBtn.addEventListener('click', restoreUndo);
  if (undoClose) undoClose.addEventListener('click', hideUndo);

  function removeFileRow(button) {
    var item = button.closest('li.file-item');
    if (item) item.parentNode.removeChild(item);
    updateSelection();
  }

  function removeSelectedRows() {
    if (!filesList) return;
    var boxes = filesList.querySelectorAll('input.sel');
    for (var i = 0; i < boxes.length; i++) {
      if (!boxes[i].checked) continue;
      var row = boxes[i].closest('li.file-item');
      if (row) row.parentNode.removeChild(row);
    }
    updateSelection();
  }

  function applyView(view) {
    if (!filesList) return;
    filesList.classList.toggle('grid', view === 'grid');
    var grid = document.getElementById('view-grid');
    var list = document.getElementById('view-list');
    if (grid) grid.classList.toggle('on', view === 'grid');
    if (list) list.classList.toggle('on', view !== 'grid');
  }

  var savedView = 'grid';
  try { savedView = localStorage.getItem('landrop-view') || 'grid'; } catch (e) {}
  applyView(savedView);

  function setView(view) {
    applyView(view);
    try { localStorage.setItem('landrop-view', view); } catch (e) {}
  }

  var gridBtn = document.getElementById('view-grid');
  var listBtn = document.getElementById('view-list');
  if (gridBtn) gridBtn.addEventListener('click', function () { setView('grid'); });
  if (listBtn) listBtn.addEventListener('click', function () { setView('list'); });

  if (filesList) {
    filesList.addEventListener('change', function (e) {
      var target = e.target;
      if (target && target.className === 'sel') updateSelection();
    });

    filesList.addEventListener('click', function (e) {
      var button = e.target;
      if (!button) return;

      if (button.className === 'ren') {
        var path = button.getAttribute('data-path') || '';
        var oldName = button.getAttribute('data-name') || '';
        if (!path) return;
        var nextName = prompt('Новое имя', oldName);
        if (!nextName || nextName === oldName) return;
        fetch('/rename?path=' + encodeURIComponent(path) + '&name=' + encodeURIComponent(nextName), { method: 'POST' })
          .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
          .then(function (data) {
            if (data && data.ok) location.reload();
            else alert('Не удалось переименовать: имя занято или недопустимо');
          })
          .catch(function () { alert('Ошибка сети'); });
        return;
      }

      if (button.className !== 'del') return;
      var isDir = button.getAttribute('data-dir') === '1';
      var deletePath = button.getAttribute('data-path') || button.getAttribute('data-file') || '';
      if (!deletePath) return;

      if (isDir) {
        postDelete([deletePath]).then(function (data) {
          if (data.ok) {
            removeFileRow(button);
            showUndo(data.trash, data.deleted);
          } else {
            alert('Не удалось удалить папку');
          }
        });
        return;
      }

      var href = button.getAttribute('data-href');
      if (!href) return;
      fetch(href, { method: 'DELETE' })
        .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
        .then(function (data) {
          if (data && data.ok) {
            removeFileRow(button);
            showUndo(data.trash ? [data.trash] : [], 1);
          } else {
            alert('Не удалось удалить файл');
          }
        })
        .catch(function () { alert('Ошибка сети'); });
    });
  }

  var selAll = document.getElementById('sel-all');
  if (selAll) {
    selAll.addEventListener('click', function () {
      var boxes = filesList ? filesList.querySelectorAll('input.sel') : [];
      var allChecked = true;
      for (var i = 0; i < boxes.length; i++) if (!boxes[i].checked) allChecked = false;
      for (var j = 0; j < boxes.length; j++) boxes[j].checked = !allChecked;
      updateSelection();
    });
  }

  var selCancel = document.getElementById('sel-cancel');
  if (selCancel) {
    selCancel.addEventListener('click', function () {
      var boxes = filesList ? filesList.querySelectorAll('input.sel') : [];
      for (var i = 0; i < boxes.length; i++) boxes[i].checked = false;
      updateSelection();
    });
  }

  var selDelete = document.getElementById('sel-delete');
  if (selDelete) {
    selDelete.addEventListener('click', function () {
      var paths = selectedPaths();
      if (!paths.length) return;
      postDelete(paths).then(function (data) {
        if (!data.deleted) {
          alert('Не удалось удалить выбранное');
          return;
        }
        if (data.failed) {
          // Часть файлов осталась: показываем реальное состояние списка.
          location.reload();
          return;
        }
        removeSelectedRows();
        showUndo(data.trash, data.deleted);
      });
    });
  }

  var moveDialog = document.getElementById('move-dialog');
  var moveForm = document.getElementById('move-form');
  var moveTarget = document.getElementById('move-target');
  var selMove = document.getElementById('sel-move');

  if (selMove && moveDialog && moveTarget) {
    selMove.addEventListener('click', function () {
      movingSelection = selectedPaths();
      if (!movingSelection.length) return;
      fetch('/api/folders')
        .then(function (response) { return response.json(); })
        .then(function (data) {
          moveTarget.innerHTML = '';
          var rootOption = document.createElement('option');
          rootOption.value = '';
          rootOption.textContent = 'Все файлы (корень)';
          moveTarget.appendChild(rootOption);
          var folders = (data && data.folders) || [];
          for (var i = 0; i < folders.length; i++) {
            var option = document.createElement('option');
            option.value = folders[i];
            option.textContent = folders[i];
            moveTarget.appendChild(option);
          }
          moveDialog.showModal();
        })
        .catch(function () { alert('Не удалось получить список папок'); });
    });
  }

  var moveCancel = document.getElementById('move-cancel');
  if (moveCancel && moveDialog) {
    moveCancel.addEventListener('click', function () { moveDialog.close(); });
  }

  if (moveForm && moveDialog && moveTarget) {
    moveForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var target = moveTarget.value;
      var paths = movingSelection.slice();
      var failed = 0;
      var chain = Promise.resolve();
      paths.forEach(function (path) {
        chain = chain.then(function () {
          return fetch('/move?path=' + encodeURIComponent(path) + '&to=' + encodeURIComponent(target), { method: 'POST' })
            .then(function (response) { if (!response.ok) failed++; })
            .catch(function () { failed++; });
        });
      });
      chain.then(function () {
        moveDialog.close();
        if (failed > 0) alert('Не удалось переместить: ' + failed);
        location.reload();
      });
    });
  }

  var newFolder = document.getElementById('new-folder');
  var mkdirDialog = document.getElementById('mkdir-dialog');
  var mkdirForm = document.getElementById('mkdir-form');
  var mkdirName = document.getElementById('mkdir-name');
  var mkdirCancel = document.getElementById('mkdir-cancel');

  if (newFolder && mkdirDialog && mkdirName) {
    newFolder.addEventListener('click', function () {
      mkdirName.value = '';
      mkdirDialog.showModal();
      setTimeout(function () { mkdirName.focus(); }, 30);
    });
  }
  if (mkdirCancel && mkdirDialog) {
    mkdirCancel.addEventListener('click', function () { mkdirDialog.close(); });
  }
  if (mkdirForm && mkdirDialog && mkdirName) {
    mkdirForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var name = mkdirName.value.trim();
      if (!name) return;
      fetch('/folder?path=' + encodeURIComponent(current) + '&name=' + encodeURIComponent(name), { method: 'POST' })
        .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
        .then(function (data) {
          if (data && data.ok) location.reload();
          else alert('Не удалось создать папку');
        })
        .catch(function () { alert('Ошибка сети'); });
    });
  }

  updateSelection();

  var search = document.getElementById('q');
  if (search) {
    search.addEventListener('input', function () {
      var query = search.value.trim().toLowerCase();
      var rows = document.querySelectorAll('#files li.file-item');
      for (var i = 0; i < rows.length; i++) {
        var name = rows[i].getAttribute('data-name') || '';
        rows[i].style.display = (query === '' || name.indexOf(query) >= 0) ? '' : 'none';
      }
    });
  }
})();
</script>
</body>
</html>"""

    val GALLERY = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${THEME_BOOT}
<title>Медиатека — LanDrop</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
    background-color: var(--bg);
    background-image:
      radial-gradient(900px 460px at 12% -8%, var(--glow-1), transparent 62%),
      radial-gradient(760px 440px at 108% 0%, var(--glow-2), transparent 58%);
    background-attachment: fixed;
    -webkit-font-smoothing: antialiased;
  }
  .topbar {
    position: sticky;
    top: 0;
    z-index: 5;
    border-bottom: 1px solid var(--line);
    background: var(--glass);
    backdrop-filter: blur(18px);
  }
  .bar-inner {
    width: min(1200px, 100%);
    margin: 0 auto;
    padding: 12px 20px;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .back {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 8px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    text-decoration: none;
    font-size: 12.5px;
    white-space: nowrap;
    transition: background .18s, border-color .18s;
  }
  .back:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .back svg { width: 15px; height: 15px; }
  .head-title { flex: 1; min-width: 0; }
  h1 { margin: 0; font-size: 17px; font-weight: 650; letter-spacing: -.01em; }
  .head-title p { margin: 2px 0 0; font-size: 11.5px; color: var(--muted); }
  main { width: min(1200px, 100%); margin: 0 auto; padding: 4px 20px 60px; }
  .tabs { display: flex; gap: 8px; overflow-x: auto; padding: 16px 0 4px; scrollbar-width: none; }
  .tabs::-webkit-scrollbar { display: none; }
  .tabs:empty { display: none; }
  .tab {
    flex: 0 0 auto;
    padding: 8px 13px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--muted);
    text-decoration: none;
    font-size: 12.5px;
    white-space: nowrap;
    transition: color .18s, border-color .18s, background .18s;
  }
  .tab:hover { color: var(--text); border-color: var(--line-strong); }
  .tab.active { color: var(--on-accent); background: linear-gradient(135deg, var(--accent), var(--accent-2)); border-color: transparent; font-weight: 600; }
  .source { margin-top: 24px; }
  .source-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
  h2 { margin: 0; font-size: 15px; font-weight: 600; }
  .count {
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    font-size: 11px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
  .card {
    position: relative;
    display: block;
    overflow: hidden;
    border-radius: 14px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    text-decoration: none;
    transition: transform .18s, border-color .18s, box-shadow .18s;
  }
  .card:hover { transform: translateY(-2px); border-color: var(--accent-border); box-shadow: var(--shadow-strong); }
  .card:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .card img, .card .none {
    width: 100%;
    aspect-ratio: 1 / 1;
    object-fit: cover;
    display: grid;
    place-items: center;
    background: var(--thumb-bg);
    color: var(--thumb-fg);
    font-size: 32px;
  }
  .card .cap {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    padding: 26px 10px 9px;
    font-size: 11px;
    color: var(--cap-text);
    background: var(--cap-bg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cell { position: relative; }
  .cell .card { height: 100%; }
  .media-del {
    position: absolute;
    top: 8px;
    right: 8px;
    z-index: 2;
    width: 30px;
    height: 30px;
    padding: 0;
    border-radius: 9px;
    border: 1px solid var(--line);
    background: var(--glass-strong);
    color: var(--text);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    opacity: 0;
    transition: opacity .18s, color .18s, background .18s, border-color .18s;
  }
  .cell:hover .media-del, .media-del:focus-visible { opacity: 1; }
  .media-del:hover { color: var(--red); background: var(--err-soft); border-color: var(--err-line); }
  @media (hover: none) { .media-del { opacity: 1; } }
  .card .badge {
    position: absolute;
    top: 8px;
    left: 8px;
    display: inline-flex;
    align-items: center;
    padding: 3px 7px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--badge-bg);
    backdrop-filter: blur(8px);
    font-size: 10.5px;
    color: var(--badge-text);
  }
  .empty {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0;
    padding: 18px;
    border: 1px dashed var(--line);
    border-radius: 14px;
    color: var(--muted);
    font-size: 13px;
  }
  .empty-icon {
    width: 34px;
    height: 34px;
    flex: 0 0 auto;
    border-radius: 11px;
    display: grid;
    place-items: center;
    background: var(--panel);
    border: 1px solid var(--line);
    font-size: 15px;
  }
  @media (max-width: 600px) {
    .bar-inner, main { padding-left: 14px; padding-right: 14px; }
    .head-title p { display: none; }
    .grid { grid-template-columns: repeat(auto-fill, minmax(132px, 1fr)); gap: 10px; }
    .source { margin-top: 20px; }
  }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<header class="topbar">
  <div class="bar-inner">
    <a class="back" href="/">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
      <span>Загрузка</span>
    </a>
    <div class="head-title">
      <h1>Медиатека</h1>
      <p>Фото, видео и файлы с телефона</p>
    </div>
    ${THEME_BUTTON}
  </div>
</header>
<main>
  <nav class="tabs" aria-label="Источники"><!--TABS--></nav>
  <!--SECTIONS-->
</main>
${THEME_SCRIPT}
<script>
document.querySelectorAll('img.thumb').forEach(function (img) {
  img.addEventListener('error', function () {
    var holder = document.createElement('div');
    holder.className = 'none';
    holder.textContent = '▤';
    if (img.parentNode) img.parentNode.replaceChild(holder, img);
  });
});

// Удаление прямо из сетки. Принятый файл уйдёт в корзину, а фото/видео
// телефона Android удалит только после подтверждения на самом телефоне.
document.querySelectorAll('.media-del').forEach(function (button) {
  button.addEventListener('click', function (event) {
    event.preventDefault();
    event.stopPropagation();

    var src = button.getAttribute('data-src') || '';
    var id = button.getAttribute('data-id') || '';
    var name = button.getAttribute('data-name') || '';
    if (!src || !id) return;
    if (!confirm('Удалить «' + name + '»?')) return;

    fetch('/media/delete?src=' + encodeURIComponent(src) + '&id=' + encodeURIComponent(id), {
      method: 'POST'
    })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .then(function (data) {
        if (data && data.ok) {
          var cell = button.closest('.cell');
          if (cell) cell.parentNode.removeChild(cell);
          return;
        }
        if (data && data.needsConsent) {
          alert('Android просит подтвердить удаление на телефоне: откройте LanDrop или уведомление «Подтвердите удаление».');
          return;
        }
        alert('Не удалось удалить элемент');
      })
      .catch(function () { alert('Ошибка сети'); });
  });
});
</script>
</body>
</html>"""

    /**
     * Страница-мостик для QR: пробует открыть приложение по deep link,
     * а без приложения показывает обычный веб-интерфейс.
     */
    val OPEN = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${THEME_BOOT}
<title>LanDrop — открыть</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    padding: 32px 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
    background-color: var(--bg);
    background-image:
      radial-gradient(900px 480px at 12% -8%, var(--glow-1), transparent 62%),
      radial-gradient(760px 460px at 108% 2%, var(--glow-2), transparent 58%);
    -webkit-font-smoothing: antialiased;
  }
  main { width: min(420px, 100%); }
  .topbar { display: flex; justify-content: flex-end; margin-bottom: 14px; }
  .card { padding: 24px; text-align: center; }
  .logo {
    width: 62px;
    height: 62px;
    margin: 0 auto 16px;
    border-radius: 20px;
    display: grid;
    place-items: center;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 10px 26px var(--accent-glow);
  }
  .logo svg { width: 30px; height: 30px; }
  h1 { margin: 0; font-size: 20px; font-weight: 650; letter-spacing: -.01em; }
  .hint { margin: 8px 0 0; font-size: 13px; line-height: 1.5; color: var(--muted); }
  .address {
    margin: 18px 0 0;
    padding: 12px;
    border-radius: 14px;
    border: 1px solid var(--line);
    background: var(--field);
    font-size: 15px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    word-break: break-all;
  }
  .open-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    width: 100%;
    margin-top: 16px;
    padding: 13px 16px;
    border: 0;
    border-radius: 13px;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 8px 22px var(--accent-glow);
    font-size: 14px;
    font-weight: 600;
    text-decoration: none;
    cursor: pointer;
  }
  .open-btn svg { width: 17px; height: 17px; }
  .browser-btn {
    display: block;
    margin-top: 10px;
    padding: 11px 16px;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    font-size: 13px;
    text-decoration: none;
  }
  .browser-btn:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .note { margin: 14px 0 0; font-size: 11.5px; color: var(--faint); }
  .status { margin: 16px 0 0; font-size: 12px; color: var(--accent); display: none; }
  .status.show { display: block; }
</style>
</head>
<body>
<main>
  <div class="topbar">
    ${THEME_BUTTON}
  </div>
  <div class="card">
    <div class="logo">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/></svg>
    </div>
    <h1>Открыть LanDrop</h1>
    <p class="hint">Телефон в этой же Wi-Fi сети. Откройте галерею или отправьте файлы — прямо в приложении.</p>
    <div class="address">__ADDRESS__</div>
    <a class="open-btn" id="open-app" href="__DEEP_LINK_ATTR__">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/></svg>
      Открыть в приложении
    </a>
    <a class="browser-btn" id="open-web" href="__BROWSER__">Продолжить в браузере</a>
    <p class="status" id="status">Открываем приложение…</p>
    <p class="note">Если приложение не установлено, останется веб-версия.</p>
  </div>
</main>
${THEME_SCRIPT}
<script>
(function () {
  var deep = __DEEP_LINK_JS__;
  var status = document.getElementById('status');
  if (!deep) return;

  var isAndroid = /Android/i.test(navigator.userAgent);
  var tried = false;
  function tryOpen() {
    if (tried) return;
    tried = true;
    if (status) status.classList.add('show');
    window.location.href = deep;
  }

  if (isAndroid) setTimeout(tryOpen, 250);
  var button = document.getElementById('open-app');
  if (button) button.addEventListener('click', function (event) {
    event.preventDefault();
    tried = false;
    tryOpen();
  });
})();
</script>
</body>
</html>"""

    /**
     * Видеотека: карточки видео с заголовком и описанием. Описание хранится
     * рядом с файлом, страница — обычный список с диалогом редактирования.
     */
    val LIBRARY = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${THEME_BOOT}
<title>Видеотека — LanDrop</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    padding: 40px 20px 56px;
    display: flex;
    justify-content: center;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
    background-color: var(--bg);
    background-image:
      radial-gradient(900px 480px at 12% -8%, var(--glow-1), transparent 62%),
      radial-gradient(760px 460px at 108% 2%, var(--glow-2), transparent 58%);
    background-attachment: fixed;
    -webkit-font-smoothing: antialiased;
  }
  main { width: min(980px, 100%); }
  .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .logo {
    flex: 0 0 auto;
    width: 46px;
    height: 46px;
    border-radius: 15px;
    display: grid;
    place-items: center;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 10px 26px var(--accent-glow);
  }
  .logo svg { width: 22px; height: 22px; }
  h1 { margin: 0; font-size: 18px; font-weight: 650; letter-spacing: -.01em; }
  .brand-text p { margin: 3px 0 0; font-size: 12px; color: var(--muted); }
  .top-actions { display: flex; align-items: center; gap: 8px; }
  .ghost-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    font-size: 12px;
    text-decoration: none;
    white-space: nowrap;
    cursor: pointer;
    transition: background .18s, border-color .18s;
  }
  .ghost-btn:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .ghost-btn.btn-small { padding: 6px 10px; font-size: 11.5px; }
  .ghost-btn.primary { color: var(--on-accent); border-color: transparent; background: linear-gradient(135deg, var(--accent), var(--accent-2)); }
  .head { display: flex; align-items: center; gap: 10px; margin: 0 0 14px; }
  h2 { margin: 0; font-size: 15px; font-weight: 600; }
  .count {
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    font-size: 11px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 14px; }
  .vcard {
    display: flex;
    flex-direction: column;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 16px;
    background: var(--panel);
    overflow: hidden;
    transition: transform .18s, border-color .18s, box-shadow .18s;
  }
  .vcard:hover { transform: translateY(-2px); border-color: var(--line-strong); box-shadow: var(--shadow-strong); }
  .vthumb {
    position: relative;
    display: block;
    aspect-ratio: 16 / 9;
    background: var(--thumb-bg);
    overflow: hidden;
  }
  .vthumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
  .vthumb .play {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: #fff;
    font-size: 26px;
    text-shadow: 0 2px 12px rgba(0, 0, 0, .7);
  }
  .vbody { display: flex; flex-direction: column; gap: 6px; padding: 10px 12px 12px; min-width: 0; }
  .vbody h3 {
    margin: 0;
    font-size: 13.5px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .vdesc {
    margin: 0;
    min-height: 2.9em;
    font-size: 11.5px;
    line-height: 1.45;
    color: var(--muted);
    white-space: pre-line;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
  .vdesc.muted { color: var(--faint); font-style: italic; }
  .vfoot { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
  .vmeta { flex: 1; min-width: 0; font-size: 10.5px; color: var(--faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .vactions { display: flex; gap: 2px; flex: 0 0 auto; }
  .edit, .del {
    width: 28px;
    height: 28px;
    padding: 0;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--faint);
    font-size: 14px;
    line-height: 1;
    cursor: pointer;
    transition: color .18s, background .18s, border-color .18s;
  }
  .edit:hover { color: var(--accent); background: var(--accent-soft); border-color: var(--accent-line); }
  .del { font-size: 17px; }
  .del:hover { color: var(--red); background: var(--err-soft); border-color: var(--err-line); }
  .empty {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 10px;
    margin: 0;
    padding: 24px 18px;
    border: 1px dashed var(--line);
    border-radius: 14px;
    color: var(--muted);
    font-size: 13px;
  }
  .empty small { flex-basis: 100%; color: var(--faint); font-size: 11.5px; }
  .empty-icon {
    width: 34px;
    height: 34px;
    flex: 0 0 auto;
    border-radius: 11px;
    display: grid;
    place-items: center;
    background: var(--panel);
    border: 1px solid var(--line);
    font-size: 14px;
  }
  .dlg {
    width: min(440px, calc(100vw - 32px));
    padding: 0;
    border: 1px solid var(--line-strong);
    border-radius: 16px;
    background: var(--bg);
    color: var(--text);
    box-shadow: var(--shadow-strong);
  }
  .dlg::backdrop { background: rgba(0, 0, 0, .55); }
  .dlg form { display: flex; flex-direction: column; gap: 10px; padding: 18px; }
  .dlg h3 { margin: 0; font-size: 15px; }
  .dlg p { margin: 0; font-size: 11.5px; color: var(--muted); word-break: break-word; }
  .dlg input, .dlg textarea {
    width: 100%;
    padding: 10px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--field);
    color: var(--text);
    font: inherit;
    font-size: 13px;
    outline: none;
  }
  .dlg textarea { resize: vertical; min-height: 120px; line-height: 1.45; }
  .dlg input:focus, .dlg textarea:focus { border-color: var(--accent-border); }
  .dlg-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; margin-top: 4px; }
  .dlg-actions .reset { margin-right: auto; color: var(--muted); }
  @media (max-width: 600px) {
    body { padding: 20px 14px 40px; }
    .brand-text p { display: none; }
    .logo { width: 42px; height: 42px; border-radius: 13px; }
    h1 { font-size: 16px; }
    .grid { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<main>
  <header class="topbar">
    <div class="brand">
      <div class="logo" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m10 8 6 4-6 4V8z"/><rect x="3" y="4" width="18" height="16" rx="3"/></svg>
      </div>
      <div class="brand-text">
        <h1>Видеотека</h1>
        <p>Видео с заголовком и описанием</p>
      </div>
    </div>
    <div class="top-actions">
      ${THEME_BUTTON}
      <a class="ghost-btn btn-small" href="/trash">Корзина</a>
      <a class="ghost-btn btn-small" href="/">К файлам</a>
    </div>
  </header>

  <div class="head">
    <h2>Видео</h2>
    <span class="count"><!--LIBRARY_COUNT--></span>
  </div>
  <div class="grid"><!--LIBRARY_ITEMS--></div>

  <dialog class="dlg" id="edit-dialog">
    <form id="edit-form">
      <h3>Описание видео</h3>
      <p id="edit-name"></p>
      <input id="edit-title" type="text" maxlength="200" placeholder="Заголовок" autocomplete="off">
      <textarea id="edit-description" maxlength="5000" placeholder="Что происходит в видео"></textarea>
      <div class="dlg-actions">
        <button class="ghost-btn reset" id="edit-reset" type="button">Убрать описание</button>
        <button class="ghost-btn" id="edit-cancel" type="button">Отмена</button>
        <button class="ghost-btn primary" type="submit">Сохранить</button>
      </div>
    </form>
  </dialog>
</main>
${THEME_SCRIPT}
<script>
var LIBRARY_ITEMS = <!--LIBRARY_DATA-->;
(function () {
  var dialog = document.getElementById('edit-dialog');
  var form = document.getElementById('edit-form');
  var titleInput = document.getElementById('edit-title');
  var descriptionInput = document.getElementById('edit-description');
  var nameLabel = document.getElementById('edit-name');
  var resetButton = document.getElementById('edit-reset');
  var cancelButton = document.getElementById('edit-cancel');
  var currentPath = '';

  function openEditor(path) {
    var item = LIBRARY_ITEMS[path];
    if (!item || !dialog) return;
    currentPath = path;
    if (nameLabel) nameLabel.textContent = path;
    if (titleInput) titleInput.value = item.title || '';
    if (descriptionInput) descriptionInput.value = item.description || '';
    if (resetButton) resetButton.hidden = !item.described;
    dialog.showModal();
  }

  document.querySelectorAll('.edit').forEach(function (button) {
    button.addEventListener('click', function () {
      openEditor(button.getAttribute('data-path') || '');
    });
  });

  document.querySelectorAll('.del').forEach(function (button) {
    button.addEventListener('click', function () {
      var path = button.getAttribute('data-path') || '';
      var name = button.getAttribute('data-name') || path;
      if (!path) return;
      if (!confirm('Удалить «' + name + '»? Видео переедет в корзину.')) return;
      fetch('/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        body: path
      })
        .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
        .then(function (data) {
          if (data && data.ok) location.reload();
          else alert('Не удалось удалить видео');
        })
        .catch(function () { alert('Ошибка сети'); });
    });
  });

  if (cancelButton) cancelButton.addEventListener('click', function () { dialog.close(); });

  if (resetButton) resetButton.addEventListener('click', function () {
    if (!currentPath) return;
    fetch('/library/reset?path=' + encodeURIComponent(currentPath), { method: 'POST' })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .then(function (data) {
        if (data && data.ok) location.reload();
        else alert('Не удалось убрать описание');
      })
      .catch(function () { alert('Ошибка сети'); });
  });

  if (form) form.addEventListener('submit', function (event) {
    event.preventDefault();
    if (!currentPath) return;
    var url = '/library/save?path=' + encodeURIComponent(currentPath) +
      '&title=' + encodeURIComponent(titleInput ? titleInput.value : '');
    fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: descriptionInput ? descriptionInput.value : ''
    })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .then(function (data) {
        if (data && data.ok) location.reload();
        else alert('Не удалось сохранить описание');
      })
      .catch(function () { alert('Ошибка сети'); });
  });
})();
</script>
</body>
</html>"""

    /**
     * Корзина: удалённые элементы можно вернуть на место или стереть навсегда.
     * Список рендерится сервером, действия — обычными POST/DELETE.
     */
    val TRASH = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${THEME_BOOT}
<title>LanDrop — корзина</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    padding: 40px 20px 56px;
    display: flex;
    justify-content: center;
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    color: var(--text);
    background-color: var(--bg);
    background-image:
      radial-gradient(900px 480px at 12% -8%, var(--glow-1), transparent 62%),
      radial-gradient(760px 460px at 108% 2%, var(--glow-2), transparent 58%);
    background-attachment: fixed;
    -webkit-font-smoothing: antialiased;
  }
  main { width: min(760px, 100%); }
  .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .logo {
    flex: 0 0 auto;
    width: 46px;
    height: 46px;
    border-radius: 15px;
    display: grid;
    place-items: center;
    color: var(--on-accent);
    background: linear-gradient(135deg, var(--accent), var(--accent-2));
    box-shadow: 0 10px 26px var(--accent-glow);
  }
  .logo svg { width: 22px; height: 22px; }
  h1 { margin: 0; font-size: 18px; font-weight: 650; letter-spacing: -.01em; }
  .brand-text p { margin: 3px 0 0; font-size: 12px; color: var(--muted); }
  .top-actions { display: flex; align-items: center; gap: 8px; }
  .ghost-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    border-radius: 10px;
    border: 1px solid var(--line);
    background: var(--panel);
    color: var(--text);
    font-size: 12px;
    text-decoration: none;
    white-space: nowrap;
    cursor: pointer;
    transition: background .18s, border-color .18s;
  }
  .ghost-btn:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .btn-small { padding: 6px 10px; font-size: 11.5px; }
  .ghost-btn.danger { color: var(--red); border-color: var(--err-line); }
  .ghost-btn.danger:hover { background: var(--err-soft); border-color: var(--err-line); }
  .head { display: flex; align-items: center; gap: 10px; margin: 0 0 12px; }
  h2 { margin: 0; font-size: 15px; font-weight: 600; }
  .count {
    padding: 3px 9px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--panel);
    font-size: 11px;
    color: var(--muted);
    font-variant-numeric: tabular-nums;
  }
  .grow { flex: 1 1 auto; }
  ul.files { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  li.file-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px 10px 10px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
    transition: background .18s, border-color .18s;
  }
  li.file-item:hover { background: var(--panel-hover); border-color: var(--line-strong); }
  .file-thumb { position: relative; flex: 0 0 auto; width: 42px; height: 42px; }
  .file-thumb .ftype { position: absolute; inset: 0; width: 100%; height: 100%; }
  .ftype {
    flex: 0 0 auto;
    width: 42px;
    height: 42px;
    border-radius: 12px;
    display: grid;
    place-items: center;
    font-size: 9px;
    font-weight: 700;
    letter-spacing: .06em;
    color: var(--accent);
    background: var(--accent-soft);
    border: 1px solid var(--accent-line);
  }
  .ftype[data-kind="image"] { color: #ffc078; background: rgba(255, 192, 120, .1); border-color: rgba(255, 192, 120, .2); }
  .ftype[data-kind="video"] { color: #c4a2ff; background: rgba(196, 162, 255, .1); border-color: rgba(196, 162, 255, .2); }
  .ftype[data-kind="audio"] { color: #6ee7d8; background: rgba(110, 231, 216, .1); border-color: rgba(110, 231, 216, .2); }
  .ftype[data-kind="doc"] { color: #8ab4f8; background: rgba(138, 180, 248, .1); border-color: rgba(138, 180, 248, .2); }
  .ftype[data-kind="archive"] { color: #ffb4a2; background: rgba(255, 180, 162, .1); border-color: rgba(255, 180, 162, .2); }
  .file-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1 1 auto; }
  .file { color: var(--text); font-size: 13.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .meta { color: var(--muted); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .file-actions { display: flex; gap: 4px; flex: 0 0 auto; }
  .restore, .del {
    flex: 0 0 auto;
    width: 32px;
    height: 32px;
    border-radius: 9px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--faint);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    transition: color .18s, background .18s, border-color .18s;
  }
  .restore:hover { color: var(--accent); background: var(--accent-soft); border-color: var(--accent-line); }
  .del { font-size: 18px; }
  .del:hover { color: var(--red); background: var(--err-soft); border-color: var(--err-line); }
  li.empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 26px 16px;
    border: 1px dashed var(--line);
    border-radius: 14px;
    color: var(--muted);
    font-size: 13px;
    text-align: center;
  }
  li.empty small { color: var(--faint); font-size: 11.5px; }
  .note {
    margin: 18px 0 0;
    padding: 12px 14px;
    border-radius: 12px;
    border: 1px solid var(--line);
    background: var(--drop-bg);
    color: var(--muted);
    font-size: 11.5px;
    line-height: 1.5;
  }
  .note b { color: var(--strong); font-weight: 600; }
  @media (max-width: 600px) {
    body { padding: 20px 14px 40px; }
    .brand-text p { display: none; }
    .logo { width: 42px; height: 42px; border-radius: 13px; }
    h1 { font-size: 16px; }
    .meta { font-size: 11px; }
  }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<main>
  <header class="topbar">
    <div class="brand">
      <div class="logo" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>
      </div>
      <div class="brand-text">
        <h1>Корзина</h1>
        <p>Удалённые файлы можно вернуть на место</p>
      </div>
    </div>
    <div class="top-actions">
      ${THEME_BUTTON}
      <a class="ghost-btn" href="/">К файлам</a>
    </div>
  </header>

  <div class="head">
    <h2>Удалено</h2>
    <span class="count"><!--TRASH_COUNT--></span>
    <span class="grow"></span>
    <!--TRASH_CLEAR-->
  </div>
  <ul class="files" id="items"><!--TRASH_ITEMS--></ul>

  <p class="note">Элементы хранятся в скрытой папке <b>.trash</b> и не видны в списке файлов. «Очистить корзину» удаляет их безвозвратно.</p>
</main>
${THEME_SCRIPT}
<script>
(function () {
  var list = document.getElementById('items');

  function post(url) {
    return fetch(url, { method: 'POST' })
      .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
      .catch(function () { return { ok: false }; });
  }

  function reload() { location.reload(); }

  if (list) {
    list.addEventListener('click', function (e) {
      var button = e.target;
      if (!button) return;
      var id = button.getAttribute('data-id') || '';
      if (!id) return;

      if (button.className === 'restore') {
        post('/restore?id=' + encodeURIComponent(id)).then(function (data) {
          if (data && data.ok) reload();
          else alert('Не удалось восстановить элемент');
        });
        return;
      }

      if (button.className !== 'del') return;
      if (!confirm('Удалить навсегда? Восстановить этот элемент больше не получится.')) return;
      fetch('/trash/' + encodeURIComponent(id), { method: 'DELETE' })
        .then(function (response) { return response.json().catch(function () { return { ok: false }; }); })
        .then(function (data) {
          if (data && data.ok) {
            var row = button.closest('li.file-item');
            if (row) row.parentNode.removeChild(row);
            var left = list.querySelectorAll('li.file-item').length;
            var count = document.querySelector('.count');
            if (count) count.textContent = left;
            if (!left) reload();
          } else {
            alert('Не удалось удалить элемент');
          }
        })
        .catch(function () { alert('Ошибка сети'); });
    });
  }

  var empty = document.getElementById('trash-empty');
  if (empty) {
    empty.addEventListener('click', function () {
      if (!confirm('Удалить всё из корзины навсегда?')) return;
      post('/trash/empty').then(function (data) {
        if (data && data.ok) reload();
        else alert('Не удалось очистить корзину');
      });
    });
  }
})();
</script>
</body>
</html>"""

    val VIEWER = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
${THEME_BOOT}
<title>__TITLE__</title>
<style>
${THEME_CSS}
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    height: 100%;
    overflow: hidden;
    overscroll-behavior: none;
    background: var(--stage-bg);
    color: var(--text);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  header {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 3;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    background: linear-gradient(var(--glass), transparent);
  }
  header a {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--glass-strong);
    backdrop-filter: blur(14px);
    color: var(--text);
    text-decoration: none;
    font-size: 12px;
    white-space: nowrap;
    transition: background .18s, border-color .18s;
  }
  header a:hover { background: var(--glass-hover); border-color: var(--line-strong); }
  header a svg { width: 14px; height: 14px; }
  header .title { flex: 1; min-width: 0; text-align: center; }
  header .name { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  header .counter { margin-top: 2px; font-size: 11px; color: var(--muted); }
  #stage {
    position: fixed;
    inset: 0;
    padding: 64px 0 24px;
    display: flex;
    align-items: center;
    justify-content: center;
    touch-action: pan-y;
    background: radial-gradient(900px 600px at 50% 42%, var(--stage-glow), transparent 70%);
  }
  /* Кадр растягивается на всю сцену: маленькая миниатюра сразу занимает экран
     и не «прыгает» в размере, когда подгрузится оригинал. */
  #stage img, #stage video {
    width: 100%;
    height: 100%;
    max-width: 100%;
    max-height: 100%;
    min-width: 0;
    min-height: 0;
    object-fit: contain;
    background: var(--stage-bg);
  }
  #stage iframe { width: 100%; height: 100%; border: 0; background: #fff; }
  #nav button {
    position: fixed;
    top: 50%;
    transform: translateY(-50%);
    z-index: 2;
    width: 46px;
    height: 46px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    border: 1px solid var(--line);
    cursor: pointer;
    background: var(--glass-strong);
    backdrop-filter: blur(14px);
    color: var(--text);
    font-size: 24px;
    line-height: 1;
    transition: background .18s, opacity .18s;
  }
  #nav button:hover:not(:disabled) { background: var(--glass-hover); }
  #nav button:disabled { opacity: .22; cursor: default; }
  #prev { left: 14px; }
  #next { right: 14px; }
  #hint {
    position: fixed;
    left: 50%;
    bottom: 14px;
    transform: translateX(-50%);
    z-index: 2;
    max-width: calc(100vw - 28px);
    padding: 7px 12px;
    border-radius: 999px;
    border: 1px solid var(--line);
    background: var(--glass);
    backdrop-filter: blur(10px);
    color: var(--muted);
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  #error {
    position: fixed;
    inset: 0;
    z-index: 4;
    display: none;
    align-items: center;
    justify-content: center;
    padding: 24px;
    text-align: center;
    font-size: 14px;
    color: var(--red);
    background: var(--bg-soft);
  }
  @media (max-width: 600px) {
    header { padding: 10px; }
    header a { padding: 8px 10px; }
    #nav button { width: 42px; height: 42px; }
    #prev { left: 8px; }
    #next { right: 8px; }
  }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<header>
  <a href="__GALLERY__" aria-label="Вернуться в галерею">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></svg>
    <span>Галерея</span>
  </a>
  <div class="title">
    <div class="name" id="name">__NAME__</div>
    <div class="counter" id="counter"></div>
  </div>
  ${THEME_BUTTON}
  <a href="__MEDIA__" download title="Скачать файл" aria-label="Скачать файл">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/><path d="M12 15V3"/></svg>
  </a>
</header>
<div id="stage"><img id="photo" alt=""></div>
<div id="nav">
  <button id="prev" type="button" title="Предыдущий (←)" aria-label="Предыдущий">‹</button>
  <button id="next" type="button" title="Следующий (→)" aria-label="Следующий">›</button>
</div>
<div id="hint">← → или смахните влево/вправо</div>
<div id="error"></div>
<noscript><p style="padding:80px 20px;text-align:center">
  <a href="__MEDIA__" style="color:#8ab4f8">Открыть файл</a>
</p></noscript>
${THEME_SCRIPT}
<script>
(function () {
  var SRC = __SRC__;
  var START = __ID__;
  var SWIPE_MIN = 60;

  var stage = document.getElementById('stage');
  var photo = document.getElementById('photo');
  var nameBox = document.getElementById('name');
  var counter = document.getElementById('counter');
  var prevButton = document.getElementById('prev');
  var nextButton = document.getElementById('next');
  var errorBox = document.getElementById('error');

  var items = [];
  var index = 0;
  var video = null;
  var frame = null;
  var loadToken = 0;

  function mediaUrl(item) {
    return '/media?src=' + encodeURIComponent(SRC) + '&id=' + encodeURIComponent(item.id);
  }

  function thumbUrl(item) {
    return '/thumb?src=' + encodeURIComponent(SRC) + '&id=' + encodeURIComponent(item.id);
  }

  function setUrl(item) {
    try {
      history.replaceState(null, '', '/view?src=' + encodeURIComponent(SRC) + '&id=' + encodeURIComponent(item.id));
    } catch (err) { /* старый браузер — просто остаёмся с исходным адресом */ }
  }

  function clearStage() {
    photo.style.display = 'none';
    if (video) { video.pause(); video.style.display = 'none'; }
    if (frame) frame.style.display = 'none';
  }

  function showImage(item, token) {
    photo.style.display = '';
    photo.alt = item.name;
    // Сначала миниатюра из кеша, затем оригинал — листание выглядит мгновенным.
    photo.src = thumbUrl(item);
    var full = new Image();
    full.onload = function () {
      if (token === loadToken) photo.src = full.src;
    };
    full.src = mediaUrl(item);
  }

  function showVideo(item) {
    if (!video) {
      video = document.createElement('video');
      video.controls = true;
      video.playsInline = true;
      video.preload = 'metadata';
      stage.appendChild(video);
    }
    video.style.display = '';
    video.src = mediaUrl(item);
    video.load();
  }

  function showFrame(item) {
    if (!frame) {
      frame = document.createElement('iframe');
      frame.title = item.name;
      stage.appendChild(frame);
    }
    frame.style.display = '';
    frame.src = mediaUrl(item);
  }

  function preload(item) {
    if (!item || item.kind !== 'image') return;
    var img = new Image();
    img.src = mediaUrl(item);
  }

  function show(i, updateUrl) {
    if (i < 0 || i >= items.length) return;
    index = i;
    var item = items[i];
    var token = ++loadToken;

    nameBox.textContent = item.name;
    counter.textContent = (i + 1) + ' / ' + items.length;
    prevButton.disabled = i === 0;
    nextButton.disabled = i === items.length - 1;
    if (updateUrl !== false) setUrl(item);

    clearStage();
    if (item.kind === 'image') showImage(item, token);
    else if (item.kind === 'video') showVideo(item);
    else showFrame(item);

    preload(items[i + 1]);
  }

  function fail(message) {
    errorBox.textContent = message;
    errorBox.style.display = 'flex';
  }

  prevButton.addEventListener('click', function () { show(index - 1); });
  nextButton.addEventListener('click', function () { show(index + 1); });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowLeft') { e.preventDefault(); show(index - 1); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); show(index + 1); }
    else if (e.key === 'Escape') { location.href = '__GALLERY__'; }
  });

  var startX = 0, startY = 0, tracking = false;
  stage.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { tracking = false; return; }
    tracking = true;
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
  }, { passive: true });
  stage.addEventListener('touchend', function (e) {
    if (!tracking) return;
    tracking = false;
    var touch = e.changedTouches && e.changedTouches[0];
    if (!touch) return;
    var dx = touch.clientX - startX;
    var dy = touch.clientY - startY;
    if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    show(dx < 0 ? index + 1 : index - 1);
  }, { passive: true });
  stage.addEventListener('touchcancel', function () { tracking = false; }, { passive: true });

  fetch('/api/media?src=' + encodeURIComponent(SRC))
    .then(function (response) { return response.json(); })
    .then(function (data) {
      var source = data && data.sources && data.sources[0];
      if (!source || !source.items || !source.items.length) {
        fail('В этом источнике нет файлов');
        return;
      }
      items = source.items;
      var start = 0;
      for (var i = 0; i < items.length; i++) {
        if (items[i].id === START) { start = i; break; }
      }
      show(start, false);
    })
    .catch(function () { fail('Не удалось загрузить список файлов'); });
})();
</script>
</body>
</html>"""
}
