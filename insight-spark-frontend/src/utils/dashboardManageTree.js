import { currentUser } from '../store/session'

export function currentUserId() {
  return String(currentUser.value?.userId || '').trim()
}

/** 当前登录用户是否为看板创建者（owner，永久绑定） */
export function isBoardOwner(row, userId) {
  const me = String(userId ?? currentUserId()).trim()
  const owner = String(row?.ownerUserId || row?.raw?.ownerUserId || '').trim()
  return Boolean(me && owner && me === owner)
}

/** 发布状态、开放类型仅创建者可决定（管理员代管他人看板时不可代发布/改类型） */
export function canOwnerDecidePublishAndVisibility(row) {
  return isBoardOwner(row)
}

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
    shareToken: board.shareToken,
    raw: board
  }
}

export function buildNavTree(groups, boards, keyword, options = {}) {
  const { includePublicGroup = false, publicBoards = [], includeUnassignedGroup = true } = options
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
  if (includeUnassignedGroup) {
    roots.unshift({
      kind: 'virtual',
      nodeKey: 'unassigned',
      name: '未分组',
      children: unassignedBoards
    })
  } else if (unassignedBoards.length) {
    roots.unshift(...unassignedBoards)
  }

  if (includePublicGroup) {
    const publicNodes = (Array.isArray(publicBoards) ? publicBoards : [])
      .map(toBoardTreeNode)
      .sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'))
    roots.unshift({
      kind: 'virtual',
      nodeKey: 'public',
      name: '公共看板',
      children: publicNodes
    })
  }

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

/** 扁平化分组树，供「移动到」子菜单使用 */
export function flattenGroupOptions(nodes, prefix = '') {
  const items = []
  for (const node of Array.isArray(nodes) ? nodes : []) {
    const name = String(node.name || '').trim()
    if (!name) continue
    const label = prefix ? `${prefix} / ${name}` : name
    items.push({ id: normalizeTreeId(node.id), name: label })
    if (Array.isArray(node.children) && node.children.length) {
      items.push(...flattenGroupOptions(node.children, label))
    }
  }
  return items
}

export function boardCurrentGroupKey(groupId, rootParentId = 0) {
  const gid = normalizeTreeId(groupId)
  return gid == null ? rootParentId : gid
}

export function groupCurrentParentKey(parentId, rootParentId = 0) {
  const pid = normalizeTreeId(parentId)
  return pid == null ? rootParentId : pid
}

/** 收集分组的所有子孙分组 id（不含自身） */
export function collectGroupDescendantIds(groupId, nodes) {
  const descendants = new Set()
  const targetId = Number(groupId)
  if (!Number.isFinite(targetId) || targetId <= 0) return descendants

  function collectAll(list) {
    for (const node of list || []) {
      const id = Number(node.id)
      if (Number.isFinite(id) && id > 0) descendants.add(id)
      if (Array.isArray(node.children) && node.children.length) collectAll(node.children)
    }
  }

  function findAndCollect(list) {
    for (const node of list || []) {
      if (Number(node.id) === targetId) {
        collectAll(node.children)
        return true
      }
      if (findAndCollect(node.children)) return true
    }
    return false
  }

  findAndCollect(nodes)
  return descendants
}

export function isGroupMoveTargetDisabled(group, targetParentId, groupTreeNodes, rootParentId = 0) {
  const groupId = Number(group?.id)
  const target = Number(targetParentId)
  if (!Number.isFinite(groupId) || groupId <= 0) return true
  if (target === groupCurrentParentKey(group?.parentId, rootParentId)) return true
  if (target === groupId) return true
  return collectGroupDescendantIds(groupId, groupTreeNodes).has(target)
}

export function isBoardInGroup(row, targetGroupId, rootParentId = 0) {
  return boardCurrentGroupKey(row?.groupId, rootParentId) === Number(targetGroupId)
}

/** 分组「移动到」可选目标（不含自身、子孙、当前父级） */
export function filterGroupMoveTargets(group, groupOptions, groupTreeNodes, rootParentId = 0) {
  return (Array.isArray(groupOptions) ? groupOptions : []).filter(
    (g) => !isGroupMoveTargetDisabled(group, g.id, groupTreeNodes, rootParentId)
  )
}

export function canMoveGroupToRoot(group, groupTreeNodes, rootParentId = 0) {
  return !isGroupMoveTargetDisabled(group, rootParentId, groupTreeNodes, rootParentId)
}

export function hasGroupMoveTargets(group, groupOptions, groupTreeNodes, rootParentId = 0) {
  if (canMoveGroupToRoot(group, groupTreeNodes, rootParentId)) return true
  return filterGroupMoveTargets(group, groupOptions, groupTreeNodes, rootParentId).length > 0
}

/** 看板「移动到」可选目标（不含当前所在分组） */
export function filterBoardMoveTargets(row, groupOptions, rootParentId = 0) {
  return (Array.isArray(groupOptions) ? groupOptions : []).filter(
    (g) => !isBoardInGroup(row, g.id, rootParentId)
  )
}

export function canMoveBoardToRoot(row, rootParentId = 0) {
  return !isBoardInGroup(row, rootParentId)
}

export function hasBoardMoveTargets(row, groupOptions, rootParentId = 0) {
  if (canMoveBoardToRoot(row, rootParentId)) return true
  return filterBoardMoveTargets(row, groupOptions, rootParentId).length > 0
}

/** 开放类型展示文案 */
export function boardVisibilityLabel(isPublic) {
  return boardIsPublic({ isPublic }) ? '公共' : '私密'
}

/** 解析看板 isPublic（兼容数字/字符串） */
export function boardIsPublic(row) {
  const v = row?.isPublic ?? row?.raw?.isPublic
  if (v === true || v === 1) return true
  if (v === false || v === 0) return false
  const s = String(v ?? '').trim().toLowerCase()
  if (s === '1' || s === 'true') return true
  if (s === '0' || s === 'false' || s === '') return false
  return Boolean(v)
}

/** 当前用户是否为另存/复制生成该看板的人 */
export function isBoardSaveAsUser(row, userId) {
  const me = String(userId ?? currentUserId()).trim()
  const saveAs = String(row?.saveAsUserId || '').trim()
  return Boolean(me && saveAs && me === saveAs)
}

/** 所有者或另存人可直接编辑，无需二次另存 */
export function canDirectEditBoard(row, userId) {
  return isBoardOwner(row, userId) || isBoardSaveAsUser(row, userId)
}

/** 仅所有者可查看分组归属；他人看公共看板时隐藏 */
export function canViewBoardGroup(row, userId) {
  return canDirectEditBoard(row, userId)
}

export function boardGroupDisplay(row, userId) {
  if (row?.groupIsPrivate || !canViewBoardGroup(row, userId)) return '—'
  const path = String(row?.groupPath || '').trim()
  if (path) return path
  const name = String(row?.groupName || '').trim()
  if (name) return name
  const gid = row?.groupId
  if (gid == null || gid === '' || Number(gid) <= 0) return '根目录'
  return '根目录'
}

/** 预览：创建者始终可预览；他人须已发布 */
export function canPreviewBoard(row) {
  if (isBoardOwner(row)) return true
  return isBoardPublished(row)
}

/** 已发布公共看板：他人须另存为后编辑；所有者与另存人可直接改 */
export function isPublicSaveAsDesign(row) {
  if (!boardIsPublic(row) || !isBoardPublished(row)) return false
  return !canDirectEditBoard(row)
}

/** 设计：所有者可设计；他人仅可设计已发布公共看板（只读须另存） */
export function canDesignBoard(row) {
  if (canDirectEditBoard(row)) return true
  return boardIsPublic(row) && isBoardPublished(row)
}

/** 表格「更多」：仅创建者自己的看板（他人须先另存为私密看板后才在自己列表中出现） */
export function canShowBoardMoreActions(row) {
  return isBoardOwner(row)
}

export function userDisplayName(row, prefix = 'owner') {
  const user = String(row?.[`${prefix}Username`] || '').trim()
  const uid = String(row?.[`${prefix}UserId`] || '').trim()
  if (user) return user
  return uid || '—'
}

/** 原作者（首创者），永久不变；副本继承来源看板的 author */
export function authorDisplay(row) {
  const author = userDisplayName(row, 'author')
  if (author !== '—') return author
  return userDisplayName(row, 'owner')
}

/** 另存/复制生成当前看板的人；手动新建的原生看板无另存人 */
export function saveAsDisplay(row) {
  const saveAsUserId = String(row?.saveAsUserId || '').trim()
  if (!saveAsUserId) return '—'
  return userDisplayName(row, 'saveAs')
}

/** 执行发布的人；未发布时显示 — */
export function publisherDisplay(row) {
  if (!isBoardPublished(row)) return '—'
  const publisherUserId = String(row?.publisherUserId || '').trim()
  if (!publisherUserId) return '—'
  return userDisplayName(row, 'publisher')
}

export function boardStatusTag(data) {
  const s = String(data?.status || data?.raw?.status || 'ACTIVE').toUpperCase()
  if (s === 'ACTIVE') return '已发布'
  if (s === 'DISABLED') return '待发布'
  return s
}

export function boardStatusTagType(data) {
  const s = String(data?.status || data?.raw?.status || 'ACTIVE').toUpperCase()
  if (s === 'ACTIVE') return 'success'
  if (s === 'DISABLED') return 'warning'
  return 'info'
}

export function isBoardPublished(data) {
  return String(data?.status || data?.raw?.status || 'ACTIVE').toUpperCase() === 'ACTIVE'
}

/** 可分发给团队：已发布公共看板，或已发布且本人创建/另存的私密看板 */
export function canDistributeBoard(row, userId) {
  if (!isBoardPublished(row)) return false
  if (boardIsPublic(row)) return true
  return isBoardOwner(row, userId) || isBoardSaveAsUser(row, userId)
}
