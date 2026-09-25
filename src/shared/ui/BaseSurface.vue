<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    as?: 'article' | 'aside' | 'div' | 'section'
    padding?: 'none' | 'small' | 'medium' | 'large'
    elevation?: 'flat' | 'raised'
  }>(),
  {
    as: 'div',
    padding: 'large',
    elevation: 'raised',
  },
)

const classes = computed(() => [
  `base-surface--padding-${props.padding}`,
  `base-surface--${props.elevation}`,
])
</script>

<template>
  <component :is="as" class="base-surface" :class="classes">
    <slot />
  </component>
</template>

<style scoped>
.base-surface {
  border: var(--cmp-surface-border-width) solid var(--sys-color-border);
  border-radius: var(--cmp-surface-radius);
  background: var(--sys-color-surface-raised);
}

.base-surface--flat {
  box-shadow: none;
}

.base-surface--raised {
  box-shadow: var(--sys-shadow-raised);
}

.base-surface--padding-none {
  padding: 0;
}

.base-surface--padding-small {
  padding: var(--sys-space-3);
}

.base-surface--padding-medium {
  padding: var(--sys-space-5);
}

.base-surface--padding-large {
  padding: var(--sys-space-7);
}
</style>
