import DOMPurify from 'dompurify'
import { Marked, type Tokens } from 'marked'

interface WikiLinkToken extends Tokens.Generic {
  type: 'wikiLink'
  name: string
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Renders entry content. [[wiki-links]] become real links when the target entry is known,
 * and a muted "missing" chip otherwise. Output is sanitised before it reaches the DOM.
 */
export function renderMarkdown(raw: string | null | undefined, resolve: (name: string) => string | null): string {
  if (!raw) {
    return ''
  }

  const marked = new Marked({ gfm: true, breaks: false })
  marked.use({
    extensions: [
      {
        name: 'wikiLink',
        level: 'inline',
        start(src: string) {
          return src.indexOf('[[')
        },
        tokenizer(src: string) {
          const match = /^\[\[([^\][\n]+)]]/.exec(src)
          if (!match) {
            return undefined
          }
          return { type: 'wikiLink', raw: match[0], name: match[1].trim() } satisfies WikiLinkToken
        },
        renderer(token: Tokens.Generic) {
          const name = escapeHtml((token as WikiLinkToken).name)
          const href = resolve((token as WikiLinkToken).name)
          return href
            ? `<a class="wikilink" href="${escapeHtml(href)}">${name}</a>`
            : `<span class="wikilink-missing" title="No entry named ${name}">${name}</span>`
        },
      },
    ],
  })

  return DOMPurify.sanitize(marked.parse(raw, { async: false }), {
    ADD_ATTR: ['target', 'rel'],
  })
}

// External links open in a new tab; internal ones stay in the SPA (see MarkdownBody.vue).
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof HTMLAnchorElement && /^https?:\/\//i.test(node.getAttribute('href') ?? '')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})
