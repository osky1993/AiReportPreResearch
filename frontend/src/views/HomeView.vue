<script setup lang="ts">
const scenarios = [
  {
    key: 'ask',
    title: '智能问数',
    desc: '用一句话查询国库库存、机构等数据。已核验口径直接复用、确定性取数；新问题由 AI 辅助生成并标注可信度，全程可查看取数依据。',
    to: '/ask',
    ready: true,
    icon: '💬',
  },
  {
    key: 'report',
    title: '智能报告',
    desc: '国库库存月报一键生成：锁定口径后程序确定性取数，AI 只负责成文，数字一致率 100% 双检把关，人工审批后签发。',
    to: '/report',
    ready: true,
    icon: '📄',
  },
  {
    key: 'assets',
    title: '资产治理',
    desc: '报告模板、指标口径与问数沉淀口径统一管理：多版本行不可变、历史可追溯，业务确认过的口径越问越准。',
    to: '/assets',
    ready: true,
    icon: '📚',
  },
]
</script>

<template>
  <main class="home">
    <section class="hero">
      <h1>国库智能数据服务</h1>
      <p>
        面向国库业务的智能查数与报告平台——口径可确认、取数可复现、数字可追溯
      </p>
    </section>

    <section class="cards">
      <template v-for="s in scenarios" :key="s.key">
        <RouterLink v-if="s.ready" :to="s.to" class="card card-ready">
          <div class="card-icon">{{ s.icon }}</div>
          <div class="card-head">
            <h2>{{ s.title }}</h2>
            <span class="tag tag-ready">可使用</span>
          </div>
          <p>{{ s.desc }}</p>
          <span class="card-cta">进入 →</span>
        </RouterLink>
        <div v-else class="card card-soon">
          <div class="card-icon">{{ s.icon }}</div>
          <div class="card-head">
            <h2>{{ s.title }}</h2>
            <span class="tag tag-soon">建设中</span>
          </div>
          <p>{{ s.desc }}</p>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.home {
  flex: 1;
  overflow-y: auto;
}

.hero {
  padding: 72px 32px 56px;
  text-align: center;
  background:
    radial-gradient(ellipse 60% 80% at 50% -20%, var(--tb-blue-100), transparent),
    linear-gradient(var(--tb-blue-50), var(--tb-bg));
}

.hero h1 {
  font-size: 34px;
  font-weight: 700;
  color: var(--tb-blue-900);
  letter-spacing: 2px;
}

.hero p {
  margin-top: 14px;
  font-size: 16px;
  color: var(--tb-text-2);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  max-width: 1080px;
  margin: 0 auto;
  padding: 8px 32px 64px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 26px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
  color: var(--tb-text);
}

.card-ready {
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease,
    border-color 0.15s ease;
}

.card-ready:hover {
  transform: translateY(-3px);
  border-color: var(--tb-blue-500);
  box-shadow: 0 6px 24px rgba(16, 42, 82, 0.14);
}

.card-soon {
  opacity: 0.72;
}

.card-icon {
  font-size: 26px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-head h2 {
  font-size: 18px;
  font-weight: 600;
}

.tag {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
}

.tag-ready {
  color: var(--tb-green);
  background: var(--tb-green-bg);
}

.tag-soon {
  color: var(--tb-text-3);
  background: var(--tb-bg);
  border: 1px solid var(--tb-border);
}

.card p {
  flex: 1;
  color: var(--tb-text-2);
  font-size: 13.5px;
}

.card-cta {
  color: var(--tb-blue-600);
  font-weight: 500;
  font-size: 14px;
}
</style>
