'use client';

import { useEffect, useLayoutEffect, useRef } from 'react';
import { Annotation, Compartment, EditorState, Prec } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers, tooltips } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { HighlightStyle, bracketMatching, foldGutter, foldKeymap, indentOnInput, indentUnit, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { cpp } from '@codemirror/lang-cpp';
import { python } from '@codemirror/lang-python';
import { javaLanguage } from '@codemirror/lang-java';
import { autocompletion, closeCompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { tags } from '@lezer/highlight';
import { vim as vimMode, Vim } from '@replit/codemirror-vim';
import { javaNameCompletion } from './java-completion';
import {cppNameCompletion,pythonNameCompletion} from './native-completion';
import {memberCompletionSource} from './member-completion-source';

// Ex commands are registered globally by Vim, but actions belong to the focused editor.
const editorActions = new WeakMap();
for (const [name, prefix, action] of [['write', 'w', 'save'], ['run', 'run', 'run'], ['submit', 'submit', 'submit']]) {
  Vim.defineEx(name, prefix, (cm, params) => {
    if (params.args?.length || params.line != null || params.lineEnd != null) {
      const message = document.createElement('span');
      message.textContent = `:${prefix}는 인수나 범위 없이 사용해 주세요.`;
      cm.openNotification(message, {bottom:true, duration:3000});
      return;
    }
    editorActions.get(cm.cm6)?.(action);
  });
}
const externalChange = Annotation.define();
// Declaration roles come from the Java syntax tree, not regexes over strings/comments.
function declarations(view) {
  const ranges = [];
  for (const { from, to } of view.visibleRanges) {
    syntaxTree(view.state).iterate({ from, to, enter(node) {
      if (node.name !== 'Definition') return;
      const parent = node.node.parent?.name;
      const role = ['FormalParameter', 'SpreadParameter'].includes(parent) ? 'parameter'
        : ['MethodDeclaration', 'ConstructorDeclaration'].includes(parent) ? 'method'
        : ['ClassDeclaration', 'InterfaceDeclaration'].includes(parent) ? 'type' : null;
      if (role) ranges.push(Decoration.mark({ class: `java-${role}` }).range(node.from, node.to));
    } });
  }
  return Decoration.set(ranges, true);
}
const declarationColors = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = declarations(view); }
  update(update) {
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = declarations(update.view);
    }
  }
}, { decorations: plugin => plugin.decorations });
// Keep selected code readable independently of its syntax color and hue perception.
const selectedText = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = this.build(view); }
  update(update) {
    if (update.selectionSet || update.docChanged) this.decorations = this.build(update.view);
  }
  build(view) {
    return Decoration.set(view.state.selection.ranges.filter(range=>!range.empty).map(range=>
      Decoration.mark({class:'cm-selected-code'}).range(range.from,range.to)), true);
  }
}, {decorations:plugin=>plugin.decorations});

// Classic IntelliJ Darcula-inspired palette; Java semantic roles remain parser-based.
const colors = HighlightStyle.define([
  { tag: [tags.keyword, tags.bool, tags.null, tags.standard(tags.typeName)], color: '#cc7832' },
  { tag: [tags.typeName, tags.className], color: '#a9b7c6' },
  { tag: tags.function(tags.variableName), color: '#ffc66d' },
  { tag: [tags.string, tags.character], color: '#6a8759' },
  { tag: tags.number, color: '#6897bb' },
  { tag: tags.comment, color: '#808080' },
  { tag: [tags.operator, tags.punctuation], color: '#a9b7c6' },
  { tag: tags.meta, color: '#bbb529' },
]);
const theme = EditorView.theme({
  '&': { height: '100%', backgroundColor: '#2b2b2b', color: '#a9b7c6', fontSize: '14px' },
  '.cm-scroller': { overflow: 'auto', fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', lineHeight: '1.65' },
  '.cm-content': { padding: '12px 0', caretColor: '#bbbbbb' },
  '.cm-line': { padding: '0 12px' },
  '.java-parameter': { color: '#a9b7c6' },
  '.java-method': { color: '#ffc66d' },
  '.java-type': { color: '#a9b7c6' },
  '.cm-cursor': { borderLeftColor: '#bbbbbb' },
  '.cm-gutters': { backgroundColor: '#313335', color: '#909090', borderRight: '1px solid #3c3f41' },
  '.cm-activeLineGutter': { backgroundColor: '#323232' },
  '.cm-activeLine': { backgroundColor: '#ffffff08' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: '#ffe08a !important' },
  '.cm-selected-code, .cm-selected-code *': { color:'#18232d !important' },
  '.cm-content ::selection': { backgroundColor:'#ffe08a', color:'#18232d' },
  '.cm-matchingBracket': { backgroundColor: '#3b514d', outline: '1px solid #7f9c96' },
  '.cm-panels': { backgroundColor: '#3c3f41', color: '#a9b7c6' },
  '.cm-panel input, .cm-panel button': { color: '#a9b7c6', backgroundColor: '#2b2b2b' },
  '.cm-tooltip-autocomplete': { backgroundColor:'#313335', color:'#d9e2dc', border:'1px solid #62756a' },
  '.cm-tooltip-autocomplete > ul > li[aria-selected="true"]': { backgroundColor:'#ffe08a', color:'#18232d', outline:'1px solid #fff3cf', outlineOffset:'-1px' },
  '.cm-tooltip-autocomplete > ul > li[aria-selected="true"] .cm-completionDetail': {color:'#33414a'},
  '.cm-completionDetail': { color:'#bccac1', fontSize:'11px' },
  '.cm-searchMatch': { backgroundColor: '#62533a', outline: '1px solid #987e46' },
  '.cm-vim-panel': { backgroundColor: '#313335', color: '#bbbbbb', fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', fontSize: '12px', padding: '2px 10px' },
  '.cm-fat-cursor': { backgroundColor: '#ffe08a99 !important', color: '#18232d !important' },
  '&:not(.cm-focused) .cm-fat-cursor': { background: 'none !important', outline: '1px solid #ffe08a' },
}, { dark: true });

export default function CodeEditor({ id = 'source', label = 'Main.java', language = 'JAVA', value, disabled, onChange, onSubmit, onRun, onLimit, vim = false }) {
  const host = useRef(null);
  const editor = useRef(null);
  const callbacks = useRef({ onChange, onSubmit, onRun, onLimit, disabled, vim });
  const editable = useRef(new Compartment()), keys = useRef(new Compartment());
  useLayoutEffect(() => { callbacks.current = { onChange, onSubmit, onRun, onLimit, disabled, vim }; }, [onChange, onSubmit, onRun, onLimit, disabled, vim]);

  useEffect(() => {
    const act = action => {
      if (!callbacks.current.disabled) {
        if (action === 'save') callbacks.current.onChange(view.state.doc.toString());
        else if (action === 'run') callbacks.current.onRun?.();
        else callbacks.current.onSubmit?.();
      }
      return true;
    };
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({ doc: value, extensions: [
        // App actions win over Vim and browser shortcuts; ordinary editing stays with Vim.
        Prec.highest(keymap.of([
          {key:'Escape', run:view=>{
            if(!callbacks.current.vim)return false;
            closeCompletion(view);
            // Let Vim process Escape itself so undo groups, status and visual selections stay intact.
            return false;
          }},
          {key:'F5', run:()=>act('run'), preventDefault:true},
          {key:'Mod-Shift-Enter', run:()=>act('run'), preventDefault:true},
          {key:'Mod-Enter', run:()=>act('submit'), preventDefault:true},
          {key:'Mod-s', run:()=>act('save'), preventDefault:true},
        ])),
        // Vim bindings go first so they see keys before the default keymaps; completion still works in insert mode.
        keys.current.of(vim ? vimMode({ status: true }) : []),
        lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(),
        history(), ...(language==='JAVA'?[javaLanguage,declarationColors]:[language==='CPP'?cpp():python()]), selectedText, indentUnit.of('    '), EditorState.tabSize.of(4),
        indentOnInput(), bracketMatching(), closeBrackets(), foldGutter(), highlightSelectionMatches(),
        syntaxHighlighting(colors), theme,
        tooltips({tooltipSpace:()=>({left:8,top:8,right:document.documentElement.clientWidth-8,bottom:window.innerHeight-8})}),
        autocompletion({override:[memberCompletionSource(language),language==='JAVA'?javaNameCompletion:language==='CPP'?cppNameCompletion:pythonNameCompletion], defaultKeymap:false, activateOnTyping:true, selectOnOpen:true}),
        editable.current.of([EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled)]),
        EditorView.contentAttributes.of({ 'aria-label': label, 'aria-description': 'Ctrl+Space 후보 열기, 방향키 선택, Enter 확정, Esc 닫기. 스페이스는 공백, Tab은 들여쓰기.',
          'aria-multiline': 'true', spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off' }),
        keymap.of([
          ...completionKeymap,
          {key:'Tab', run:view=>{closeCompletion(view); return indentWithTab.run(view);}, shift:indentWithTab.shift},
          ...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, ...foldKeymap, ...searchKeymap, indentWithTab]),
        EditorState.changeFilter.of(transaction => {
          if (transaction.docChanged && transaction.newDoc.length > 65536
              && transaction.newDoc.length > transaction.startState.doc.length) {
            queueMicrotask(() => callbacks.current.onLimit());
            return false;
          }
          return true;
        }),
        EditorView.updateListener.of(update => {
          if (update.docChanged && !update.transactions.some(transaction => transaction.annotation(externalChange))) callbacks.current.onChange(update.state.doc.toString());
        }),
      ] }),
    });
    editor.current = view;
    editorActions.set(view, act);
    return () => { editorActions.delete(view); editor.current = null; view.destroy(); };
    // The parent keys by account/problem. Never rebuild the editor for a keystroke.
  }, [language]);

  useEffect(() => {
    const view = editor.current;
    if (view && view.state.doc.toString() !== value) {
      // Explicit external replacement (file import); do not echo it into draft persistence.
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value }, annotations: externalChange.of(true) });
    }
  }, [value]);
  useEffect(() => {
    editor.current?.dispatch({ effects: keys.current.reconfigure(vim ? vimMode({ status: true }) : []) });
  }, [vim]);
  useEffect(() => {
    if (disabled && editor.current) closeCompletion(editor.current);
    editor.current?.dispatch({ effects: editable.current.reconfigure([
      EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled),
    ]) });
  }, [disabled]);

  return <div id={id} className="code-editor" ref={host} />;
}
