<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="topbar">
    <RouterLink to="/" class="brand">
      <span class="brand-mark">库</span>
      <span class="brand-name">司库智能数据服务</span>
    </RouterLink>
    <nav class="nav">
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/ask">智能问数</RouterLink>
      <RouterLink to="/report">智能报告</RouterLink>
      <RouterLink to="/assets">资产治理</RouterLink>
    </nav>
  </header>

  <RouterView />
</template>

<style scoped>
.topbar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 40px;
  height: 56px;
  padding: 0 28px;
  background: var(--tb-blue-900);
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--tb-blue-500), var(--tb-blue-700));
  font-weight: 700;
  font-size: 15px;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}

.nav {
  display: flex;
  gap: 6px;
}

.nav a {
  padding: 5px 14px;
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 14px;
}

.nav a:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.nav a.router-link-active {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
  font-weight: 500;
}
</style>
