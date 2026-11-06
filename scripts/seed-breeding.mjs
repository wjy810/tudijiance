// 育种演示数据：四季（2024-2025 年度大田、2025 夏繁、2025-2026 年度大田、2026-2027 年度大田），
// 原始材料、选株、杂交（单交、三交）、每季的地块和种植、录入的数据。按需求一的验收场景走一遍。
// 只用于本地体验和截图，不要对正式服务器运行。
//
// 用法（后端先用 local 配置启动）：
//   node scripts/seed-breeding.mjs [服务器地址]
// 默认服务器地址 http://localhost:8080，管理员 admin / admin123；老师 laoshi、学生 xuesheng，密码 demo1234。

const SERVER = process.argv[2] ?? 'http://localhost:8080'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'admin123'
const DEMO_PASSWORD = 'demo1234'

let token = null

async function api(method, path, body) {
  const res = await fetch(SERVER + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`)
  return text ? JSON.parse(text) : null
}

async function login(username, password) {
  token = (await api('POST', '/api/auth/login', { username, password })).token
}

let seed = 20261106
function rand() {
  seed = (seed * 1103515245 + 12345) % 2147483648
  return seed / 2147483648
}
const between = (lo, hi) => lo + (hi - lo) * rand()
const uuid = () => crypto.randomUUID()

/** 几行几列区块（区块间 1 m 道路），每个区块里几行几列小区块 */
function layout(blockRows, blockCols, innerRows, innerCols, blockW = 6, blockH = 20) {
  const rows = []
  const cols = []
  for (let i = 0; i < blockRows; i++) rows.push(...(i ? [1, blockH] : [blockH]))
  for (let j = 0; j < blockCols; j++) cols.push(...(j ? [1, blockW] : [blockW]))
  const blocks = []
  const merges = []
  let no = 1
  let road = 1
  for (let r = 0; r < rows.length; r++) {
    if (r % 2 === 1) {
      if (cols.length > 1) merges.push({ r, c: 0, rs: 1, cs: cols.length })
      blocks.push({ id: `R${road++}`, r, c: 0, name: '道路', kind: 'ROAD', fit: null, inner: null })
      continue
    }
    for (let c = 0; c < cols.length; c++) {
      if (c % 2 === 1) {
        blocks.push({ id: `R${road++}`, r, c, name: '道路', kind: 'ROAD', fit: null, inner: null })
      } else {
        blocks.push({
          id: `B${no}`, r, c, name: `区块${no}`, kind: 'PLANT', fit: null,
          inner: { rows: Array(innerRows).fill(1), cols: Array(innerCols).fill(1), merges: [], plots: {} },
        })
        no++
      }
    }
  }
  return {
    version: 1, unit: { name: '米', symbol: 'm', meters: 1 }, rows, cols, merges, blocks,
    nextBlockNo: Math.max(no, road), defaultInner: { rows: innerRows, cols: innerCols }, northDeg: 0,
  }
}

/** 区块 id → 里面的小区块 id（按行列顺序） */
function blockPlots(detail) {
  const map = {}
  for (const b of detail.layout.blocks) {
    if (b.kind !== 'PLANT') continue
    map[b.id] = Object.entries(b.inner.plots)
      .sort(([a], [b2]) => {
        const [ra, ca] = a.split(',').map(Number)
        const [rb, cb] = b2.split(',').map(Number)
        return ra - rb || ca - cb
      })
      .map(([, id]) => id)
  }
  return map
}

let traits = []
const traitId = (code) => {
  const t = traits.find((x) => x.trait.code === code)
  if (!t) throw new Error('没有录入项目 ' + code)
  return t.trait.id
}

async function record(trialId, code, plotIds, value, date) {
  if (!plotIds.length) return
  await api('POST', '/api/observations', {
    trialId, traitId: traitId(code), observedOn: date, ...value,
    items: plotIds.map((plotId) => ({ plotId, clientUuid: uuid() })),
  })
}

/** 一块地块上一个品系的数据：每个小区块单独录（各有一点差别） */
async function recordLine(trialId, plotIds, p, dates) {
  for (const plotId of plotIds) {
    await record(trialId, 'sowing_date', [plotId], { valueDate: dates.sow }, dates.sow)
    if (dates.heading) {
      await record(trialId, 'heading_date', [plotId], { valueDate: dates.heading }, dates.heading)
      await record(trialId, 'plant_height', [plotId], { valueNum: +(p.height + between(-3, 3)).toFixed(0) }, dates.heading)
    }
    if (dates.harvest) {
      await record(trialId, 'maturity_date', [plotId], { valueDate: dates.harvest }, dates.harvest)
      await record(trialId, 'thousand_grain_weight', [plotId], { valueNum: +(p.tgw + between(-1.5, 1.5)).toFixed(1) }, dates.harvest)
      await record(trialId, 'plot_yield', [plotId], { valueNum: +(p.yieldKg * between(0.93, 1.07)).toFixed(2) }, dates.harvest)
    }
  }
}

async function main() {
  await login('admin', ADMIN_PASSWORD)
  const users = await api('GET', '/api/users')
  for (const [username, displayName, role] of [['laoshi', '王老师', 'TEACHER'], ['xuesheng', '李同学', 'STUDENT']]) {
    if (!users.some((u) => u.username === username)) {
      await api('POST', '/api/users', { username, displayName, role, password: DEMO_PASSWORD })
    }
  }
  await login('laoshi', DEMO_PASSWORD)
  const seasons = await api('GET', '/api/seasons')
  if (seasons.some((s) => s.season.name === '2024-2025 年度大田')) {
    console.log('育种演示数据已经有了，跳过。')
    return
  }
  traits = await api('GET', '/api/traits')

  // ---------- 第一季：全新开始 ----------
  const s1 = (await api('POST', '/api/seasons', {
    name: '2024-2025 年度大田', startDate: '2024-10-12', endDate: '2025-06-08', remark: null, followIds: [],
  })).season
  const east1 = await api('POST', `/api/seasons/${s1.id}/fields`, { name: '东地', layout: layout(2, 3, 2, 3), location: '学校农场东区' })
  const t1 = (await api('GET', '/api/seasons')).find((v) => v.season.id === s1.id).fields[0].trialId
  const b1 = blockPlots(east1)

  const founders = await api('POST', '/api/lines/founders', {
    items: [
      { name: '周麦16', ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM', breeder: '周口市农科院' },
      { name: '郑麦366', ecotype: 'SEMI_WINTER', qualityType: 'STRONG', breeder: '河南省农科院' },
      { name: '百农207', ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM', breeder: '河南科技学院' },
      { name: '济麦22', ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM', breeder: '山东省农科院' },
      { name: '矮抗58', ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM', breeder: '河南科技学院' },
      { name: '西农979', ecotype: 'SEMI_WINTER', qualityType: 'STRONG', breeder: '西北农林科技大学' },
    ],
  })
  const F = Object.fromEntries(founders.map((l) => [l.name, l.id]))
  const plant = (fieldId, plotIds, lineId) => api('PUT', `/api/breeding/fields/${fieldId}/plantings`, { plotIds, lineId })
  const fid1 = east1.field.id
  // 周麦16 种满区块1，再把其中 2 个小区块改种百农207
  await plant(fid1, b1.B1, F['周麦16'])
  await plant(fid1, b1.B1.slice(4), F['百农207'])
  await plant(fid1, b1.B2, F['郑麦366'])
  await plant(fid1, b1.B3.slice(0, 3), F['济麦22'])
  await plant(fid1, b1.B3.slice(3), F['矮抗58'])
  await plant(fid1, b1.B4, F['西农979'])
  await plant(fid1, b1.B5, F['百农207'])
  const P = {
    周麦16: { height: 78, tgw: 46, yieldKg: 7.6 }, 郑麦366: { height: 74, tgw: 44, yieldKg: 7.2 },
    百农207: { height: 76, tgw: 45, yieldKg: 7.9 }, 济麦22: { height: 72, tgw: 43, yieldKg: 7.5 },
    矮抗58: { height: 70, tgw: 44, yieldKg: 7.4 }, 西农979: { height: 75, tgw: 41, yieldKg: 7.0 },
  }
  const d1 = { sow: '2024-10-12', heading: '2025-04-20', harvest: '2025-06-02' }
  await recordLine(t1, b1.B1.slice(0, 4), P['周麦16'], d1)
  await recordLine(t1, [...b1.B1.slice(4), ...b1.B5], P['百农207'], d1)
  await recordLine(t1, b1.B2, P['郑麦366'], d1)
  await recordLine(t1, b1.B3.slice(0, 3), P['济麦22'], d1)
  await recordLine(t1, b1.B3.slice(3), P['矮抗58'], d1)
  await recordLine(t1, b1.B4, P['西农979'], d1)

  // 选株：从区块1 的周麦16 里选两株（挂牌 7、12），录单株数据
  const sel = await api('POST', `/api/lines/${F['周麦16']}/selections`, { count: 2, tagNos: ['7', '12'], fieldId: fid1, blockId: 'B1' })
  for (const [i, s] of sel.entries()) {
    await api('PUT', `/api/lines/${s.id}/plant-values`, { values: [
      { traitId: traitId('plant_height'), valueNum: 80 + i * 3 },
      { traitId: traitId('grains_per_spike'), valueNum: 42 + i * 4 },
      { traitId: traitId('thousand_grain_weight'), valueNum: 48.5 - i },
    ] })
  }
  const zm1 = sel[0].id
  const zm2 = sel[1].id
  // 杂交
  const c1 = (await api('POST', '/api/lines/crosses', { motherId: F['周麦16'], fatherId: F['郑麦366'], seasonId: s1.id })).line
  const c2 = (await api('POST', '/api/lines/crosses', { motherId: F['济麦22'], fatherId: F['矮抗58'], seasonId: s1.id })).line
  const c3 = (await api('POST', '/api/lines/crosses', { motherId: F['百农207'], fatherId: F['西农979'], seasonId: s1.id })).line
  console.log(`第一季：6 个原始材料，选株 ${sel.map((s) => s.code).join('、')}，杂交 ${[c1, c2, c3].map((c) => c.code).join('、')}`)

  // ---------- 第二季：夏繁，只带走杂交组合 ----------
  const s2 = (await api('POST', '/api/seasons', {
    name: '2025 夏繁', startDate: '2025-06-20', endDate: '2025-09-25', remark: '温室加代', followIds: [s1.id],
  })).season
  const green = await api('POST', `/api/seasons/${s2.id}/fields`, { name: '温室', layout: layout(1, 2, 2, 3, 4, 8) })
  const t2 = (await api('GET', '/api/seasons')).find((v) => v.season.id === s2.id).fields[0].trialId
  const bg = blockPlots(green)
  await plant(green.field.id, bg.B1.slice(0, 3), c1.id)
  await plant(green.field.id, bg.B1.slice(3), c2.id)
  await plant(green.field.id, bg.B2.slice(0, 3), c3.id)
  const d2 = { sow: '2025-06-22', heading: '2025-08-20', harvest: null }
  await recordLine(t2, bg.B1.slice(0, 3), { height: 82, tgw: 0, yieldKg: 0 }, d2)
  await recordLine(t2, bg.B1.slice(3), { height: 75, tgw: 0, yieldKg: 0 }, d2)
  await recordLine(t2, bg.B2.slice(0, 3), { height: 79, tgw: 0, yieldKg: 0 }, d2)

  // ---------- 第三季：接着去年大田和夏繁 ----------
  const s3 = (await api('POST', '/api/seasons', {
    name: '2025-2026 年度大田', startDate: '2025-10-10', endDate: '2026-06-06', remark: null, followIds: [s1.id, s2.id],
  })).season
  const east3 = await api('POST', `/api/seasons/${s3.id}/fields`, { name: '东地', copyFromFieldId: fid1 })
  const west3 = await api('POST', `/api/seasons/${s3.id}/fields`, { name: '西地', layout: layout(1, 2, 3, 3) })
  const lands3 = (await api('GET', '/api/seasons')).find((v) => v.season.id === s3.id).fields
  const t3e = lands3.find((f) => f.id === east3.field.id).trialId
  const t3w = lands3.find((f) => f.id === west3.field.id).trialId
  const b3 = blockPlots(east3)
  const w3 = blockPlots(west3)
  await plant(east3.field.id, b3.B1, c1.id)
  await plant(east3.field.id, b3.B2, c2.id)
  await plant(east3.field.id, b3.B3, c3.id)
  await plant(east3.field.id, b3.B4, F['周麦16'])
  await plant(east3.field.id, b3.B5, F['郑麦366'])
  await plant(east3.field.id, b3.B6, F['百农207'])
  await plant(west3.field.id, w3.B1, zm1)
  await plant(west3.field.id, w3.B2.slice(0, 5), F['济麦22'])
  await plant(west3.field.id, w3.B2.slice(5), F['矮抗58'])
  const d3 = { sow: '2025-10-10', heading: '2026-04-22', harvest: '2026-06-01' }
  const P3 = {
    c1: { height: 77, tgw: 47.5, yieldKg: 8.1 }, c2: { height: 71, tgw: 44.5, yieldKg: 7.8 }, c3: { height: 74, tgw: 43.0, yieldKg: 7.6 },
  }
  await recordLine(t3e, b3.B1, P3.c1, d3)
  await recordLine(t3e, b3.B2, P3.c2, d3)
  await recordLine(t3e, b3.B3, P3.c3, d3)
  await recordLine(t3e, b3.B4, P['周麦16'], d3)
  await recordLine(t3e, b3.B5, P['郑麦366'], d3)
  await recordLine(t3e, b3.B6, P['百农207'], d3)
  await recordLine(t3w, w3.B1, { height: 81, tgw: 48, yieldKg: 8.3 }, d3)
  await recordLine(t3w, w3.B2.slice(0, 5), P['济麦22'], d3)
  await recordLine(t3w, w3.B2.slice(5), P['矮抗58'], d3)

  // 淘汰整理：周麦16-2 没再种、也没子代
  await api('PUT', '/api/lines/status', { lineIds: [zm2], status: 'DROPPED' })
  // 从 25-001（F2）里选 3 株；25-001 × 百农207 三交；周麦16 × 郑麦366 再做一次
  const sel3 = await api('POST', `/api/lines/${c1.id}/selections`, { count: 3, tagNos: ['3', '9', '15'], fieldId: east3.field.id, blockId: 'B1' })
  const c4 = (await api('POST', '/api/lines/crosses', { motherId: c1.id, fatherId: F['百农207'], seasonId: s3.id })).line
  const c5 = (await api('POST', '/api/lines/crosses', { motherId: F['周麦16'], fatherId: F['郑麦366'], seasonId: s3.id })).line
  console.log(`第三季：从 ${c1.code} 选出 ${sel3.map((s) => s.code).join('、')}，杂交 ${c4.code}（${c4.pedigree}）、${c5.code}`)

  // ---------- 第四季：刚播种 ----------
  const s4 = (await api('POST', '/api/seasons', {
    name: '2026-2027 年度大田', startDate: '2026-10-08', endDate: '2027-06-05', remark: null, followIds: [s3.id],
  })).season
  const east4 = await api('POST', `/api/seasons/${s4.id}/fields`, { name: '东地', copyFromFieldId: east3.field.id })
  const t4 = (await api('GET', '/api/seasons')).find((v) => v.season.id === s4.id).fields[0].trialId
  const b4 = blockPlots(east4)
  await plant(east4.field.id, b4.B1.slice(0, 2), sel3[0].id)
  await plant(east4.field.id, b4.B1.slice(2, 4), sel3[1].id)
  await plant(east4.field.id, b4.B1.slice(4), sel3[2].id)
  await plant(east4.field.id, b4.B2, c4.id)
  await plant(east4.field.id, b4.B3, c5.id)
  await plant(east4.field.id, b4.B4, zm1)
  await plant(east4.field.id, b4.B5, F['周麦16'])
  for (const ids of [b4.B1, b4.B2, b4.B3, b4.B4, b4.B5]) {
    await record(t4, 'sowing_date', ids, { valueDate: '2026-10-08' }, '2026-10-08')
  }
  await api('PUT', `/api/lines/${zm1}`, { name: '校麦1号', breeder: '周口市农科院', ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM', remark: '株型紧凑，抗倒' })
  console.log('完成。季和地块、品系库、系谱图里都能看到。老师 laoshi、学生 xuesheng，密码 ' + DEMO_PASSWORD)
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
