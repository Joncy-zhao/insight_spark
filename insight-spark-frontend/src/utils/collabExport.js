import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

async function captureElement(element, options = {}) {
  if (!element) throw new Error('未找到可导出的画布区域')
  await new Promise((r) => requestAnimationFrame(r))
  return html2canvas(element, {
    scale: 2,
    useCORS: true,
    allowTaint: true,
    backgroundColor: options.backgroundColor || '#ffffff',
    logging: false,
    ...options
  })
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** 导出长图 PNG（含批注侧栏可选） */
export async function exportCollabLongPng(element, filename) {
  const canvas = await captureElement(element)
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new Error('生成图片失败'))
        return
      }
      downloadBlob(blob, filename.endsWith('.png') ? filename : `${filename}.png`)
      resolve()
    }, 'image/png')
  })
}

/**
 * 导出 PPT 风格 PDF：将长画布按 A4 宽度分页
 */
export async function exportCollabPptPdf(element, filename, { title = '协作汇报' } = {}) {
  const canvas = await captureElement(element)
  const imgData = canvas.toDataURL('image/png')
  const pdf = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' })
  const pageWidth = pdf.internal.pageSize.getWidth()
  const pageHeight = pdf.internal.pageSize.getHeight()
  const margin = 10
  const contentWidth = pageWidth - margin * 2

  pdf.setFontSize(14)
  pdf.text(title, margin, margin + 4)

  const imgWidth = contentWidth
  const imgHeight = (canvas.height * imgWidth) / canvas.width
  let heightLeft = imgHeight
  let position = margin + 10

  pdf.addImage(imgData, 'PNG', margin, position, imgWidth, imgHeight)
  heightLeft -= pageHeight - position - margin

  while (heightLeft > 0) {
    pdf.addPage()
    const offsetY = position - (imgHeight - heightLeft)
    pdf.addImage(imgData, 'PNG', margin, offsetY, imgWidth, imgHeight)
    heightLeft -= pageHeight - margin * 2
  }

  pdf.save(filename.endsWith('.pdf') ? filename : `${filename}.pdf`)
}
