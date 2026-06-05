export function normalizeTreeId(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function toBoardTreeNode(board) {
  return {
    kind: 'board',
    nodeKey: `b-${board.id}`,
    id: board.id,
    name: board.name || `看板 #${board.id}`,
    status: board.status,
    isPublic: board.isPublic,
    groupId: board.groupId,
    raw: board
  }
}

export function buildNavTree(groups, boards, keyword) {
  const boardsByGroupId = new Map()
  const unassignedBoards = []
  for (const board of Array.isArray(boards) ? boards : []) {
    const gid = Number(board.groupId)
    const node = toBoardTreeNode(board)
    if (Number.isFinite(gid) && gid > 0) {
      if (!boardsByGroupId.has(gid)) boardsByGroupId.set(gid, [])
      boardsByGroupId.get(gid).push(node)
    } else {
      unassignedBoards.push(node)
    }
  }

  function mapGroup(node) {
    const childGroups = (node.children || []).map(mapGroup)
    const childBoards = boardsByGroupId.get(Number(node.id)) || []
    childBoards.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
    return {
      kind: 'group',
      nodeKey: `g-${node.id}`,
      id: node.id,
      name: node.name,
      parentId: node.parentId,
      children: [...childGroups, ...childBoards]
    }
  }

  const roots = (Array.isArray(groups) ? groups : []).map(mapGroup)
  unassignedBoards.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
  roots.unshift({
    kind: 'virtual',
    nodeKey: 'unassigned',
    name: '未分组',
    children: unassignedBoards
  })

  const kw = String(keyword || '').trim().toLowerCase()
  if (!kw) return roots
  return filterNavTree(roots, kw)
}

export function filterNavTree(nodes, keyword) {
  const result = []
  for (const node of nodes) {
    const children = Array.isArray(node.children) ? filterNavTree(node.children, keyword) : []
    const selfMatch = String(node.name || '').toLowerCase().includes(keyword)
    if (selfMatch || children.length) {
      result.push({
        ...node,
        children: selfMatch ? node.children || [] : children
      })
    }
  }
  return result
}

export function decorateGroupNodesOnly(nodes) {
  if (!Array.isArray(nodes)) return []
  return nodes.map((node) => ({
    ...node,
    id: normalizeTreeId(node.id) ?? node.id,
    parentId: normalizeTreeId(node.parentId) ?? node.parentId ?? null,
    children: decorateGroupNodesOnly(node.children)
  }))
}

export function boardStatusTag(data) {
  const s = String(data?.status || 'ACTIVE').toUpperCase()
  if (s === 'ACTIVE') return '已发布'
  if (s === 'DISABLED') return '已停用'
  return s
}

export function boardStatusTagType(data) {
  const s = String(data?.status || 'ACTIVE').toUpperCase()
  return s === 'ACTIVE' ? 'success' : 'info'
}
