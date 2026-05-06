<template>
  <main class="auth-page">
    <section class="auth-hero">
    </section>

    <section class="auth-panel">
      <el-tabs v-model="mode" stretch @tab-change="refreshCaptcha">
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="账号">
              <el-input v-model="loginForm.account" placeholder="用户名 / 手机号 / 邮箱" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" />
            </el-form-item>
            <CaptchaInput v-model="loginForm.captchaCode" :image="captcha.image" @refresh="refreshCaptcha" />
            <el-button type="primary" size="large" class="full-width" :loading="submitting" @click="submitLogin">登录</el-button>
            <p class="auth-hint">默认账号：admin / admin123456，demo-user / user123456</p>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="角色">
              <el-radio-group v-model="registerForm.role">
                <el-radio-button value="USER">用户</el-radio-button>
                <el-radio-button value="ADMIN">管理员</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="用户名">
              <el-input v-model="registerForm.username" placeholder="例如 zhangsan" />
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="registerForm.nickname" placeholder="显示名称" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input v-model="registerForm.phone" placeholder="可用于登录" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="registerForm.email" placeholder="可用于登录" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="registerForm.password" type="password" show-password placeholder="至少 8 位" />
            </el-form-item>
            <CaptchaInput v-model="registerForm.captchaCode" :image="captcha.image" @refresh="refreshCaptcha" />
            <el-button type="primary" size="large" class="full-width" :loading="submitting" @click="submitRegister">注册并进入</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<script setup>
import { h, onMounted, reactive, ref, resolveComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { getCaptcha, login, register } from '../api/auth'
import { setSession } from '../store/session'

const emit = defineEmits(['authenticated'])

const mode = ref('login')
const submitting = ref(false)
const captcha = reactive({ captchaId: '', image: '' })
const loginForm = reactive({ account: 'admin', password: 'admin123456', captchaCode: '' })
const registerForm = reactive({
  role: 'USER',
  username: '',
  nickname: '',
  phone: '',
  email: '',
  password: '',
  captchaCode: ''
})

const CaptchaInput = {
  props: ['modelValue', 'image'],
  emits: ['update:modelValue', 'refresh'],
  setup(props, { emit }) {
    const ElInput = resolveComponent('el-input')
    return () => h('div', { class: 'captcha-row' }, [
      h(ElInput, {
        modelValue: props.modelValue,
        'onUpdate:modelValue': value => emit('update:modelValue', value),
        placeholder: '验证码结果'
      }),
      h('button', { class: 'captcha-image', type: 'button', onClick: () => emit('refresh') }, [
        props.image ? h('img', { src: props.image, alt: 'captcha' }) : '刷新'
      ])
    ])
  }
}

const refreshCaptcha = async () => {
  const data = await getCaptcha()
  captcha.captchaId = data.captchaId
  captcha.image = data.image
  loginForm.captchaCode = ''
  registerForm.captchaCode = ''
}

const submitLogin = async () => {
  submitting.value = true
  try {
    const data = await login({ ...loginForm, captchaId: captcha.captchaId })
    setSession(data)
    ElMessage.success('登录成功')
    emit('authenticated')
  } catch (error) {
    ElMessage.error(error.message || '登录失败')
    await refreshCaptcha()
  } finally {
    submitting.value = false
  }
}

const submitRegister = async () => {
  submitting.value = true
  try {
    const data = await register({ ...registerForm, captchaId: captcha.captchaId })
    setSession(data)
    ElMessage.success('注册成功')
    emit('authenticated')
  } catch (error) {
    ElMessage.error(error.message || '注册失败')
    await refreshCaptcha()
  } finally {
    submitting.value = false
  }
}

onMounted(refreshCaptcha)
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(420px, 1fr) 460px;
  background: url('/bg.png') center/cover no-repeat;
}

.auth-hero {
  padding: 54px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: #fff;
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 16px;
}

.auth-mark {
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: #2f7cf6;
  font-weight: 800;
  font-size: 20px;
}

.auth-brand h1 {
  margin: 0;
  font-size: 34px;
}

.auth-brand p {
  margin: 8px 0 0;
  color: #dbeafe;
}

.auth-strip {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.auth-strip span {
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.12);
}

.auth-panel {
  align-self: center;
  margin: 32px;
  padding: 28px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 18px 45px rgba(15, 23, 42, 0.12);
}

.captcha-row {
  display: grid;
  grid-template-columns: 1fr 132px;
  gap: 10px;
  margin-bottom: 18px;
}

.captcha-image {
  height: 44px;
  padding: 0;
  border: 1px solid #d8e2ef;
  border-radius: 8px;
  background: #f8fbff;
  cursor: pointer;
  overflow: hidden;
}

.captcha-image img {
  width: 132px;
  height: 44px;
  display: block;
}

.full-width {
  width: 100%;
}

.auth-hint {
  margin: 12px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
  }

  .auth-hero {
    min-height: 220px;
    padding: 28px;
  }

  .auth-panel {
    margin: 18px;
  }
}
</style>
