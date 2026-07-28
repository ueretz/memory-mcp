<script setup lang="ts">
import { drag as d3drag } from 'd3-drag'
import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from 'd3-force'
import { select } from 'd3-selection'
import { zoom as d3zoom, zoomIdentity, type ZoomTransform } from 'd3-zoom'
import { onBeforeUnmount, ref, shallowRef, triggerRef, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import type { GraphResponse, MemoryType } from '@/api/types'
import { entryHref } from '@/lib/links'

interface SimNode extends SimulationNodeDatum {
  name: string
  type: MemoryType
  degree: number
}

const props = defineProps<{
  graph: GraphResponse
  projectScope: string
  taskKey?: string | null
}>()

const router = useRouter()

const svgRef = useTemplateRef<SVGSVGElement>('svg')
const nodes = shallowRef<SimNode[]>([])
const links = shallowRef<SimulationLinkDatum<SimNode>[]>([])
const transform = ref<ZoomTransform>(zoomIdentity)
const hovered = ref<string | null>(null)
const size = ref({ width: 900, height: 560 })

const NODE_FILL: Record<MemoryType, string> = {
  USER: 'fill-type-user',
  FEEDBACK: 'fill-type-feedback',
  PROJECT: 'fill-type-project',
  REFERENCE: 'fill-type-reference',
  LOCATION: 'fill-type-location',
}

let simulation: Simulation<SimNode, SimulationLinkDatum<SimNode>> | null = null
let observer: ResizeObserver | null = null
let zoomBehaviour: ReturnType<typeof d3zoom<SVGSVGElement, unknown>> | null = null
let dragged = false

function radius(node: SimNode): number {
  return node.type === 'LOCATION' ? 5 : 7 + Math.min(node.degree, 6)
}

function nodeOf(end: string | number | SimNode): SimNode {
  return end as SimNode
}

function build() {
  simulation?.stop()

  const degrees = new Map<string, number>()
  for (const edge of props.graph.edges) {
    degrees.set(edge.source, (degrees.get(edge.source) ?? 0) + 1)
    degrees.set(edge.target, (degrees.get(edge.target) ?? 0) + 1)
  }

  const simNodes: SimNode[] = props.graph.nodes.map((node) => ({
    name: node.name,
    type: node.type,
    degree: degrees.get(node.name) ?? 0,
  }))
  const byName = new Map(simNodes.map((node) => [node.name, node]))
  const simLinks: SimulationLinkDatum<SimNode>[] = props.graph.edges
    .filter((edge) => byName.has(edge.source) && byName.has(edge.target))
    .map((edge) => ({ source: byName.get(edge.source)!, target: byName.get(edge.target)! }))

  nodes.value = simNodes
  links.value = simLinks

  const { width, height } = size.value
  simulation = forceSimulation(simNodes)
    .force('charge', forceManyBody<SimNode>().strength(-420).distanceMax(600))
    .force(
      'link',
      forceLink<SimNode, SimulationLinkDatum<SimNode>>(simLinks)
        .id((node) => node.name)
        .distance(110)
        .strength(0.4),
    )
    .force('center', forceCenter(width / 2, height / 2))
    .force('collide', forceCollide<SimNode>().radius((node) => radius(node) + 22))
    .on('tick', () => {
      triggerRef(nodes)
      triggerRef(links)
    })
}

function attachZoom() {
  const svg = svgRef.value
  if (!svg) {
    return
  }
  zoomBehaviour = d3zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.25, 4])
    // Node drags must not pan the canvas.
    .filter((event: Event) => !(event.target as Element | null)?.closest('.graph-node'))
    .on('zoom', (event: { transform: ZoomTransform }) => {
      transform.value = event.transform
    })
  select(svg).call(zoomBehaviour).on('dblclick.zoom', null)
}

function attachDrag(element: SVGGElement | null, node: SimNode) {
  if (!element) {
    return
  }
  select(element).call(
    d3drag<SVGGElement, unknown>()
      .on('start', () => {
        dragged = false
        simulation?.alphaTarget(0.25).restart()
        node.fx = node.x
        node.fy = node.y
      })
      .on('drag', (event: { dx: number; dy: number }) => {
        dragged = true
        node.fx = (node.fx ?? 0) + event.dx / transform.value.k
        node.fy = (node.fy ?? 0) + event.dy / transform.value.k
      })
      .on('end', () => {
        simulation?.alphaTarget(0)
        node.fx = null
        node.fy = null
      }),
  )
}

function openEntry(node: SimNode) {
  if (dragged) {
    return
  }
  const href = entryHref({
    name: node.name,
    projectScope: props.projectScope,
    taskKey: props.taskKey ?? null,
  })
  if (href) {
    void router.push(href)
  }
}

/** Resets pan/zoom and gives the layout a nudge so it re-settles in view. */
function fit() {
  const svg = svgRef.value
  if (svg && zoomBehaviour) {
    select(svg).call(zoomBehaviour.transform, zoomIdentity)
  }
  simulation?.alpha(0.5).restart()
}

const container = useTemplateRef<HTMLDivElement>('container')

watch(
  () => props.graph,
  () => build(),
  { immediate: true },
)

watch(container, (element) => {
  observer?.disconnect()
  if (!element) {
    return
  }
  observer = new ResizeObserver(([entry]) => {
    const box = entry.contentRect
    size.value = { width: box.width, height: box.height }
    simulation?.force('center', forceCenter(box.width / 2, box.height / 2)).alpha(0.3).restart()
  })
  observer.observe(element)
  attachZoom()
})

onBeforeUnmount(() => {
  simulation?.stop()
  observer?.disconnect()
})

defineExpose({ fit })
</script>

<template>
  <div ref="container" class="relative h-[clamp(24rem,62vh,44rem)] w-full">
    <svg
      ref="svg"
      class="size-full cursor-grab touch-none select-none active:cursor-grabbing"
      :viewBox="`0 0 ${size.width} ${size.height}`"
    >
      <g :transform="`translate(${transform.x},${transform.y}) scale(${transform.k})`">
        <line
          v-for="(link, index) in links"
          :key="index"
          class="stroke-border-strong transition-[stroke]"
          :class="{
            'stroke-accent': hovered === nodeOf(link.source).name || hovered === nodeOf(link.target).name,
          }"
          stroke-width="1.2"
          :x1="nodeOf(link.source).x ?? 0"
          :y1="nodeOf(link.source).y ?? 0"
          :x2="nodeOf(link.target).x ?? 0"
          :y2="nodeOf(link.target).y ?? 0"
        />

        <g
          v-for="node in nodes"
          :key="node.name"
          :ref="(element) => attachDrag(element as SVGGElement | null, node)"
          class="graph-node cursor-pointer"
          :transform="`translate(${node.x ?? 0},${node.y ?? 0})`"
          @mouseenter="hovered = node.name"
          @mouseleave="hovered = null"
          @click="openEntry(node)"
        >
          <circle
            :r="radius(node) + 6"
            class="fill-accent/20 transition-opacity"
            :class="hovered === node.name ? 'opacity-100' : 'opacity-0'"
          />
          <circle
            :r="radius(node)"
            :class="NODE_FILL[node.type]"
            class="stroke-bg"
            stroke-width="2"
          />
          <text
            :y="-radius(node) - 8"
            text-anchor="middle"
            class="pointer-events-none fill-muted text-[10px] font-medium"
            :class="{ 'fill-content': hovered === node.name }"
          >
            {{ node.name.length > 28 ? `${node.name.slice(0, 26)}…` : node.name }}
          </text>
        </g>
      </g>
    </svg>
  </div>
</template>
