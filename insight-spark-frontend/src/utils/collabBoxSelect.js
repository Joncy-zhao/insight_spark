import * as echarts from 'echarts'

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

/** ECharts 实际挂载节点（非外层包装） */
function resolveChartHost(containerEl) {
  if (!containerEl) return null
  return containerEl.querySelector('.dc-host, .lic-host') || containerEl
}

/** 将相对 fromEl 的归一化区域映射到 toEl */
function mapSelectionRectBetween(fromEl, toEl, rect) {
  if (!fromEl || !toEl || !rect) return null
  if (fromEl === toEl) return rect
  const fromBox = fromEl.getBoundingClientRect()
  const toBox = toEl.getBoundingClientRect()
  if (fromBox.width < 1 || fromBox.height < 1 || toBox.width < 1 || toBox.height < 1) return null

  const selLeft = rect.x * fromBox.width
  const selTop = rect.y * fromBox.height
  const selRight = selLeft + rect.w * fromBox.width
  const selBottom = selTop + rect.h * fromBox.height

  const toLeft = toBox.left - fromBox.left
  const toTop = toBox.top - fromBox.top
  const toRight = toLeft + toBox.width
  const toBottom = toTop + toBox.height

  const ix1 = Math.max(selLeft, toLeft)
  const iy1 = Math.max(selTop, toTop)
  const ix2 = Math.min(selRight, toRight)
  const iy2 = Math.min(selBottom, toBottom)
  const iw = ix2 - ix1
  const ih = iy2 - iy1
  if (iw < 4 || ih < 4) return null

  return {
    x: (ix1 - toLeft) / toBox.width,
    y: (iy1 - toTop) / toBox.height,
    w: iw / toBox.width,
    h: ih / toBox.height
  }
}

function fillCanvasBackground(ctx, width, height, color) {
  ctx.save()
  ctx.fillStyle = color
  ctx.fillRect(0, 0, width, height)
  ctx.restore()
}

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

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = src
  })
}

async function waitForPaintFrames(count = 2) {
  for (let i = 0; i < count; i += 1) {
    await new Promise((resolve) => requestAnimationFrame(resolve))
  }
}

/** 等待 ECharts 实例与 canvas 就绪（柱形图动画/大数据渲染可能略慢） */
async function waitForChartReady(containerEl, timeoutMs = 1200) {
  const hostEl = resolveChartHost(containerEl)
  if (!hostEl) return null
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await waitForPaintFrames(1)
    const inst = echarts.getInstanceByDom(hostEl)
    const canvas = hostEl.querySelector('canvas')
    if (inst && !inst.isDisposed?.() && canvas && canvas.width >= 4 && canvas.height >= 4) {
      return inst
    }
    await new Promise((resolve) => setTimeout(resolve, 40))
  }
  return echarts.getInstanceByDom(hostEl)
}

function cropNormalizedRegion(source, srcW, srcH, rect, background, maxWidth, quality) {
  const px = Math.round(rect.x * srcW)
  const py = Math.round(rect.y * srcH)
  const pw = Math.max(1, Math.round(rect.w * srcW))
  const ph = Math.max(1, Math.round(rect.h * srcH))
  if (pw < 4 || ph < 4) return null

  const cropCanvas = document.createElement('canvas')
  cropCanvas.width = pw
  cropCanvas.height = ph
  const cropCtx = cropCanvas.getContext('2d')
  if (!cropCtx) return null
  fillCanvasBackground(cropCtx, pw, ph, background)
  cropCtx.drawImage(source, px, py, pw, ph, 0, 0, pw, ph)

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

function pauseChartAnimation(inst) {
  if (!inst || inst.isDisposed?.()) return null
  try {
    const prev = inst.getOption?.()?.animation
    inst.setOption({ animation: false, animationDuration: 0, animationDurationUpdate: 0 }, false)
    return prev
  } catch {
    return null
  }
}

function restoreChartAnimation(inst, prevAnimation) {
  if (!inst || inst.isDisposed?.() || prevAnimation == null) return
  try {
    inst.setOption({ animation: prevAnimation }, false)
  } catch {
    // ignore
  }
}

async function captureViaEcharts(hostEl, hostRect, background, pixelRatio, maxWidth, quality) {
  const inst = echarts.getInstanceByDom(hostEl)
  if (!inst || inst.isDisposed?.()) return null
  const prevAnimation = pauseChartAnimation(inst)
  await waitForPaintFrames(2)
  try {
    inst.resize?.()
    const dataUrl = inst.getDataURL({
      type: 'png',
      pixelRatio,
      backgroundColor: background
    })
    const img = await loadImage(dataUrl)
    return cropNormalizedRegion(
      img,
      img.naturalWidth,
      img.naturalHeight,
      hostRect,
      background,
      maxWidth,
      quality
    )
  } finally {
    restoreChartAnimation(inst, prevAnimation)
  }
}

async function captureViaDomSnapshot(captureEl, captureRect, background, pixelRatio, maxWidth, quality) {
  const html2canvas = (await import('html2canvas')).default
  const shot = await html2canvas(captureEl, {
    backgroundColor: background,
    scale: pixelRatio,
    logging: false,
    useCORS: true,
    ignoreElements: (el) => {
      if (!el?.classList) return false
      return (
        el.classList.contains('cbs-hitmask')
        || el.classList.contains('cbs-rect')
        || el.classList.contains('cbs-capturing')
      )
    }
  })
  return cropNormalizedRegion(shot, shot.width, shot.height, captureRect, background, maxWidth, quality)
}

async function captureViaCanvas(hostEl, hostRect, background, maxWidth, quality) {
  const canvases = hostEl.querySelectorAll('canvas')
  let sourceCanvas = null
  for (const canvas of canvases) {
    if (canvas.width >= 4 && canvas.height >= 4) {
      sourceCanvas = canvas
      break
    }
  }
  if (!sourceCanvas) return null
  sourceCanvas = compositeCanvasOnBackground(sourceCanvas, background)
  return cropNormalizedRegion(
    sourceCanvas,
    sourceCanvas.width,
    sourceCanvas.height,
    hostRect,
    background,
    maxWidth,
    quality
  )
}

/**
 * 截取框选区域缩略图（JPEG data URL）
 * @param layerEl 框选层根节点（归一化坐标基准）
 * @param rect 0~1 归一化框选区域
 * @param options.contentEl 图表内容容器，默认等于 layerEl
 * @param options.captureEl DOM 截图目标，默认 contentEl
 */
export async function captureSelectionThumbnail(layerEl, rect, options = {}) {
  const norm = normalizeSelectionRect(rect)
  const contentEl = options.contentEl || layerEl
  const captureEl = options.captureEl || contentEl
  if (!layerEl || !contentEl || !norm) return null

  const maxWidth = options.maxWidth ?? 360
  const quality = options.quality ?? 0.82
  const background = options.background || resolveCaptureBackground(contentEl)
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)

  await waitForChartReady(contentEl)

  const hostEl = resolveChartHost(contentEl)
  const captureRect = mapSelectionRectBetween(layerEl, captureEl, norm) || norm
  const hostRect = hostEl ? mapSelectionRectBetween(layerEl, hostEl, norm) : null

  // 1. DOM 快照：与框选层同一坐标系，柱形/折线表现一致
  try {
    const viaDom = await captureViaDomSnapshot(captureEl, captureRect, background, pixelRatio, maxWidth, quality)
    if (viaDom) return viaDom
  } catch {
    // fallback below
  }

  if (!hostEl || !hostRect) return null

  // 2. ECharts 导出（坐标映射到 .dc-host / .lic-host）
  try {
    const viaEcharts = await captureViaEcharts(hostEl, hostRect, background, pixelRatio, maxWidth, quality)
    if (viaEcharts) return viaEcharts
  } catch {
    // fallback below
  }

  // 3. 直接裁 canvas
  try {
    return await captureViaCanvas(hostEl, hostRect, background, maxWidth, quality)
  } catch {
    return null
  }
}
