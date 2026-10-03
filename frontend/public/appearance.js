// Shared by the pre-paint bootstrap and the settings UI. Web fonts are self-hosted.
(function () {
  var key = 'gamjaoj-appearance-v1', root = document.documentElement;
  var groups = {
    ui: [
      ['canvas','화면 배경','#f5f8f7','#0f1513'],['surface','패널 배경','#ffffff','#161e1c'],
      ['subtle','보조 배경','#eff5f2','#1d2825'],['ink','기본 글자','#182f2b','#e2ebe7'],
      ['muted','보조 글자','#63736e','#9aaba5'],['line','테두리','#dde6e2','#2b3935'],
      ['green','강조·버튼','#16765d','#25896b'],['button-ink','버튼 글자','#ffffff','#ffffff'],
      ['sidebar-background','사이드바 배경','#143e34','#0f2b24'],['sidebar-ink','사이드바 글자','#c7ded3','#c7ded3'],
      ['sidebar-active','선택 메뉴 배경','#e3f4b1','#e3f4b1'],['sidebar-active-ink','선택 메뉴 글자','#183f2e','#183f2e'],
      ['success','성공 표시','#26744b','#7fd3a3'],['danger','오류 표시','#a1271b','#ff9a86']
    ],
    editor: [
      ['editor-background','에디터 배경','#2b2b2b'],['editor-ink','코드 기본 글자','#a9b7c6'],
      ['editor-gutter','줄 번호 배경','#313335'],['editor-gutter-ink','줄 번호 글자','#909090'],
      ['editor-active-line','현재 줄 배경','#323232'],['editor-caret','커서','#bbbbbb'],
      ['editor-selection','선택 영역 배경','#ffe08a'],['editor-selection-ink','선택 영역 글자','#18232d'],
      ['editor-keyword','키워드','#cc7832'],['editor-type','타입·클래스','#a9b7c6'],
      ['editor-function','함수·메서드','#ffc66d'],['editor-string','문자열','#6a8759'],
      ['editor-number','숫자','#6897bb'],['editor-comment','주석','#808080'],['editor-meta','어노테이션','#bbb529'],
      ['editor-border','에디터 테두리','#3c3f41'],['editor-bracket','짝 괄호 배경','#3b514d'],['editor-bracket-line','짝 괄호 테두리','#7f9c96'],
      ['editor-search','검색 결과 배경','#62533a'],['editor-search-line','검색 결과 테두리','#987e46']
    ],
    console: [
      ['console-background','터미널 배경','#16211f'],['console-header','터미널 제목 배경','#1f2d2a'],
      ['console-ink','터미널 글자','#e6efec'],['console-muted','터미널 보조 글자','#9fb2ad'],
      ['console-line','터미널 테두리','#2c3d39'],['console-input','입력·오류 배경','#0f1716'],
      ['console-success','통과 표시','#7ee2a8'],['console-error','실패·오류 표시','#ff8f86']
    ]
  };
  var fonts = {
    system: ['시스템 고정폭','ui-monospace, SFMono-Regular, Consolas, monospace'],
    consolas: ['Consolas (기기 설치)','Consolas, ui-monospace, monospace'],
    menlo: ['Menlo (기기 설치)','Menlo, ui-monospace, monospace'],
    jetbrains: ['JetBrains Mono','"JetBrains Mono", ui-monospace, monospace'],
    fira: ['Fira Code','"Fira Code", ui-monospace, monospace'],
    d2coding: ['D2Coding','D2Coding, ui-monospace, monospace'],
    custom: ['직접 입력','ui-monospace, monospace']
  };
  function clean(raw) {
    raw = raw && typeof raw === 'object' ? raw : {};
    var next = { light: {}, dark: {}, editor: {}, console: {}, font: 'system', fontSize: 14, customFont: '' };
    ['light','dark','editor','console'].forEach(function (group) {
      (groups[group === 'light' || group === 'dark' ? 'ui' : group]).forEach(function (field) {
        var value = raw[group] && raw[group][field[0]];
        if (typeof value === 'string' && /^#[\da-f]{6}$/i.test(value)) next[group][field[0]] = value;
      });
    });
    if (Object.prototype.hasOwnProperty.call(fonts,raw.font)) next.font = raw.font;
    if (typeof raw.fontSize === 'number' && Number.isFinite(raw.fontSize)) next.fontSize = Math.round(Math.max(10,Math.min(28,raw.fontSize)));
    if (typeof raw.customFont === 'string') next.customFont = raw.customFont.replace(/[^\p{L}\p{N} _-]/gu,'').slice(0,80);
    return next;
  }
  function read() { try { return clean(JSON.parse(localStorage.getItem(key))); } catch (_) { return clean(); } }
  var current = read();
  function apply() {
    var mode = root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    Object.keys(groups).forEach(function (group) {
      var values = current[group === 'ui' ? mode : group];
      groups[group].forEach(function (field) {
        if (values[field[0]]) root.style.setProperty('--'+field[0], values[field[0]]);
        else root.style.removeProperty('--'+field[0]);
      });
    });
    root.setAttribute('data-custom-colors',Object.keys(current[mode]).length ? 'true' : 'false');
    root.style.setProperty('--editor-font-family',current.font === 'custom' && current.customFont
      ? '"'+current.customFont+'", ui-monospace, monospace' : fonts[current.font][1]);
    root.style.setProperty('--editor-font-size',current.fontSize+'px');
    window.dispatchEvent(new Event('gamjaoj-appearance'));
  }
  function save(value) { current = clean(value); try { localStorage.setItem(key,JSON.stringify(current)); } catch (_) {} apply(); }
  window.GamjaAppearance = {groups: groups, fonts: fonts, get: function () { return current; }, save: save, reset: function () { save({}); }};
  apply();
  new MutationObserver(apply).observe(root,{attributes:true,attributeFilter:['data-theme']});
  window.addEventListener('storage',function (event) { if (event.key === key || event.key === null) { current = read(); apply(); } });
})();
