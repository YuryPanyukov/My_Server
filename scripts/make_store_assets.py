"""Генератор ассетов для публикации LanDrop.

Делает из шаблонов веб-интерфейса русский и английский наборы по 4 скриншота
1080×1980, две обложки 1024×500, иконку 512/1024 px и обновляет legacy-иконки
лаунчера. Запуск из корня проекта:

    python scripts/make_store_assets.py

Нужен установленный Chrome (headless) и Pillow.
"""

import html
import json
import pathlib
import re
import socket
import subprocess
import tempfile
import threading
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from functools import partial

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / "app/src/main/java/Code_SyS/nas_server/WebTemplates.kt"
OUT = ROOT / "screenshots"
CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "google-chrome",
    "chromium",
]


def chrome_binary():
    import shutil

    for candidate in CHROME_CANDIDATES:
        if pathlib.Path(candidate).exists():
            return candidate
        found = shutil.which(candidate)
        if found:
            return found
    raise SystemExit("Chrome не найден")


def photo_svg(seed=0):
    """Абстрактная «фотография» для превью: без внешних файлов и сети."""
    palettes = [
        ("#7EA6FF", "#C79BFF", "#2A3350"),
        ("#79E0C8", "#5B8CFF", "#173C4A"),
        ("#FFC48A", "#FF8AA8", "#4A2A32"),
        ("#A6FFD8", "#58B6FF", "#1C3A46"),
        ("#E0B4FF", "#7C8CFF", "#2B2340"),
        ("#FFD8A8", "#FF9E7A", "#40291F"),
    ]
    sky_a, sky_b, ground = palettes[seed % len(palettes)]
    return (
        "<svg xmlns='http://www.w3.org/2000/svg' width='640' height='640' viewBox='0 0 640 640'>"
        f"<defs><linearGradient id='s' x1='0' y1='0' x2='1' y2='1'>"
        f"<stop offset='0' stop-color='{sky_a}'/><stop offset='1' stop-color='{sky_b}'/>"
        "</linearGradient></defs>"
        "<rect width='640' height='640' fill='url(#s)'/>"
        f"<circle cx='420' cy='210' r='96' fill='#FFFFFF' opacity='.55'/>"
        f"<path d='M0 470 L180 300 L320 430 L470 330 L640 470 L640 640 L0 640 Z' fill='{ground}' opacity='.55'/>"
        f"<path d='M0 560 L150 470 L300 540 L470 450 L640 560 L640 640 L0 640 Z' fill='{ground}' opacity='.8'/>"
        "</svg>"
    )


def data_uri(svg_text):
    return "data:image/svg+xml;charset=utf-8," + urllib.parse.quote(svg_text, safe="")


def read_templates():
    src = TEMPLATES.read_text(encoding="utf-8")
    consts = dict(re.findall(r'private const val (\w+) = """(.*?)"""', src, re.S))
    templates = dict(re.findall(r'\n    val (PAGE|GALLERY|VIEWER) = """(.*?)"""', src, re.S))

    def expand(text):
        for _ in range(6):
            new = re.sub(r"\$\{(\w+)\}", lambda m: consts.get(m.group(1), m.group(0)), text)
            if new == text:
                break
            text = new
        return text

    return {name: expand(body) for name, body in templates.items()}


def file_row(name, kind, label, size, thumb=None, play=False, lang="ru"):
    image = ""
    if thumb:
        image = f"<img class='fthumb' src='{thumb}' loading='lazy' decoding='async' alt=''>"
        if play:
            image += "<span class='play' aria-hidden='true'>▶</span>"
    meta = (
        f"{size} · 23.10.2026 12:40"
        if lang == "ru"
        else f"{size} · Oct 23, 2026 12:40"
    )
    delete = "Удалить" if lang == "ru" else "Delete"
    return (
        f"<li class='file-item' data-name='{name.lower()}'>"
        f"<span class='file-thumb' data-kind='{kind}'>{image}"
        f"<span class='ftype' data-kind='{kind}'>{label}</span></span>"
        f"<span class='file-main'><a class='file' href='/files/{urllib.parse.quote(name)}' "
        f"target='_blank' rel='noopener'>{html.escape(name)}</a>"
        f"<span class='meta'>{meta}</span></span>"
        f"<button class='del' type='button' title='{delete}' aria-label='{delete}' "
        f"data-href='/files/{urllib.parse.quote(name)}' data-file='{html.escape(name)}'>×</button></li>"
    )


# Текст интерфейса веб-страниц для англоязычных макетов. Пары применяются
# от длинных к коротким, чтобы вложенные фразы не переводились дважды.
PAGE_EN = [
    ("LanDrop — файлы по Wi-Fi", "LanDrop — files over Wi-Fi"),
    ("Медиатека — LanDrop", "Media library — LanDrop"),
    ("Передача файлов по локальной сети", "File transfer on your local network"),
    ("Выбрать файлы или перетащить их сюда", "Choose files or drop them here"),
    ("или нажмите, чтобы выбрать на устройстве", "or tap to choose on this device"),
    ("Браузер не поддерживает его аудиокодек (например, DTS/AC3 в MKV) — "
     "скачайте файл и откройте в VLC.",
     "Your browser doesn't support its audio codec (e.g. DTS/AC3 in MKV) — "
     "download the file and play it in VLC."),
    ("Фото, видео и файлы с телефона", "Photos, videos and files from your phone"),
    ("Фото и видео телефона", "Phone photos & videos"),
    ("Вернуться в галерею", "Back to gallery"),
    ("Не удалось загрузить список файлов", "Could not load the file list"),
    ("В этом источнике нет файлов", "No files in this source"),
    ("Нет звука у видео?", "No sound in a video?"),
    ("Перетащите файлы сюда", "Drop files here"),
    ("Поиск по имени файла", "Search by file name"),
    ("или смахните влево/вправо", "or swipe left or right"),
    ("Предыдущий (←)", "Previous (←)"),
    ("Следующий (→)", "Next (→)"),
    ("Отменить загрузку ", "Cancel upload "),
    ("Не удалось удалить файл", "Could not delete the file"),
    ("Передача отменена", "Transfer cancelled"),
    ("Загрузить не удалось", "Upload failed"),
    ("Полученные файлы", "Received files"),
    ("Выбрать файлы", "Choose files"),
    ("Загрузка файлов", "File upload"),
    ("Отменить всё", "Cancel all"),
    ("Открыть файл", "Open file"),
    ("Скачать файл", "Download file"),
    ("Удалить файл ", "Delete file "),
    ("Удалить", "Delete"),
    ("Галерея", "Gallery"),
    ("Медиатека", "Media library"),
    ("Загрузка", "Upload"),
    ("Принятые", "Received"),
    ("Все", "All"),
    ("Источники", "Sources"),
    ("Онлайн", "Online"),
    ("Светлая тема", "Light theme"),
    ("Тёмная тема", "Dark theme"),
    ("Предыдущий", "Previous"),
    ("Следующий", "Next"),
    ("Отменить", "Cancel"),
    ("Ошибка сети", "Network error"),
]


def localize_page(text):
    """Переводит статичный текст страницы для англоязычных макетов."""
    text = text.replace('<html lang="ru">', '<html lang="en">')
    for russian, english in sorted(PAGE_EN, key=lambda pair: -len(pair[0])):
        text = text.replace(russian, english)
    return text


def assert_english(text, page):
    """Страховка от непереведённых подписей в англоязычных макетах."""
    visible = re.sub(r"(?is)<script.*?</script>|<style.*?</style>|<!--.*?-->", " ", text)
    leftovers = re.findall(r"[А-Яа-яЁё][^<\n]{0,60}", visible)
    if leftovers:
        raise RuntimeError(f"{page}: остался русский текст: {leftovers[:3]}")


def build_pages(webroot, lang="ru"):
    templates = read_templates()
    photo_a = data_uri(photo_svg(0))
    photo_b = data_uri(photo_svg(2))
    en = lang == "en"
    suffix = "-en" if en else ""

    rows = "".join(
        [
            file_row("IMG_2041.jpg", "image", "JPG", "4.8 MB" if en else "4,8 МБ", photo_a, lang=lang),
            file_row("VID_0007.mp4", "video", "MP4", "38.2 MB" if en else "38,2 МБ", photo_b, play=True, lang=lang),
            file_row("Report.pdf" if en else "Отчёт.pdf", "doc", "PDF", "1.2 MB" if en else "1,2 МБ", lang=lang),
            file_row("archive.zip", "archive", "ZIP", "12.1 MB" if en else "12,1 МБ", lang=lang),
        ]
    )
    index = templates["PAGE"].replace("<!--FILES-->", rows).replace("<!--FILE_COUNT-->", "4")
    if en:
        index = localize_page(index)
        assert_english(index, "index-en.html")
    (webroot / f"index{suffix}.html").write_text(index, encoding="utf-8")

    # Экран загрузки в браузере: два файла в процессе, скорость и отмена.
    if en:
        queue = (
            "<li class='upload-item'>"
            f"<span class='u-thumb' data-kind='image'><img class='u-thumb-img' src='{photo_a}' alt=''>"
            "<span class='ftype' data-kind='image'>JPG</span></span>"
            "<span class='file-main'><span class='uname'>IMG_2041.jpg</span>"
            "<span class='bar'><span class='fill' style='width:68%'></span></span>"
            "<span class='sub'>4.8 MB · 12.4 MB/s · ≈ 1 s</span></span>"
            "<span class='state'>68%</span>"
            "<button class='cancel' type='button' title='Cancel' aria-label='Cancel'>×</button></li>"
            "<li class='upload-item'>"
            "<span class='u-thumb' data-kind='video'>"
            "<span class='ftype' data-kind='video'>MP4</span></span>"
            "<span class='file-main'><span class='uname'>VID_0007.mp4</span>"
            "<span class='bar'><span class='fill' style='width:24%'></span></span>"
            "<span class='sub'>38.2 MB · 7.1 MB/s · ≈ 4 s</span></span>"
            "<span class='state'>24%</span>"
            "<button class='cancel' type='button' title='Cancel' aria-label='Cancel'>×</button></li>"
        )
        summary = "0 of 2 · 42% · 19.5 MB/s · ≈ 6 s"
    else:
        queue = (
            "<li class='upload-item'>"
            f"<span class='u-thumb' data-kind='image'><img class='u-thumb-img' src='{photo_a}' alt=''>"
            "<span class='ftype' data-kind='image'>JPG</span></span>"
            "<span class='file-main'><span class='uname'>IMG_2041.jpg</span>"
            "<span class='bar'><span class='fill' style='width:68%'></span></span>"
            "<span class='sub'>4,8 МБ · 12,4 МБ/с · ≈ 1 с</span></span>"
            "<span class='state'>68%</span>"
            "<button class='cancel' type='button' title='Отменить' aria-label='Отменить'>×</button></li>"
            "<li class='upload-item'>"
            "<span class='u-thumb' data-kind='video'>"
            "<span class='ftype' data-kind='video'>MP4</span></span>"
            "<span class='file-main'><span class='uname'>VID_0007.mp4</span>"
            "<span class='bar'><span class='fill' style='width:24%'></span></span>"
            "<span class='sub'>38,2 МБ · 7,1 МБ/с · ≈ 4 с</span></span>"
            "<span class='state'>24%</span>"
            "<button class='cancel' type='button' title='Отменить' aria-label='Отменить'>×</button></li>"
        )
        summary = "0 из 2 · 42% · 19,5 МБ/с · ≈ 6 с"
    upload = index.replace(
        '<div id="queue" hidden>',
        '<div id="queue">',
    ).replace(
        '<button id="cancel-all" class="ghost-btn btn-small" type="button" hidden>',
        '<button id="cancel-all" class="ghost-btn btn-small" type="button">',
    ).replace(
        '<span id="queue-sum" class="queue-sum" aria-live="polite"></span>',
        '<span id="queue-sum" class="queue-sum" aria-live="polite">'
        f"{summary}</span>",
    ).replace(
        '<ul id="list" class="upload-list"></ul>',
        f'<ul id="list" class="upload-list">{queue}</ul>',
    )
    if en:
        assert_english(upload, "upload-en.html")
    (webroot / f"upload{suffix}.html").write_text(upload, encoding="utf-8")

    cards = ""
    for i, name in enumerate(
        ["IMG_2041.jpg", "VID_0007.mp4", "IMG_2042.jpg", "IMG_2043.jpg", "IMG_2050.jpg", "IMG_2051.jpg"]
    ):
        badge = "<span class='badge'>▶</span>" if name.endswith(".mp4") else ""
        cards += (
            f"<a class='card' href='/view?src=received&amp;id={name}' target='_blank' rel='noopener'>"
            f"<img class='thumb' src='{data_uri(photo_svg(i))}' loading='lazy' decoding='async' alt=''>"
            f"{badge}<span class='cap' title='{name}'>{name}</span></a>"
        )
    tabs = (
        "<a class='tab active' href='/gallery'>Все</a>"
        "<a class='tab' href='/gallery?src=received'>Принятые</a>"
        "<a class='tab' href='/gallery?src=device'>Фото и видео телефона</a>"
    )
    sections = (
        "<section class='source'><div class='source-head'><h2>Фото и видео телефона</h2>"
        "<span class='count'>128</span></div><div class='grid'>" + cards + "</div></section>"
    )
    gallery = templates["GALLERY"].replace("<!--TABS-->", tabs).replace("<!--SECTIONS-->", sections)
    if en:
        gallery = localize_page(gallery)
        assert_english(gallery, "gallery-en.html")
    (webroot / f"gallery{suffix}.html").write_text(gallery, encoding="utf-8")

    # Просмотрщик: подставляем кадр и список, чтобы страница выглядела как в работе.
    photo = data_uri(
        "<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='900' viewBox='0 0 1200 900'>"
        "<defs><linearGradient id='sky' x1='0' y1='0' x2='0.3' y2='1'>"
        "<stop offset='0' stop-color='#9FBDFF'/><stop offset='.55' stop-color='#E7B6FF'/>"
        "<stop offset='1' stop-color='#FFD9C2'/></linearGradient></defs>"
        "<rect width='1200' height='900' fill='url(#sky)'/>"
        "<circle cx='790' cy='300' r='110' fill='#FFF6E8' opacity='.9'/>"
        "<path d='M0 700 L240 470 L420 640 L640 430 L860 660 L1040 520 L1200 680 L1200 900 L0 900 Z' fill='#4B3E72' opacity='.65'/>"
        "<path d='M0 790 L220 640 L430 760 L670 600 L900 770 L1080 670 L1200 780 L1200 900 L0 900 Z' fill='#2B2344'/>"
        "</svg>"
    )
    viewer = templates["VIEWER"]
    viewer = (
        viewer.replace("__TITLE__", "IMG_2041.jpg")
        .replace("__NAME__", "IMG_2041.jpg")
        .replace("__GALLERY__", "/gallery")
        .replace("__MEDIA__", "/media?src=received&amp;id=IMG_2041.jpg")
        .replace("__SRC__", json.dumps("received"))
        .replace("__ID__", json.dumps("IMG_2041.jpg"))
    )
    stub = (
        "<script>window.fetch=function(){return Promise.resolve({json:function(){return {sources:[{"
        "items:[{id:'IMG_2041.jpg',name:'IMG_2041.jpg',kind:'image'},"
        "{id:'IMG_2042.jpg',name:'IMG_2042.jpg',kind:'image'},"
        "{id:'IMG_2043.jpg',name:'IMG_2043.jpg',kind:'image'}]}]};}});};</script>"
    )
    viewer = viewer.replace("<script>\n(function () {\n  var SRC =", stub + "<script>\n(function () {\n  var SRC =")
    viewer = viewer.replace(
        "</body>",
        "<script>setTimeout(function(){var p=document.getElementById('photo');"
        f"if(p){{p.src='{photo}';}}}},250);</script>\n</body>",
    )
    if en:
        viewer = localize_page(viewer)
        assert_english(viewer, "viewer-en.html")
    (webroot / f"viewer{suffix}.html").write_text(viewer, encoding="utf-8")


APP_SCREEN = """
<div class="app">
  <div class="statusbar">
    <span class="clock">9:41</span>
    <span class="sb-icons">
      <span class="bars"><i></i><i></i><i></i></span>
      <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M3 9a15 15 0 0 1 18 0"/><path d="M6.5 12.5a10 10 0 0 1 11 0"/><circle cx="12" cy="17" r="1.2" fill="currentColor" stroke="none"/></svg>
      <span class="battery"><i></i></span>
    </span>
  </div>
  <div class="app-body">
    <div class="app-brand">
      <div class="app-logo">↓</div>
      <div>
        <div class="app-name">LanDrop</div>
        <div class="app-tag">Файлы по Wi-Fi</div>
      </div>
    </div>

    <div class="app-card server">
      <span class="dot"></span>
      <div class="server-text">
        <div class="server-title">Сервер включён</div>
        <div class="server-sub">Ждём подключения в Wi-Fi</div>
      </div>
      <span class="switch"><i></i></span>
    </div>

    <div class="app-card addr">
      <div class="addr-label">АДРЕС В БРАУЗЕРЕ</div>
      <div class="addr-value">192.168.1.42:8080</div>
      <div class="addr-sub">Порт 8080 · только ваша Wi-Fi сеть</div>
      <div class="addr-btn">Открыть страницу</div>
    </div>

    <div class="files-head">
      <span>Принятые файлы</span>
      <span class="files-count">5</span>
    </div>
    <div class="app-search">
      <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
      Поиск по файлам
    </div>

    <div class="app-row">
      <div class="badge image">JPG</div>
      <div class="row-text"><div class="row-name">IMG_2041.jpg</div><div class="row-meta">4,8 МБ · 23.10.2026 12:40</div></div>
      <span class="row-del">Удалить</span>
    </div>
    <div class="app-row">
      <div class="badge video">MP4</div>
      <div class="row-text"><div class="row-name">VID_0007.mp4</div><div class="row-meta">38,2 МБ · 23.10.2026 12:38</div></div>
      <span class="row-del">Удалить</span>
    </div>
    <div class="app-row">
      <div class="badge doc">PDF</div>
      <div class="row-text"><div class="row-name">Отчёт.pdf</div><div class="row-meta">1,2 МБ · 23.10.2026 12:31</div></div>
      <span class="row-del">Удалить</span>
    </div>
    <div class="app-row">
      <div class="badge archive">ZIP</div>
      <div class="row-text"><div class="row-name">archive.zip</div><div class="row-meta">12,1 МБ · 22.10.2026 19:04</div></div>
      <span class="row-del">Удалить</span>
    </div>
    <div class="app-row">
      <div class="badge image">JPG</div>
      <div class="row-text"><div class="row-name">IMG_2038.jpg</div><div class="row-meta">3,1 МБ · 22.10.2026 18:47</div></div>
      <span class="row-del">Удалить</span>
    </div>
  </div>
</div>
"""

APP_EN = [
    ("Ждём подключения в Wi-Fi", "Waiting for a Wi-Fi connection"),
    ("Порт 8080 · только ваша Wi-Fi сеть", "Port 8080 · your Wi-Fi only"),
    ("4,8 МБ · 23.10.2026 12:40", "4.8 MB · Oct 23, 2026 12:40"),
    ("38,2 МБ · 23.10.2026 12:38", "38.2 MB · Oct 23, 2026 12:38"),
    ("1,2 МБ · 23.10.2026 12:31", "1.2 MB · Oct 23, 2026 12:31"),
    ("12,1 МБ · 22.10.2026 19:04", "12.1 MB · Oct 22, 2026 19:04"),
    ("3,1 МБ · 22.10.2026 18:47", "3.1 MB · Oct 22, 2026 18:47"),
    ("АДРЕС В БРАУЗЕРЕ", "ADDRESS IN BROWSER"),
    ("Сервер включён", "Server is on"),
    ("Принятые файлы", "Received files"),
    ("Открыть страницу", "Open page"),
    ("Поиск по файлам", "Search files"),
    ("Файлы по Wi-Fi", "Files over Wi-Fi"),
    ("Отчёт.pdf", "Report.pdf"),
    ("Удалить", "Delete"),
]


def app_screen(lang="ru"):
    if lang == "ru":
        return APP_SCREEN
    text = APP_SCREEN
    for russian, english in sorted(APP_EN, key=lambda pair: -len(pair[0])):
        text = text.replace(russian, english)
    return text


APP_CSS = """
.app { height: 100%; background: linear-gradient(180deg, #0A0C11, #0E1320); color: #EEF2F7; }
.statusbar { height: 30px; padding: 6px 18px 0; display: flex; align-items: center; justify-content: space-between; font-size: 10.5px; color: #C9D3E4; }
.sb-icons { display: flex; align-items: center; gap: 5px; }
.bars { display: flex; align-items: flex-end; gap: 2px; height: 9px; }
.bars i { width: 2.5px; border-radius: 1px; background: currentColor; }
.bars i:nth-child(1) { height: 3px; }
.bars i:nth-child(2) { height: 6px; }
.bars i:nth-child(3) { height: 9px; }
.battery { width: 17px; height: 8.5px; border: 1px solid currentColor; border-radius: 2.5px; padding: 1.2px; display: block; }
.battery i { display: block; width: 72%; height: 100%; border-radius: 1px; background: currentColor; }
.app-body { padding: 10px 16px 0; }
.app-brand { display: flex; align-items: center; gap: 10px; }
.app-logo { width: 40px; height: 40px; border-radius: 13px; display: grid; place-items: center; font-size: 20px; font-weight: 700; color: #081018; background: linear-gradient(135deg, #7B9CFF, #B98CFF); box-shadow: 0 8px 20px rgba(123,156,255,.28); }
.app-name { font-size: 18px; font-weight: 700; letter-spacing: .1px; }
.app-tag { font-size: 10.5px; color: #98A2B3; margin-top: 1px; }
.app-card { margin-top: 12px; border: 1px solid rgba(255,255,255,.08); border-radius: 20px; background: #11151D; }
.server { display: flex; align-items: center; gap: 9px; padding: 13px 14px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #3DDC97; box-shadow: 0 0 0 4px rgba(61,220,151,.14); flex: 0 0 auto; }
.server-text { flex: 1; min-width: 0; }
.server-title { font-size: 13px; font-weight: 600; }
.server-sub { font-size: 10.5px; color: #98A2B3; margin-top: 2px; }
.switch { width: 40px; height: 23px; border-radius: 999px; background: linear-gradient(135deg,#7B9CFF,#B98CFF); padding: 2.5px; display: flex; justify-content: flex-end; }
.switch i { width: 18px; height: 18px; border-radius: 50%; background: #081018; }
.addr { padding: 13px 14px 14px; }
.addr-label { font-size: 8.5px; letter-spacing: 1.2px; color: #98A2B3; font-weight: 600; }
.addr-value { font-size: 19px; font-weight: 700; margin-top: 3px; letter-spacing: -.2px; }
.addr-sub { font-size: 10px; color: #98A2B3; margin-top: 2px; }
.addr-btn { margin-top: 10px; height: 33px; border-radius: 11px; display: grid; place-items: center; font-size: 11.5px; font-weight: 600; color: #081018; background: linear-gradient(135deg,#7B9CFF,#B98CFF); }
.files-head { display: flex; align-items: center; justify-content: space-between; margin-top: 15px; font-size: 13px; font-weight: 600; }
.files-count { font-size: 10px; font-weight: 500; color: #98A2B3; border: 1px solid rgba(255,255,255,.1); background: rgba(255,255,255,.035); border-radius: 999px; padding: 2px 8px; }
.app-search { margin-top: 8px; height: 36px; border-radius: 12px; border: 1px solid rgba(255,255,255,.09); background: rgba(255,255,255,.03); color: #6F7887; font-size: 11.5px; display: flex; align-items: center; gap: 7px; padding: 0 11px; }
.app-row { display: flex; align-items: center; gap: 9px; padding: 8px 0 7px; border-bottom: 1px solid rgba(255,255,255,.055); }
.badge { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; font-size: 7.5px; font-weight: 700; letter-spacing: .4px; flex: 0 0 auto; }
.badge.image { color: #FFC078; background: rgba(255,192,120,.12); border: 1px solid rgba(255,192,120,.24); }
.badge.video { color: #C4A2FF; background: rgba(196,162,255,.12); border: 1px solid rgba(196,162,255,.24); }
.badge.doc { color: #8AB4F8; background: rgba(138,180,248,.12); border: 1px solid rgba(138,180,248,.24); }
.badge.archive { color: #FFB4A2; background: rgba(255,180,162,.12); border: 1px solid rgba(255,180,162,.24); }
.row-text { flex: 1; min-width: 0; }
.row-name { font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row-meta { font-size: 9.5px; color: #98A2B3; margin-top: 2px; }
.row-del { font-size: 10px; color: #7F8897; }
"""

BASE_CSS = """
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; width: 540px; height: 990px; overflow: hidden; background: #07090F; }
body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #EEF2F7; -webkit-font-smoothing: antialiased; }
.canvas { position: relative; width: 540px; height: 990px; overflow: hidden; background: #07090F; }
.glow { position: absolute; inset: 0; background:
  radial-gradient(620px 520px at 50% 48%, rgba(108,140,255,.30), transparent 66%),
  radial-gradient(420px 360px at 94% 2%, rgba(185,140,255,.20), transparent 70%),
  radial-gradient(360px 320px at 2% 96%, rgba(59,110,246,.14), transparent 72%); }
.grid { position: absolute; inset: 0; background-image:
  linear-gradient(rgba(255,255,255,.035) 1px, transparent 1px),
  linear-gradient(90deg, rgba(255,255,255,.035) 1px, transparent 1px);
  background-size: 36px 36px;
  -webkit-mask-image: radial-gradient(circle at 50% 46%, #000 24%, transparent 76%);
  mask-image: radial-gradient(circle at 50% 46%, #000 24%, transparent 76%); }
.header { position: absolute; top: 30px; left: 40px; right: 40px; }
.brand { display: flex; align-items: center; gap: 9px; }
.brand img { width: 22px; height: 22px; border-radius: 7px; }
.brand span { font-size: 11px; letter-spacing: .17em; text-transform: uppercase; color: #AEB8C8; font-weight: 600; }
.net { margin-left: auto; font-size: 10.5px; color: #B9C3D3; border: 1px solid rgba(255,255,255,.12); padding: 5px 9px; border-radius: 999px; display: flex; gap: 6px; align-items: center; background: rgba(255,255,255,.04); }
.net i { width: 6px; height: 6px; border-radius: 50%; background: #3DDC97; }
h1 { margin: 15px 0 0; font-size: 35px; line-height: 1.13; letter-spacing: -.03em; font-weight: 700; }
h1 .grad { background: linear-gradient(115deg, #8FB0FF, #C79BFF); -webkit-background-clip: text; background-clip: text; color: transparent; }
.sub { margin: 11px 0 0; font-size: 13.5px; line-height: 1.45; color: #98A2B3; max-width: 436px; }
.device { position: absolute; left: 50%; top: 226px; transform: translateX(-50%); width: 380px; height: 686px; padding: 8px; border-radius: 48px; background: linear-gradient(160deg, #252C3D, #0B0F17 62%); box-shadow: 0 50px 120px rgba(0,0,0,.72), 0 0 0 1px rgba(255,255,255,.08), inset 0 0 0 1px rgba(255,255,255,.06); }
.screen { position: relative; width: 100%; height: 100%; border-radius: 40px; overflow: hidden; background: #0A0C11; }
.camera { position: absolute; top: 9px; left: 50%; transform: translateX(-50%); width: 52px; height: 13px; border-radius: 7px; background: #05070B; z-index: 6; box-shadow: inset 0 0 0 1px rgba(255,255,255,.07); }
.screen iframe { width: 364px; height: 670px; border: 0; display: block; }
.web .screen iframe { position: absolute; top: 26px; left: 0; height: 644px; }
.webstatus { position: absolute; top: 0; left: 0; right: 0; height: 26px; padding: 5px 16px 0; display: flex; align-items: center; justify-content: space-between; font-size: 10px; color: #C9D3E4; background: #0A0C11; z-index: 2; }
.webstatus .sb-icons { display: flex; align-items: center; gap: 5px; }
.light .webstatus { background: #F5F7FB; color: #344054; }
.chips { position: absolute; left: 40px; right: 40px; bottom: 20px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
.chip { font-size: 10.5px; color: #C3CCDA; border: 1px solid rgba(255,255,255,.1); background: rgba(255,255,255,.045); padding: 6px 11px; border-radius: 999px; }
"""

MARKETING = """<!DOCTYPE html>
<html lang="__LANG__">
<head>
<meta charset="utf-8">
<script>try { localStorage.setItem('nas-theme', '__THEME__'); } catch (e) {}</script>
<style>
__BASE_CSS__
__SCREEN_CSS__
</style>
</head>
<body>
<div class="canvas">
  <div class="glow"></div>
  <div class="grid"></div>
  <header class="header">
    <div class="brand"><img src="icon.svg" alt=""><span>LanDrop</span><span class="net"><i></i>Wi-Fi</span></div>
    <h1>__H1__</h1>
    <p class="sub">__SUB__</p>
  </header>
  <div class="device __DEVICE_CLASS__">
    <div class="screen">
      <div class="camera"></div>
      __STATUSBAR__
      __SCREEN__
    </div>
  </div>
  <div class="chips">__CHIPS__</div>
</div>
</body>
</html>
"""

STATUSBAR = """
      <div class="webstatus">
        <span>9:41</span>
        <span class="sb-icons">
          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M3 9a15 15 0 0 1 18 0"/><path d="M6.5 12.5a10 10 0 0 1 11 0"/><circle cx="12" cy="17" r="1.2" fill="currentColor" stroke="none"/></svg>
          <span style="width:15px;height:8px;border:1px solid currentColor;border-radius:2px;display:inline-block;padding:1px"><span style="display:block;width:70%;height:100%;background:currentColor;border-radius:1px"></span></span>
        </span>
      </div>"""


FEATURE = """<!DOCTYPE html>
<html lang="ru">
<head><meta charset="utf-8"><style>
* { box-sizing: border-box; }
html, body { margin: 0; width: 1024px; height: 500px; overflow: hidden; }
body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #EEF2F7; background: #07090F; }
.canvas { position: relative; width: 1024px; height: 500px; overflow: hidden; background: #07090F; }
.glow { position: absolute; inset: 0; background:
  radial-gradient(560px 420px at 82% 44%, rgba(108,140,255,.34), transparent 66%),
  radial-gradient(420px 320px at 100% 0%, rgba(185,140,255,.24), transparent 70%),
  radial-gradient(420px 340px at 0% 100%, rgba(59,110,246,.16), transparent 72%); }
.grid { position: absolute; inset: 0; background-image:
  linear-gradient(rgba(255,255,255,.04) 1px, transparent 1px),
  linear-gradient(90deg, rgba(255,255,255,.04) 1px, transparent 1px);
  background-size: 40px 40px;
  -webkit-mask-image: radial-gradient(circle at 70% 50%, #000 20%, transparent 74%);
  mask-image: radial-gradient(circle at 70% 50%, #000 20%, transparent 74%); }
.content { position: absolute; left: 64px; top: 62px; width: 600px; }
.brand { display: flex; align-items: center; gap: 10px; }
.brand img { width: 30px; height: 30px; border-radius: 9px; }
.brand span.name { font-size: 16px; font-weight: 700; letter-spacing: .3px; }
.brand span.pill { margin-left: 6px; font-size: 11px; color: #B9C3D3; border: 1px solid rgba(255,255,255,.12); background: rgba(255,255,255,.04); border-radius: 999px; padding: 4px 9px; }
h1 { margin: 24px 0 0; font-size: 54px; line-height: 1.08; letter-spacing: -.035em; font-weight: 700; }
h1 .grad { background: linear-gradient(115deg, #8FB0FF, #C79BFF); -webkit-background-clip: text; background-clip: text; color: transparent; }
p { margin: 16px 0 0; font-size: 17px; line-height: 1.45; color: #98A2B3; max-width: 500px; }
.chips { margin-top: 28px; display: flex; gap: 8px; flex-wrap: wrap; }
.chip { font-size: 11px; color: #C3CCDA; border: 1px solid rgba(255,255,255,.1); background: rgba(255,255,255,.045); padding: 7px 11px; border-radius: 999px; }
.appicon { position: absolute; right: 84px; top: 100px; width: 300px; height: 300px; border-radius: 84px; box-shadow: 0 50px 110px rgba(0,0,0,.6), 0 0 0 1px rgba(255,255,255,.1); }
.ring { position: absolute; border: 1px solid rgba(255,255,255,.08); border-radius: 50%; }
.ring.one { right: 34px; top: 50px; width: 400px; height: 400px; }
.ring.two { right: 4px; top: 20px; width: 460px; height: 460px; }
</style></head>
<body>
<div class="canvas">
  <div class="glow"></div><div class="grid"></div>
  <div class="ring one"></div><div class="ring two"></div>
  <img src="icon.svg" class="appicon" alt="">
  <div class="content">
    <div class="brand"><img src="icon.svg" alt=""><span class="name">LanDrop</span><span class="pill">Wi-Fi</span></div>
    <h1>Телефон —<br><span class="grad">папка</span> в Wi-Fi</h1>
    <p>Файлы с компьютера в браузере, медиатека телефона, скорость и отмена передачи.</p>
    <div class="chips"><span class="chip">Без облака</span><span class="chip">Локальная сеть</span><span class="chip">Тёмная и светлая темы</span></div>    </div>
</div>
</body></html>
"""

FEATURE_EN = [
    ("Телефон —<br><span class=\"grad\">папка</span> в Wi-Fi",
     "Your phone —<br><span class=\"grad\">a folder</span> on Wi-Fi"),
    ("Файлы с компьютера в браузере, медиатека телефона, скорость и отмена передачи.",
     "Send files from any browser, browse your phone's media, watch speed and cancel anytime."),
    ("<span class=\"chip\">Без облака</span>",
     "<span class=\"chip\">No cloud</span>"),
    ("<span class=\"chip\">Локальная сеть</span>",
     "<span class=\"chip\">Local network</span>"),
    ("<span class=\"chip\">Тёмная и светлая темы</span>",
     "<span class=\"chip\">Dark &amp; light themes</span>"),
]


def feature_html(lang="ru"):
    text = FEATURE
    if lang == "en":
        text = text.replace('<html lang="ru">', '<html lang="en">')
        for russian, english in FEATURE_EN:
            text = text.replace(russian, english)
    return text


def marketing(h1, sub, screen, chips, theme, device_class="", screen_css="", statusbar="", lang="ru"):
    return (
        MARKETING.replace("__BASE_CSS__", BASE_CSS)
        .replace("__LANG__", lang)
        .replace("__SCREEN_CSS__", screen_css)
        .replace("__THEME__", theme)
        .replace("__H1__", h1)
        .replace("__SUB__", sub)
        .replace("__DEVICE_CLASS__", device_class)
        .replace("__STATUSBAR__", statusbar)
        .replace("__SCREEN__", screen)
        .replace("__CHIPS__", "".join(f"<span class='chip'>{c}</span>" for c in chips))
    )


def shoot(chrome, url, out, width, height, scale, budget=2500):
    out = pathlib.Path(out)
    out.unlink(missing_ok=True)
    profile = tempfile.mkdtemp(prefix="landrop-shot-")
    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--no-first-run",
        "--no-default-browser-check",
        f"--user-data-dir={profile}",
        f"--force-device-scale-factor={scale}",
        f"--window-size={width},{height}",
        f"--virtual-time-budget={budget}",
        f"--screenshot={out}",
        url,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if not out.exists():
        raise RuntimeError(f"Chrome не сделал скриншот: {result.stderr[-500:]}")
    size = Image.open(out).size
    expected = (width * scale, height * scale)
    if size != expected:
        raise RuntimeError(f"{out.name}: {size}, ожидалось {expected}")


def render_icon(webroot):
    svg = (OUT / "icon.svg").read_text(encoding="utf-8")
    page = f"""<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{{margin:0;width:1024px;height:1024px;overflow:hidden}}svg{{display:block}}</style>
</head><body>{svg}</body></html>"""
    (webroot / "icon-render.html").write_text(page, encoding="utf-8")


def launcher_icons(base_png):
    icon = Image.open(base_png).convert("RGBA")
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in sizes.items():
        resized = icon.resize((size, size), Image.LANCZOS)
        folder = ROOT / f"app/src/main/res/mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        rounded_mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(rounded_mask).rounded_rectangle(
            (0, 0, size - 1, size - 1), radius=int(size * 0.225), fill=255
        )
        square = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        square.paste(resized, (0, 0), rounded_mask)
        square.save(folder / "ic_launcher.webp", "WEBP", quality=92, method=6)

        circle_mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(circle_mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        circle = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        circle.paste(resized, (0, 0), circle_mask)
        circle.save(folder / "ic_launcher_round.webp", "WEBP", quality=92, method=6)


def main():
    chrome = chrome_binary()
    OUT.mkdir(exist_ok=True)
    webroot = pathlib.Path(tempfile.mkdtemp(prefix="landrop-web-"))
    build_pages(webroot, "ru")
    build_pages(webroot, "en")
    (webroot / "icon.svg").write_text((OUT / "icon.svg").read_text(encoding="utf-8"), encoding="utf-8")
    render_icon(webroot)

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    handler = partial(SimpleHTTPRequestHandler, directory=str(webroot))
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}/"

    # Иконка: 1024 и 512 px.
    icon_png = OUT / "icon-1024.png"
    shoot(chrome, base + "icon-render.html", str(icon_png), 1024, 1024, 1, 800)
    Image.open(icon_png).convert("RGBA").resize((512, 512), Image.LANCZOS).save(
        OUT / "icon-512.png", "PNG"
    )
    launcher_icons(icon_png)

    ru_shots = [
        (
            "01-app-main.html",
            "01-app-main.png",
            "Телефон — <span class='grad'>папка</span> в Wi-Fi",
            "Включите сервер и передавайте файлы с компьютера — без кабеля, облака и регистрации.",
            app_screen("ru"),
            ["Локальная сеть", "Без облака", "Android 7+"],
            "dark",
            "",
            APP_CSS,
            "",
        ),
        (
            "02-web-upload.html",
            "02-web-upload.png",
            "Загрузка <span class='grad'>прямо в браузере</span>",
            "Перетащите файлы на страницу с ПК: видно скорость, остаток времени и можно отменить передачу.",
            f"<iframe src='{base}upload.html' title='Загрузка'></iframe>",
            ["До 3 передач сразу", "Скорость и отмена", "Превью файлов"],
            "dark",
            "web",
            "",
            "",
        ),
        (
            "03-gallery.html",
            "03-gallery.png",
            "Вся медиатека <span class='grad'>под рукой</span>",
            "Фото и видео с телефона открываются в браузере — с превью, поиском и просмотром.",
            f"<iframe src='{base}gallery.html' title='Галерея'></iframe>",
            ["Фото и видео", "Превью", "Листание свайпом"],
            "dark",
            "web",
            "",
            "",
        ),
        (
            "04-light-viewer.html",
            "04-light-viewer.png",
            "Светлая тема <span class='grad'>для любого света</span>",
            "Тёмное и светлое оформление, превью и листание — на телефоне, ноутбуке и планшете.",
            f"<iframe src='{base}viewer.html' title='Просмотр'></iframe>",
            ["Тёмная и светлая", "Выбор запоминается", "Без перезагрузки"],
            "light",
            "web light",
            "",
            "",
        ),
    ]

    en_shots = [
        (
            "01-app-main-en.html",
            "01-app-main.png",
            "Your phone — <span class='grad'>a folder</span> on Wi-Fi",
            "Turn on the server and move files from your computer — no cable, cloud or sign-up.",
            app_screen("en"),
            ["Local network", "No cloud", "Android 7+"],
            "dark",
            "",
            APP_CSS,
            "",
        ),
        (
            "02-web-upload-en.html",
            "02-web-upload.png",
            "Upload <span class='grad'>from any browser</span>",
            "Drop files from your PC: see the speed, time left, and cancel any transfer.",
            f"<iframe src='{base}upload-en.html' title='Upload'></iframe>",
            ["Up to 3 at once", "Speed & cancel", "File previews"],
            "dark",
            "web",
            "",
            "",
        ),
        (
            "03-gallery-en.html",
            "03-gallery.png",
            "All your media <span class='grad'>within reach</span>",
            "Browse your phone's photos and videos in any browser — thumbnails, search, viewer.",
            f"<iframe src='{base}gallery-en.html' title='Gallery'></iframe>",
            ["Photos & videos", "Thumbnails", "Swipe to browse"],
            "dark",
            "web",
            "",
            "",
        ),
        (
            "04-light-viewer-en.html",
            "04-light-viewer.png",
            "Light theme <span class='grad'>for any lighting</span>",
            "Dark and light themes, previews and swipe — on your phone, laptop and tablet.",
            f"<iframe src='{base}viewer-en.html' title='Viewer'></iframe>",
            ["Dark & light", "Choice is remembered", "No reload"],
            "light",
            "web light",
            "",
            "",
        ),
    ]

    for lang, out_dir, shots in (("ru", OUT, ru_shots), ("en", OUT / "en", en_shots)):
        out_dir.mkdir(parents=True, exist_ok=True)
        for source, name, h1, sub, screen, chips, theme, device_class, screen_css, statusbar in shots:
            page = marketing(h1, sub, screen, chips, theme, device_class, screen_css, statusbar, lang)
            (webroot / source).write_text(page, encoding="utf-8")
            shoot(chrome, base + source, str(out_dir / name), 540, 990, 2)
            print(f"{lang}/{name}: 1080x1980")

    # Обложка для карточки приложения в магазине.
    for lang, out_dir, source in (
        ("ru", OUT, "feature-graphic.html"),
        ("en", OUT / "en", "feature-graphic-en.html"),
    ):
        (webroot / source).write_text(feature_html(lang), encoding="utf-8")
        shoot(chrome, base + source, str(out_dir / "feature-graphic.png"), 1024, 500, 1, 1200)
        print(f"{lang}/feature-graphic.png: 1024x500")

    server.shutdown()
    print("Готово:", OUT)


if __name__ == "__main__":
    main()
