<script setup lang="ts">
// 实验田 3D 视角：可以自由转动、俯仰、缩放、平移，看实验田的立体样子。
//   小区画成立起来的方块：高度按长势（没种、生长中、已收获），"按指标"着色时按指标的值（像立体柱状图）；
//   道路、保护区、空地按类型铺；选中的小区抬高、变金色。
//   左键拖动转视角，右键（或按住 Ctrl 左键）拖动平移，滚轮缩放；单击选小区，Ctrl 单击加选，Shift 拖动框选，双击看详情。
// 坐标：1 个单位 = 1 米；布局的 x 向右是 X，y 向下是 Z，Y 朝上。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'

import { saveFile } from '@/shared/api/http'
import MapCompass from './MapCompass.vue'
import guardTileUrl from '@/assets/art/guard-tile.webp'
import roadTileUrl from '@/assets/art/road-tile.webp'
import {
  CELL_KIND, blockRect, blockSlot, fieldSize, innerCells, type CellKind, type FieldLayout, type Rect,
} from '@/shared/fieldLayout'
import type { CanvasCell, CanvasDevice, CanvasLink } from './FieldCanvas.vue'

export type CameraPreset = 'top' | 'oblique' | 'north' | 'east' | 'south' | 'west'

const props = withDefaults(defineProps<{
  layout: FieldLayout
  cells: Map<number, CanvasCell>
  /** 每个小区立起来多高（米） */
  heights: Map<number, number>
  selectedPlots?: number[]
  focus?: string | null
  links?: CanvasLink[]
  devices?: CanvasDevice[]
  /** 慢慢转着看（展示用） */
  autoRotate?: boolean
}>(), { selectedPlots: () => [], focus: null, links: () => [], devices: () => [], autoRotate: false })

const emit = defineEmits<{
  'update:selectedPlots': [ids: number[]]
  open: [plotId: number]
  contextmenu: [target: { plotId?: number; blockId?: string }, x: number, y: number]
  /** 显卡画不了 3D：回俯视图 */
  unsupported: []
}>()

const wrap = ref<HTMLDivElement | null>(null)
const labelsEl = ref<HTMLDivElement | null>(null)
const tipEl = ref<HTMLDivElement | null>(null)
const bandEl = ref<HTMLDivElement | null>(null)
const compassDeg = ref(0)
/** 这台电脑的显卡画不了 3D */
const failed = ref(false)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene
let camera: THREE.PerspectiveCamera
let controls: OrbitControls
let root: THREE.Group | null = null
let plotMesh: THREE.InstancedMesh | null = null
let hoverBox: THREE.LineSegments
let sun: THREE.DirectionalLight
let raf = 0
let dirty = true

const BG = new THREE.Color('#e9e3d3')
const GOLD = new THREE.Color('#e2b65a')
const PLATE = 0.04

// ---------- 坐标 ----------

/** 布局中心（布局单位）和 1 个单位多少米 */
let cx = 0
let cy = 0
let m = 1
let radius = 10
/** 高度放大几倍：地块几十米大，几十厘米高的麦子按真实比例几乎看不出来 */
let ex = 1

function world(x: number, y: number, h = 0) {
  return new THREE.Vector3((x - cx) * m, h, (y - cy) * m)
}

/** 北在世界里的方向：图纸上方是 −Z，顺时针转 northDeg 度 */
function northVec() {
  const r = ((props.layout.northDeg ?? 0) * Math.PI) / 180
  return new THREE.Vector3(Math.sin(r), 0, -Math.cos(r))
}

// ---------- 场景 ----------

const loader = new THREE.TextureLoader()
const textures: { road?: THREE.Texture; guard?: THREE.Texture } = {}
const disposables: { dispose: () => void }[] = []

function track<T extends { dispose: () => void }>(v: T): T {
  disposables.push(v)
  return v
}

function loadTexture(url: string, key: 'road' | 'guard') {
  loader.load(url, (t) => {
    t.wrapS = THREE.RepeatWrapping
    t.wrapT = THREE.RepeatWrapping
    t.colorSpace = THREE.SRGBColorSpace
    t.anisotropy = 4
    textures[key] = t
    build()
  })
}

/** 一块平铺的地面（道路、保护区、空地、区块底） */
function slab(r: Rect, height: number, base: number, kind: CellKind | 'PLATE'): THREE.Mesh {
  const w = r.w * m
  const d = r.h * m
  const geo = track(new THREE.BoxGeometry(w, height, d))
  let mat: THREE.MeshStandardMaterial
  const tile = kind === 'ROAD' ? textures.road : kind === 'GUARD' ? textures.guard : undefined
  if (tile) {
    const t = tile.clone()
    t.needsUpdate = true
    const size = kind === 'ROAD' ? 2 : 1.6
    t.repeat.set(w / size, d / size)
    track(t)
    mat = track(new THREE.MeshStandardMaterial({ map: t, roughness: 0.95 }))
  } else {
    const color = kind === 'PLATE' ? '#a58d62' : kind === 'BLANK' ? '#e6dcc4' : kind === 'GUARD' ? '#a9c48f' : '#d9cfb9'
    mat = track(new THREE.MeshStandardMaterial({ color, roughness: 0.95 }))
  }
  const mesh = new THREE.Mesh(geo, mat)
  mesh.position.copy(world(r.x + r.w / 2, r.y + r.h / 2, base + height / 2))
  mesh.receiveShadow = true
  mesh.castShadow = kind === 'GUARD'
  return mesh
}

interface Inst {
  plotId: number | null
  blockId: string
  kind: CellKind
  rect: Rect
  height: number
  color: THREE.Color
}

let insts: Inst[] = []
const instByPlot = new Map<number, number>()
let blockLabels: { el: HTMLDivElement; pos: THREE.Vector3 }[] = []
let plotLabels: { el: HTMLDivElement; idx: number }[] = []
let dirLabels: { el: HTMLDivElement; pos: THREE.Vector3 }[] = []

function disposeScene() {
  if (root) scene.remove(root)
  root = null
  plotMesh = null
  for (const d of disposables.splice(0)) d.dispose()
  if (labelsEl.value) labelsEl.value.innerHTML = ''
  blockLabels = []
  plotLabels = []
  dirLabels = []
}

function label(text: string, cls: string): HTMLDivElement {
  const el = document.createElement('div')
  el.className = `lbl ${cls}`
  el.textContent = text
  labelsEl.value?.appendChild(el)
  return el
}

function build() {
  if (!renderer) return
  disposeScene()
  const l = props.layout
  const s = fieldSize(l)
  m = l.unit.meters
  cx = s.w / 2
  cy = s.h / 2
  radius = Math.max(4, Math.hypot(s.w * m, s.h * m) / 2)
  // 高度放大得太多，小区立起来像一根根柱子，看着变形：大地块最多放大 2.5 倍
  ex = Math.min(2.5, Math.max(1, radius / 12))
  root = new THREE.Group()

  // 地面和整块地的底
  const groundGeo = track(new THREE.PlaneGeometry(radius * 12, radius * 12))
  const ground = new THREE.Mesh(groundGeo, track(new THREE.MeshStandardMaterial({ color: '#bfae84', roughness: 1 })))
  ground.rotation.x = -Math.PI / 2
  ground.position.y = -0.02
  ground.receiveShadow = true
  root.add(ground)
  root.add(slab({ x: 0, y: 0, w: s.w, h: s.h }, 0.06, -0.06, 'PLATE'))

  insts = []
  instByPlot.clear()
  for (const b of l.blocks) {
    const slot = blockSlot(l, b)
    const rect = blockRect(l, b)
    if (b.fit) {
      // 特殊区块：槽位里其余部分是道路
      root.add(slab(slot, 0.02, 0, 'ROAD'))
    }
    if (b.kind !== 'PLANT') {
      root.add(slab(rect, b.kind === 'GUARD' ? 0.3 * ex : 0.02, 0, b.kind))
      continue
    }
    root.add(slab(rect, PLATE, 0, 'PLATE'))
    for (const c of innerCells(l, b)) {
      const info = c.plotId != null ? props.cells.get(c.plotId) : undefined
      if (c.kind !== 'PLANT') {
        root.add(slab(c.rect, c.kind === 'GUARD' ? 0.3 * ex : 0.02, PLATE, c.kind))
        continue
      }
      const h = c.plotId != null ? props.heights.get(c.plotId) ?? 0.3 : 0.05
      const color = vivid(new THREE.Color(info?.fill ?? '#f4eedc'))
      if (c.plotId != null) instByPlot.set(c.plotId, insts.length)
      insts.push({ plotId: c.plotId, blockId: b.id, kind: c.kind, rect: c.rect, height: Math.max(0.02, h * ex), color })
    }
    if (labelsEl.value) {
      blockLabels.push({ el: label(b.name, 'block'), pos: world(rect.x + rect.w / 2, rect.y, 0.6) })
    }
  }

  // 小区：一个实例化网格画全部
  const geo = track(new THREE.BoxGeometry(1, 1, 1))
  const mat = track(new THREE.MeshStandardMaterial({ roughness: 0.88, metalness: 0 }))
  plotMesh = new THREE.InstancedMesh(geo, mat, Math.max(1, insts.length))
  plotMesh.count = insts.length
  plotMesh.castShadow = true
  plotMesh.receiveShadow = true
  root.add(plotMesh)
  applyInstances()

  // 对照关系：小区之间拱起来的虚线
  for (const link of props.links) {
    for (const seg of link.segments) {
      const a = instByPlot.get(seg.from)
      const b = instByPlot.get(seg.to)
      if (a == null || b == null) continue
      const ra = insts[a]!.rect
      const rb = insts[b]!.rect
      const pa = world(ra.x + ra.w / 2, ra.y + ra.h / 2, PLATE + insts[a]!.height + 0.05)
      const pb = world(rb.x + rb.w / 2, rb.y + rb.h / 2, PLATE + insts[b]!.height + 0.05)
      const mid = pa.clone().add(pb).multiplyScalar(0.5)
      mid.y += Math.max(0.6 * ex, pa.distanceTo(pb) * 0.25)
      const curve = new THREE.QuadraticBezierCurve3(pa, mid, pb)
      const lineGeo = track(new THREE.BufferGeometry().setFromPoints(curve.getPoints(32)))
      const line = new THREE.Line(lineGeo, track(new THREE.LineDashedMaterial({ color: '#c0562f', dashSize: 0.4, gapSize: 0.25 })))
      line.computeLineDistances()
      root.add(line)
    }
  }

  // 传感器：一根杆子顶上一个球
  for (const d of props.devices) {
    const p = world(d.x, d.y, 0)
    const pole = new THREE.Mesh(track(new THREE.CylinderGeometry(0.03, 0.03, 1.4, 8)), track(new THREE.MeshStandardMaterial({ color: '#6b6450' })))
    pole.position.set(p.x, 0.7, p.z)
    const ball = new THREE.Mesh(track(new THREE.SphereGeometry(0.16, 16, 12)), track(new THREE.MeshStandardMaterial({ color: d.color, emissive: d.color, emissiveIntensity: 0.3 })))
    ball.position.set(p.x, 1.45, p.z)
    pole.castShadow = true
    root.add(pole, ball)
  }

  // 东南西北：放在地块外面一圈
  if (labelsEl.value) {
    const n = northVec()
    const e = new THREE.Vector3(-n.z, 0, n.x)
    const k = radius * 1.12
    for (const [text, v] of [['北', n], ['东', e], ['南', n.clone().negate()], ['西', e.clone().negate()]] as [string, THREE.Vector3][]) {
      dirLabels.push({ el: label(text, text === '北' ? 'dir north' : 'dir'), pos: v.clone().multiplyScalar(k) })
    }
    // 小区编号：靠近时才显示，先建一池
    for (let i = 0; i < Math.min(insts.length, 400); i++) plotLabels.push({ el: label('', 'plot'), idx: -1 })
  }

  scene.add(root)
  // 阴影只罩住地块
  const sc = sun.shadow.camera
  sc.left = -radius * 1.3
  sc.right = radius * 1.3
  sc.top = radius * 1.3
  sc.bottom = -radius * 1.3
  sc.far = radius * 8
  sun.position.set(radius * 1.6, radius * 1.7, radius * 1.3)
  sc.updateProjectionMatrix()
  scene.fog = new THREE.Fog(BG, radius * 3, radius * 9)
  controls.minDistance = Math.max(1.5, radius * 0.08)
  controls.maxDistance = radius * 6
  camera.far = radius * 20
  camera.updateProjectionMatrix()
  dirty = true
}

/** 平面图的颜色偏淡，立起来以后加一点饱和度、压一点亮度，看得更清楚 */
const hsl = { h: 0, s: 0, l: 0 }
function vivid(c: THREE.Color): THREE.Color {
  c.getHSL(hsl)
  return c.setHSL(hsl.h, Math.min(1, hsl.s * 1.45), hsl.l * 0.9)
}

const tmp = new THREE.Object3D()
const tint = new THREE.Color()

/** 选中的小区抬多高 */
const lift = () => 0.35 * ex

/** 按选中情况放好每个小区：选中的抬高、偏金色 */
function applyInstances() {
  if (!plotMesh) return
  const sel = new Set(props.selectedPlots)
  insts.forEach((it, i) => {
    const on = it.plotId != null && sel.has(it.plotId)
    const h = it.height + (on ? lift() : 0)
    const r = it.rect
    const gap = Math.min(r.w, r.h) * m * 0.1
    tmp.position.copy(world(r.x + r.w / 2, r.y + r.h / 2, PLATE + h / 2))
    tmp.scale.set(Math.max(0.05, r.w * m - gap), h, Math.max(0.05, r.h * m - gap))
    tmp.updateMatrix()
    plotMesh!.setMatrixAt(i, tmp.matrix)
    tint.copy(it.color)
    if (on) tint.lerp(GOLD, 0.55)
    if (it.plotId == null) tint.set('#fbf7ee')
    plotMesh!.setColorAt(i, tint)
  })
  plotMesh.instanceMatrix.needsUpdate = true
  if (plotMesh.instanceColor) plotMesh.instanceColor.needsUpdate = true
  plotMesh.computeBoundingSphere()
  dirty = true
}

// ---------- 相机 ----------

/** 能放下整块地的距离 */
function fitDistance(r = radius) {
  const fov = (camera.fov * Math.PI) / 180
  const aspect = Math.max(0.3, camera.aspect)
  const hfov = 2 * Math.atan(Math.tan(fov / 2) * aspect)
  return (r * 1.15) / Math.sin(Math.min(fov, hfov) / 2)
}

let tween: { from: THREE.Vector3; to: THREE.Vector3; tf: THREE.Vector3; tt: THREE.Vector3; t0: number; ms: number } | null = null

/**
 * 从 dir 方向看 target，正好放下这片矩形（布局坐标）的距离：把矩形的角投影到画面上，
 * 按超出多少调整距离，迭代几次。比按外接球算紧凑，窄长的地块也能占满画面
 */
function fitAlong(dir: THREE.Vector3, target: THREE.Vector3, r: Rect, top = 0.6): number {
  const cam = camera.clone()
  const corners: THREE.Vector3[] = []
  for (const [x, y] of [[r.x, r.y], [r.x + r.w, r.y], [r.x, r.y + r.h], [r.x + r.w, r.y + r.h]]) {
    corners.push(world(x!, y!, 0), world(x!, y!, top * ex))
  }
  let d = fitDistance(Math.max(2, (Math.hypot(r.w, r.h) * m) / 2))
  const p = new THREE.Vector3()
  for (let i = 0; i < 4; i++) {
    cam.position.copy(target).addScaledVector(dir, d)
    cam.up.set(0, 1, 0)
    cam.lookAt(target)
    cam.updateMatrixWorld()
    let e = 0
    for (const c of corners) {
      p.copy(c).project(cam)
      e = Math.max(e, Math.abs(p.x), Math.abs(p.y))
    }
    d *= Math.max(0.3, e / 0.82)
  }
  return THREE.MathUtils.clamp(d, controls.minDistance, controls.maxDistance)
}

function fieldRect(): Rect {
  const s = fieldSize(props.layout)
  return { x: 0, y: 0, w: s.w, h: s.h }
}

function flyTo(pos: THREE.Vector3, target: THREE.Vector3, ms = 650) {
  tween = { from: camera.position.clone(), to: pos, tf: controls.target.clone(), tt: target, t0: performance.now(), ms }
}

/** 从 bearing 方向（从北顺时针的度数）、仰角 elevation 度看 target */
/** 从 phi（离正上方的角度）、theta（方位）看整块地 */
function viewSpherical(phi: number, theta: number, r = fieldRect()) {
  const target = world(r.x + r.w / 2, r.y + r.h / 2, 0)
  const dir = new THREE.Vector3().setFromSphericalCoords(1, Math.max(0.0001, phi), theta)
  flyTo(target.clone().addScaledVector(dir, fitAlong(dir, target, r)), target)
}

function viewFrom(bearing: number, elevation: number) {
  const nd = ((props.layout.northDeg ?? 0) * Math.PI) / 180
  viewSpherical(((90 - elevation) * Math.PI) / 180, Math.PI - (nd + (bearing * Math.PI) / 180))
}

/** 默认：从图纸的东南（右下）斜着看，长条的地块也能看清两个方向 */
function home() {
  viewSpherical((48 * Math.PI) / 180, 0.55)
}

function preset(p: CameraPreset) {
  if (p === 'top') viewSpherical(0.0001, 0)
  else if (p === 'oblique') home()
  else viewFrom({ north: 0, east: 90, south: 180, west: 270 }[p], 35)
}

function zoomBy(factor: number) {
  const off = camera.position.clone().sub(controls.target)
  const len = THREE.MathUtils.clamp(off.length() / factor, controls.minDistance, controls.maxDistance)
  flyTo(controls.target.clone().add(off.setLength(len)), controls.target.clone(), 250)
}

function zoomToPlots(ids: number[]) {
  const rects = ids.map((id) => instByPlot.get(id)).filter((i): i is number => i != null).map((i) => insts[i]!.rect)
  if (rects.length === 0) return
  const x = Math.min(...rects.map((r) => r.x))
  const y = Math.min(...rects.map((r) => r.y))
  const x2 = Math.max(...rects.map((r) => r.x + r.w))
  const y2 = Math.max(...rects.map((r) => r.y + r.h))
  const r: Rect = { x, y, w: x2 - x, h: y2 - y }
  const target = world(x + r.w / 2, y + r.h / 2, 0)
  const dir = camera.position.clone().sub(controls.target).normalize()
  flyTo(target.clone().addScaledVector(dir, fitAlong(dir, target, r) * 1.6), target)
}

watch(() => props.focus, (id) => {
  const b = id ? props.layout.blocks.find((x) => x.id === id) : null
  if (!b) {
    home()
    return
  }
  const r = blockRect(props.layout, b)
  const target = world(r.x + r.w / 2, r.y + r.h / 2, 0)
  const dir = camera.position.clone().sub(controls.target).normalize()
  flyTo(target.clone().addScaledVector(dir, fitAlong(dir, target, r)), target)
})

// ---------- 每一帧 ----------

const v = new THREE.Vector3()
const size = { w: 800, h: 600 }

function project(p: THREE.Vector3): { x: number; y: number; z: number } {
  v.copy(p).project(camera)
  return { x: (v.x * 0.5 + 0.5) * size.w, y: (-v.y * 0.5 + 0.5) * size.h, z: v.z }
}

function place(el: HTMLDivElement, p: { x: number; y: number; z: number }, show = true) {
  if (!show || p.z > 1 || p.x < -40 || p.y < -20 || p.x > size.w + 40 || p.y > size.h + 20) {
    el.style.display = 'none'
    return
  }
  el.style.display = 'block'
  el.style.transform = `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px) translate(-50%, -50%)`
}

function updateLabels() {
  for (const b of blockLabels) place(b.el, project(b.pos))
  for (const d of dirLabels) place(d.el, project(d.pos))
  // 小区编号：屏幕上够大的才写，最近的优先
  const focal = size.h / 2 / Math.tan((camera.fov * Math.PI) / 360)
  const camPos = camera.position
  const shown: { idx: number; d: number; px: number }[] = []
  insts.forEach((it, i) => {
    if (it.plotId == null) return
    const r = it.rect
    const p = world(r.x + r.w / 2, r.y + r.h / 2, PLATE + it.height)
    const d = p.distanceTo(camPos)
    const px = (Math.min(r.w, r.h) * m * focal) / d
    if (px > 26) shown.push({ idx: i, d, px })
  })
  shown.sort((a, b) => a.d - b.d)
  plotLabels.forEach((pl, k) => {
    const s = shown[k]
    if (!s) {
      pl.el.style.display = 'none'
      pl.idx = -1
      return
    }
    const it = insts[s.idx]!
    // 够大时编号后面写上品种（品系）名
    const lines = props.cells.get(it.plotId!)?.lines ?? []
    const text = s.px > 64 && lines[1] && lines[1] !== '—' ? `${lines[0]} ${lines[1]}` : lines[0] ?? ''
    if (pl.idx !== s.idx || pl.el.textContent !== text) {
      pl.idx = s.idx
      pl.el.textContent = text
    }
    const r = it.rect
    const sel = props.selectedPlots.includes(it.plotId!)
    place(pl.el, project(world(r.x + r.w / 2, r.y + r.h / 2, PLATE + it.height + (sel ? lift() : 0) + 0.02)))
  })
  // 指北针：屏幕上北朝哪（和俯视图一样的罗盘）
  const a = project(controls.target)
  const b = project(controls.target.clone().add(northVec().multiplyScalar(radius * 0.3)))
  const deg = (Math.atan2(b.x - a.x, -(b.y - a.y)) * 180) / Math.PI
  if (Math.abs(deg - compassDeg.value) > 0.5) compassDeg.value = deg
}

function loop(now: number) {
  raf = requestAnimationFrame(loop)
  if (!renderer) return
  if (tween) {
    const t = Math.min(1, (now - tween.t0) / tween.ms)
    const e = t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2
    camera.position.lerpVectors(tween.from, tween.to, e)
    controls.target.lerpVectors(tween.tf, tween.tt, e)
    if (t >= 1) tween = null
    dirty = true
  }
  if (controls.update()) dirty = true
  if (!dirty) return
  dirty = false
  renderer.render(scene, camera)
  updateLabels()
}

// ---------- 鼠标 ----------

const raycaster = new THREE.Raycaster()
const ndc = new THREE.Vector2()

function local(e: MouseEvent) {
  const r = wrap.value!.getBoundingClientRect()
  return { x: e.clientX - r.left, y: e.clientY - r.top }
}

function pickAt(p: { x: number; y: number }): Inst | null {
  if (!plotMesh) return null
  ndc.set((p.x / size.w) * 2 - 1, -(p.y / size.h) * 2 + 1)
  raycaster.setFromCamera(ndc, camera)
  const hit = raycaster.intersectObject(plotMesh, false)[0]
  return hit?.instanceId != null ? insts[hit.instanceId] ?? null : null
}

let press: { x: number; y: number; button: number; band: boolean; additive: boolean } | null = null

function onPointerDown(e: PointerEvent) {
  const p = local(e)
  hideTip()
  const band = e.button === 0 && e.shiftKey
  press = { x: p.x, y: p.y, button: e.button, band, additive: e.ctrlKey || e.metaKey || e.shiftKey }
  // Shift 拖动是框选，不让相机跟着动
  controls.enabled = !band
}

function onPointerMove(e: PointerEvent) {
  const p = local(e)
  if (press?.band) {
    const el = bandEl.value!
    el.style.display = 'block'
    el.style.left = `${Math.min(press.x, p.x)}px`
    el.style.top = `${Math.min(press.y, p.y)}px`
    el.style.width = `${Math.abs(p.x - press.x)}px`
    el.style.height = `${Math.abs(p.y - press.y)}px`
    return
  }
  if (e.buttons) return
  hoverAt(p)
}

function onPointerUp(e: PointerEvent) {
  const p = local(e)
  const pr = press
  press = null
  controls.enabled = true
  if (bandEl.value) bandEl.value.style.display = 'none'
  if (!pr) return
  const movedFar = Math.hypot(p.x - pr.x, p.y - pr.y) > 4
  if (pr.band && movedFar) {
    const [l, r] = [Math.min(pr.x, p.x), Math.max(pr.x, p.x)]
    const [t, b] = [Math.min(pr.y, p.y), Math.max(pr.y, p.y)]
    const ids = insts.filter((it) => {
      if (it.plotId == null) return false
      const c = project(world(it.rect.x + it.rect.w / 2, it.rect.y + it.rect.h / 2, PLATE + it.height))
      return c.z < 1 && c.x >= l && c.x <= r && c.y >= t && c.y <= b
    }).map((it) => it.plotId!)
    emit('update:selectedPlots', [...new Set([...props.selectedPlots, ...ids])])
    return
  }
  if (pr.button !== 0 || movedFar) return
  const it = pickAt(p)
  const id = it?.plotId ?? null
  if (id == null) {
    if (!pr.additive) emit('update:selectedPlots', [])
    return
  }
  if (pr.additive) {
    emit('update:selectedPlots', props.selectedPlots.includes(id) ? props.selectedPlots.filter((x) => x !== id) : [...props.selectedPlots, id])
  } else {
    emit('update:selectedPlots', [id])
  }
}

function onDblClick(e: MouseEvent) {
  const it = pickAt(local(e))
  if (it?.plotId != null) emit('open', it.plotId)
}

let downAt: { x: number; y: number } | null = null

function onContextDown(e: PointerEvent) {
  if (e.button === 2) downAt = local(e)
}

function onContextMenu(e: MouseEvent) {
  e.preventDefault()
  const p = local(e)
  // 右键拖动是平移，松手时不弹菜单
  if (downAt && Math.hypot(p.x - downAt.x, p.y - downAt.y) > 4) return
  const it = pickAt(p)
  if (it?.plotId == null) return
  if (!props.selectedPlots.includes(it.plotId)) emit('update:selectedPlots', [it.plotId])
  emit('contextmenu', { plotId: it.plotId, blockId: it.blockId }, e.clientX, e.clientY)
}

let hovered = -1

function hoverAt(p: { x: number; y: number }) {
  const it = pickAt(p)
  const idx = it ? insts.indexOf(it) : -1
  if (idx !== hovered) {
    hovered = idx
    if (it) {
      const r = it.rect
      const sel = it.plotId != null && props.selectedPlots.includes(it.plotId)
      const h = it.height + (sel ? lift() : 0)
      hoverBox.position.copy(world(r.x + r.w / 2, r.y + r.h / 2, PLATE + h / 2))
      hoverBox.scale.set(r.w * m, h + 0.02, r.h * m)
      hoverBox.visible = true
    } else {
      hoverBox.visible = false
    }
    dirty = true
  }
  if (!it) {
    hideTip()
    return
  }
  const text = it.plotId != null ? props.cells.get(it.plotId)?.tooltip ?? '' : it.kind === 'PLANT' ? '新小区' : CELL_KIND[it.kind]
  const el = tipEl.value
  if (!el || !text) {
    hideTip()
    return
  }
  el.textContent = text
  el.style.display = 'block'
  el.style.transform = `translate(${Math.round(p.x > size.w - 250 ? p.x - 240 : p.x + 16)}px, ${Math.round(p.y > size.h - 130 ? p.y - 120 : p.y + 16)}px)`
}

function hideTip() {
  if (tipEl.value) tipEl.value.style.display = 'none'
}

function onLeave() {
  hideTip()
  if (hovered !== -1) {
    hovered = -1
    hoverBox.visible = false
    dirty = true
  }
}

// ---------- 导出 ----------

function exportJpeg(filename: string) {
  if (!renderer) return
  hoverBox.visible = false
  renderer.render(scene, camera)
  saveFile(renderer.domElement.toDataURL('image/jpeg', 0.92), filename)
  dirty = true
}

defineExpose({ preset, home, zoomBy, zoomToPlots, exportJpeg })

// 开发时给自动化测试用：小区中心在画面上的位置、点一下能选中什么
if (import.meta.env.DEV) {
  ;(window as unknown as { __scene3d?: unknown }).__scene3d = {
    pick: (x: number, y: number) => pickAt({ x, y })?.plotId ?? null,
    screenOfPlot: (id: number) => {
      const i = instByPlot.get(id)
      if (i == null) return null
      const it = insts[i]!
      return project(world(it.rect.x + it.rect.w / 2, it.rect.y + it.rect.h / 2, PLATE + it.height))
    },
    count: () => insts.length,
    plotIds: () => insts.filter((i) => i.plotId != null).map((i) => i.plotId!),
  }
}

// ---------- 生命周期 ----------

let observer: ResizeObserver | null = null

onMounted(() => {
  const el = wrap.value!
  size.w = Math.max(200, el.clientWidth)
  size.h = Math.max(200, el.clientHeight)
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true })
  } catch {
    failed.value = true
    emit('unsupported')
    return
  }
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1))
  renderer.setSize(size.w, size.h)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  // 中性色调映射：保留平面图那套颜色，不像 ACES 那样发灰
  renderer.toneMapping = THREE.NeutralToneMapping
  renderer.toneMappingExposure = 1
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  el.prepend(renderer.domElement)

  scene = new THREE.Scene()
  scene.background = BG
  camera = new THREE.PerspectiveCamera(38, size.w / size.h, 0.1, 2000)
  camera.position.set(0, 30, 40)

  scene.add(new THREE.HemisphereLight('#fff8ea', '#7d6c48', 0.85))
  sun = new THREE.DirectionalLight('#fff0d2', 2.9)
  sun.castShadow = true
  sun.shadow.mapSize.set(2048, 2048)
  sun.shadow.bias = -0.0005
  sun.shadow.normalBias = 0.02
  scene.add(sun)

  hoverBox = new THREE.LineSegments(
    new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)),
    new THREE.LineBasicMaterial({ color: '#1c4231' }),
  )
  hoverBox.visible = false
  scene.add(hoverBox)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.09
  controls.maxPolarAngle = (86 * Math.PI) / 180
  controls.screenSpacePanning = false
  controls.zoomToCursor = true
  controls.autoRotate = props.autoRotate
  controls.autoRotateSpeed = 0.7
  controls.mouseButtons = { LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN }
  controls.addEventListener('change', () => { dirty = true })

  const canvas = renderer.domElement
  canvas.addEventListener('pointerdown', onContextDown)
  // 框选要在相机控制之前拦下来
  el.addEventListener('pointerdown', onPointerDown, { capture: true })
  canvas.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  canvas.addEventListener('dblclick', onDblClick)
  canvas.addEventListener('contextmenu', onContextMenu)
  canvas.addEventListener('pointerleave', onLeave)

  loadTexture(roadTileUrl, 'road')
  loadTexture(guardTileUrl, 'guard')
  build()
  const start = new THREE.Vector3().setFromSphericalCoords(fitDistance() * 1.6, (60 * Math.PI) / 180, 0.35)
  camera.position.copy(start)
  home()
  raf = requestAnimationFrame(loop)

  observer = new ResizeObserver(() => {
    if (!renderer) return
    size.w = Math.max(200, el.clientWidth)
    size.h = Math.max(200, el.clientHeight)
    renderer.setSize(size.w, size.h)
    camera.aspect = size.w / size.h
    camera.updateProjectionMatrix()
    dirty = true
  })
  observer.observe(el)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  observer?.disconnect()
  window.removeEventListener('pointerup', onPointerUp)
  wrap.value?.removeEventListener('pointerdown', onPointerDown, { capture: true })
  disposeScene()
  controls?.dispose()
  hoverBox?.geometry.dispose()
  textures.road?.dispose()
  textures.guard?.dispose()
  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null
})

watch(() => [props.layout, props.cells, props.heights, props.links, props.devices], build)
watch(() => props.selectedPlots, applyInstances)
watch(() => props.autoRotate, (v) => {
  if (controls) controls.autoRotate = v
})
</script>

<template>
  <div ref="wrap" class="scene3d" @contextmenu.prevent>
    <div ref="labelsEl" class="labels" />
    <div ref="bandEl" class="band" />
    <div ref="tipEl" class="tip" />
    <MapCompass
      v-if="!failed"
      class="compass"
      :north-deg="compassDeg"
      :rotation="0"
      :rotatable="false"
      :size="84"
      title="点一下：正北朝上，从南往北看"
      @north-up="preset('south')"
    />
    <div v-if="failed" class="failed">这台电脑的显卡画不了 3D（不支持 WebGL），请用俯视图。</div>
    <slot />
  </div>
</template>

<style scoped>
.scene3d {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  user-select: none;
  touch-action: none;
  background: #ece4d1;
}

.scene3d :deep(canvas) {
  display: block;
  outline: none;
}

.labels {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.labels :deep(.lbl) {
  position: absolute;
  left: 0;
  top: 0;
  display: none;
  white-space: nowrap;
  will-change: transform;
}

.labels :deep(.lbl.block) {
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(28, 66, 49, 0.88);
  color: #fbf3df;
  font-size: 12px;
  font-weight: 700;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.18);
}

.labels :deep(.lbl.plot) {
  font-size: 11.5px;
  font-weight: 600;
  color: #1f2a22;
  text-shadow: 0 0 3px rgba(255, 253, 245, 0.95), 0 0 2px rgba(255, 253, 245, 0.95);
}

.labels :deep(.lbl.dir) {
  width: 28px;
  height: 28px;
  line-height: 28px;
  text-align: center;
  border-radius: 50%;
  background: rgba(255, 253, 248, 0.92);
  border: 1px solid rgba(90, 70, 30, 0.25);
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-weight: 700;
  font-size: 14px;
}

.labels :deep(.lbl.dir.north) {
  background: #b0543a;
  color: #fff;
  border-color: #8f3f28;
}

.band {
  display: none;
  position: absolute;
  z-index: 2;
  background: rgba(46, 107, 79, 0.1);
  border: 1.5px dashed #2e6b4f;
  pointer-events: none;
}

.tip {
  display: none;
  position: absolute;
  left: 0;
  top: 0;
  z-index: 3;
  max-width: 280px;
  padding: 8px 11px;
  border-radius: 9px;
  background: rgba(24, 58, 43, 0.95);
  color: #f6efdc;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-line;
  pointer-events: none;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.22);
}

.compass {
  position: absolute;
  right: 16px;
  bottom: 120px;
  z-index: 2;
}

.failed {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--ink-2);
  font-size: var(--fs-md);
}
</style>
