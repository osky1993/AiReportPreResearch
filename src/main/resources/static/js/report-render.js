/* =====================================================================
 * 报告证据共享渲染（Gate3）：report.html（正式运行详情）与 template-admin.html（模板预览）共用。
 * 抽出的都是「吃数据结构、吐 HTML」的纯渲染件——两处各自内联会在事实/图表结构演进时漂移。
 * 依赖宿主页面提供全局 $(id) 与 esc(s) 两个工具函数（两页均已定义），以及以下 CSS 类：
 * .factref/.factdetail/tr.facthl/.chartgrid/.chartbox/.charttitle/.chart/.badge/.b-run
 * ===================================================================== */

/** 事实表：FactRecord[] → 可点击展开 SQL/双哈希/spec 的表格（行 id 约定 fact-<key>/factd-<key>）。 */
function factsTable(facts){
  return `<table><tr><th>fact_key</th><th>指标</th><th>周期</th><th>值</th><th>展示</th><th>类型</th><th>质量</th></tr>
    ${facts.map(f=>`<tr id="fact-${f.factKey}" onclick="toggleFactDetail('${f.factKey}')" style="cursor:pointer">
      <td class="factref" style="background:none">${f.factKey}</td><td>${esc(f.metricName)}${f.metricVersion?` <span class="badge b-run">v${f.metricVersion}</span>`:''}</td>
      <td>${esc(f.periodLabel)}</td><td>${f.value}</td><td>${esc(f.displayValue)}</td>
      <td>${f.factType}${f.derivedFrom?'<span class="muted">←'+esc(f.derivedFrom)+'</span>':''}</td>
      <td>${f.qualityStatus}</td></tr>
      <tr id="factd-${f.factKey}" style="display:none"><td colspan="7">
        ${f.sqlText?`<div class="factdetail">SQL: ${esc(f.sqlText)}\nsql_hash: ${esc(f.sqlHash)}\nresult_hash: ${esc(f.resultHash)}</div>`:'<div class="factdetail">程序派生事实（无 SQL）：来源 '+esc(f.derivedFrom||'')+'</div>'}
        ${f.specJson?`<div class="factdetail">MetricQuerySpec: ${esc(f.specJson)}</div>`:''}
      </td></tr>`).join('')}</table>`;
}

function toggleFactDetail(key){
  const el = $('factd-'+key);
  if(el) el.style.display = el.style.display==='none' ? '' : 'none';
}

/** 高亮并滚动到指定事实行（[fact_xxx] 引用点击联动）。 */
function showFact(key){
  document.querySelectorAll('tr.facthl').forEach(t=>t.classList.remove('facthl'));
  const row = $('fact-'+key);
  if(row){ row.classList.add('facthl'); const det=$('factd-'+key); if(det) det.style.display='';
    row.scrollIntoView({behavior:'smooth', block:'center'}); }
}

/** 图表栅格骨架：ChartRecord[] → 容器 HTML；容器 id = <prefix>_<i>，渲染交给 initChartsInto。 */
function chartGridHtml(charts, prefix){
  return `<div class="chartgrid">${charts.map((c,i)=>`
    <div class="chartbox"><div class="charttitle"><b>${esc(c.title)}</b>
      <span>章 ${esc(c.chapterId)} · ${(c.boundFactKeys||[]).length} 点 · ${esc(c.type)}</span></div>
    <div class="chart" id="${prefix}_${i}"></div></div>`).join('')}</div>`;
}

/** 用库中/预览返回的 optionJson 初始化 ECharts（数据 100% 程序绑定自 fact，LLM 零接触）。 */
function initChartsInto(charts, prefix){
  if(typeof echarts === 'undefined') return;
  charts.forEach((c,i)=>{
    const el = document.getElementById(prefix+'_'+i);
    if(!el) return;
    try{ echarts.init(el).setOption(JSON.parse(c.optionJson)); }
    catch(e){ el.textContent = '图表渲染失败: '+e.message; }
  });
}
