import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

export const PERF_REPORT_HISTORY_KEY = 'insight_spark_perf_bottleneck_reports'
const MAX_HISTORY = 30

export function loadPerfReportHistory() {
  try {
    const raw = localStorage.getItem(PERF_REPORT_HISTORY_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

export function savePerfReportHistory(list) {
  localStorage.setItem(PERF_REPORT_HISTORY_KEY, JSON.stringify(list.slice(0, MAX_HISTORY)))
}

export function appendPerfReportHistory(report) {
  const entry = {
    id: report.reportId,
    generatedAt: report.generatedAtDisplay || report.generatedAt,
    overallLevel: report.overallLevel,
    conclusion: report.conclusion,
    report
  }
  const list = [entry, ...loadPerfReportHistory().filter((item) => item.id !== entry.id)]
  savePerfReportHistory(list)
  return list
}

export function removePerfReportHistory(id) {
  const list = loadPerfReportHistory().filter((item) => item.id !== id)
  savePerfReportHistory(list)
  return list
}

export function clearPerfReportHistory() {
  localStorage.removeItem(PERF_REPORT_HISTORY_KEY)
  return []
}

export function buildPerfReportMarkdown(report, helpers = {}) {
  const overallLevelLabel = helpers.overallLevelLabel || ((level) => level || '—')
  const formatReportTime = helpers.formatReportTime || ((raw) => raw || '—')
  const lines = [
    `# ${report.title || '性能瓶颈诊断报告'}`,
    `报告编号：${report.reportId || '—'}`,
    `生成时间：${report.generatedAtDisplay || formatReportTime(report.generatedAt)}`,
    `综合等级：${overallLevelLabel(report.overallLevel)}`,
    '',
    '## 结论',
    report.conclusion || '',
    ''
  ]
  const summary = report.summary || {}
  lines.push(
    '## 核心指标',
    `- 慢查询：${summary.slowCount ?? 0}`,
    `- 缓存命中率：${Number(summary.cacheHitRate ?? 0).toFixed(2)}%`,
    `- 堆占用：${Number(summary.heapUsedPercent ?? 0).toFixed(2)}%`,
    `- 拦截 SQL：${summary.blockedCount ?? 0}`,
    ''
  )
  for (const sec of report.sections || []) {
    lines.push(`## ${sec.title}`, sec.content || '', '')
  }
  if (report.suggestions?.length) {
    lines.push('## 优化建议')
    for (const s of report.suggestions) {
      lines.push(`- [${s.severity}] ${s.title}：${s.detail}`)
    }
    lines.push('')
  }
  if (report.topSlowUsers?.length) {
    lines.push('## 慢查询用户 TOP')
    for (const u of report.topSlowUsers) {
      lines.push(`- ${u.userId}：${u.queryCount} 次，均耗时 ${u.avgDurationMs}ms，最大 ${u.maxDurationMs}ms`)
    }
  }
  return lines.join('\n')
}

function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function buildPerfReportWordHtml(report, helpers = {}) {
  const overallLevelLabel = helpers.overallLevelLabel || ((level) => level || '—')
  const formatReportTime = helpers.formatReportTime || ((raw) => raw || '—')
  const summary = report.summary || {}
  const sections = (report.sections || [])
    .map(
      (sec) => `
      <h2 style="color:#1e3a8a;border-bottom:1px solid #dbeafe;padding-bottom:6px;">${escapeHtml(sec.title)}</h2>
      <pre style="white-space:pre-wrap;font-family:'Microsoft YaHei',sans-serif;font-size:13px;line-height:1.7;background:#f8fafc;padding:12px;border-radius:8px;">${escapeHtml(sec.content)}</pre>`
    )
    .join('')
  const suggestions = (report.suggestions || [])
    .map(
      (s) => `
      <div style="margin-bottom:12px;padding:12px;border-left:4px solid #93c5fd;background:#f8fbff;">
        <p style="margin:0 0 6px;font-weight:700;">[${escapeHtml(s.severity)}] ${escapeHtml(s.type)} · ${escapeHtml(s.title)}</p>
        <p style="margin:0;color:#475569;line-height:1.6;">${escapeHtml(s.detail)}</p>
      </div>`
    )
    .join('')
  const topUsers = (report.topSlowUsers || [])
    .map(
      (u) =>
        `<tr>
          <td style="border:1px solid #dbeafe;padding:8px;">${escapeHtml(u.userId)}</td>
          <td style="border:1px solid #dbeafe;padding:8px;">${escapeHtml(u.queryCount)}</td>
          <td style="border:1px solid #dbeafe;padding:8px;">${escapeHtml(u.avgDurationMs)}</td>
          <td style="border:1px solid #dbeafe;padding:8px;">${escapeHtml(u.maxDurationMs)}</td>
        </tr>`
    )
    .join('')

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>${escapeHtml(report.title || '性能瓶颈诊断报告')}</title>
</head>
<body style="font-family:'Microsoft YaHei',sans-serif;color:#0f172a;line-height:1.6;padding:24px;">
  <h1 style="margin:0 0 8px;font-size:24px;">${escapeHtml(report.title || '性能瓶颈诊断报告')}</h1>
  <p style="margin:0 0 16px;color:#64748b;">报告编号：${escapeHtml(report.reportId)} · 生成时间：${escapeHtml(report.generatedAtDisplay || formatReportTime(report.generatedAt))} · 综合等级：${escapeHtml(overallLevelLabel(report.overallLevel))}</p>
  <div style="padding:14px 16px;background:#eff6ff;border-left:4px solid #2563eb;margin-bottom:18px;">
    <strong>结论</strong>
    <p style="margin:8px 0 0;">${escapeHtml(report.conclusion)}</p>
  </div>
  <table style="width:100%;border-collapse:collapse;margin-bottom:18px;">
    <tr>
      <td style="border:1px solid #dbeafe;padding:10px;text-align:center;"><strong>慢查询</strong><br/>${escapeHtml(summary.slowCount ?? 0)}</td>
      <td style="border:1px solid #dbeafe;padding:10px;text-align:center;"><strong>缓存命中率</strong><br/>${Number(summary.cacheHitRate ?? 0).toFixed(2)}%</td>
      <td style="border:1px solid #dbeafe;padding:10px;text-align:center;"><strong>堆占用</strong><br/>${Number(summary.heapUsedPercent ?? 0).toFixed(2)}%</td>
      <td style="border:1px solid #dbeafe;padding:10px;text-align:center;"><strong>拦截 SQL</strong><br/>${escapeHtml(summary.blockedCount ?? 0)}</td>
    </tr>
  </table>
  ${sections}
  <h2 style="color:#1e3a8a;border-bottom:1px solid #dbeafe;padding-bottom:6px;">优化建议明细</h2>
  ${suggestions || '<p>暂无优化建议</p>'}
  <h2 style="color:#1e3a8a;border-bottom:1px solid #dbeafe;padding-bottom:6px;">慢查询用户 TOP</h2>
  <table style="width:100%;border-collapse:collapse;">
    <thead>
      <tr style="background:#eff6ff;">
        <th style="border:1px solid #dbeafe;padding:8px;">用户</th>
        <th style="border:1px solid #dbeafe;padding:8px;">次数</th>
        <th style="border:1px solid #dbeafe;padding:8px;">均耗时(ms)</th>
        <th style="border:1px solid #dbeafe;padding:8px;">最大(ms)</th>
      </tr>
    </thead>
    <tbody>${topUsers || '<tr><td colspan="4" style="border:1px solid #dbeafe;padding:8px;">暂无数据</td></tr>'}</tbody>
  </table>
</body>
</html>`
}

export async function buildPerfReportPdfBlob(element) {
  if (!element) throw new Error('报告预览尚未渲染完成')
  const pdf = new jsPDF({ orientation: 'p', unit: 'mm', format: 'a4' })
  const pageWidth = pdf.internal.pageSize.getWidth()
  const pageHeight = pdf.internal.pageSize.getHeight()
  const margin = 10
  const contentWidth = pageWidth - margin * 2
  const contentHeight = pageHeight - margin * 2

  const canvas = await html2canvas(element, {
    backgroundColor: '#ffffff',
    scale: Math.max(2, window.devicePixelRatio || 1),
    useCORS: true,
    logging: false
  })

  const imgWidth = contentWidth
  const imgHeight = (canvas.height * imgWidth) / canvas.width
  const imgData = canvas.toDataURL('image/png')
  let offsetY = 0
  let pageIndex = 0

  while (offsetY < imgHeight) {
    if (pageIndex > 0) pdf.addPage()
    pdf.addImage(imgData, 'PNG', margin, margin - offsetY, imgWidth, imgHeight)
    offsetY += contentHeight
    pageIndex += 1
  }

  return pdf.output('blob')
}

export function mountOffscreenReportElement(report, helpers = {}) {
  const html = buildPerfReportWordHtml(report, helpers)
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const wrapper = document.createElement('div')
  wrapper.className = 'perf-report-print-host'
  wrapper.style.cssText = 'position:fixed;left:-12000px;top:0;width:794px;background:#fff;z-index:-1;pointer-events:none;'
  wrapper.appendChild(doc.body.cloneNode(true))
  document.body.appendChild(wrapper)
  return wrapper
}

export function unmountOffscreenReportElement(element) {
  if (element?.parentNode) element.parentNode.removeChild(element)
}

export async function createPerfReportPdfBlob(report, helpers = {}) {
  const element = mountOffscreenReportElement(report, helpers)
  try {
    return await buildPerfReportPdfBlob(element)
  } finally {
    unmountOffscreenReportElement(element)
  }
}

export async function exportPerfReportPdf(element, filename) {
  const blob = await buildPerfReportPdfBlob(element)
  downloadBlob(blob, filename)
}

export async function downloadPerfReportPdf(report, filename, helpers = {}) {
  const blob = await createPerfReportPdfBlob(report, helpers)
  downloadBlob(blob, filename)
}

export function exportPerfReportWord(report, filename, helpers = {}) {
  const html = buildPerfReportWordHtml(report, helpers)
  const blob = new Blob(['\ufeff', html], { type: 'application/msword;charset=utf-8' })
  downloadBlob(blob, filename)
}

export function reportExportFilename(report, ext) {
  const id = String(report?.reportId || 'perf-report').replace(/[^\w-]+/g, '_')
  return `${id}.${ext}`
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
