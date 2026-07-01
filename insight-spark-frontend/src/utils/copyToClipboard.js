/**
 * 写入剪贴板。优先 Clipboard API；HTTP 非 localhost 等不安全上下文下降级 execCommand。
 */
export async function copyToClipboard(text) {
  const value = String(text ?? '')
  if (!value) return false

  if (window.isSecureContext && navigator?.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // fall through to legacy copy
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', 'readonly')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  return copied
}
