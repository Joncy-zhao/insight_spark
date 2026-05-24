import axios from 'axios'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { API_BASE } from '../api/http'

export const voiceLocaleOptions = [
  { label: '普通话（zh-CN）', value: 'zh-CN' },
  { label: '粤语 / 港澳口音（zh-HK）', value: 'zh-HK' },
  { label: '台湾普通话（zh-TW）', value: 'zh-TW' },
  { label: '英语（en-US）', value: 'en-US' }
]

export const voiceGenderOptions = [
  { label: '男声', value: 'male' },
  { label: '女声', value: 'female' }
]

const STORAGE_KEY = 'insight-spark.voice-settings'
const VOICE_HISTORY_KEY = 'insight-spark.voice-history'
const SPEECH_CACHE_LIMIT = 16
const SPEECH_PRELOAD_LIMIT = 8
const VOICE_HISTORY_LIMIT = 12
const DEFAULT_SAMPLE_RATE = 24000
const DEFAULT_RECORDING_MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/mp4',
  'audio/ogg;codecs=opus',
  'audio/ogg'
]
const DEFAULT_SETTINGS = {
  recognitionLocale: 'zh-CN',
  voiceLocale: 'zh-CN',
  selectedVoiceGender: 'female',
  speechRate: 1,
  speechVolume: 0.85,
  autoSpeakConclusion: false,
  autoSendAfterRecognize: false
}
const STREAM_AUDIO_MARKER = '__streamObjectUrl'

const isBrowser = () => typeof window !== 'undefined'

const getRecognitionCtor = () => {
  if (!isBrowser()) return null
  return window.SpeechRecognition || window.webkitSpeechRecognition || null
}

const clamp = (value, min, max) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return min
  return Math.min(max, Math.max(min, num))
}

const safeParse = (value, fallback) => {
  try {
    return JSON.parse(value)
  } catch {
    return fallback
  }
}

const normalizeGender = (value) => {
  const text = String(value || '').trim().toLowerCase()
  if (text === 'male' || text === 'man' || text === 'm' || text === '男' || text.includes('男')) {
    return 'male'
  }
  return 'female'
}

const mapRecognitionError = (code) => {
  const text = String(code || '').toLowerCase()
  if (text === 'not-allowed' || text === 'service-not-allowed') return '麦克风权限被拒绝'
  if (text === 'audio-capture') return '未检测到可用麦克风'
  if (text === 'network') return '语音识别网络异常'
  if (text === 'no-speech') return '未识别到有效语音'
  if (text === 'aborted') return '语音识别已中止'
  return '语音识别失败'
}

const readToken = () => {
  if (!isBrowser()) return ''
  const directToken = localStorage.getItem('token')
  if (directToken) return directToken
  try {
    return JSON.parse(localStorage.getItem('insight_auth') || 'null')?.token || ''
  } catch {
    return ''
  }
}

const trimMapSize = (map, maxSize, onEvict) => {
  while (map.size > maxSize) {
    const oldestKey = map.keys().next().value
    const oldestValue = map.get(oldestKey)
    map.delete(oldestKey)
    onEvict?.(oldestValue, oldestKey)
  }
}

const createPcmWavBlob = (chunks, sampleRate = DEFAULT_SAMPLE_RATE) => {
  const totalLength = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0)
  const wavBuffer = new ArrayBuffer(44 + totalLength)
  const view = new DataView(wavBuffer)
  const bytes = new Uint8Array(wavBuffer)

  const writeString = (offset, value) => {
    for (let index = 0; index < value.length; index += 1) {
      view.setUint8(offset + index, value.charCodeAt(index))
    }
  }

  writeString(0, 'RIFF')
  view.setUint32(4, 36 + totalLength, true)
  writeString(8, 'WAVE')
  writeString(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeString(36, 'data')
  view.setUint32(40, totalLength, true)

  let offset = 44
  for (const chunk of chunks) {
    bytes.set(new Uint8Array(chunk), offset)
    offset += chunk.byteLength
  }

  return new Blob([wavBuffer], { type: 'audio/wav' })
}

const createWavBlobFromAudioBuffer = (audioBuffer) => {
  const sampleRate = audioBuffer.sampleRate || DEFAULT_SAMPLE_RATE
  const channelCount = audioBuffer.numberOfChannels || 1
  const frameLength = audioBuffer.length || 0
  const monoData = new Float32Array(frameLength)

  for (let channelIndex = 0; channelIndex < channelCount; channelIndex += 1) {
    const channelData = audioBuffer.getChannelData(channelIndex)
    for (let frameIndex = 0; frameIndex < frameLength; frameIndex += 1) {
      monoData[frameIndex] += channelData[frameIndex] || 0
    }
  }

  for (let frameIndex = 0; frameIndex < frameLength; frameIndex += 1) {
    monoData[frameIndex] /= channelCount
  }

  const pcmBytes = new Uint8Array(frameLength * 2)
  for (let frameIndex = 0; frameIndex < frameLength; frameIndex += 1) {
    const sample = Math.max(-1, Math.min(1, monoData[frameIndex] || 0))
    const int16 = sample < 0 ? sample * 0x8000 : sample * 0x7fff
    pcmBytes[frameIndex * 2] = int16 & 0xff
    pcmBytes[frameIndex * 2 + 1] = (int16 >> 8) & 0xff
  }

  return createPcmWavBlob([pcmBytes.buffer], sampleRate)
}

export function useVoiceInteraction() {
  const recognitionLocale = ref(DEFAULT_SETTINGS.recognitionLocale)
  const voiceLocale = ref(DEFAULT_SETTINGS.voiceLocale)
  const selectedVoiceGender = ref(DEFAULT_SETTINGS.selectedVoiceGender)
  const speechRate = ref(DEFAULT_SETTINGS.speechRate)
  const speechVolume = ref(DEFAULT_SETTINGS.speechVolume)
  const autoSpeakConclusion = ref(DEFAULT_SETTINGS.autoSpeakConclusion)
  const autoSendAfterRecognize = ref(DEFAULT_SETTINGS.autoSendAfterRecognize)
  const listening = ref(false)
  const speaking = ref(false)
  const speechPaused = ref(false)
  const recognitionError = ref('')
  const interimTranscript = ref('')
  const finalTranscript = ref('')
  const voiceHistory = ref([])

  let recognitionInstance = null
  let mediaRecorder = null
  let mediaStream = null
  let recordingChunks = []
  let recordingPreviewTimer = null
  let recordingPreviewInFlight = false
  let recordingPreviewChunkCount = 0
  let recordingSessionToken = 0
  let currentAudio = null
  let currentAudioUrl = ''
  let currentAudioContext = null
  let savePreferenceTimer = null
  let speakToken = 0
  const speechResponseCache = new Map()
  const inflightSpeechRequests = new Map()
  const preloadedAudioCache = new Map()

  const speechSupported = computed(() => isBrowser() && typeof window.Audio !== 'undefined' && typeof window.fetch !== 'undefined')
  const browserRecognitionSupported = computed(() => Boolean(getRecognitionCtor()))
  const mediaRecordingSupported = computed(() => isBrowser() && typeof window.MediaRecorder !== 'undefined' && typeof navigator !== 'undefined' && Boolean(navigator.mediaDevices?.getUserMedia))
  const recognitionSupported = computed(() => browserRecognitionSupported.value || mediaRecordingSupported.value)
  const voiceCapabilityText = computed(() => {
    if (recognitionSupported.value && speechSupported.value) return '语音识别与云端播报可用'
    if (recognitionSupported.value) return '仅支持语音识别'
    if (speechSupported.value) return '仅支持云端语音播报'
    return '当前浏览器不支持语音能力'
  })
  const voiceStatusText = computed(() => {
    if (listening.value) return '正在听写'
    if (speaking.value && speechPaused.value) return '播报已暂停'
    if (speaking.value) return '正在播报'
    return voiceCapabilityText.value
  })

  const persistSettings = () => {
    if (!isBrowser()) return
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
        recognitionLocale: recognitionLocale.value,
        voiceLocale: voiceLocale.value,
        selectedVoiceGender: selectedVoiceGender.value,
        speechRate: speechRate.value,
        speechVolume: speechVolume.value,
        autoSpeakConclusion: autoSpeakConclusion.value,
        autoSendAfterRecognize: autoSendAfterRecognize.value
      }))
    } catch {
      // ignore storage failures
    }
  }

  const persistVoiceHistory = () => {
    if (!isBrowser()) return
    try {
      window.localStorage.setItem(VOICE_HISTORY_KEY, JSON.stringify(voiceHistory.value.slice(0, VOICE_HISTORY_LIMIT)))
    } catch {
      // ignore storage failures
    }
  }

  const restoreSettings = () => {
    if (!isBrowser()) return
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed = safeParse(raw, null)
    if (!parsed || typeof parsed !== 'object') return
    if (typeof parsed.recognitionLocale === 'string') recognitionLocale.value = parsed.recognitionLocale
    if (typeof parsed.voiceLocale === 'string') voiceLocale.value = parsed.voiceLocale
    if (typeof parsed.selectedVoiceGender === 'string') selectedVoiceGender.value = normalizeGender(parsed.selectedVoiceGender)
    if (parsed.speechRate !== undefined) speechRate.value = clamp(parsed.speechRate, 0.6, 1.4)
    if (parsed.speechVolume !== undefined) speechVolume.value = clamp(parsed.speechVolume, 0, 1)
    if (parsed.autoSpeakConclusion !== undefined) autoSpeakConclusion.value = Boolean(parsed.autoSpeakConclusion)
    if (parsed.autoSendAfterRecognize !== undefined) autoSendAfterRecognize.value = Boolean(parsed.autoSendAfterRecognize)
  }

  const restoreVoiceHistory = () => {
    if (!isBrowser()) return
    const raw = window.localStorage.getItem(VOICE_HISTORY_KEY)
    if (!raw) return
    const parsed = safeParse(raw, [])
    if (!Array.isArray(parsed)) return
    voiceHistory.value = parsed
      .map((item) => {
        if (!item || typeof item !== 'object') return null
        const text = String(item.text || '').trim()
        if (!text) return null
        return {
          text,
          locale: String(item.locale || recognitionLocale.value || 'zh-CN').trim() || 'zh-CN',
          createdAt: String(item.createdAt || new Date().toISOString())
        }
      })
      .filter(Boolean)
      .slice(0, VOICE_HISTORY_LIMIT)
  }

  const clearTranscript = () => {
    recognitionError.value = ''
    interimTranscript.value = ''
    finalTranscript.value = ''
  }

  const recordVoiceHistory = (text, locale = recognitionLocale.value) => {
    const content = String(text || '').trim()
    if (!content) return null
    const entry = {
      text: content,
      locale: String(locale || recognitionLocale.value || 'zh-CN').trim() || 'zh-CN',
      createdAt: new Date().toISOString()
    }
    voiceHistory.value = [entry, ...voiceHistory.value.filter((item) => item?.text !== content)].slice(0, VOICE_HISTORY_LIMIT)
    persistVoiceHistory()
    return entry
  }

  const clearVoiceHistory = () => {
    voiceHistory.value = []
    if (!isBrowser()) return
    try {
      window.localStorage.removeItem(VOICE_HISTORY_KEY)
    } catch {
      // ignore storage failures
    }
  }

  const stopMediaTracks = () => {
    if (mediaStream?.getTracks) {
      mediaStream.getTracks().forEach((track) => {
        try {
          track.stop()
        } catch {
          // ignore
        }
      })
    }
    mediaStream = null
  }

  const cleanupRecorder = () => {
    mediaRecorder = null
    recordingChunks = []
    recordingPreviewChunkCount = 0
    recordingPreviewInFlight = false
    if (recordingPreviewTimer) {
      window.clearInterval(recordingPreviewTimer)
      recordingPreviewTimer = null
    }
    stopMediaTracks()
  }

  const resolveRecorderMimeType = () => {
    if (!isBrowser() || typeof window.MediaRecorder === 'undefined') return ''
    for (const mimeType of DEFAULT_RECORDING_MIME_TYPES) {
      try {
        if (window.MediaRecorder.isTypeSupported?.(mimeType)) return mimeType
      } catch {
        // ignore
      }
    }
    return ''
  }

  const blobToDataUrl = (blob) => new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onloadend = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('语音录音读取失败'))
    reader.readAsDataURL(blob)
  })

  const decodeRecordedBlobToWav = async (blob) => {
    const AudioContextCtor = window.AudioContext || window.webkitAudioContext
    if (!AudioContextCtor) {
      throw new Error('当前浏览器不支持音频解码')
    }

    const arrayBuffer = await blob.arrayBuffer()
    const audioContext = new AudioContextCtor()
    try {
      const audioBuffer = await audioContext.decodeAudioData(arrayBuffer.slice(0))
      if (!audioBuffer?.length) {
        throw new Error('音频解码结果为空')
      }
      return createWavBlobFromAudioBuffer(audioBuffer)
    } finally {
      try {
        await audioContext.close?.()
      } catch {
        // ignore close failures
      }
    }
  }

  const fetchCloudTranscript = async (audioDataUrl, locale) => {
    const token = readToken()
    const response = await axios.post(
      `${API_BASE}/api/voice/asr`,
      {
        audioBase64: audioDataUrl,
        locale
      },
      {
        headers: {
          Authorization: `Bearer ${token}`
        }
      }
    )
    const payload = response?.data?.data ?? response?.data
    const transcript = String(payload?.text || '').trim()
    if (!transcript) {
      throw new Error('云端语音识别未返回文本')
    }
    return transcript
  }

  const resolveSpeechRequest = (text, options = {}) => ({
    text: String(text || '').trim(),
    voiceGender: normalizeGender(options.voiceGender || selectedVoiceGender.value),
    locale: String(options.lang || recognitionLocale.value || 'zh-CN').trim() || 'zh-CN',
    voiceLocale: String(options.voiceLocale || voiceLocale.value || recognitionLocale.value || 'zh-CN').trim() || 'zh-CN',
    rate: clamp(options.rate ?? speechRate.value, 0.6, 1.4),
    volume: clamp(options.volume ?? speechVolume.value, 0, 1)
  })

  const buildSpeechCacheKey = (requestPayload) => JSON.stringify({
    text: requestPayload.text,
    voiceGender: requestPayload.voiceGender,
    locale: requestPayload.locale.toLowerCase(),
    voiceLocale: requestPayload.voiceLocale.toLowerCase()
  })

  const isCachedStreamAudioUrl = (audioUrl) => {
    if (!audioUrl) return false
    for (const entry of speechResponseCache.values()) {
      if (entry?.[STREAM_AUDIO_MARKER] && entry.audioUrl === audioUrl) {
        return true
      }
    }
    return false
  }

  const touchSpeechCacheEntry = (cacheKey, payload) => {
    if (speechResponseCache.has(cacheKey)) {
      const previous = speechResponseCache.get(cacheKey)
      if (previous?.[STREAM_AUDIO_MARKER] && previous?.audioUrl && isBrowser() && typeof window.URL?.revokeObjectURL === 'function') {
        window.URL.revokeObjectURL(previous.audioUrl)
      }
      speechResponseCache.delete(cacheKey)
    }
    speechResponseCache.set(cacheKey, payload)
    trimMapSize(speechResponseCache, SPEECH_CACHE_LIMIT, (entry) => {
      if (entry?.[STREAM_AUDIO_MARKER] && entry?.audioUrl && isBrowser() && typeof window.URL?.revokeObjectURL === 'function') {
        window.URL.revokeObjectURL(entry.audioUrl)
      }
    })
  }

  const getCachedSpeechResponse = (cacheKey) => {
    const cached = speechResponseCache.get(cacheKey)
    if (!cached) return null
    speechResponseCache.delete(cacheKey)
    speechResponseCache.set(cacheKey, cached)
    return cached
  }

  const disposeAudioInstance = (audio) => {
    if (!audio) return
    try {
      audio.pause()
      audio.src = ''
      audio.load?.()
    } catch {
      // ignore cleanup failures
    }
  }

  const disposeAudioContext = () => {
    if (!currentAudioContext) return
    try {
      currentAudioContext.close?.()
    } catch {
      // ignore close failures
    } finally {
      currentAudioContext = null
    }
  }

  const warmAudioUrl = (audioUrl) => {
    const normalizedUrl = String(audioUrl || '').trim()
    if (!speechSupported.value || !normalizedUrl) return

    const cachedAudio = preloadedAudioCache.get(normalizedUrl)
    if (cachedAudio) {
      preloadedAudioCache.delete(normalizedUrl)
      preloadedAudioCache.set(normalizedUrl, cachedAudio)
      return
    }

    try {
      const audio = new Audio()
      audio.preload = 'auto'
      audio.src = normalizedUrl
      audio.load()
      preloadedAudioCache.set(normalizedUrl, audio)
      trimMapSize(preloadedAudioCache, SPEECH_PRELOAD_LIMIT, disposeAudioInstance)
    } catch {
      // ignore preload failures
    }
  }

  const clearPreloadedAudio = () => {
    for (const audio of preloadedAudioCache.values()) {
      disposeAudioInstance(audio)
    }
    preloadedAudioCache.clear()
  }

  const cleanupAudio = () => {
    if (currentAudio) {
      currentAudio.pause()
      currentAudio.src = ''
      currentAudio = null
    }
    if (currentAudioUrl && !isCachedStreamAudioUrl(currentAudioUrl) && isBrowser() && typeof window.URL?.revokeObjectURL === 'function') {
      window.URL.revokeObjectURL(currentAudioUrl)
    }
    currentAudioUrl = ''
    speechPaused.value = false
    disposeAudioContext()
  }

  const stopSpeaking = () => {
    speakToken += 1
    cleanupAudio()
    speaking.value = false
    speechPaused.value = false
  }

  const pauseSpeaking = async () => {
    if (!speaking.value || speechPaused.value) return
    if (currentAudio) {
      currentAudio.pause()
      speechPaused.value = true
      return
    }
    if (currentAudioContext?.state === 'running') {
      await currentAudioContext.suspend?.()
      speechPaused.value = true
    }
  }

  const resumeSpeaking = async () => {
    if (!speaking.value || !speechPaused.value) return
    if (currentAudio) {
      await currentAudio.play()
      speechPaused.value = false
      return
    }
    if (currentAudioContext?.state === 'suspended') {
      await currentAudioContext.resume?.()
      speechPaused.value = false
    }
  }

  const stopListening = () => {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try {
        mediaRecorder.stop()
      } catch {
        cleanupRecorder()
      } finally {
        listening.value = false
      }
      return
    }
    if (!recognitionInstance) return
    try {
      recognitionInstance.stop()
    } catch {
      try {
        recognitionInstance.abort()
      } catch {
        // ignore
      }
    } finally {
      recognitionInstance = null
      listening.value = false
    }
  }

  const startBrowserListening = (handlers = {}) => {
    const RecognitionCtor = getRecognitionCtor()
    if (!RecognitionCtor) {
      const err = new Error('当前浏览器不支持语音识别')
      recognitionError.value = err.message
      throw err
    }
    if (listening.value) return

    stopSpeaking()
    clearTranscript()

    const recognition = new RecognitionCtor()
    recognitionInstance = recognition
    recognition.lang = recognitionLocale.value
    recognition.continuous = false
    recognition.interimResults = true
    recognition.maxAlternatives = 1

    recognition.onstart = () => {
      listening.value = true
      recognitionError.value = ''
      handlers.onStart?.()
    }

    recognition.onresult = (event) => {
      let committedText = ''
      let interimText = ''
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index]
        const transcript = String(result?.[0]?.transcript || '').trim()
        if (!transcript) continue
        if (result.isFinal) {
          committedText += `${transcript} `
        } else {
          interimText += `${transcript} `
        }
      }
      if (committedText) {
        finalTranscript.value = `${finalTranscript.value} ${committedText}`.trim()
      }
      interimTranscript.value = interimText.trim()
      handlers.onPartial?.(finalTranscript.value, interimTranscript.value, event)
    }

    recognition.onerror = (event) => {
      const message = mapRecognitionError(event?.error)
      recognitionError.value = message
      handlers.onError?.(message, event)
    }

    recognition.onend = () => {
      recognitionInstance = null
      listening.value = false
      const committed = String(finalTranscript.value || '').trim()
      handlers.onEnd?.(committed)
      if (committed) {
        handlers.onFinal?.(committed)
        recordVoiceHistory(committed, recognitionLocale.value)
      }
    }

    try {
      recognition.start()
    } catch (error) {
      recognitionInstance = null
      listening.value = false
      recognitionError.value = error?.message || '语音识别启动失败'
      throw error
    }
  }

  const startCloudListening = async (handlers = {}) => {
    if (!mediaRecordingSupported.value) {
      throw new Error('当前浏览器不支持录音采集')
    }
    if (listening.value) return

    stopSpeaking()
    clearTranscript()
    recognitionError.value = ''
    const sessionToken = ++recordingSessionToken
    let finalizing = false

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaStream = stream
    recordingChunks = []

    const mimeType = resolveRecorderMimeType()
    const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream)
    mediaRecorder = recorder

    recorder.ondataavailable = (event) => {
      if (event?.data?.size) {
        recordingChunks.push(event.data)
      }
    }

    recorder.onerror = (event) => {
      const message = event?.error?.message || '语音录音失败'
      recognitionError.value = message
      handlers.onError?.(message, event)
      cleanupRecorder()
      listening.value = false
    }

    recorder.onstart = () => {
      listening.value = true
      finalTranscript.value = ''
      interimTranscript.value = ''
      handlers.onStart?.()
    }

    const runPreviewRecognition = async () => {
      if (finalizing || !listening.value) return
      if (recordingPreviewInFlight) return
      if (recordingChunks.length < 2 || recordingChunks.length === recordingPreviewChunkCount) return
      if (sessionToken !== recordingSessionToken) return

      recordingPreviewInFlight = true
      recordingPreviewChunkCount = recordingChunks.length

      try {
        const blob = new Blob(recordingChunks, { type: recorder.mimeType || 'audio/webm' })
        if (!blob.size) return
        let previewBlob = blob
        try {
          previewBlob = await decodeRecordedBlobToWav(blob)
        } catch {
          previewBlob = blob
        }
        const previewText = await fetchCloudTranscript(await blobToDataUrl(previewBlob), recognitionLocale.value)
        if (finalizing || sessionToken !== recordingSessionToken || !listening.value) return
        finalTranscript.value = previewText
        interimTranscript.value = ''
        handlers.onPartial?.(previewText, '', null)
      } catch {
        // ignore preview failures and keep the recording session alive
      } finally {
        recordingPreviewInFlight = false
      }
    }

    recordingPreviewTimer = window.setInterval(() => {
      void runPreviewRecognition()
    }, 1800)

    recorder.onstop = async () => {
      finalizing = true
      listening.value = false
      try {
        const blob = new Blob(recordingChunks, { type: recorder.mimeType || 'audio/webm' })
        if (!blob.size) {
          throw new Error('未录制到有效语音')
        }
        let uploadBlob = blob
        try {
          uploadBlob = await decodeRecordedBlobToWav(blob)
        } catch {
          uploadBlob = blob
        }
        const dataUrl = await blobToDataUrl(uploadBlob)
        const transcript = await fetchCloudTranscript(dataUrl, recognitionLocale.value)
        if (sessionToken !== recordingSessionToken) return
        finalTranscript.value = transcript
        interimTranscript.value = ''
        handlers.onPartial?.(transcript, '', null)
        handlers.onEnd?.(transcript)
        handlers.onFinal?.(transcript)
        recordVoiceHistory(transcript, recognitionLocale.value)
      } catch (error) {
        const message = error?.message || '云端语音识别失败'
        recognitionError.value = message
        handlers.onError?.(message, error)
        handlers.onEnd?.('')
      } finally {
        cleanupRecorder()
        finalizing = false
      }
    }

    recorder.start(1000)
  }

  const startListening = (handlers = {}) => {
    if (mediaRecordingSupported.value) {
      return startCloudListening(handlers).catch((error) => {
        cleanupRecorder()
        if (!browserRecognitionSupported.value) {
          recognitionError.value = error?.message || '语音识别启动失败'
          throw error
        }
        return startBrowserListening(handlers)
      })
    }
    return startBrowserListening(handlers)
  }

  const loadVoicePreferences = async () => {
    const token = readToken()
    if (!token) return
    try {
      const response = await axios.get(`${API_BASE}/api/voice/preferences`, {
        headers: {
          Authorization: `Bearer ${token}`
        }
      })
      const payload = response?.data?.data ?? response?.data
      if (!payload || typeof payload !== 'object') return
      if (typeof payload.recognitionLocale === 'string') recognitionLocale.value = payload.recognitionLocale
      if (typeof payload.voiceLocale === 'string') voiceLocale.value = payload.voiceLocale
      if (typeof payload.selectedVoiceGender === 'string') selectedVoiceGender.value = normalizeGender(payload.selectedVoiceGender)
      if (payload.speechRate !== undefined) speechRate.value = clamp(payload.speechRate, 0.6, 1.4)
      if (payload.speechVolume !== undefined) speechVolume.value = clamp(payload.speechVolume, 0, 1)
      if (payload.autoSpeakConclusion !== undefined) autoSpeakConclusion.value = Boolean(payload.autoSpeakConclusion)
      if (payload.autoSendAfterRecognize !== undefined) autoSendAfterRecognize.value = Boolean(payload.autoSendAfterRecognize)
    } catch {
      // ignore remote preference load failures
    }
  }

  const syncVoicePreferences = () => {
    const token = readToken()
    if (!token || !isBrowser()) return
    if (savePreferenceTimer) {
      window.clearTimeout(savePreferenceTimer)
    }
    savePreferenceTimer = window.setTimeout(async () => {
      try {
        await axios.post(
          `${API_BASE}/api/voice/preferences`,
          {
            recognitionLocale: recognitionLocale.value,
            voiceLocale: voiceLocale.value,
            selectedVoiceGender: selectedVoiceGender.value,
            speechRate: speechRate.value,
            speechVolume: speechVolume.value,
            autoSpeakConclusion: autoSpeakConclusion.value,
            autoSendAfterRecognize: autoSendAfterRecognize.value
          },
          {
            headers: {
              Authorization: `Bearer ${token}`
            }
          }
        )
      } catch {
        // ignore remote sync failures
      }
    }, 300)
  }

  const fetchCloudSpeech = async (text, options = {}) => {
    const requestPayload = resolveSpeechRequest(text, options)
    if (!requestPayload.text) {
      throw new Error('播报内容不能为空')
    }

    const cacheKey = buildSpeechCacheKey(requestPayload)
    const cached = getCachedSpeechResponse(cacheKey)
    if (cached) {
      warmAudioUrl(cached.audioUrl)
      return cached
    }

    const inflightRequest = inflightSpeechRequests.get(cacheKey)
    if (inflightRequest) {
      return inflightRequest
    }

    const token = readToken()
    const requestPromise = axios.post(
      `${API_BASE}/api/voice/tts-url`,
      requestPayload,
      {
        headers: {
          Authorization: `Bearer ${token}`
        }
      }
    ).then((response) => {
      const payload = response?.data?.data ?? response?.data
      const audioUrl = String(payload?.audioUrl || '').trim()
      if (!audioUrl) {
        throw new Error('云端 TTS 未返回音频地址')
      }
      const result = {
        audioUrl,
        audioFormat: String(payload?.audioFormat || 'wav').trim().toLowerCase()
      }
      touchSpeechCacheEntry(cacheKey, result)
      warmAudioUrl(result.audioUrl)
      return result
    }).finally(() => {
      inflightSpeechRequests.delete(cacheKey)
    })

    inflightSpeechRequests.set(cacheKey, requestPromise)
    return requestPromise
  }

  const prefetchSpeechText = async (text, options = {}) => {
    const content = String(text || '').trim()
    if (!content || !speechSupported.value) return null
    try {
      return await fetchCloudSpeech(content, options)
    } catch {
      return null
    }
  }

  const playAudioUrl = async (audioUrl, options = {}, token) => {
    const audio = new Audio(audioUrl)
    audio.preload = 'auto'
    audio.volume = clamp(options.volume ?? speechVolume.value, 0, 1)
    audio.playbackRate = clamp(options.rate ?? speechRate.value, 0.6, 1.4)

    currentAudio = audio
    currentAudioUrl = ''
    speaking.value = true
    speechPaused.value = false

    return new Promise((resolve, reject) => {
      const finalize = () => {
        if (token === speakToken) {
          speaking.value = false
          speechPaused.value = false
        }
        cleanupAudio()
      }

      audio.onended = () => {
        finalize()
        options.onEnd?.()
        resolve()
      }

      audio.onerror = () => {
        finalize()
        const message = '语音播报失败'
        recognitionError.value = message
        options.onError?.(message)
        reject(new Error(message))
      }

      audio.onpause = () => {
        if (token === speakToken && !audio.ended) {
          speechPaused.value = true
        }
      }

      audio.onplay = () => {
        speaking.value = true
        speechPaused.value = false
        options.onStart?.()
      }

      audio.play().catch((error) => {
        finalize()
        const message = error?.message || '语音播报失败'
        recognitionError.value = message
        reject(error instanceof Error ? error : new Error(message))
      })
    })
  }

  const playStreamSpeech = async (requestPayload, options = {}, token) => {
    const authToken = readToken()
    const response = await fetch(`${API_BASE}/api/voice/tts-stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Authorization: `Bearer ${authToken}`
      },
      body: JSON.stringify(requestPayload)
    })

    if (!response.ok || !response.body) {
      const errorText = await response.text().catch(() => '')
      throw new Error(errorText || '实时语音播报失败')
    }

    const audioContext = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: DEFAULT_SAMPLE_RATE
    })
    currentAudioContext = audioContext
    await audioContext.resume?.()

    const gainNode = audioContext.createGain()
    gainNode.gain.value = clamp(options.volume ?? speechVolume.value, 0, 1)
    gainNode.connect(audioContext.destination)

    const reader = response.body.getReader()
    const chunks = []
    let nextTime = audioContext.currentTime + 0.05
    let streamStarted = false
    let pendingPcmByte = null

    const schedulePcmChunk = (arrayBuffer) => {
      const int16 = new Int16Array(arrayBuffer.slice(0))
      if (!int16.length) return
      const float32 = new Float32Array(int16.length)
      for (let index = 0; index < int16.length; index += 1) {
        float32[index] = int16[index] / 32768
      }
      const audioBuffer = audioContext.createBuffer(1, float32.length, DEFAULT_SAMPLE_RATE)
      audioBuffer.copyToChannel(float32, 0)

      const source = audioContext.createBufferSource()
      source.buffer = audioBuffer
      source.playbackRate.value = clamp(options.rate ?? speechRate.value, 0.6, 1.4)
      source.connect(gainNode)

      const now = audioContext.currentTime
      nextTime = Math.max(nextTime, now + 0.02)
      source.start(nextTime)
      nextTime += audioBuffer.duration / source.playbackRate.value
    }

    const normalizePcmChunk = (value) => {
      let pcmBytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
      if (pendingPcmByte !== null) {
        const merged = new Uint8Array(pcmBytes.byteLength + 1)
        merged[0] = pendingPcmByte
        merged.set(pcmBytes, 1)
        pcmBytes = merged
        pendingPcmByte = null
      }

      if (pcmBytes.byteLength % 2 !== 0) {
        pendingPcmByte = pcmBytes[pcmBytes.byteLength - 1]
        pcmBytes = pcmBytes.slice(0, pcmBytes.byteLength - 1)
      }

      if (!pcmBytes.byteLength) return null
      return pcmBytes.buffer.slice(pcmBytes.byteOffset, pcmBytes.byteOffset + pcmBytes.byteLength)
    }

    speaking.value = true
    speechPaused.value = false
    options.onStart?.()

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (token !== speakToken) {
          await reader.cancel().catch(() => {})
          return
        }
        if (done) break
        if (!value?.byteLength) continue

        const chunk = normalizePcmChunk(value)
        if (!chunk) continue
        chunks.push(chunk)
        schedulePcmChunk(chunk)
        streamStarted = true
      }
    } finally {
      try {
        reader.releaseLock()
      } catch {
        // ignore
      }
    }

    if (!streamStarted) {
      throw new Error('实时语音播报未返回音频数据')
    }

    while (token === speakToken) {
      if (speechPaused.value || audioContext.state === 'suspended') {
        await new Promise((resolve) => window.setTimeout(resolve, 120))
        continue
      }
      const waitMs = Math.max(0, (nextTime - audioContext.currentTime) * 1000)
      if (waitMs <= 30) break
      await new Promise((resolve) => window.setTimeout(resolve, Math.min(waitMs, 120)))
    }

    const wavBlob = createPcmWavBlob(chunks, DEFAULT_SAMPLE_RATE)
    const objectUrl = window.URL.createObjectURL(wavBlob)
    const cacheKey = buildSpeechCacheKey(requestPayload)
    touchSpeechCacheEntry(cacheKey, {
      audioUrl: objectUrl,
      audioFormat: 'wav',
      [STREAM_AUDIO_MARKER]: true
    })
    currentAudioUrl = objectUrl

    if (token === speakToken) {
      speaking.value = false
      speechPaused.value = false
      options.onEnd?.()
    }
  }

  const speakText = async (text, options = {}) => {
    if (!speechSupported.value) {
      const err = new Error('当前浏览器不支持语音播报')
      recognitionError.value = err.message
      throw err
    }
    const content = String(text || '').trim()
    if (!content) {
      const err = new Error('播报内容不能为空')
      recognitionError.value = err.message
      throw err
    }

    stopSpeaking()
    recognitionError.value = ''
    const token = ++speakToken
    const requestPayload = resolveSpeechRequest(content, options)
    const cacheKey = buildSpeechCacheKey(requestPayload)
    const cached = getCachedSpeechResponse(cacheKey)

    try {
      if (cached?.audioUrl) {
        await playAudioUrl(cached.audioUrl, options, token)
        return
      }

      await playStreamSpeech(requestPayload, options, token)
    } catch (streamError) {
      if (token !== speakToken) return
      try {
        const { audioUrl } = await fetchCloudSpeech(content, options)
        if (token !== speakToken) return
        await playAudioUrl(audioUrl, options, token)
      } catch (fallbackError) {
        const finalError = fallbackError instanceof Error ? fallbackError : streamError
        const message = finalError?.message || '语音播报失败'
        recognitionError.value = message
        throw finalError
      }
    }
  }

  restoreSettings()
  restoreVoiceHistory()

  watch(
    [recognitionLocale, voiceLocale, selectedVoiceGender, speechRate, speechVolume, autoSpeakConclusion, autoSendAfterRecognize],
    () => {
      persistSettings()
      syncVoicePreferences()
    },
    { deep: false }
  )

  onBeforeUnmount(() => {
    stopListening()
    stopSpeaking()
    clearPreloadedAudio()
    cleanupRecorder()
    if (savePreferenceTimer) {
      window.clearTimeout(savePreferenceTimer)
    }
    savePreferenceTimer = null
  })

  return {
    voiceLocaleOptions,
    recognitionLocale,
    voiceLocale,
    selectedVoiceGender,
    speechRate,
    speechVolume,
    autoSpeakConclusion,
    autoSendAfterRecognize,
    voiceGenderOptions,
    recognitionSupported,
    speechSupported,
    voiceCapabilityText,
    voiceStatusText,
    listening,
    speaking,
    speechPaused,
    recognitionError,
    interimTranscript,
    finalTranscript,
    voiceHistory,
    recordVoiceHistory,
    clearVoiceHistory,
    startListening,
    stopListening,
    clearTranscript,
    loadVoicePreferences,
    prefetchSpeechText,
    speakText,
    stopSpeaking,
    pauseSpeaking,
    resumeSpeaking
  }
}
