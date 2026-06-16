/** 框选区域（相对图表容器 0~1 归一化坐标） */
export function normalizeSelectionRect(rect) {
  if (!rect || typeof rect !== 'object') return null
  const x = Number(rect.x)
  const y = Number(rect.y)
  const w = Number(rect.w)
  const h = Number(rect.h)
  if (![x, y, w, h].every(Number.isFinite)) return null
  if (w <= 0 || h <= 0) return null
  const nx = Math.max(0, Math.min(1, x))
  const ny = Math.max(0, Math.min(1, y))
  const nw = Math.max(0, Math.min(1 - nx, w))
  const nh = Math.max(0, Math.min(1 - ny, h))
  if (nw < 0.01 || nh < 0.01) return null
  return { x: nx, y: ny, w: nw, h: nh }
}

export function parseSelectionRect(bind) {
  if (!bind || typeof bind !== 'object') return null
  return normalizeSelectionRect(bind.selectionRect)
}

export function parseSelectionImage(bind) {
  if (!bind || typeof bind !== 'object') return null
  const img = bind.selectionImage
  if (typeof img === 'string' && img.startsWith('data:image/')) return img
  return null
}

export function hasSelectionRect(bind) {
  return !!parseSelectionRect(bind)
}

export function hasSelectionPreview(bind) {
  return !!parseSelectionImage(bind) || hasSelectionRect(bind)
}

export function rectToPercentStyle(rect) {
  const norm = normalizeSelectionRect(rect)
  if (!norm) return null
  return {
    left: `${norm.x * 100}%`,
    top: `${norm.y * 100}%`,
    width: `${norm.w * 100}%`,
    height: `${norm.h * 100}%`
  }
}

function resolveCaptureBackground(containerEl) {
  if (!containerEl) return '#ffffff'
  const candidates = [
    containerEl,
    containerEl.querySelector('.dc-root'),
    containerEl.querySelector('.lic-root'),
    containerEl.closest('.dbv-chart-host'),
    containerEl.closest('.dbv-card')
  ]
  for (const el of candidates) {
    if (!el) continue
    const bg = getComputedStyle(el).backgroundColor
    if (bg && bg !== 'transparent' && bg !== 'rgba(0, 0, 0, 0)') return bg
  }
  return '#ffffff'
}

function fillCanvasBackground(ctx, width, height, color) {
  ctx.save()
  ctx.fillStyle = color
  ctx.fillRect(0, 0, width, height)
  ctx.restore()
}

/** 将 ECharts 透明画布合成到不透明底图上，避免导出 JPEG 时透明区变黑 */
function compositeCanvasOnBackground(sourceCanvas, background = '#ffffff') {
  const out = document.createElement('canvas')
  out.width = sourceCanvas.width
  out.height = sourceCanvas.height
  const ctx = out.getContext('2d')
  if (!ctx) return sourceCanvas
  fillCanvasBackground(ctx, out.width, out.height, background)
  ctx.drawImage(sourceCanvas, 0, 0)
  return out
}

/** 截取框选区域缩略图（JPEG data URL） */
export async function captureSelectionThumbnail(containerEl, rect, options = {}) {
  const norm = normalizeSelectionRect(rect)
  if (!containerEl || !norm) return null

  const maxWidth = options.maxWidth ?? 360
  const quality = options.quality ?? 0.82
  const background = options.background || resolveCaptureBackground(containerEl)

  let sourceCanvas = containerEl.querySelector('canvas')
  if (!sourceCanvas || sourceCanvas.width < 2) {
    const html2canvas = (await import('html2canvas')).default
    sourceCanvas = await html2canvas(containerEl, {
      backgroundColor: background,
      scale: Math.min(window.devicePixelRatio || 1, 2),
      logging: false,
      useCORS: true
    })
  } else {
    sourceCanvas = compositeCanvasOnBackground(sourceCanvas, background)
  }

  const srcW = sourceCanvas.width
  const srcH = sourceCanvas.height
  const px = Math.round(norm.x * srcW)
  const py = Math.round(norm.y * srcH)
  const pw = Math.max(1, Math.round(norm.w * srcW))
  const ph = Math.max(1, Math.round(norm.h * srcH))
  if (pw < 4 || ph < 4) return null

  const cropCanvas = document.createElement('canvas')
  cropCanvas.width = pw
  cropCanvas.height = ph
  const cropCtx = cropCanvas.getContext('2d')
  if (!cropCtx) return null
  fillCanvasBackground(cropCtx, pw, ph, background)
  cropCtx.drawImage(sourceCanvas, px, py, pw, ph, 0, 0, pw, ph)

  const out = document.createElement('canvas')
  let outW = pw
  let outH = ph
  if (outW > maxWidth) {
    outH = Math.max(1, Math.round(outH * (maxWidth / outW)))
    outW = maxWidth
  }
  out.width = outW
  out.height = outH
  const ctx = out.getContext('2d')
  if (!ctx) return null
  fillCanvasBackground(ctx, outW, outH, background)
  ctx.drawImage(cropCanvas, 0, 0, outW, outH)
  return out.toDataURL('image/jpeg', quality)
}
