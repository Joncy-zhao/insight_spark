import { onBeforeUnmount, ref, watch } from 'vue'

import { collabWebSocketUrl } from '../api/collab'



export function useCollabWebSocket({

  targetType,

  targetId,

  onCommentCreated,

  onCommentDeleted,

  onAnnotationCreated,

  onAnnotationDeleted

}) {

  const connected = ref(false)

  let socket = null

  let reconnectTimer = null



  function disconnect() {

    if (reconnectTimer) {

      clearTimeout(reconnectTimer)

      reconnectTimer = null

    }

    if (socket) {

      socket.onopen = null

      socket.onmessage = null

      socket.onclose = null

      socket.onerror = null

      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {

        socket.close()

      }

      socket = null

    }

    connected.value = false

  }



  function joinRoom() {

    if (!socket || socket.readyState !== WebSocket.OPEN) return

    socket.send(JSON.stringify({

      type: 'JOIN',

      targetType: targetType.value,

      targetId: targetId.value

    }))

  }



  function connect() {

    disconnect()

    if (!targetType.value || !targetId.value) return

    const token = localStorage.getItem('token')

    if (!token) return



    socket = new WebSocket(collabWebSocketUrl())

    socket.onopen = () => {

      connected.value = true

      joinRoom()

    }

    socket.onmessage = (evt) => {

      try {

        const msg = JSON.parse(evt.data)

        if (msg.type === 'COMMENT_CREATED' && msg.payload) {

          onCommentCreated?.(msg.payload)

        } else if (msg.type === 'COMMENT_DELETED' && msg.payload?.id != null) {

          onCommentDeleted?.(msg.payload.id)

        } else if (msg.type === 'ANNOTATION_CREATED' && msg.payload) {

          onAnnotationCreated?.(msg.payload)

        } else if (msg.type === 'ANNOTATION_DELETED' && msg.payload?.id != null) {

          onAnnotationDeleted?.(msg.payload.id)

        }

      } catch {

        // ignore malformed frames

      }

    }

    socket.onclose = () => {

      connected.value = false

      reconnectTimer = setTimeout(connect, 3000)

    }

    socket.onerror = () => {

      connected.value = false

    }

  }



  watch([targetType, targetId], () => {

    if (targetType.value && targetId.value) {

      if (socket && socket.readyState === WebSocket.OPEN) {

        joinRoom()

      } else {

        connect()

      }

    } else {

      disconnect()

    }

  })



  onBeforeUnmount(disconnect)



  return { connected, connect, disconnect }

}

