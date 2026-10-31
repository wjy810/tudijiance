// 演示数据：建账号、一块实验田、12 个品种、近四个年度的品种比较试验和录入数据。
// 只用于本地体验和截图，不要对正式服务器运行。
//
// 用法（后端先用 local 配置启动）：
//   node scripts/seed-demo.mjs [服务器地址]
// 默认服务器地址 http://localhost:8080，管理员 admin / admin123。
// 建好的账号：老师 laoshi、学生 xuesheng、游客 youke，密码都是 demo1234。

const SERVER = process.argv[2] ?? 'http://localhost:8080'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'admin123'
const DEMO_PASSWORD = 'demo1234'

let token = null

async function api(method, path, body) {
  const res = await fetch(SERVER + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`${method} ${path} → ${res.status} ${text}`)
  }
  return text ? JSON.parse(text) : null
}

async function login(username, password) {
  token = (await api('POST', '/api/auth/login', { username, password })).token
}

// 固定种子的随机数，每次生成的数据一样
let seed = 20251031
function rand() {
  seed = (seed * 1103515245 + 12345) % 2147483648
  return seed / 2147483648
}
const between = (lo, hi) => lo + (hi - lo) * rand()
const pick = (list) => list[Math.floor(rand() * list.length)]
function shuffle(list) {
  const a = [...list]
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
  return a
}
const uuid = () => crypto.randomUUID()
const iso = (d) => d.toISOString().slice(0, 10)
const addDays = (d, n) => new Date(d.getTime() + n * 86400000)

// 品种：名字、产量水平（相对对照）、株高、熟期早晚（天）、抗病性
const VARIETIES = [
  { name: '周麦 36', code: 'CK', control: 'YIELD', yieldFactor: 1.0, height: 78, late: 0, rust: 3 },
  { name: '百农 207', code: 'V01', yieldFactor: 1.04, height: 76, late: 1, rust: 2 },
  { name: '济麦 22', code: 'V02', yieldFactor: 1.02, height: 72, late: 2, rust: 3 },
  { name: '郑麦 379', code: 'V03', yieldFactor: 1.06, height: 80, late: -1, rust: 2 },
  { name: '西农 979', code: 'V04', yieldFactor: 0.97, height: 75, late: -3, rust: 4 },
  { name: '山农 28', code: 'V05', yieldFactor: 1.07, height: 74, late: 1, rust: 2 },
  { name: '烟农 1212', code: 'V06', yieldFactor: 1.09, height: 77, late: 2, rust: 3 },
  { name: '中麦 895', code: 'V07', yieldFactor: 1.03, height: 73, late: 0, rust: 2 },
  { name: '新麦 26', code: 'V08', yieldFactor: 0.95, height: 82, late: -2, rust: 4 },
  { name: '洛麦 26', code: 'V09', yieldFactor: 1.01, height: 79, late: 1, rust: 3 },
  { name: '矮抗 58', code: 'V10', yieldFactor: 0.99, height: 70, late: 0, rust: 2 },
  { name: '泛麦 5 号', code: 'V11', yieldFactor: 1.05, height: 81, late: -1, rust: 3 },
]

// 各年度的整体条件（年份效应）。最后一个年度刚播种，只有播种和出苗的记录
const SEASONS = [
  { year: 2024, sow: '2023-10-14', yieldBase: 560, note: '春季倒春寒' },
  { year: 2025, sow: '2024-10-12', yieldBase: 605, note: '' },
  { year: 2026, sow: '2025-10-15', yieldBase: 590, note: '灌浆期干热风' },
  { year: 2027, sow: '2026-10-12', yieldBase: 600, note: '' },
]

const ROWS = 8
const COLS = 6

async function main() {
  await login('admin', ADMIN_PASSWORD)
  const users = await api('GET', '/api/users')
  for (const [username, displayName, role] of [
    ['laoshi', '王老师', 'TEACHER'],
    ['xuesheng', '李同学', 'STUDENT'],
    ['youke', '参观者', 'GUEST'],
  ]) {
    if (!users.some((u) => u.username === username)) {
      await api('POST', '/api/users', { username, displayName, role, password: DEMO_PASSWORD })
    }
  }

  await login('laoshi', DEMO_PASSWORD)
  const fields = await api('GET', '/api/fields')
  if (fields.some((f) => f.name === '东区品种试验田')) {
    console.log('演示数据已经有了，跳过。')
    return
  }

  const traits = await api('GET', '/api/traits')
  const trait = (code) => {
    const t = traits.find((x) => x.trait.code === code)
    if (!t) throw new Error('没有录入项目 ' + code)
    return t
  }

  // 品种
  const existing = await api('GET', '/api/varieties')
  const varietyIds = []
  for (const v of VARIETIES) {
    const found = existing.find((e) => e.name === v.name)
    const saved = found ?? await api('POST', '/api/varieties', {
      name: v.name, code: v.code, breeder: null, ecotype: 'SEMI_WINTER', qualityType: 'MEDIUM',
      controlType: v.control ?? null, remark: null,
    })
    varietyIds.push(saved.id)
  }

  // 实验田：8 行 × 6 列，第 1 行和第 8 行是保护区，中间 6 行分 3 个重复
  const detail = await api('POST', '/api/fields', {
    name: '东区品种试验田', location: '学校农场东区', latitude: 34.79, longitude: 113.65, altitudeM: 95,
    areaMu: 1.2, rowCount: ROWS, colCount: COLS, soilType: '壤土', irrigated: true, previousCrop: '玉米',
    remark: null, plotLengthM: 6.67, plotWidthM: 2,
  })
  const fieldId = detail.field.id
  const plotAt = (r, c) => detail.plots.find((p) => p.rowNo === r && p.colNo === c)
  const guardIds = detail.plots.filter((p) => p.rowNo === 1 || p.rowNo === ROWS).map((p) => p.id)
  await api('PATCH', `/api/fields/${fieldId}/plots`, { plotIds: guardIds, kind: 'GUARD' })
  const codes = {}
  for (const p of detail.plots) {
    if (p.rowNo !== 1 && p.rowNo !== ROWS) {
      codes[p.id] = String((p.rowNo - 1) * 100 + p.colNo)
    }
  }
  await api('PUT', `/api/fields/${fieldId}/plot-codes`, { codes })

  for (const season of SEASONS) {
    await seedSeason(fieldId, season, plotAt, varietyIds, trait)
    console.log(`${season.year} 年度试验已建好`)
  }
  console.log('完成。老师 laoshi、学生 xuesheng、游客 youke，密码 ' + DEMO_PASSWORD)
}

async function seedSeason(fieldId, season, plotAt, varietyIds, trait) {
  const trial = await api('POST', '/api/trials', {
    fieldId, seasonYear: season.year, name: `${season.year - 1}—${season.year} 年度品种比较试验`,
    trialType: 'COMPARISON', wheatZone: 'Q3', replicateCount: 3, remark: season.note || null,
  })
  const trialId = trial.id
  // 每个重复占 2 行 × 6 列 = 12 个小区，品种在重复内随机排列
  const layout = [] // { plotId, v }
  for (let rep = 1; rep <= 3; rep++) {
    const order = shuffle(VARIETIES.map((_, i) => i))
    let k = 0
    for (const r of [rep * 2, rep * 2 + 1]) {
      for (let c = 1; c <= COLS; c++) {
        layout.push({ plotId: plotAt(r, c).id, v: order[k++], rep })
      }
    }
  }
  for (const rep of [1, 2, 3]) {
    await api('PUT', `/api/trials/${trialId}/plantings`, {
      plotIds: layout.filter((x) => x.rep === rep).map((x) => x.plotId), attribute: 'REPLICATE', value: rep,
    })
  }
  for (let i = 0; i < VARIETIES.length; i++) {
    await api('PUT', `/api/trials/${trialId}/plantings`, {
      plotIds: layout.filter((x) => x.v === i).map((x) => x.plotId), attribute: 'VARIETY', value: varietyIds[i],
    })
  }
  // 对照关系：每个重复里，对照品种和它同一行的小区连起来
  for (const rep of [1, 2, 3]) {
    const ck = layout.find((x) => x.rep === rep && x.v === 0)
    const ckPlot = ck.plotId
    const row = [rep * 2, rep * 2 + 1].find((r) => [...Array(COLS)].some((_, c) => plotAt(r, c + 1).id === ckPlot))
    const rowPlots = [...Array(COLS)].map((_, c) => plotAt(row, c + 1).id)
    await api('POST', `/api/trials/${trialId}/control-links`, {
      plotIds: rowPlots, controlPlotIds: [ckPlot], label: `第 ${rep} 重复对照`,
    })
  }

  const sow = new Date(season.sow + 'T00:00:00Z')
  const today = new Date()
  const entry = async (code, plotIds, observedOn, value, note = null) => {
    if (observedOn > today) {
      return
    }
    await api('POST', '/api/observations', {
      trialId, traitId: trait(code).trait.id, items: plotIds.map((plotId) => ({ plotId, clientUuid: uuid() })),
      observedOn: iso(observedOn), note, valueNum: value.num ?? null, valueText: value.text ?? null,
      valueDate: value.date ? iso(value.date) : null,
    })
  }
  const allPlots = layout.map((x) => x.plotId)

  // 农事操作：同一天对所有小区
  await entry('op_fertilize', allPlots, addDays(sow, -2), { text: '复合肥', num: 40 }, '底肥')
  await entry('sowing_date', allPlots, sow, { date: sow })
  await entry('op_irrigate', allPlots, addDays(sow, 50), { text: '漫灌', num: 50 }, '冬灌')
  await entry('op_fertilize', allPlots, addDays(sow, 150), { text: '尿素', num: 12 }, '拔节追肥')
  await entry('op_pest_control', allPlots, addDays(sow, 195), { text: '一喷三防' })

  for (const x of layout) {
    const v = VARIETIES[x.v]
    const emerge = addDays(sow, 7 + Math.round(between(0, 2)))
    const heading = addDays(sow, 185 + v.late + Math.round(between(-1, 1)))
    const flower = addDays(heading, 5 + Math.round(between(0, 1)))
    const mature = addDays(sow, 232 + v.late + Math.round(between(-1, 1)))
    const steps = [
      ['emergence_date', emerge, { date: emerge }],
      ['basic_seedlings', addDays(sow, 25), { num: +between(19, 23).toFixed(1) }],
      ['max_tillers', addDays(sow, 140), { num: +between(85, 110).toFixed(1) }],
      ['heading_date', heading, { date: heading }],
      ['flowering_date', flower, { date: flower }],
      ['stripe_rust_reaction', addDays(heading, 15), { num: Math.min(5, Math.max(1, v.rust + Math.round(between(-0.6, 0.6)))) }],
      ['powdery_mildew', addDays(heading, 15), { num: Math.min(5, Math.max(1, Math.round(between(1, 3.4)))) }],
      ['plant_height', addDays(heading, 20), { num: Math.round(v.height + between(-3, 3)) }],
      ['maturity_date', mature, { date: mature }],
      ['effective_spikes', addDays(mature, -5), { num: +(between(40, 46) * (0.97 + 0.06 * v.yieldFactor - 0.03)).toFixed(1) }],
      ['lodging_final_grade', addDays(mature, -3), { num: v.height > 79 && rand() > 0.5 ? 2 : 1 }],
      ['grains_per_spike', addDays(mature, 10), { num: +between(30, 36).toFixed(1) }],
      ['thousand_grain_weight', addDays(mature, 12), { num: +(between(42, 49) * (0.96 + 0.04 * v.yieldFactor)).toFixed(1) }],
      ['test_weight', addDays(mature, 12), { num: Math.round(between(785, 815)) }],
      ['plot_yield', addDays(mature, 3), { num: +(season.yieldBase * v.yieldFactor * between(0.94, 1.06) * 13.34 / 666.7).toFixed(2) }],
    ]
    for (const [code, on, value] of steps) {
      await entry(code, [x.plotId], on, value)
    }
  }
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
