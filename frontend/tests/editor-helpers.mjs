import { expect } from '@playwright/test';

// Test snippets fit in the rendered viewport. Join line elements to preserve whitespace.
export async function expectCode(editor, expected, negate = false) {
  const assertion = expect.poll(() => editor.evaluate(element =>
    [...element.querySelectorAll('.cm-line')].map(line => line.textContent).join('\n')));
  await (negate ? assertion.not : assertion).toBe(expected);
}
