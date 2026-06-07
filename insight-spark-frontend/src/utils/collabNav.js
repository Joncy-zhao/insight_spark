export const COLLAB_NAV_KEY = 'collab_nav'

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
