<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BookOutlined,
  BranchesOutlined,
  CrownOutlined,
  DashboardOutlined,
  DownOutlined,
  LogoutOutlined,
  MenuOutlined,
  ReadOutlined,
  SettingOutlined,
  TranslationOutlined,
} from '@ant-design/icons-vue'
import { useAuthStore } from './stores/auth'
import { themeConfig } from './design/theme'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const publicPage = computed(() => Boolean(route.meta.public))
const learningPaths = new Set(['/learning', '/vocabulary', '/review', '/confusions'])
const routeLabels: Record<string, string> = {
  '/translate': '语境翻译',
  '/domains': '场景管理',
  '/learning': '学习概览',
  '/vocabulary': '个人词库',
  '/review': '开始复习',
  '/confusions': '易混词',
  '/plans': '套餐与用量',
  '/admin': '运营指标',
}
const inLearningWorkspace = computed(() => learningPaths.has(route.path))
const selectedPrimaryKeys = computed(() => [inLearningWorkspace.value ? 'learning' : 'translate'])
const selectedSecondaryKeys = computed(() => [route.path])
const currentLabel = computed(() => routeLabels[route.path] ?? 'LexiForge')
const primaryMenu = [
  { key: 'translate', path: '/translate', label: '语境翻译', icon: TranslationOutlined },
  { key: 'learning', path: '/learning', label: '单词学习', icon: BookOutlined },
] as const
const learningMenu = [
  { path: '/learning', label: '学习概览', icon: DashboardOutlined },
  { path: '/vocabulary', label: '个人词库', icon: BookOutlined },
  { path: '/review', label: '开始复习', icon: ReadOutlined },
  { path: '/confusions', label: '易混词', icon: BranchesOutlined },
] as const

function navigate(path: string) {
  router.push(path)
}

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <a-config-provider :theme="themeConfig">
    <router-view v-if="publicPage" />
    <a-layout v-else class="app-shell">
      <header class="global-header">
        <div class="global-header-inner">
          <button class="top-brand" type="button" aria-label="前往语境翻译" @click="navigate('/translate')">
            <span class="brand-mark" aria-hidden="true">Lx</span>
            <span class="brand-name">LexiForge</span>
          </button>

          <nav class="desktop-primary-nav" aria-label="主要导航">
            <a-menu mode="horizontal" :selected-keys="selectedPrimaryKeys">
              <a-menu-item v-for="item in primaryMenu" :key="item.key" @click="navigate(item.path)">
                {{ item.label }}
              </a-menu-item>
            </a-menu>
          </nav>

          <div class="mobile-current-page">{{ currentLabel }}</div>

          <div class="header-actions">
            <a-dropdown class="mobile-nav-trigger" placement="bottomRight" trigger="click">
              <a-button type="text" aria-label="打开主要导航"><MenuOutlined /></a-button>
              <template #overlay>
                <a-menu :selected-keys="selectedPrimaryKeys">
                  <a-menu-item v-for="item in primaryMenu" :key="item.key" @click="navigate(item.path)">
                    <component :is="item.icon" />{{ item.label }}
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>

            <a-dropdown placement="bottomRight" trigger="click">
              <button class="user-trigger" type="button" aria-label="打开用户菜单">
                <a-avatar :size="30">{{ auth.user?.displayName?.slice(0, 1) }}</a-avatar>
                <span class="user-trigger-name">{{ auth.user?.displayName }}</span>
                <DownOutlined />
              </button>
              <template #overlay>
                <a-menu class="user-menu">
                  <li class="user-menu-identity" aria-hidden="true">
                    <strong>{{ auth.user?.displayName }}</strong>
                    <span>{{ auth.user?.email }}</span>
                  </li>
                  <a-menu-divider />
                  <a-menu-item key="domains" @click="navigate('/domains')"><SettingOutlined />AI 翻译场景</a-menu-item>
                  <a-menu-item key="plans" @click="navigate('/plans')"><CrownOutlined />套餐与用量</a-menu-item>
                  <a-menu-item v-if="auth.user?.role === 'ADMIN'" key="admin" @click="navigate('/admin')"><DashboardOutlined />运营指标</a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="logout" danger @click="logout"><LogoutOutlined />退出登录</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </div>
      </header>

      <nav v-if="inLearningWorkspace" class="secondary-nav" aria-label="单词学习导航">
        <div class="secondary-nav-inner">
          <a-menu mode="horizontal" :selected-keys="selectedSecondaryKeys">
            <a-menu-item v-for="item in learningMenu" :key="item.path" @click="navigate(item.path)">
              <component :is="item.icon" />{{ item.label }}
            </a-menu-item>
          </a-menu>
        </div>
      </nav>

      <a-layout-content class="app-content" :class="{ 'with-secondary-nav': inLearningWorkspace }"><router-view /></a-layout-content>
    </a-layout>
  </a-config-provider>
</template>
