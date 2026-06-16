export const COLLAB_NAV_KEY = 'collab_nav'
export const COLLAB_ROOM_KEY = 'collab_room'

export function setCollabNav(payload) {
  sessionStorage.setItem(COLLAB_NAV_KEY, JSON.stringify(payload))
}

export function consumeCollabNav() {
  const raw = sessionStorage.getItem(COLLAB_NAV_KEY)
  sessionStorage.removeItem(COLLAB_NAV_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** 进入协作房间（独立页面） */
export function openCollabRoom(payload) {
  sessionStorage.setItem(
    COLLAB_ROOM_KEY,
    JSON.stringify({
      returnModule: 'collaboration',
      returnTab: 'workbench',
      ...payload
    })
  )
}

export function peekCollabRoom() {
  const raw = sessionStorage.getItem(COLLAB_ROOM_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function consumeCollabRoom() {
  const raw = sessionStorage.getItem(COLLAB_ROOM_KEY)
  sessionStorage.removeItem(COLLAB_ROOM_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function clearCollabRoom() {
  sessionStorage.removeItem(COLLAB_ROOM_KEY)
}
